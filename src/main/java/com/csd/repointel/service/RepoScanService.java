package com.csd.repointel.service;

import com.csd.repointel.model.DependencyInfo;
import com.csd.repointel.model.RepoScanResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

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

        // Parse owner, repo, and branch from URL (assume main if not specified)
       String owner = null, repo = null, branch = null; // <--- change branch default to null
        try {
        String[] parts = cleanUrl.replace("https://github.com/", "").replace(".git", "").split("/");
            if (parts.length >= 2) {
                 owner = parts[0];
                 repo = parts[1];
        // Optionally support branch in URL as .../tree/branch
             if (parts.length >= 4 && "tree".equals(parts[2])) {
                branch = parts[3];
            }
    }
        } catch (Exception e) {
            log.error("Failed to parse owner/repo/branch from URL: {}", cleanUrl);
            return RepoScanResult.builder()
                    .repoName(repoName)
                    .url(url)
                    .success(false)
                    .error("Invalid GitHub URL")
                    .dependencies(List.of())
                    .build();
        }

        if (owner == null || repo == null) {
            log.error("Owner or repo could not be determined from URL: {}", cleanUrl);
            return RepoScanResult.builder()
                    .repoName(repoName)
                    .url(url)
                    .success(false)
                    .error("Invalid GitHub URL")
                    .dependencies(List.of())
                    .build();
        }

        // Use GitHubFileFetcher to fetch only pom.xml and package.json files
        Map<String, String> fileContents;
        try {
            fileContents = GitHubFileFetcher.fetchPomAndPackageJsonFiles(owner, repo, branch, token);
        } catch (Exception e) {
            log.error("Failed to fetch files from GitHub API: {}", e.getMessage());
            return RepoScanResult.builder()
                    .repoName(repoName)
                    .url(url)
                    .success(false)
                    .error("GitHub API fetch error: " + e.getMessage())
                    .dependencies(List.of())
                    .build();
        }


        // --- Enhanced: Collect all properties from all pom.xml files ---
        Map<String, String> globalProperties = new HashMap<>();
        Map<String, Model> allPomModels = new HashMap<>();
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();
            if (path.endsWith("pom.xml")) {
                try {
                    MavenXpp3Reader reader = new MavenXpp3Reader();
                    Model model = reader.read(new java.io.StringReader(content));
                    allPomModels.put(path, model);
                    if (model.getProperties() != null) {
                        for (String propName : model.getProperties().stringPropertyNames()) {
                            globalProperties.putIfAbsent(propName, model.getProperties().getProperty(propName));
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse properties from {}: {}", path, e.getMessage());
                }
            }
        }

        // --- Now extract dependencies, resolving property references using globalProperties ---
        List<DependencyInfo> deps = new ArrayList<>();
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            String path = entry.getKey();
            String content = entry.getValue();
            try {
                if (path.endsWith("pom.xml")) {
                    MavenXpp3Reader reader = new MavenXpp3Reader();
                    Model model = reader.read(new java.io.StringReader(content));
                    if (model.getDependencies() != null) {
                        for (Dependency d : model.getDependencies()) {
                            String resolvedGroupId = resolvePropertyRecursiveWithGlobal(model, d.getGroupId(), new HashSet<>(), globalProperties);
                            String resolvedArtifactId = resolvePropertyRecursiveWithGlobal(model, d.getArtifactId(), new HashSet<>(), globalProperties);
                            String resolvedVersion = resolvePropertyRecursiveWithGlobal(model, d.getVersion(), new HashSet<>(), globalProperties);
                            deps.add(DependencyInfo.builder()
                                    .groupId(resolvedGroupId)
                                    .artifactId(resolvedArtifactId)
                                    .version(resolvedVersion)
                                    .scope(d.getScope())
                                    .build());
                        }
                    }
                } else if (path.endsWith("package.json")) {
                    Map<String, Object> json = new com.fasterxml.jackson.databind.ObjectMapper().readValue(content, Map.class);
                    if (json.containsKey("dependencies")) {
                        Map<String, String> npmDeps = (Map<String, String>) json.get("dependencies");
                        for (Map.Entry<String, String> dep : npmDeps.entrySet()) {
                            deps.add(DependencyInfo.builder()
                                    .groupId("")
                                    .artifactId(dep.getKey())
                                    .version(dep.getValue())
                                    .scope("npm-dependency")
                                    .build());
                        }
                    }
                    if (json.containsKey("devDependencies")) {
                        Map<String, String> devDeps = (Map<String, String>) json.get("devDependencies");
                        for (Map.Entry<String, String> dep : devDeps.entrySet()) {
                            deps.add(DependencyInfo.builder()
                                    .groupId("")
                                    .artifactId(dep.getKey())
                                    .version(dep.getValue())
                                    .scope("npm-devDependency")
                                    .build());
                        }
                    }
                } else if (path.endsWith("package-lock.json")) {
                    // Prefer exact versions from lock file (npm v2/v3 format)
                    @SuppressWarnings("unchecked")
                    Map<String, Object> lockJson = (Map<String, Object>) new com.fasterxml.jackson.databind.ObjectMapper().readValue(content, Map.class);
                    Object depsNode = lockJson.get("dependencies");
                    if (depsNode instanceof Map<?, ?>) {
                        Map<?, ?> lockDeps = (Map<?, ?>) depsNode;
                        for (Map.Entry<?, ?> entryDep : lockDeps.entrySet()) {
                            String name = String.valueOf(entryDep.getKey());
                            Object val = entryDep.getValue();
                            if (val instanceof Map<?, ?>) {
                                Map<?, ?> vmap = (Map<?, ?>) val;
                                Object verObj = vmap.get("version");
                                String exactVersion = verObj != null ? verObj.toString() : "";
                                if (!exactVersion.isEmpty()) {
                                    deps.add(DependencyInfo.builder()
                                            .groupId("")
                                            .artifactId(name)
                                            .version(exactVersion)
                                            .scope("npm-lock")
                                            .build());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse {}: {}", path, e.getMessage());
            }
        }

        // Deduplicate dependencies by groupId:artifactId:version:scope
        Map<String, DependencyInfo> uniqueDeps = new LinkedHashMap<>();
        for (DependencyInfo dep : deps) {
            String key = (dep.getGroupId() != null ? dep.getGroupId() : "") + ":"
                    + (dep.getArtifactId() != null ? dep.getArtifactId() : "") + ":"
                    + (dep.getVersion() != null ? dep.getVersion() : "") + ":"
                    + (dep.getScope() != null ? dep.getScope() : "");
            uniqueDeps.putIfAbsent(key, dep);
        }
        // Merge: prefer lock file exact versions over package.json ranges for same package
        Map<String, DependencyInfo> byName = new LinkedHashMap<>();
        for (DependencyInfo d : uniqueDeps.values()) {
            String nameKey = (d.getGroupId() != null ? d.getGroupId() : "") + ":" + (d.getArtifactId() != null ? d.getArtifactId() : "");
            DependencyInfo existing = byName.get(nameKey);
            if (existing == null) {
                byName.put(nameKey, d);
            } else {
                boolean dIsLock = d.getScope() != null && d.getScope().startsWith("npm-lock");
                boolean existingIsLock = existing.getScope() != null && existing.getScope().startsWith("npm-lock");
                if (dIsLock && !existingIsLock) {
                    byName.put(nameKey, d);
                }
            }
        }
        List<DependencyInfo> dedupedDeps = new ArrayList<>(byName.values());

        repoDependencies.put(repoName, dedupedDeps);
        // JDK version detection from pom.xml (optional, can be enhanced)
        String jdkVersion = "Unknown";
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            if (entry.getKey().endsWith("pom.xml")) {
                try {
                    MavenXpp3Reader reader = new MavenXpp3Reader();
                    Model model = reader.read(new java.io.StringReader(entry.getValue()));
                    if (model.getProperties() != null) {
                        String v = model.getProperties().getProperty("maven.compiler.source");
                        if (v != null && !v.isBlank()) {
                            jdkVersion = v;
                            break;
                        }
                    }
                } catch (Exception ignore) {}
            }
        }
        repoJdkVersions.put(repoName, jdkVersion);
        log.info("=== Scan completed for {}: {} dependencies found ===", repoName, dedupedDeps.size());

        return RepoScanResult.builder()
                .repoName(repoName)
                .url(url)
                .success(true)
                .dependencies(dedupedDeps)
                .jdkVersion(jdkVersion)
                .build();
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

}

