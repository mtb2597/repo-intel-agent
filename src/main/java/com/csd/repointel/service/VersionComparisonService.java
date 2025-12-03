package com.csd.repointel.service;

import com.csd.repointel.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service to compare versions across all scanned repositories
 */
@Slf4j
@Service
public class VersionComparisonService {

    private final RepoScanService repoScanService;

    public VersionComparisonService(RepoScanService repoScanService) {
        this.repoScanService = repoScanService;
    }

    /**
     * Scan all repositories and compare versions of a specific package
     */
    public VersionComparisonResult compareVersionsAcrossRepos(QueryIntent intent) {
        log.info("Comparing versions across repos for: {}", intent.getPackageName());

        List<RepoVersionStatus> repoStatuses = new ArrayList<>();
        Map<String, List<DependencyInfo>> allRepos = repoScanService.getRepoDependencies();

        for (Map.Entry<String, List<DependencyInfo>> entry : allRepos.entrySet()) {
            String repoName = entry.getKey();
            List<DependencyInfo> dependencies = entry.getValue();

            RepoVersionStatus status = analyzeRepoForPackage(repoName, dependencies, intent);
            repoStatuses.add(status);
        }

        // Generate summary
        ComparisonSummary summary = generateSummary(repoStatuses, intent);

        return VersionComparisonResult.builder()
                .packageName(intent.getPackageName())
                .requestedVersion(intent.getVersion())
                .repoStatuses(repoStatuses)
                .summary(summary)
                .build();
    }

    private RepoVersionStatus analyzeRepoForPackage(String repoName, List<DependencyInfo> dependencies, QueryIntent intent) {
        // Filter dependencies matching the package
        List<String> foundVersions = dependencies.stream()
                .filter(dep -> matchesPackage(dep, intent))
                .map(DependencyInfo::getVersion)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .collect(Collectors.toList());

        if (foundVersions.isEmpty()) {
            return RepoVersionStatus.builder()
                    .repoName(repoName)
                    .packageName(intent.getPackageName() != null ? intent.getPackageName() : "unknown")
                    .foundVersions(List.of())
                    .status(VersionStatus.NOT_FOUND)
                    .statusEmoji("❌")
                    .message("Package not found")
                    .build();
        }

        // Determine status based on comparison type
        VersionStatus status;
        String emoji;
        String message;

        if (intent.getVersion() == null) {
            // Just listing versions
            status = VersionStatus.MATCHES;
            emoji = "📦";
            message = "Found: " + String.join(", ", foundVersions);
        } else {
            String highestVersion = selectHighestVersion(foundVersions);
            if (highestVersion == null || highestVersion.isBlank()) {
                status = VersionStatus.UNKNOWN;
                emoji = "❓";
                message = "Version cannot be determined";
            } else {
                int comparison = VersionUtil.compare(highestVersion, intent.getVersion());
                
                if (comparison == 0) {
                    status = VersionStatus.MATCHES;
                    emoji = "✅";
                    message = "Matches " + intent.getVersion();
                } else if (comparison < 0) {
                    status = VersionStatus.OLDER;
                    emoji = "⚠️";
                    message = "Older: " + highestVersion + " (target: " + intent.getVersion() + ")";
                } else {
                    status = VersionStatus.NEWER;
                    emoji = "⬆️";
                    message = "Newer: " + highestVersion + " (target: " + intent.getVersion() + ")";
                }
            }
        }

        return RepoVersionStatus.builder()
                .repoName(repoName)
                .packageName(intent.getPackageName())
                .foundVersions(foundVersions)
                .status(status)
                .statusEmoji(emoji)
                .message(message)
                .build();
    }

    private boolean matchesPackage(DependencyInfo dep, QueryIntent intent) {
        if (intent.getGroupId() != null && intent.getArtifactId() != null) {
            return intent.getGroupId().equals(dep.getGroupId()) && 
                   intent.getArtifactId().equals(dep.getArtifactId());
        }
        
        if (intent.getPackageName() != null) {
            String pkgLower = intent.getPackageName().toLowerCase();
            String depName = (dep.getGroupId() + ":" + dep.getArtifactId()).toLowerCase();
            
            // Exact match or contains
            return depName.equals(pkgLower) || 
                   depName.contains(pkgLower) ||
                   dep.getArtifactId().toLowerCase().contains(pkgLower);
        }
        
        return false;
    }

    private String selectHighestVersion(List<String> versions) {
        String best = null;
        for (String v : versions) {
            if (v == null || v.isBlank()) continue;
            if (best == null || VersionUtil.compare(v, best) > 0) {
                best = v;
            }
        }
        return best;
    }

    private ComparisonSummary generateSummary(List<RepoVersionStatus> repoStatuses, QueryIntent intent) {
        int total = repoStatuses.size();
        int matching = 0;
        int older = 0;
        int newer = 0;
        int notFound = 0;
        int unknown = 0;

        for (RepoVersionStatus status : repoStatuses) {
            switch (status.getStatus()) {
                case MATCHES -> matching++;
                case OLDER -> older++;
                case NEWER -> newer++;
                case NOT_FOUND -> notFound++;
                case UNKNOWN -> unknown++;
            }
        }

        String nlSummary = buildNaturalLanguageSummary(intent, total, matching, older, newer, notFound, unknown);

        return ComparisonSummary.builder()
                .totalRepos(total)
                .matchingRepos(matching)
                .olderRepos(older)
                .newerRepos(newer)
                .notFoundRepos(notFound)
                .unknownRepos(unknown)
                .naturalLanguageSummary(nlSummary)
                .build();
    }

    private String buildNaturalLanguageSummary(QueryIntent intent, int total, int matching, 
                                               int older, int newer, int notFound, int unknown) {
        StringBuilder sb = new StringBuilder();
        
        String packageName = intent.getPackageName() != null ? intent.getPackageName() : "the requested package";
        
        if (intent.getVersion() != null) {
            sb.append(String.format("I found %d repositories using %s. ", total, packageName));
            
            if (matching > 0) {
                sb.append(String.format("%d %s on version %s, ", 
                    matching, matching == 1 ? "is" : "are", intent.getVersion()));
            }
            if (older > 0) {
                sb.append(String.format("%d %s older, ", 
                    older, older == 1 ? "is" : "are"));
            }
            if (newer > 0) {
                sb.append(String.format("%d %s newer, ", 
                    newer, newer == 1 ? "is" : "are"));
            }
            if (notFound > 0) {
                sb.append(String.format("%d %s not have it, ", 
                    notFound, notFound == 1 ? "does" : "do"));
            }
            if (unknown > 0) {
                sb.append(String.format("%d %s unknown versions", 
                    unknown, unknown == 1 ? "has" : "have"));
            }
            
            // Remove trailing comma and space
            String result = sb.toString();
            if (result.endsWith(", ")) {
                result = result.substring(0, result.length() - 2);
            }
            return result + ".";
        } else {
            int found = total - notFound;
            if (found == 0) {
                return String.format("No repositories are using %s in the currently scanned set.", packageName);
            } else {
                return String.format("Found %d %s using %s across %d %s.", 
                    found, found == 1 ? "repository" : "repositories", 
                    packageName, total, total == 1 ? "repository" : "repositories");
            }
        }
    }
}
