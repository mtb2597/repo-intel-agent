package com.csd.repointel.controller;

import com.csd.repointel.model.*;
import com.csd.repointel.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

/**
 * REST API for exporting version comparison results
 */
@Slf4j
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final IntentParserService intentParserService;
    private final VersionComparisonService versionComparisonService;
    private final ExportService exportService;

    public ExportController(IntentParserService intentParserService,
                           VersionComparisonService versionComparisonService,
                           ExportService exportService) {
        this.intentParserService = intentParserService;
        this.versionComparisonService = versionComparisonService;
        this.exportService = exportService;
    }

    /**
     * Export version comparison as CSV
     * 
     * @param packageName Package to compare (e.g., "log4j" or "org.apache.logging.log4j:log4j-core")
     * @param version Optional version to compare against
     * @param scope Export scope: "combined" or "per_repo"
     */
    @GetMapping(value = "/csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv(
            @RequestParam String packageName,
            @RequestParam(required = false) String version,
            @RequestParam(defaultValue = "combined") String scope) {
        
        log.info("Export CSV request: package={}, version={}, scope={}", packageName, version, scope);

        // Build query intent
        QueryIntent intent = QueryIntent.builder()
                .packageName(packageName)
                .version(version)
                .intentType(IntentType.VERSION_CHECK)
                .comparisonType(ComparisonType.ALL)
                .build();

        // Parse coordinates if not provided
        if (intent.getGroupId() == null && intent.getArtifactId() == null) {
            QueryIntent parsed = intentParserService.parseQuery(packageName);
            intent.setGroupId(parsed.getGroupId());
            intent.setArtifactId(parsed.getArtifactId());
        }

        // Get comparison results
        VersionComparisonResult result = versionComparisonService.compareVersionsAcrossRepos(intent);

        // Export as CSV
        ExportScope exportScope = scope.equalsIgnoreCase("per_repo") ? ExportScope.PER_REPO : ExportScope.COMBINED;
        String csv = exportService.exportCsv(result, exportScope);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"version-comparison.csv\"");

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    /**
     * Export version comparison as Excel
     */
    @GetMapping(value = "/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam String packageName,
            @RequestParam(required = false) String version,
            @RequestParam(defaultValue = "combined") String scope) throws IOException {
        
        log.info("Export Excel request: package={}, version={}, scope={}", packageName, version, scope);

        // Build query intent
        QueryIntent intent = QueryIntent.builder()
                .packageName(packageName)
                .version(version)
                .intentType(IntentType.VERSION_CHECK)
                .comparisonType(ComparisonType.ALL)
                .build();

        // Parse coordinates if not provided
        if (intent.getGroupId() == null && intent.getArtifactId() == null) {
            QueryIntent parsed = intentParserService.parseQuery(packageName);
            intent.setGroupId(parsed.getGroupId());
            intent.setArtifactId(parsed.getArtifactId());
        }

        // Get comparison results
        VersionComparisonResult result = versionComparisonService.compareVersionsAcrossRepos(intent);

        // Export as Excel
        ExportScope exportScope = scope.equalsIgnoreCase("per_repo") ? ExportScope.PER_REPO : ExportScope.COMBINED;
        byte[] excel = exportService.exportExcel(result, exportScope);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"version-comparison.xlsx\"");

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel);
    }

    /**
     * Export version comparison as JSON
     */
    @GetMapping(value = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> exportJson(
            @RequestParam String packageName,
            @RequestParam(required = false) String version) throws IOException {
        
        log.info("Export JSON request: package={}, version={}", packageName, version);

        // Build query intent
        QueryIntent intent = QueryIntent.builder()
                .packageName(packageName)
                .version(version)
                .intentType(IntentType.VERSION_CHECK)
                .comparisonType(ComparisonType.ALL)
                .build();

        // Parse coordinates if not provided
        if (intent.getGroupId() == null && intent.getArtifactId() == null) {
            QueryIntent parsed = intentParserService.parseQuery(packageName);
            intent.setGroupId(parsed.getGroupId());
            intent.setArtifactId(parsed.getArtifactId());
        }

        // Get comparison results
        VersionComparisonResult result = versionComparisonService.compareVersionsAcrossRepos(intent);

        // Export as JSON
        String json = exportService.exportJson(result);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    /**
     * Get available export options for a package
     */
    @GetMapping("/links")
    public ResponseEntity<Map<String, String>> getExportLinks(@RequestParam String packageName) {
        log.info("Getting export links for package: {}", packageName);
        Map<String, String> links = exportService.getExportLinks(packageName);
        return ResponseEntity.ok(links);
    }
}
