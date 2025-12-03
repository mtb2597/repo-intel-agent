package com.csd.repointel.controller;

import com.csd.repointel.model.*;
import com.csd.repointel.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * REST API for fix generation and download (FUTURE FEATURE - STUBS)
 */
@Slf4j
@RestController
@RequestMapping("/api/fix")
public class FixController {

    private final IntentParserService intentParserService;
    private final VersionComparisonService versionComparisonService;
    private final FixGenerationService fixGenerationService;
    private final ChatResponseFormatter chatResponseFormatter;

    public FixController(IntentParserService intentParserService,
                        VersionComparisonService versionComparisonService,
                        FixGenerationService fixGenerationService,
                        ChatResponseFormatter chatResponseFormatter) {
        this.intentParserService = intentParserService;
        this.versionComparisonService = versionComparisonService;
        this.fixGenerationService = fixGenerationService;
        this.chatResponseFormatter = chatResponseFormatter;
    }

    /**
     * Generate fix preview for repositories with outdated versions
     * 
     * @param packageName Package to fix (e.g., "log4j")
     * @param currentVersion Current version (optional)
     * @param targetVersion Target version to upgrade to
     * @return Fix suggestions with preview diffs
     */
    @GetMapping("/preview")
    public ResponseEntity<Map<String, Object>> generateFixPreview(
            @RequestParam String packageName,
            @RequestParam(required = false) String currentVersion,
            @RequestParam String targetVersion) {
        
        log.info("Fix preview request: package={}, target={}", packageName, targetVersion);

        // Build query intent
        QueryIntent intent = QueryIntent.builder()
                .packageName(packageName)
                .version(currentVersion)
                .intentType(IntentType.VERSION_COMPARE)
                .comparisonType(ComparisonType.BELOW)
                .build();

        // Parse coordinates if not provided
        if (intent.getGroupId() == null && intent.getArtifactId() == null) {
            QueryIntent parsed = intentParserService.parseQuery(packageName);
            intent.setGroupId(parsed.getGroupId());
            intent.setArtifactId(parsed.getArtifactId());
        }

        // Get comparison results
        VersionComparisonResult result = versionComparisonService.compareVersionsAcrossRepos(intent);

        // Generate fix suggestions
        List<FixSuggestion> fixes = fixGenerationService.generateFixSuggestions(result, targetVersion);

        // Format response
        String formattedPreview = chatResponseFormatter.formatFixPreview(fixes);
        String statusMessage = fixGenerationService.getFixStatusMessage();

        return ResponseEntity.ok(Map.of(
                "fixes", fixes,
                "preview", formattedPreview,
                "status", statusMessage,
                "reposAffected", fixes.size()
        ));
    }

    /**
     * Download patched files as a ZIP archive
     * 
     * STUB IMPLEMENTATION - Returns mock ZIP for now
     */
    @GetMapping(value = "/download", produces = "application/zip")
    public ResponseEntity<byte[]> downloadPatchedFiles(
            @RequestParam String packageName,
            @RequestParam String targetVersion) throws IOException {
        
        log.info("Download patched files: package={}, target={}", packageName, targetVersion);

        // Build query intent
        QueryIntent intent = QueryIntent.builder()
                .packageName(packageName)
                .intentType(IntentType.FIX_REQUEST)
                .fixRequested(true)
                .build();

        // Parse coordinates if not provided
        if (intent.getGroupId() == null && intent.getArtifactId() == null) {
            QueryIntent parsed = intentParserService.parseQuery(packageName);
            intent.setGroupId(parsed.getGroupId());
            intent.setArtifactId(parsed.getArtifactId());
        }

        // Get comparison results
        VersionComparisonResult result = versionComparisonService.compareVersionsAcrossRepos(intent);

        // Generate fix suggestions
        List<FixSuggestion> fixes = fixGenerationService.generateFixSuggestions(result, targetVersion);

        // Create ZIP with all patched files
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (FixSuggestion fix : fixes) {
                Map<String, byte[]> patchedFiles = fixGenerationService.generatePatchedFiles(fix);
                
                for (Map.Entry<String, byte[]> entry : patchedFiles.entrySet()) {
                    ZipEntry zipEntry = new ZipEntry(entry.getKey());
                    zos.putNextEntry(zipEntry);
                    zos.write(entry.getValue());
                    zos.closeEntry();
                }
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.CONTENT_DISPOSITION, 
                "attachment; filename=\"patched-files-" + packageName + ".zip\"");

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(baos.toByteArray());
    }

    /**
     * Validate if a fix can be applied
     */
    @GetMapping("/validate")
    public ResponseEntity<Map<String, Object>> validateFix(
            @RequestParam String packageName,
            @RequestParam String repoName,
            @RequestParam String targetVersion) {
        
        log.info("Validate fix: repo={}, package={}, target={}", repoName, packageName, targetVersion);

        // This is a stub - actual validation would check file existence, version validity, etc.
        boolean isValid = true;
        String message = "Fix can be applied";

        return ResponseEntity.ok(Map.of(
                "valid", isValid,
                "message", message,
                "repoName", repoName,
                "packageName", packageName,
                "targetVersion", targetVersion
        ));
    }

    /**
     * Get affected files for a repository
     */
    @GetMapping("/affected-files")
    public ResponseEntity<Map<String, Object>> getAffectedFiles(
            @RequestParam String repoName,
            @RequestParam String packageName) {
        
        log.info("Get affected files: repo={}, package={}", repoName, packageName);

        List<String> affectedFiles = fixGenerationService.getAffectedFiles(repoName, packageName);

        return ResponseEntity.ok(Map.of(
                "repoName", repoName,
                "packageName", packageName,
                "affectedFiles", affectedFiles,
                "count", affectedFiles.size()
        ));
    }
}
