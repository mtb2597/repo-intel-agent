package com.csd.repointel.service;

import com.csd.repointel.model.ChatIntent;
import com.csd.repointel.model.ChatResponse;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatService {

    private final CompareService compareService;
    private final RepoScanService repoScanService;

    private static final Pattern COORD = Pattern.compile("([a-zA-Z0-9_.-]+):([a-zA-Z0-9_.-]+)(?:\\s+below\\s+([a-zA-Z0-9_.-]+))?");
    private static final Pattern BELOW_PATTERN = Pattern.compile("below\\s+([0-9A-Za-z_.-]+)");

    public ChatService(CompareService compareService, RepoScanService repoScanService) {
        this.compareService = compareService;
        this.repoScanService = repoScanService;
    }

    public ChatResponse interpret(String query) {
        String q = query.trim().toLowerCase();

        // Check for JDK version query
        if (q.contains("jdk") && (q.contains("version") || q.contains("java version"))) {
            Map<String, Object> data = new HashMap<>();
            data.put("jdkVersions", repoScanService.getJdkVersions());
            return ChatResponse.builder()
                    .intent(ChatIntent.SEARCH)
                    .summary("JDK versions used in repositories")
                    .link("/api/state")
                    .data(data)
                    .build();
        }

        Matcher m = COORD.matcher(query);
        List<String> coords = new ArrayList<>();
        String minVersion = null;
        while (m.find()) {
            coords.add(m.group(1) + ":" + m.group(2));
            if (m.group(3) != null) minVersion = m.group(3);
        }
        if (coords.isEmpty()) {
            // fallback search
            Map<String, Object> data = new HashMap<>();
            data.put("results", compareService.search(q));
            return ChatResponse.builder()
                    .intent(ChatIntent.SEARCH)
                    .summary("Search for keyword: " + q)
                    .link("/api/search?keyword=" + encode(q))
                    .data(data)
                    .build();
        }
        if (coords.size() == 1 && minVersion != null) {
            String[] parts = coords.get(0).split(":");
            Map<String, Object> data = new HashMap<>();
            data.put("drift", compareService.drift(parts[0], parts[1], minVersion));
            return ChatResponse.builder()
                    .intent(ChatIntent.DRIFT)
                    .summary("Repos with " + coords.get(0) + " below " + minVersion)
                    .link("/api/drift?groupId=" + encode(parts[0]) + "&artifactId=" + encode(parts[1]) + "&minVersion=" + encode(minVersion))
                    .data(data)
                    .build();
        }
        if (coords.size() == 1) {
            String[] parts = coords.get(0).split(":");
            Map<String, Object> data = new HashMap<>();
            data.put("compare", compareService.compareSingle(parts[0], parts[1]));
            return ChatResponse.builder()
                    .intent(ChatIntent.SINGLE)
                    .summary("Compare single artifact: " + coords.get(0))
                    .link("/api/compare?groupId=" + encode(parts[0]) + "&artifactId=" + encode(parts[1]))
                    .data(data)
                    .build();
        }
        // multiple compare
        Map<String, Object> data = new HashMap<>();
        data.put("matrix", compareService.matrix(coords));
        return ChatResponse.builder()
                .intent(ChatIntent.COMPARE)
                .summary("Matrix compare: " + String.join(", ", coords))
                .link("/api/compare/matrix/table?artifacts=" + encode(String.join(",", coords)))
                .data(data)
                .build();
    }

    private String encode(String s) { return s.replace(" ", "%20"); }
}

