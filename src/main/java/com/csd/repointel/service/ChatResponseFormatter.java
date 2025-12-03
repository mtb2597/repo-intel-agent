package com.csd.repointel.service;

import com.csd.repointel.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service to format chat responses with tables and structured output
 */
@Slf4j
@Service
public class ChatResponseFormatter {

    /**
     * Format version comparison results into a structured chat response
     */
    public String formatVersionComparison(VersionComparisonResult result) {
        StringBuilder sb = new StringBuilder();

        // Natural language summary
        sb.append(result.getSummary().getNaturalLanguageSummary()).append("\n\n");

        // Table header
        sb.append("```\n");
        sb.append(String.format("%-30s | %-25s | %-20s | %s\n", 
            "Repo Name", "Package", "Found Versions", "Status"));
        sb.append("-".repeat(120)).append("\n");

        // Table rows
        for (RepoVersionStatus status : result.getRepoStatuses()) {
            String versions = status.getFoundVersions().isEmpty() 
                ? "none" 
                : String.join(", ", status.getFoundVersions());
            
            sb.append(String.format("%-30s | %-25s | %-20s | %s %s\n",
                truncate(status.getRepoName(), 30),
                truncate(status.getPackageName() != null ? status.getPackageName() : "N/A", 25),
                truncate(versions, 20),
                status.getStatusEmoji(),
                status.getMessage()));
        }
        sb.append("```\n\n");

        // Export options
        sb.append("**Export options:**\n");
        sb.append("- CSV: `/api/export/csv?package=").append(encode(result.getPackageName())).append("`\n");
        sb.append("- Excel: `/api/export/excel?package=").append(encode(result.getPackageName())).append("`\n");
        sb.append("- JSON: `/api/export/json?package=").append(encode(result.getPackageName())).append("`\n\n");

        // Recommendations
        int needsUpdate = result.getSummary().getOlderRepos();
        if (needsUpdate > 0) {
            sb.append("**💡 Recommendation:** ");
            sb.append(String.format("%d %s need updates. ", 
                needsUpdate, needsUpdate == 1 ? "repository" : "repositories"));
            sb.append("Want me to prepare patched files?\n");
        }

        return sb.toString();
    }

    /**
     * Format simple search results
     */
    public String formatSearchResults(String keyword, List<String> results) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d results for keyword '%s':\n\n", results.size(), keyword));
        
        for (String result : results) {
            sb.append("- ").append(result).append("\n");
        }
        
        return sb.toString();
    }

    /**
     * Format export confirmation
     */
    public String formatExportConfirmation(ExportFormat format, ExportScope scope, String packageName) {
        return String.format("Results for '%s' exported as %s (%s mode). Download available at the provided link.",
            packageName, format.name(), scope.name().toLowerCase().replace("_", " "));
    }

    /**
     * Format fix preview
     */
    public String formatFixPreview(List<FixSuggestion> fixes) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**Fix Preview** - %d %s will be updated:\n\n", 
            fixes.size(), fixes.size() == 1 ? "repository" : "repositories"));

        for (FixSuggestion fix : fixes) {
            sb.append(String.format("### %s\n", fix.getRepoName()));
            sb.append(String.format("- Package: %s\n", fix.getPackageName()));
            sb.append(String.format("- Current: %s → Target: %s\n", fix.getCurrentVersion(), fix.getTargetVersion()));
            sb.append(String.format("- Files affected: %d\n", fix.getFileChanges().size()));
            
            if (fix.getPreviewDiff() != null && !fix.getPreviewDiff().isBlank()) {
                sb.append("\n**Preview:**\n```diff\n");
                sb.append(fix.getPreviewDiff()).append("\n```\n");
            }
            sb.append("\n");
        }

        sb.append("**Download patched files:** `/api/fix/download?package=...`\n");
        
        return sb.toString();
    }

    /**
     * Format error message
     */
    public String formatError(String message) {
        return "❌ " + message;
    }

    /**
     * Format clarification request
     */
    public String formatClarification(String question) {
        return "🤔 " + question;
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength - 3) + "...";
    }

    private String encode(String s) {
        if (s == null) return "";
        return s.replace(" ", "%20");
    }
}
