package com.csd.repointel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM Assistant Service for natural language processing and explanations.
 * 
 * CRITICAL RULES:
 * - This service is ONLY for NLP, explanations, and text generation
 * - It NEVER decides vulnerability status, affected ranges, or fixed versions
 * - All vulnerability data must come from VulnerabilitySourceClient
 * - This service formats and explains data-driven results in human-friendly language
 */
@Service
@Slf4j
public class LlmAssistantService {

    private final WebClient openaiClient;
    private final ObjectMapper objectMapper;
    
    @Value("${openai.api.key:}")
    private String apiKey;
    
    @Value("${openai.model:gpt-4}")
    private String model;
    
    @Value("${openai.enabled:false}")
    private boolean enabled;

    public LlmAssistantService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.openaiClient = webClientBuilder
                .baseUrl("https://api.openai.com/v1")
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Parse user query to extract intent and entities.
     * Returns structured intent that can be used for routing.
     */
    public QueryIntent parseUserQuery(String query) {
        if (!enabled || apiKey == null || apiKey.isEmpty()) {
            log.debug("LLM disabled, using keyword-based parsing");
            return parseQueryKeywordBased(query);
        }

        try {
            String systemPrompt = """
                You are a query parser for a vulnerability detection system.
                Parse the user's question and extract:
                1. Intent: (list_vulnerabilities, find_by_cve, find_by_package, suggest_fixes, explain, summary)
                2. Entities: CVE IDs, package names, repo names mentioned
                3. Filters: severity levels, status (vulnerable/safe)
                
                Respond in JSON format:
                {
                  "intent": "intent_name",
                  "entities": {
                    "cve": ["CVE-2021-44228"],
                    "package": ["log4j-core"],
                    "repo": ["service-a"],
                    "severity": ["HIGH", "CRITICAL"]
                  }
                }
                """;

            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", query)
            ));
            request.put("temperature", 0.3);
            request.put("max_tokens", 300);

            String response = openaiClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .onErrorResume(e -> {
                        log.warn("LLM query parsing failed, falling back to keywords: {}", e.getMessage());
                        return Mono.empty();
                    })
                    .block();

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                String content = root.path("choices").get(0).path("message").path("content").asText();
                JsonNode intentData = objectMapper.readTree(content);
                
                return QueryIntent.builder()
                        .intent(intentData.path("intent").asText())
                        .entities(objectMapper.convertValue(intentData.path("entities"), Map.class))
                        .build();
            }
        } catch (Exception e) {
            log.error("LLM parsing error: {}", e.getMessage());
        }

        return parseQueryKeywordBased(query);
    }

    /**
     * Generate human-readable explanation of vulnerability results.
     * This ONLY formats existing data, does NOT invent data.
     */
    public String generateExplanation(String context, Map<String, Object> vulnerabilityData) {
        if (!enabled || apiKey == null || apiKey.isEmpty()) {
            return generateSimpleExplanation(vulnerabilityData);
        }

        try {
            String systemPrompt = """
                You are a vulnerability explanation assistant.
                Given vulnerability data from a real database (OSV.dev, Mend, etc.),
                generate a clear, professional explanation.
                
                CRITICAL RULES:
                - Use ONLY the data provided
                - If fixedVersion is null, say "No fixed version available" - do NOT suggest "latest"
                - If affectedRange is missing, say "Affected range unknown"
                - Do NOT invent or guess any technical data
                - Focus on impact explanation and actionable recommendations based on provided data
                """;

            String dataJson = objectMapper.writeValueAsString(vulnerabilityData);
            String userPrompt = context + "\n\nVulnerability Data (use exactly this):\n" + dataJson;

            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
            ));
            request.put("temperature", 0.5);
            request.put("max_tokens", 500);

            String response = openaiClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                return root.path("choices").get(0).path("message").path("content").asText();
            }
        } catch (Exception e) {
            log.error("LLM explanation generation failed: {}", e.getMessage());
        }

        return generateSimpleExplanation(vulnerabilityData);
    }

    /**
     * Generate upgrade recommendations based on real fixed versions.
     * NEVER invents version numbers.
     */
    public String generateUpgradeRecommendation(Map<String, Object> vulnerabilityContext) {
        if (!enabled || apiKey == null || apiKey.isEmpty()) {
            return generateSimpleUpgradeText(vulnerabilityContext);
        }

        try {
            String systemPrompt = """
                Generate a professional upgrade recommendation for a vulnerability fix.
                
                RULES:
                - Use ONLY the fixedVersion provided in the data
                - If fixedVersion is null, recommend checking official sources
                - Include severity and CVE/advisory ID
                - Provide clear upgrade steps
                - Do NOT invent version numbers
                """;

            String dataJson = objectMapper.writeValueAsString(vulnerabilityContext);

            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", "Generate upgrade recommendation:\n" + dataJson)
            ));
            request.put("temperature", 0.4);
            request.put("max_tokens", 400);

            String response = openaiClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (response != null) {
                JsonNode root = objectMapper.readTree(response);
                return root.path("choices").get(0).path("message").path("content").asText();
            }
        } catch (Exception e) {
            log.error("LLM recommendation generation failed: {}", e.getMessage());
        }

        return generateSimpleUpgradeText(vulnerabilityContext);
    }

    // Fallback methods when LLM is disabled or fails
    
    private QueryIntent parseQueryKeywordBased(String query) {
        String lowerQuery = query.toLowerCase();
        String intent = "list_vulnerabilities"; // default
        Map<String, Object> entities = new HashMap<>();

        if (lowerQuery.contains("cve-")) {
            intent = "find_by_cve";
            // Extract CVE IDs
        } else if (lowerQuery.contains("fix") || lowerQuery.contains("upgrade")) {
            intent = "suggest_fixes";
        } else if (lowerQuery.contains("explain")) {
            intent = "explain";
        } else if (lowerQuery.contains("summary")) {
            intent = "summary";
        }

        return QueryIntent.builder()
                .intent(intent)
                .entities(entities)
                .build();
    }

    private String generateSimpleExplanation(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("Vulnerability Details:\n");
        
        if (data.containsKey("cveId")) {
            sb.append("- CVE/Advisory: ").append(data.get("cveId")).append("\n");
        }
        if (data.containsKey("severity")) {
            sb.append("- Severity: ").append(data.get("severity")).append("\n");
        }
        if (data.containsKey("affectedRange")) {
            sb.append("- Affected Range: ").append(data.get("affectedRange")).append("\n");
        }
        if (data.containsKey("fixedVersion")) {
            Object fixed = data.get("fixedVersion");
            sb.append("- Fixed Version: ").append(fixed != null ? fixed : "Not available").append("\n");
        }
        
        return sb.toString();
    }

    private String generateSimpleUpgradeText(Map<String, Object> context) {
        Object fixedVersion = context.get("fixedVersion");
        if (fixedVersion != null && !fixedVersion.toString().isEmpty()) {
            return String.format("Upgrade to version %s or later to address this vulnerability.", fixedVersion);
        }
        return "No fixed version available in vulnerability database. Check official sources for updates.";
    }

    // Inner class for structured query intent
    @lombok.Builder
    @lombok.Data
    public static class QueryIntent {
        private String intent;
        private Map<String, Object> entities;
    }
}
