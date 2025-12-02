package com.csd.repointel.controller;

import com.csd.repointel.model.RepoScanResult;
import com.csd.repointel.service.RepoScanService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@Slf4j
public class ScanController {

    private final RepoScanService repoScanService;

    public ScanController(RepoScanService repoScanService) { this.repoScanService = repoScanService; }

    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestBody ScanRequest request) {
        List<CompletableFuture<RepoScanResult>> futures = request.getRepos().stream()
                .map(repoScanService::scanRepo)
                .collect(Collectors.toList());
        List<RepoScanResult> results = futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
        return ResponseEntity.ok(Map.of(
                "results", results,
                "summary", results.stream().collect(Collectors.toMap(RepoScanResult::getRepoName, RepoScanResult::isSuccess))
        ));
    }

    @GetMapping("/state")
    public Map<String, Integer> state() {
        return Map.of("repoCount", repoScanService.getRepoDependencies().size());
    }

    @Data
    public static class ScanRequest {
        private List<String> repos;
    }
}

