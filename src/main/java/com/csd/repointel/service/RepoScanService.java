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

            for (Path pom : poms) {
                try (FileInputStream fis = new FileInputStream(pom.toFile())) {
                    Model model = reader.read(fis);

                    int depCount = 0;
                    if (model.getDependencies() != null && !model.getDependencies().isEmpty()) {
                        for (Dependency d : model.getDependencies()) {
                            String resolvedVersion = resolveVersion(d, model);
                            String scope = d.getScope();
                            String key = d.getGroupId() + ":" + d.getArtifactId() + ":" +
                                        (resolvedVersion == null ? "" : resolvedVersion) + ":" +
                                        (scope == null ? "" : scope);

                            if (seen.add(key)) {  // Only add if not seen before
                                list.add(DependencyInfo.builder()
                                        .groupId(d.getGroupId())
                                        .artifactId(d.getArtifactId())
                                        .version(resolvedVersion)
                                        .scope(scope)
                                        .build());
                                depCount++;
                            }
                        }
                        totalDeps += depCount;
                    }

                    // Log each pom.xml file processed
                    String relativePath = repoDir.relativize(pom).toString();
                    if (depCount > 0) {
                        log.info("Extracted {} unique dependencies from {}", depCount, relativePath);
                    } else {
                        log.debug("No new dependencies found in {} (parent POM or duplicates)", relativePath);
                    }

                } catch (IOException | XmlPullParserException e) {
                    log.error("Failed to parse pom {}: {}", pom, e.getMessage(), e);
                }
            }

            log.info("Total unique dependencies extracted: {} from {} pom.xml files", totalDeps, poms.size());

        } catch (IOException e) {
            log.error("Failed to walk repository directory", e);
        }
        return list;
    }

    /**
     * Resolve dependency version considering property placeholders and dependencyManagement.
     */
    private String resolveVersion(Dependency d, Model model) {
        String version = d.getVersion();

        // Resolve property placeholders like ${spring.version}
        if (version != null && version.startsWith("${") && version.endsWith("}")) {
            String propName = version.substring(2, version.length() - 1);
            if (model.getProperties() != null) {
                String propVal = model.getProperties().getProperty(propName);
                if (propVal != null && !propVal.isBlank()) {
                    version = propVal;
                }
            }
        }

        // If still null/blank, check dependencyManagement
        if ((version == null || version.isBlank()) && model.getDependencyManagement() != null) {
            for (Dependency dmDep : model.getDependencyManagement().getDependencies()) {
                if (Objects.equals(dmDep.getGroupId(), d.getGroupId()) &&
                    Objects.equals(dmDep.getArtifactId(), d.getArtifactId())) {
                    String dmVersion = dmDep.getVersion();

                    // Resolve properties in dependencyManagement version too
                    if (dmVersion != null && dmVersion.startsWith("${") && dmVersion.endsWith("}")) {
                        String propName = dmVersion.substring(2, dmVersion.length() - 1);
                        if (model.getProperties() != null) {
                            String propVal = model.getProperties().getProperty(propName);
                            if (propVal != null && !propVal.isBlank()) {
                                dmVersion = propVal;
                            }
                        }
                    }
                    version = dmVersion;
                    break;
                }
            }
        }

        return version;  // May still be null if unresolved
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

