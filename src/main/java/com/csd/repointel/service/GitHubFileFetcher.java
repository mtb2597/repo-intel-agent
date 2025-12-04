package com.csd.repointel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.util.*;

public class GitHubFileFetcher {
    private static final OkHttpClient client = new OkHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Fetches all pom.xml, package.json and package-lock.json files from a GitHub repo using the GitHub API.
     * @param owner GitHub repo owner
     * @param repo GitHub repo name
     * @param branch Branch name (e.g., "main" or "master")
     * @param token Personal access token for authentication
     * @return Map of file path to file content
     * @throws IOException on network or API error
     */
    public static Map<String, String> fetchPomAndPackageJsonFiles(String owner, String repo, String branch, String token) throws IOException {
        Map<String, String> result = new HashMap<>();
        String effectiveBranch = branch;
        if (effectiveBranch == null || effectiveBranch.isEmpty()) {
            // Fetch repo metadata to get default branch
            String repoUrl = String.format("https://api.github.com/repos/%s/%s", owner, repo);
            Request repoRequest = new Request.Builder()
                    .url(repoUrl)
                    .header("Authorization", "token " + token)
                    .build();
            try (Response repoResponse = client.newCall(repoRequest).execute()) {
                if (!repoResponse.isSuccessful()) throw new IOException("Failed to fetch repo metadata: " + repoResponse);
                JsonNode repoRoot = mapper.readTree(repoResponse.body().string());
                JsonNode defaultBranchNode = repoRoot.get("default_branch");
                if (defaultBranchNode != null && !defaultBranchNode.asText().isEmpty()) {
                    effectiveBranch = defaultBranchNode.asText();
                } else {
                    effectiveBranch = "main"; // fallback
                }
            }
        }
        String treeUrl = String.format("https://api.github.com/repos/%s/%s/git/trees/%s?recursive=1", owner, repo, effectiveBranch);
        Request treeRequest = new Request.Builder()
                .url(treeUrl)
                .header("Authorization", "token " + token)
                .build();
        try (Response response = client.newCall(treeRequest).execute()) {
            if (!response.isSuccessful()) throw new IOException("Failed to fetch repo tree: " + response);
            JsonNode root = mapper.readTree(response.body().string());
            JsonNode tree = root.get("tree");
            if (tree == null || !tree.isArray()) throw new IOException("Invalid tree response");
            for (JsonNode node : tree) {
                String path = node.get("path").asText();
                if (path.endsWith("pom.xml") || path.endsWith("package.json") || path.endsWith("package-lock.json")) {
                    String rawUrl = String.format("https://raw.githubusercontent.com/%s/%s/%s/%s", owner, repo, effectiveBranch, path);
                    Request fileRequest = new Request.Builder()
                            .url(rawUrl)
                            .header("Authorization", "token " + token)
                            .build();
                    try (Response fileResponse = client.newCall(fileRequest).execute()) {
                        if (fileResponse.isSuccessful()) {
                            result.put(path, fileResponse.body().string());
                        }
                    }
                }
            }
        }
        return result;
    }
}
