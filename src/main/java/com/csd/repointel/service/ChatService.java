
package com.csd.repointel.service;

import com.csd.repointel.model.RepoVulnerabilityReport;
import com.csd.repointel.model.DependencyVulnerability;

import com.csd.repointel.model.ChatIntent;
import com.csd.repointel.model.ChatResponse;
import org.springframework.stereotype.Service;
import java.util.*;

@Service

public class ChatService {
    private final CompareService compareService;
    private final RepoScanService repoScanService;
    private final LlmAssistantService llmAssistantService;
    private final VulnerabilityAnalysisService vulnerabilityAnalysisService;

    public ChatService(CompareService compareService, RepoScanService repoScanService, LlmAssistantService llmAssistantService, VulnerabilityAnalysisService vulnerabilityAnalysisService) {
        this.compareService = compareService;
        this.repoScanService = repoScanService;
        this.llmAssistantService = llmAssistantService;
        this.vulnerabilityAnalysisService = vulnerabilityAnalysisService;
    }

    public ChatResponse interpret(String query) {
        String q = query.trim().toLowerCase();
        Map<String, Object> data = new HashMap<>();

        // AI-powered intent parsing
        LlmAssistantService.QueryIntent intent = llmAssistantService.parseUserQuery(query);

        // Example: Check where log4j 1.x is used
        if (q.contains("log4j 1.x") || (intent.getEntities() != null && intent.getEntities().toString().contains("log4j"))) {
            Map<String, String> drift = compareService.drift("log4j", "log4j", "1.9.9");
            data.put("drift", drift);
            return ChatResponse.builder()
                    .intent(ChatIntent.DRIFT)
                    .summary("Repositories using log4j 1.x (below 2.0.0)")
                    .link("/api/drift?groupId=log4j&artifactId=log4j&minVersion=2.0.0")
                    .data(data)
                    .build();
        }

        // Example: Which repos use spring-boot below version 3.0.0
        if (q.contains("spring-boot below version 3.0.0") || (intent.getEntities() != null && intent.getEntities().toString().contains("spring-boot"))) {
            Map<String, String> drift = compareService.drift("org.springframework.boot", "spring-boot", "3.0.0");
            data.put("drift", drift);
            return ChatResponse.builder()
                    .intent(ChatIntent.DRIFT)
                    .summary("Repositories using spring-boot below 3.0.0")
                    .link("/api/drift?groupId=org.springframework.boot&artifactId=spring-boot&minVersion=3.0.0")
                    .data(data)
                    .build();
        }

        // Example: Show me all versions of jackson
        if (q.contains("versions of jackson") || (intent.getEntities() != null && intent.getEntities().toString().contains("jackson"))) {
            Map<String, String> compare = compareService.compareSingle("com.fasterxml.jackson.core", "jackson-databind");
            data.put("compare", compare);
            return ChatResponse.builder()
                    .intent(ChatIntent.SINGLE)
                    .summary("All versions of jackson-databind in repositories")
                    .link("/api/compare?groupId=com.fasterxml.jackson.core&artifactId=jackson-databind")
                    .data(data)
                    .build();
        }

        // Example: Export the version comparison for org.apache.logging.log4j:log4j-core
        if (q.contains("export version comparison") || q.contains("org.apache.logging.log4j:log4j-core")) {
            Map<String, String> compare = compareService.compareSingle("org.apache.logging.log4j", "log4j-core");
            data.put("compare", compare);
            // You can add CSV/XLSX export logic here if needed
            return ChatResponse.builder()
                    .intent(ChatIntent.SINGLE)
                    .summary("Version comparison for org.apache.logging.log4j:log4j-core")
                    .link("/api/compare?groupId=org.apache.logging.log4j&artifactId=log4j-core")
                    .data(data)
                    .build();
        }



        // Example: Show all vulnerabilities
        if (q.contains("show all vulnerabilities") || intent.getIntent().equals("list_vulnerabilities")) {
            List<RepoVulnerabilityReport> reports = vulnerabilityAnalysisService.analyzeAllRepositories();
            Map<String, List<DependencyVulnerability>> vulnerabilitiesByRepo = new LinkedHashMap<>();
            for (RepoVulnerabilityReport report : reports) {
                List<DependencyVulnerability> vulns = report.getVulnerabilities();
                if (vulns != null && !vulns.isEmpty()) {
                    vulnerabilitiesByRepo.put(report.getRepoName(), vulns.stream()
                        .filter(DependencyVulnerability::isVulnerable)
                        .toList());
                }
            }
            data.put("vulnerabilities", vulnerabilitiesByRepo);
            return ChatResponse.builder()
                    .intent(ChatIntent.SEARCH)
                    .summary("All vulnerable dependencies across repositories")
                    .link("/api/vulnerabilities")
                    .data(data)
                    .build();
        }

        // Example: Which repos have critical CVEs?
        if (q.contains("critical cve") || (intent.getEntities() != null && intent.getEntities().toString().contains("critical"))) {
            List<RepoVulnerabilityReport> reports = vulnerabilityAnalysisService.analyzeAllRepositories();
            Map<String, List<DependencyVulnerability>> criticalByRepo = new LinkedHashMap<>();
            int totalCritical = 0;
            for (RepoVulnerabilityReport report : reports) {
                List<DependencyVulnerability> criticalVulns = report.getCriticalVulnerabilities();
                if (criticalVulns != null && !criticalVulns.isEmpty()) {
                    criticalByRepo.put(report.getRepoName(), criticalVulns);
                    totalCritical += criticalVulns.size();
                }
            }
            // Use LlmAssistantService to generate a human-friendly summary
            String aiSummary = llmAssistantService.generateExplanation(
                "Summarize which repositories have critical CVEs and what actions are recommended.",
                Map.of(
                    "totalCritical", totalCritical,
                    "repos", criticalByRepo
                )
            );
            data.put("criticalVulnerabilities", criticalByRepo);
            data.put("aiSummary", aiSummary);
            return ChatResponse.builder()
                    .intent(ChatIntent.SEARCH)
                    .summary(aiSummary)
                    .link("/api/vulnerabilities?severity=critical")
                    .data(data)
                    .build();
        }

        // Fallback: JDK version query
        if (q.contains("jdk") && (q.contains("version") || q.contains("java version"))) {
            data.put("jdkVersions", repoScanService.getJdkVersions());
            return ChatResponse.builder()
                    .intent(ChatIntent.SEARCH)
                    .summary("JDK versions used in repositories")
                    .link("/api/state")
                    .data(data)
                    .build();
        }

        // Fallback: keyword search
        data.put("results", compareService.search(q));
        return ChatResponse.builder()
                .intent(ChatIntent.SEARCH)
                .summary("Search for keyword: " + q)
                .link("/api/search?keyword=" + encode(q))
                .data(data)
                .build();
    }

    private String encode(String s) { return s.replace(" ", "%20"); }
}

