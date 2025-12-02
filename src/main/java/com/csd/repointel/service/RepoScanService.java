package com.csd.repointel.service;

import com.csd.repointel.model.DependencyInfo;
import com.csd.repointel.model.RepoScanResult;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RepoScanService {

    private final Map<String, List<DependencyInfo>> repoDependencies = new ConcurrentHashMap<>();
    private final Map<String, String> repoJdkVersions = new ConcurrentHashMap<>();
    private final Path workdir = Path.of("workdir");

    public RepoScanService() {
        try {
            Files.createDirectories(workdir);
        } catch (IOException e) {
            log.error("Failed to create workdir", e);
        }
    }

    public Map<String, List<DependencyInfo>> getRepoDependencies() {
        return repoDependencies;
    }

    public Map<String, String> getJdkVersions() {
        return repoJdkVersions;
    }

    public CompletableFuture<RepoScanResult> scanRepo(String url) {
        return CompletableFuture.supplyAsync(() -> doScan(url));
    }

    private RepoScanResult doScan(String url) {
        String repoName = repoNameFromUrl(url);
        Path targetDir = workdir.resolve(repoName);

        log.info("=== Starting scan for repository: {} ===", repoName);

        // Extract credentials from URL if present (format: https://token@github.com/user/repo.git)
        String cleanUrl = url;
        String token = null;

        if (url.startsWith("https://") && url.contains("@")) {
            int atIndex = url.indexOf("@");
            int protocolEnd = url.indexOf("://") + 3;
            if (atIndex > protocolEnd) {
                token = url.substring(protocolEnd, atIndex);
                cleanUrl = url.substring(0, protocolEnd) + url.substring(atIndex + 1);
                log.info("Extracted credentials from URL for {}", repoName);
            }
        }

        if (Files.exists(targetDir)) {
            // simple reuse (could pull latest later)
            log.info("Reusing existing clone at: {}", targetDir.toAbsolutePath());
        } else {
            try {
                log.info("Cloning repository to: {}", targetDir.toAbsolutePath());
                CloneCommand cmd = Git.cloneRepository()
                        .setURI(cleanUrl)
                        .setDirectory(targetDir.toFile())
                        .setDepth(1);

                // If token was extracted, use it for authentication
                if (token != null && !token.isEmpty()) {
                    cmd.setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""));
                    log.info("Using authentication for {}", repoName);
                }

                try (Git ignored = cmd.call()) {
                    log.info("Successfully cloned repository: {}", repoName);
                }
            } catch (GitAPIException e) {
                log.error("Clone failed for {}: {}", repoName, e.getMessage(), e);
                return RepoScanResult.builder()
                        .repoName(repoName)
                        .url(url)
                        .success(false)
                        .error(e.getMessage())
                        .dependencies(List.of())
                        .build();
            }
        }

        log.info("Scanning for dependencies in: {}", repoName);
        List<DependencyInfo> deps = parseDependencies(targetDir);

        // Detect JDK version from pom.xml files
        String jdkVersion = detectJdkVersion(targetDir);
        log.info("Detected JDK version for {}: {}", repoName, jdkVersion);

        repoDependencies.put(repoName, deps);
        repoJdkVersions.put(repoName, jdkVersion);  // Store JDK version
        log.info("=== Scan completed for {}: {} dependencies found ===", repoName, deps.size());

        return RepoScanResult.builder()
                .repoName(repoName)
                .url(url)
                .success(true)
                .dependencies(deps)
                .jdkVersion(jdkVersion)
                .build();
    }

    private List<DependencyInfo> parseDependencies(Path repoDir) {
        List<DependencyInfo> list = new ArrayList<>();
        Map<String, String> globalProperties = new HashMap<>();
        List<Model> allModels = new ArrayList<>();
        try (var stream = Files.walk(repoDir)) {
            // Find all pom.xml files, excluding build directories
            List<Path> poms = stream
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !p.toString().contains("target" + File.separator))  // Skip Maven build output
                    .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator))  // Skip .git directory
                    .filter(p -> !p.toString().contains("node_modules" + File.separator))  // Skip npm packages
                    .filter(p -> !p.toString().contains(File.separator + ".idea" + File.separator))  // Skip IDE files
                    .collect(Collectors.toList());

            log.info("Found {} pom.xml file(s) in repository", poms.size());

            MavenXpp3Reader reader = new MavenXpp3Reader();
            int totalDeps = 0;
            Set<String> seen = new HashSet<>();  // Track unique dependencies across modules

            // First pass: collect all properties from all poms
            for (Path pom : poms) {
                try (FileInputStream fis = new FileInputStream(pom.toFile())) {
                    Model model = reader.read(fis);
                    model.setPomFile(pom.toFile());
                    allModels.add(model);
                    if (model.getProperties() != null) {
                        for (String propName : model.getProperties().stringPropertyNames()) {
                            globalProperties.putIfAbsent(propName, model.getProperties().getProperty(propName));
                        }
                    }
                } catch (IOException | XmlPullParserException e) {
                    log.error("Failed to parse pom {}: {}", pom, e.getMessage(), e);
                }
            }

            log.info("Global properties collected from all poms: {}", globalProperties);

            // Second pass: extract dependencies with global property fallback
            for (Model model : allModels) {
                Path pom = model.getPomFile() != null ? model.getPomFile().toPath() : null;
                // Log all properties found in this pom.xml
                if (model.getProperties() != null && !model.getProperties().isEmpty()) {
                    log.info("Properties in {}: {}", pom, model.getProperties());
                } else {
                    log.info("No properties found in {}", pom);
                }

                int depCount = 0;
                if (model.getDependencies() != null && !model.getDependencies().isEmpty()) {
                    for (Dependency d : model.getDependencies()) {
                        // Resolve groupId, artifactId, and version for property references
                        String resolvedGroupId = resolvePropertyRecursiveWithGlobal(model, d.getGroupId(), new HashSet<>(), globalProperties);
                        String resolvedArtifactId = resolvePropertyRecursiveWithGlobal(model, d.getArtifactId(), new HashSet<>(), globalProperties);
                        String resolvedVersion = resolveVersionWithGlobal(d, model, globalProperties);
                        String scope = d.getScope();
                        String key = resolvedGroupId + ":" + resolvedArtifactId + ":" +
                                    (resolvedVersion == null ? "" : resolvedVersion) + ":" +
                                    (scope == null ? "" : scope);

                        if (seen.add(key)) {  // Only add if not seen before
                            list.add(DependencyInfo.builder()
                                    .groupId(resolvedGroupId)
                                    .artifactId(resolvedArtifactId)
                                    .version(resolvedVersion)
                                    .scope(scope)
                                    .build());
                            depCount++;
                            if (resolvedVersion != null && resolvedVersion.matches("\\$\\{.+}")) {
                                log.warn("Unresolved property for dependency version: {}:{} version={} in {}. Available properties: {}", resolvedGroupId, resolvedArtifactId, resolvedVersion, pom, model.getProperties());
                            }
                        }
                    }
                    totalDeps += depCount;
                }

                // Log each pom.xml file processed
                String relativePath = pom != null ? repoDir.relativize(pom).toString() : "unknown";
                if (depCount > 0) {
                    log.info("Extracted {} unique dependencies from {}", depCount, relativePath);
                } else {
                    log.debug("No new dependencies found in {} (parent POM or duplicates)", relativePath);
                }
            }

            log.info("Total unique dependencies extracted: {} from {} pom.xml files", totalDeps, poms.size());

        } catch (IOException e) {
            log.error("Failed to walk repository directory", e);
        }
        return list;
    }

    // Helper: resolve property with global fallback
    private static String resolvePropertyRecursiveWithGlobal(Model model, String value, Set<String> seenProps, Map<String, String> globalProperties) {
        if (value == null) return null;
        String result = value;
        int maxDepth = 10;
        int depth = 0;
        while (result != null && result.contains("${") && result.contains("}")) {
            int start = result.indexOf("${");
            int end = result.indexOf("}", start);
            if (start == -1 || end == -1) break;
            String propName = result.substring(start + 2, end);
            if (!seenProps.add(propName)) {
                org.slf4j.LoggerFactory.getLogger(RepoScanService.class).warn("Cyclic property reference detected: {}", propName);
                return value;
            }
            String propVal = null;
            Model current = model;
            while (current != null && propVal == null) {
                if (current.getProperties() != null) {
                    propVal = current.getProperties().getProperty(propName);
                }
                if (propVal == null && current.getParent() != null) {
                    try {
                        String parentRelativePath = current.getParent().getRelativePath();
                        java.nio.file.Path baseDir = null;
                        if (current.getPomFile() != null) {
                            baseDir = current.getPomFile().getParentFile().toPath();
                        }
                        java.nio.file.Path parentPomPath = null;
                        if (baseDir != null && parentRelativePath != null) {
                            parentPomPath = baseDir.resolve(parentRelativePath).normalize();
                        } else if (baseDir != null) {
                            parentPomPath = baseDir.resolve("../pom.xml").normalize();
                        }
                        if (parentPomPath != null && java.nio.file.Files.exists(parentPomPath)) {
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(parentPomPath.toFile())) {
                                org.apache.maven.model.io.xpp3.MavenXpp3Reader reader = new org.apache.maven.model.io.xpp3.MavenXpp3Reader();
                                Model parentModel = reader.read(fis);
                                parentModel.setPomFile(parentPomPath.toFile());
                                current = parentModel;
                                continue;
                            } catch (Exception e) {
                                org.slf4j.LoggerFactory.getLogger(RepoScanService.class).warn("Failed to load parent pom {}: {}", parentPomPath, e.getMessage());
                                break;
                            }
                        } else {
                            break;
                        }
                    } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger(RepoScanService.class).warn("Error resolving parent pom: {}", e.getMessage());
                        break;
                    }
                } else {
                    break;
                }
            }
            // Fallback to global properties
            if (propVal == null && globalProperties != null) {
                propVal = globalProperties.get(propName);
            }
            if (propVal == null) {
                org.slf4j.LoggerFactory.getLogger(RepoScanService.class).warn("Property '{}' not found in pom properties, parent chain, or global map while resolving '{}'", propName, value);
                return value;
            }
            String resolvedPropVal = resolvePropertyRecursiveWithGlobal(model, propVal, seenProps, globalProperties);
            result = result.substring(0, start) + resolvedPropVal + result.substring(end + 1);
            depth++;
            if (depth > maxDepth) {
                org.slf4j.LoggerFactory.getLogger(RepoScanService.class).warn("Property resolution exceeded max depth for '{}', possible infinite recursion", value);
                return value;
            }
        }
        return result;
    }

    // Helper: resolve version with global fallback
    private static String resolveVersionWithGlobal(Dependency d, Model model, Map<String, String> globalProperties) {
        String version = d.getVersion();
        if (version != null) {
            version = resolvePropertyRecursiveWithGlobal(model, version, new HashSet<>(), globalProperties);
        }
        // If still null/blank, check dependencyManagement
        if ((version == null || version.isBlank()) && model.getDependencyManagement() != null) {
            for (Dependency dmDep : model.getDependencyManagement().getDependencies()) {
                if (Objects.equals(dmDep.getGroupId(), d.getGroupId()) &&
                    Objects.equals(dmDep.getArtifactId(), d.getArtifactId())) {
                    String dmVersion = dmDep.getVersion();
                    if (dmVersion != null) {
                        dmVersion = resolvePropertyRecursiveWithGlobal(model, dmVersion, new HashSet<>(), globalProperties);
                    }
                    version = dmVersion;
                    break;
                }
            }
        }
        return version;
    }

    /**
     * Resolve dependency version considering property placeholders, nested properties, and dependencyManagement.
     * Handles cycles and nested property references.
     */
    private String resolveVersion(Dependency d, Model model) {
        String version = d.getVersion();
        if (version != null) {
            version = resolvePropertyRecursive(model, version, new HashSet<>());
        }

        // If still null/blank, check dependencyManagement
        if ((version == null || version.isBlank()) && model.getDependencyManagement() != null) {
            for (Dependency dmDep : model.getDependencyManagement().getDependencies()) {
                if (Objects.equals(dmDep.getGroupId(), d.getGroupId()) &&
                    Objects.equals(dmDep.getArtifactId(), d.getArtifactId())) {
                    String dmVersion = dmDep.getVersion();
                    if (dmVersion != null) {
                        dmVersion = resolvePropertyRecursive(model, dmVersion, new HashSet<>());
                    }
                    version = dmVersion;
                    break;
                }
            }
        }

        return version;  // May still be null if unresolved
    }

    /**
     * Recursively resolve Maven property references in the form ${...} in the given value.
     * Handles nested properties and cycle detection. If a cycle is detected, returns the unresolved string and logs a warning.
     */
    public static String resolvePropertyRecursive(Model model, String value, Set<String> seenProps) {
        if (value == null) return null;
        String result = value;
        int maxDepth = 10; // Prevent infinite recursion in pathological cases
        int depth = 0;
        while (result != null && result.contains("${") && result.contains("}")) {
            int start = result.indexOf("${");
            int end = result.indexOf("}", start);
            if (start == -1 || end == -1) break;
            String propName = result.substring(start + 2, end);
            if (!seenProps.add(propName)) {
                // Cycle detected
                org.slf4j.LoggerFactory.getLogger(RepoScanService.class).warn("Cyclic property reference detected: {}", propName);
                return value;
            }
            String propVal = null;
            Model current = model;
            while (current != null && propVal == null) {
                if (current.getProperties() != null) {
                    propVal = current.getProperties().getProperty(propName);
                }
                if (propVal == null && current.getParent() != null) {
                    // Try to load parent POM from file if available
                    try {
                        String parentGroupId = current.getParent().getGroupId();
                        String parentArtifactId = current.getParent().getArtifactId();
                        String parentVersion = current.getParent().getVersion();
                        String parentRelativePath = current.getParent().getRelativePath();
                        java.nio.file.Path baseDir = null;
                        if (current.getPomFile() != null) {
                            baseDir = current.getPomFile().getParentFile().toPath();
                        }
                        java.nio.file.Path parentPomPath = null;
                        if (baseDir != null && parentRelativePath != null) {
                            parentPomPath = baseDir.resolve(parentRelativePath).normalize();
                        } else if (baseDir != null) {
                            parentPomPath = baseDir.resolve("../pom.xml").normalize();
                        }
                        if (parentPomPath != null && java.nio.file.Files.exists(parentPomPath)) {
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(parentPomPath.toFile())) {
                                org.apache.maven.model.io.xpp3.MavenXpp3Reader reader = new org.apache.maven.model.io.xpp3.MavenXpp3Reader();
                                Model parentModel = reader.read(fis);
                                parentModel.setPomFile(parentPomPath.toFile());
                                current = parentModel;
                                continue;
                            } catch (Exception e) {
                                org.slf4j.LoggerFactory.getLogger(RepoScanService.class).warn("Failed to load parent pom {}: {}", parentPomPath, e.getMessage());
                                break;
                            }
                        } else {
                            break;
                        }
                    } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger(RepoScanService.class).warn("Error resolving parent pom: {}", e.getMessage());
                        break;
                    }
                } else {
                    break;
                }
            }
            if (propVal == null) {
                // Not found, leave unresolved
                org.slf4j.LoggerFactory.getLogger(RepoScanService.class).warn("Property '{}' not found in pom properties or parent chain while resolving '{}'", propName, value);
                return value;
            }
            // Recursively resolve the property value
            String resolvedPropVal = resolvePropertyRecursive(model, propVal, seenProps);
            // Replace only the first occurrence
            result = result.substring(0, start) + resolvedPropVal + result.substring(end + 1);
            depth++;
            if (depth > maxDepth) {
                org.slf4j.LoggerFactory.getLogger(RepoScanService.class).warn("Property resolution exceeded max depth for '{}', possible infinite recursion", value);
                return value;
            }
        }
        return result;
    }

    private String repoNameFromUrl(String url) {
        // Remove credentials if present (format: https://token@github.com/user/repo.git)
        String cleanUrl = url;
        if (url.startsWith("https://") && url.contains("@")) {
            int atIndex = url.indexOf("@");
            int protocolEnd = url.indexOf("://") + 3;
            if (atIndex > protocolEnd) {
                cleanUrl = url.substring(0, protocolEnd) + url.substring(atIndex + 1);
            }
        }

        String trimmed = cleanUrl.endsWith(".git") ? cleanUrl.substring(0, cleanUrl.length() - 4) : cleanUrl;
        int idx = trimmed.lastIndexOf('/') + 1;
        return trimmed.substring(idx);
    }

    /**
     * Detect JDK version from pom.xml files in the repository.
     * Aggregates all candidates from maven.compiler.source, maven.compiler.release, or java.version properties
     * across all pom.xml files and chooses the highest version.
     */
    private String detectJdkVersion(Path repoDir) {
        try (var stream = Files.walk(repoDir)) {
            List<Path> poms = stream
                    .filter(p -> p.getFileName().toString().equals("pom.xml"))
                    .filter(p -> !p.toString().contains("target" + File.separator))
                    .filter(p -> !p.toString().contains(File.separator + ".git" + File.separator))
                    .collect(Collectors.toList());

            MavenXpp3Reader reader = new MavenXpp3Reader();
            Set<String> candidates = new HashSet<>();

            // Collect all JDK version candidates from all pom.xml files
            for (Path pom : poms) {
                try (FileInputStream fis = new FileInputStream(pom.toFile())) {
                    Model model = reader.read(fis);

                    if (model.getProperties() != null) {
                        java.util.Properties props = model.getProperties();

                        // Check maven.compiler.source (most common)
                        if (props.containsKey("maven.compiler.source")) {
                            String version = props.getProperty("maven.compiler.source");
                            if (version != null && !version.isBlank()) {
                                candidates.add(version);
                            }
                        }

                        // Check maven.compiler.release (preferred in newer Maven)
                        if (props.containsKey("maven.compiler.release")) {
                            String version = props.getProperty("maven.compiler.release");
                            if (version != null && !version.isBlank()) {
                                candidates.add(version);
                            }
                        }

                        // Check java.version
                        if (props.containsKey("java.version")) {
                            String version = props.getProperty("java.version");
                            if (version != null && !version.isBlank()) {
                                candidates.add(version);
                            }
                        }
                    }

                } catch (IOException | XmlPullParserException e) {
                    log.debug("Failed to parse {} for JDK version: {}", pom, e.getMessage());
                }
            }

            if (candidates.isEmpty()) {
                return "Not specified";
            }

            // Choose the highest version among all candidates
            String best = null;
            for (String candidate : candidates) {
                if (candidate == null || candidate.isBlank()) continue;

                if (best == null) {
                    best = candidate;
                    continue;
                }

                try {
                    // Use VersionUtil for numeric/semver comparison
                    if (VersionUtil.compare(candidate, best) > 0) {
                        best = candidate;
                    }
                } catch (Exception ex) {
                    // Fallback to lexical comparison if version parsing fails
                    if (candidate.compareTo(best) > 0) {
                        best = candidate;
                    }
                }
            }

            log.info("Detected JDK version: {} (from {} candidates across {} pom files)",
                     best, candidates.size(), poms.size());
            return best;

        } catch (IOException e) {
            log.error("Failed to detect JDK version", e);
            return "Unknown";
        }
    }
}

