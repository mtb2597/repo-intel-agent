package com.csd.repointel.service;

import com.csd.repointel.model.CveVulnerabilityRecord;
import com.csd.repointel.model.VulnerabilitySeverity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client for OSV.dev (Open Source Vulnerabilities) API.
 * Free, public API with no rate limits - perfect for real-time vulnerability scanning.
 */
@Service
@Slf4j
public class OsvApiClient {

    private static final String OSV_API_BASE_URL = "https://api.osv.dev";
    private static final int TIMEOUT_SECONDS = 10;
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    // Cache to avoid duplicate API calls for the same package:version
    private final Map<String, List<CveVulnerabilityRecord>> cache = new ConcurrentHashMap<>();

    public OsvApiClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder
                .baseUrl(OSV_API_BASE_URL)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Query OSV.dev for vulnerabilities affecting a specific Maven package version.
     * Uses the format: groupId:artifactId for Maven packages.
     * 
     * @param groupId Maven groupId (e.g., "org.apache.logging.log4j")
     * @param artifactId Maven artifactId (e.g., "log4j-core")
     * @param version Version string (e.g., "2.14.1")
     * @return List of CVE vulnerability records
     */
    public List<CveVulnerabilityRecord> queryVulnerabilities(String groupId, String artifactId, String version) {
        // Handle null or placeholder versions
        if (version == null || version.startsWith("${")) {
            log.debug("Skipping OSV query for {}:{} - version is null or unresolved: {}", groupId, artifactId, version);
            return Collections.emptyList();
        }
        // Normalize npm semver prefixes like ^ or ~
        String normalizedVersion = version.trim();
        if (normalizedVersion.startsWith("^") || normalizedVersion.startsWith("~")) {
            normalizedVersion = normalizedVersion.substring(1);
        }

        String ecosystem;
        String packageName;
        boolean isNpm = (groupId == null || groupId.isBlank());
        if (isNpm) {
            ecosystem = "npm";
            packageName = artifactId; // npm uses package name only
        } else {
            ecosystem = "Maven";
            packageName = groupId + ":" + artifactId;
        }

        String cacheKey = packageName + ":" + normalizedVersion + ":" + ecosystem;
        
        // Check cache first
        if (cache.containsKey(cacheKey)) {
            log.debug("Cache hit for {}", cacheKey);
            return cache.get(cacheKey);
        }

        try {
            log.info("Querying OSV.dev for vulnerabilities: {} (ecosystem {}), version {}", packageName, ecosystem, normalizedVersion);
            
            // Build request payload for Maven ecosystem
            // OSV uses "Maven" ecosystem with "groupId:artifactId" as package name
            Map<String, Object> request = new HashMap<>();
            request.put("version", normalizedVersion);
            
            Map<String, String> packageInfo = new HashMap<>();
            packageInfo.put("name", packageName);
            packageInfo.put("ecosystem", ecosystem);
            request.put("package", packageInfo);

            // Make API call
            final String pkgNameFinal = packageName;
            final String ecosystemFinal = ecosystem;
            final String versionFinal = normalizedVersion;

            String response = webClient.post()
                    .uri("/v1/query")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .onErrorResume(e -> {
                        log.error("OSV API error for {} ({}:{}): {}", pkgNameFinal, ecosystemFinal, versionFinal, e.getMessage());
                        return Mono.just("{\"vulns\":[]}");
                    })
                    .block();

            // Parse response
            List<CveVulnerabilityRecord> vulnerabilities = parseOsvResponse(response, isNpm ? "" : groupId, artifactId, normalizedVersion);
            
            // Cache the result
            cache.put(cacheKey, vulnerabilities);
            
            if (!vulnerabilities.isEmpty()) {
                log.info("Found {} vulnerabilities for {} (ecosystem {}):{}", vulnerabilities.size(), packageName, ecosystem, normalizedVersion);
            }
            
            return vulnerabilities;
            
        } catch (Exception e) {
            log.error("Failed to query OSV.dev for {} (ecosystem {}):{}", packageName, ecosystem, normalizedVersion, e);
            return Collections.emptyList();
        }
    }

    /**
     * Parse OSV.dev API response and convert to our internal CVE record format.
     */
    private List<CveVulnerabilityRecord> parseOsvResponse(String jsonResponse, String groupId, String artifactId, String installedVersion) {
        List<CveVulnerabilityRecord> records = new ArrayList<>();
        
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode vulns = root.get("vulns");
            
            if (vulns == null || !vulns.isArray() || vulns.isEmpty()) {
                return records;
            }

            for (JsonNode vuln : vulns) {
                try {
                    // Extract vulnerability ID (e.g., CVE-2021-44228 or GHSA-xxx)
                    String id = vuln.get("id").asText();
                    
                    // Extract summary and details
                    String summary = vuln.has("summary") ? vuln.get("summary").asText() : "";
                    String details = vuln.has("details") ? vuln.get("details").asText() : "";
                    String description = (summary + " " + details).trim();
                    if (description.length() > 500) {
                        description = description.substring(0, 497) + "...";
                    }
                    
                    // Extract severity
                    VulnerabilitySeverity severity = extractSeverity(vuln);
                    double cvssScore = extractCvssScore(vuln);
                    
                    // Extract affected version ranges
                    List<String> affectedVersions = extractAffectedVersions(vuln);
                    
                    // Extract fixed version at or above installed version (avoid downgrades)
                    String fixedVersion = extractFixedVersionAtOrAbove(vuln, installedVersion);
                    
                    // Extract references
                    List<String> references = extractReferences(vuln);
                    
                    // Extract published date
                    LocalDateTime publishedDate = extractPublishedDate(vuln);
                    
                    // Build solution message
                    String solution = fixedVersion != null 
                        ? "Upgrade to version " + fixedVersion + " or later"
                        : "No safe fix at or above installed version; check advisory for your major branch.";
                    
                    // Build CVE record
                    CveVulnerabilityRecord record = CveVulnerabilityRecord.builder()
                            .cveId(id)
                            .groupId(groupId)
                            .artifactId(artifactId)
                            .packageName(artifactId)
                            .affectedVersions(affectedVersions)
                            .severity(severity)
                            .cvssScore(cvssScore)
                            .description(description)
                            .solution(solution)
                            .recommendedVersion(fixedVersion)
                            .latestVersion(fixedVersion)
                            .publishedDate(publishedDate)
                            .source("OSV.dev")
                            .references(references)
                            .build();
                    
                    records.add(record);
                    
                } catch (Exception e) {
                    log.warn("Failed to parse OSV vulnerability entry: {}", e.getMessage());
                }
            }
            
        } catch (Exception e) {
            log.error("Failed to parse OSV response: {}", e.getMessage());
        }
        
        return records;
    }

    private VulnerabilitySeverity extractSeverity(JsonNode vuln) {
        // Check database_specific.severity
        if (vuln.has("database_specific") && vuln.get("database_specific").has("severity")) {
            String sev = vuln.get("database_specific").get("severity").asText().toUpperCase();
            try {
                return VulnerabilitySeverity.valueOf(sev);
            } catch (Exception e) {
                // Ignore
            }
        }
        
        // Check ecosystem_specific.severity
        if (vuln.has("affected")) {
            for (JsonNode affected : vuln.get("affected")) {
                if (affected.has("ecosystem_specific") && affected.get("ecosystem_specific").has("severity")) {
                    String sev = affected.get("ecosystem_specific").get("severity").asText().toUpperCase();
                    try {
                        return VulnerabilitySeverity.valueOf(sev);
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            }
        }
        
        // Default to MEDIUM if unknown
        return VulnerabilitySeverity.MEDIUM;
    }

    private double extractCvssScore(JsonNode vuln) {
        // Try to extract CVSS score from severity field
        if (vuln.has("severity")) {
            for (JsonNode sev : vuln.get("severity")) {
                if (sev.has("score")) {
                    return sev.get("score").asDouble();
                }
            }
        }
        
        // Default score based on severity
        VulnerabilitySeverity severity = extractSeverity(vuln);
        switch (severity) {
            case CRITICAL: return 9.5;
            case HIGH: return 7.5;
            case MEDIUM: return 5.0;
            case LOW: return 3.0;
            default: return 0.0;
        }
    }

    private List<String> extractAffectedVersions(JsonNode vuln) {
        List<String> versions = new ArrayList<>();
        
        if (!vuln.has("affected")) {
            return versions;
        }
        
        for (JsonNode affected : vuln.get("affected")) {
            if (affected.has("ranges")) {
                for (JsonNode range : affected.get("ranges")) {
                    String type = range.get("type").asText();
                    if ("ECOSYSTEM".equals(type) || "SEMVER".equals(type)) {
                        // Extract version ranges from events
                        if (range.has("events")) {
                            StringBuilder rangeStr = new StringBuilder();
                            for (JsonNode event : range.get("events")) {
                                if (event.has("introduced")) {
                                    rangeStr.append(">=").append(event.get("introduced").asText());
                                }
                                if (event.has("fixed")) {
                                    if (rangeStr.length() > 0) rangeStr.append(",");
                                    rangeStr.append("<").append(event.get("fixed").asText());
                                }
                            }
                            if (rangeStr.length() > 0) {
                                versions.add(rangeStr.toString());
                            }
                        }
                    }
                }
            }
        }
        
        return versions;
    }

    /**
     * Extract the fixed/safe version from OSV vulnerability data that is not lower than the installed version.
     * Returns the minimal fixed version that is >= installedVersion. If none, returns null.
     */
    private String extractFixedVersionAtOrAbove(JsonNode vuln, String installedVersion) {
        String bestCandidate = null;
        
        if (!vuln.has("affected")) {
            return null;
        }
        
        for (JsonNode affected : vuln.get("affected")) {
            if (affected.has("ranges")) {
                for (JsonNode range : affected.get("ranges")) {
                    String type = range.get("type").asText();
                    if ("ECOSYSTEM".equals(type) || "SEMVER".equals(type)) {
                        if (range.has("events")) {
                            for (JsonNode event : range.get("events")) {
                                if (event.has("fixed")) {
                                    String fixed = event.get("fixed").asText();
                                    // Only consider fixes that are >= installed version to avoid downgrades
                                    if (installedVersion != null && compareVersions(fixed, installedVersion) < 0) {
                                        continue;
                                    }
                                    // Choose the minimal fix that is >= installedVersion
                                    if (bestCandidate == null || compareVersions(fixed, bestCandidate) < 0) {
                                        bestCandidate = fixed;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return bestCandidate;
    }
    
    /**
     * Simple version comparison for finding earliest version.
     * Returns negative if v1 < v2, 0 if equal, positive if v1 > v2.
     */
    private int compareVersions(String v1, String v2) {
        try {
            String[] parts1 = v1.split("\\.");
            String[] parts2 = v2.split("\\.");
            int maxLen = Math.max(parts1.length, parts2.length);
            
            for (int i = 0; i < maxLen; i++) {
                int p1 = i < parts1.length ? parseVersionPart(parts1[i]) : 0;
                int p2 = i < parts2.length ? parseVersionPart(parts2[i]) : 0;
                if (p1 != p2) {
                    return Integer.compare(p1, p2);
                }
            }
            return 0;
        } catch (Exception e) {
            return v1.compareTo(v2); // Fallback to string comparison
        }
    }
    
    private int parseVersionPart(String part) {
        // Extract numeric part (e.g., "5" from "5.Final" or "5")
        StringBuilder num = new StringBuilder();
        for (char c : part.toCharArray()) {
            if (Character.isDigit(c)) {
                num.append(c);
            } else {
                break;
            }
        }
        return num.length() > 0 ? Integer.parseInt(num.toString()) : 0;
    }

    private List<String> extractReferences(JsonNode vuln) {
        List<String> refs = new ArrayList<>();
        
        if (vuln.has("references")) {
            for (JsonNode ref : vuln.get("references")) {
                if (ref.has("url")) {
                    refs.add(ref.get("url").asText());
                }
            }
        }
        
        // Add OSV.dev link
        if (vuln.has("id")) {
            refs.add("https://osv.dev/vulnerability/" + vuln.get("id").asText());
        }
        
        return refs;
    }

    private LocalDateTime extractPublishedDate(JsonNode vuln) {
        if (vuln.has("published")) {
            try {
                String published = vuln.get("published").asText();
                return LocalDateTime.parse(published.substring(0, 19));
            } catch (Exception e) {
                // Ignore
            }
        }
        return LocalDateTime.now().minusMonths(6);
    }

    /**
     * Clear the cache (useful for testing or manual refresh).
     */
    public void clearCache() {
        cache.clear();
        log.info("OSV API cache cleared");
    }

    /**
     * Get cache statistics.
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheSize", cache.size());
        stats.put("totalVulnerabilities", cache.values().stream().mapToInt(List::size).sum());
        return stats;
    }
}
