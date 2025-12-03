package com.csd.repointel.controller;

import com.csd.repointel.model.*;
import com.csd.repointel.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatService chatService;
    private final IntentParserService intentParserService;
    private final VersionComparisonService versionComparisonService;
    private final ChatResponseFormatter chatResponseFormatter;
    private final ExportService exportService;
    private final VulnerabilityAnalysisService vulnerabilityAnalysisService;

    public ChatController(ChatService chatService,
                         IntentParserService intentParserService,
                         VersionComparisonService versionComparisonService,
                         ChatResponseFormatter chatResponseFormatter,
                         ExportService exportService,
                         VulnerabilityAnalysisService vulnerabilityAnalysisService) {
        this.chatService = chatService;
        this.intentParserService = intentParserService;
        this.versionComparisonService = versionComparisonService;
        this.chatResponseFormatter = chatResponseFormatter;
        this.exportService = exportService;
        this.vulnerabilityAnalysisService = vulnerabilityAnalysisService;
    }

    /**
     * Enhanced chat endpoint with natural language processing
     */
    @GetMapping("/chat")
    public Map<String, Object> chat(@RequestParam(name = "query") String query) {
        log.info("Chat query received: {}", query);

        // Parse intent from natural language
        QueryIntent intent = intentParserService.parseQuery(query);
        log.info("Parsed intent: {}", intent.getIntentType());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("intent", intent.getIntentType());

        try {
            switch (intent.getIntentType()) {
                case VULNERABILITY_LIST -> {
                    // Get all vulnerability reports
                    var reports = vulnerabilityAnalysisService.analyzeAllRepositories();
                    response.put("success", true);
                    response.put("formatted", formatVulnerabilityList(reports));
                    response.put("data", reports);
                    response.put("viewLink", "/vulnerabilities.html");
                }
                case VULNERABILITY_BY_REPO -> {
                    // Get vulnerabilities for specific repo
                    if (intent.getPackageName() != null) {
                        var report = vulnerabilityAnalysisService.analyzeRepositoryByName(intent.getPackageName());
                        response.put("success", true);
                        response.put("formatted", formatRepoVulnerabilities(report));
                        response.put("data", report);
                    } else {
                        var reports = vulnerabilityAnalysisService.analyzeAllRepositories();
                        response.put("success", true);
                        response.put("formatted", formatVulnerabilityList(reports));
                        response.put("data", reports);
                    }
                    response.put("viewLink", "/vulnerabilities.html");
                }
                case VULNERABILITY_CHECK -> {
                    // Check vulnerabilities for specific package
                    var reports = vulnerabilityAnalysisService.analyzeAllRepositories();
                    response.put("success", true);
                    response.put("formatted", formatVulnerabilityList(reports));
                    response.put("data", reports);
                    response.put("viewLink", "/vulnerabilities.html");
                }
                case VULNERABILITY_FIX -> {
                    response.put("success", true);
                    response.put("message", "Vulnerability fix generation available. Use /api/vulnerabilities/fix/preview/{repoName}");
                    response.put("fixPreviewEndpoint", "/api/vulnerabilities/fix/preview");
                }
                case VULNERABILITY_EXPORT -> {
                    response.put("success", true);
                    response.put("message", "Vulnerability export options available");
                    response.put("exportLinks", Map.of(
                            "csv", "/api/vulnerabilities/export/csv",
                            "excel", "/api/vulnerabilities/export/excel",
                            "json", "/api/vulnerabilities/export/json",
                            "html", "/api/vulnerabilities/export/html"
                    ));
                }
                case VULNERABILITY_STATS -> {
                    var stats = vulnerabilityAnalysisService.getGlobalStatistics();
                    response.put("success", true);
                    response.put("formatted", formatVulnerabilityStats(stats));
                    response.put("data", stats);
                }
                case VERSION_CHECK, VERSION_COMPARE, LIST_ALL -> {
                    // Perform version comparison across all repos
                    VersionComparisonResult result = versionComparisonService.compareVersionsAcrossRepos(intent);
                    String formattedResponse = chatResponseFormatter.formatVersionComparison(result);
                    
                    response.put("success", true);
                    response.put("formatted", formattedResponse);
                    response.put("data", result);
                    response.put("exportLinks", exportService.getExportLinks(
                            intent.getPackageName() != null ? intent.getPackageName() : "results"));
                }
                case EXPORT -> {
                    // Return export options
                    response.put("success", true);
                    response.put("message", "Export options available");
                    response.put("exportLinks", exportService.getExportLinks(
                            intent.getPackageName() != null ? intent.getPackageName() : "results"));
                }
                case FIX_REQUEST -> {
                    // Return fix preview instructions
                    response.put("success", true);
                    response.put("message", "Fix generation is available. Use /api/fix/preview?packageName=X&targetVersion=Y");
                    response.put("fixPreviewEndpoint", "/api/fix/preview");
                }
                case SEARCH, UNKNOWN -> {
                    // Fallback to old behavior
                    ChatResponse oldResponse = chatService.interpret(query);
                    response.put("success", true);
                    response.put("legacy", oldResponse);
                }
            }
        } catch (Exception e) {
            log.error("Error processing chat query", e);
            response.put("success", false);
            response.put("error", e.getMessage());
            response.put("formatted", chatResponseFormatter.formatError("Failed to process query: " + e.getMessage()));
        }

        return response;
    }

    /**
     * Legacy chat endpoint for backward compatibility
     */
    @GetMapping("/chat/legacy")
    public ChatResponse chatLegacy(@RequestParam(name = "query") String query) {
        return chatService.interpret(query);
    }

    private String formatVulnerabilityList(java.util.List<RepoVulnerabilityReport> reports) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔐 Vulnerability Analysis Results\n\n");
        
        int totalVulnerable = reports.stream()
                .mapToInt(r -> r.getStatistics().getVulnerableDependencies())
                .sum();
        int totalCritical = reports.stream()
                .mapToInt(r -> r.getStatistics().getCriticalCount())
                .sum();
        
        sb.append(String.format("Total Repositories: %d\n", reports.size()));
        sb.append(String.format("Total Vulnerable Dependencies: %d\n", totalVulnerable));
        sb.append(String.format("Critical Issues: %d\n\n", totalCritical));
        
        for (RepoVulnerabilityReport report : reports) {
            if (report.getStatistics().getVulnerableDependencies() > 0) {
                sb.append(String.format("📦 %s - Risk: %s (%.1f)\n", 
                        report.getRepoName(), report.getRiskLevel(), report.getRiskScore()));
                sb.append(String.format("   Vulnerabilities: %d (Critical: %d, High: %d)\n",
                        report.getStatistics().getVulnerableDependencies(),
                        report.getStatistics().getCriticalCount(),
                        report.getStatistics().getHighCount()));
            }
        }
        
        return sb.toString();
    }

    private String formatRepoVulnerabilities(RepoVulnerabilityReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🔐 Vulnerability Report: %s\n\n", report.getRepoName()));
        sb.append(String.format("Risk Score: %.1f (%s)\n", report.getRiskScore(), report.getRiskLevel()));
        sb.append(String.format("Health Score: %d/100\n\n", report.getHealthScore()));
        
        var stats = report.getStatistics();
        sb.append(String.format("Dependencies: %d total, %d vulnerable, %d safe\n",
                stats.getTotalDependencies(),
                stats.getVulnerableDependencies(),
                stats.getSafeDependencies()));
        
        if (stats.getCriticalCount() > 0) {
            sb.append(String.format("\n⚠️  Critical: %d\n", stats.getCriticalCount()));
        }
        if (stats.getHighCount() > 0) {
            sb.append(String.format("⚠️  High: %d\n", stats.getHighCount()));
        }
        
        sb.append("\nRecommendations:\n");
        for (String rec : report.getRecommendations()) {
            sb.append("  - ").append(rec).append("\n");
        }
        
        return sb.toString();
    }

    private String formatVulnerabilityStats(VulnerabilityStatistics stats) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 Global Vulnerability Statistics\n\n");
        sb.append(String.format("Total Dependencies: %d\n", stats.getTotalDependencies()));
        sb.append(String.format("Vulnerable: %d (%.1f%%)\n", 
                stats.getVulnerableDependencies(), stats.getVulnerablePercentage()));
        sb.append(String.format("Safe: %d (%.1f%%)\n\n", 
                stats.getSafeDependencies(), stats.getSafePercentage()));
        
        sb.append("Severity Breakdown:\n");
        sb.append(String.format("  Critical: %d\n", stats.getCriticalCount()));
        sb.append(String.format("  High: %d\n", stats.getHighCount()));
        sb.append(String.format("  Medium: %d\n", stats.getMediumCount()));
        sb.append(String.format("  Low: %d\n\n", stats.getLowCount()));
        
        sb.append(stats.getSummary());
        
        return sb.toString();
    }
}
