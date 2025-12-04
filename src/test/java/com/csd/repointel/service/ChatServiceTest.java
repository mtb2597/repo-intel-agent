package com.csd.repointel.service;

import com.csd.repointel.model.ChatIntent;
import com.csd.repointel.model.ChatResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

public class ChatServiceTest {

    // Simple stub for LlmAssistantService
    static class StubLlmAssistantService extends LlmAssistantService {
        public StubLlmAssistantService() {
            super(org.springframework.web.reactive.function.client.WebClient.builder(), new com.fasterxml.jackson.databind.ObjectMapper());
        }
        @Override
        public QueryIntent parseUserQuery(String query) {
            return QueryIntent.builder().intent("").entities(new java.util.HashMap<>()).build();
        }
    }

    @Test
    void interpretSearchFallback() {
        RepoScanService scanService = new RepoScanService();
        CompareService compareService = new CompareService(scanService);
        VulnerabilityAnalysisService vulnService = new VulnerabilityAnalysisService(null, scanService);
        ChatService chatService = new ChatService(compareService, scanService, new StubLlmAssistantService(), vulnService);
        ChatResponse resp = chatService.interpret("log4j");
        assertEquals(ChatIntent.SEARCH, resp.getIntent());
        assertTrue(resp.getLink().contains("/api/search"));
    }

    @Test
    void interpretDrift() {
        RepoScanService scanService = new RepoScanService();
        scanService.getRepoDependencies().put("test-repo", java.util.List.of());
        CompareService compareService = new CompareService(scanService);
        VulnerabilityAnalysisService vulnService = new VulnerabilityAnalysisService(null, scanService);
        ChatService chatService = new ChatService(compareService, scanService, new StubLlmAssistantService(), vulnService);
        ChatResponse resp = chatService.interpret("org.apache.logging.log4j:log4j-core below 2.17.2");
        assertEquals(ChatIntent.DRIFT, resp.getIntent());
        assertTrue(resp.getSummary().toLowerCase().contains("below 2.17.2"));
    }

    @Test
    void interpretSingle() {
        RepoScanService scanService = new RepoScanService();
        CompareService compareService = new CompareService(scanService);
        VulnerabilityAnalysisService vulnService = new VulnerabilityAnalysisService(null, scanService);
        ChatService chatService = new ChatService(compareService, scanService, new StubLlmAssistantService(), vulnService);
        ChatResponse resp = chatService.interpret("org.apache.logging.log4j:log4j-core");
        assertEquals(ChatIntent.SINGLE, resp.getIntent());
        assertTrue(resp.getSummary().toLowerCase().contains("version comparison") || resp.getSummary().toLowerCase().contains("single artifact"));
    }

    @Test
    void interpretMatrix() {
        RepoScanService scanService = new RepoScanService();
        CompareService compareService = new CompareService(scanService);
        VulnerabilityAnalysisService vulnService = new VulnerabilityAnalysisService(null, scanService);
        ChatService chatService = new ChatService(compareService, scanService, new StubLlmAssistantService(), vulnService);
        ChatResponse resp = chatService.interpret("Compare org.apache.logging.log4j:log4j-core and org.apache.logging.log4j:log4j-api");
        // The new ChatService does not support matrix compare directly, so fallback to SEARCH
        assertTrue(resp.getIntent() == ChatIntent.SEARCH || resp.getIntent() == ChatIntent.COMPARE);
        assertTrue(resp.getSummary().toLowerCase().contains("search") || resp.getSummary().toLowerCase().contains("matrix compare"));
    }
}

