package com.csd.repointel.service;

import com.csd.repointel.model.ChatIntent;
import com.csd.repointel.model.ChatResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

public class ChatServiceTest {

    @Test
    void interpretSearchFallback() {
        RepoScanService scanService = new RepoScanService();
        CompareService compareService = new CompareService(scanService);
        ChatService chatService = new ChatService(compareService, scanService);
        ChatResponse resp = chatService.interpret("log4j");
        assertEquals(ChatIntent.SEARCH, resp.getIntent());
        assertTrue(resp.getLink().contains("/api/search"));
    }

    @Test
    void interpretDrift() {
        RepoScanService scanService = new RepoScanService();
        scanService.getRepoDependencies().put("test-repo", java.util.List.of());
        CompareService compareService = new CompareService(scanService);
        ChatService chatService = new ChatService(compareService, scanService);
        ChatResponse resp = chatService.interpret("org.apache.logging.log4j:log4j-core below 2.17.2");
        assertEquals(ChatIntent.DRIFT, resp.getIntent());
        assertTrue(resp.getSummary().contains("below 2.17.2"));
    }

    @Test
    void interpretSingle() {
        RepoScanService scanService = new RepoScanService();
        CompareService compareService = new CompareService(scanService);
        ChatService chatService = new ChatService(compareService, scanService);
        ChatResponse resp = chatService.interpret("org.apache.logging.log4j:log4j-core");
        assertEquals(ChatIntent.SINGLE, resp.getIntent());
        assertTrue(resp.getSummary().contains("single artifact"));
    }

    @Test
    void interpretMatrix() {
        RepoScanService scanService = new RepoScanService();
        CompareService compareService = new CompareService(scanService);
        ChatService chatService = new ChatService(compareService, scanService);
        ChatResponse resp = chatService.interpret("Compare org.apache.logging.log4j:log4j-core and org.apache.logging.log4j:log4j-api");
        assertEquals(ChatIntent.COMPARE, resp.getIntent());
        assertTrue(resp.getSummary().contains("Matrix compare"));
    }
}

