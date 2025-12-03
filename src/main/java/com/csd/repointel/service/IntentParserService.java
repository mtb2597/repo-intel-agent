package com.csd.repointel.service;

import com.csd.repointel.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to parse natural language queries and extract intent
 */
@Slf4j
@Service
public class IntentParserService {

    // Patterns for parsing
    private static final Pattern PACKAGE_COORD = Pattern.compile("([a-zA-Z0-9_.-]+):([a-zA-Z0-9_.-]+)");
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?:version\\s+)?([0-9]+(?:\\.[0-9]+)*(?:[.-][a-zA-Z0-9]+)*)");
    private static final Pattern SIMPLE_NAME = Pattern.compile("\\b([a-z][a-z0-9-]+)\\b", Pattern.CASE_INSENSITIVE);

    public QueryIntent parseQuery(String query) {
        if (query == null || query.isBlank()) {
            return buildUnknownIntent(query);
        }

        String q = query.trim();
        String qLower = q.toLowerCase();
        log.info("Parsing query: {}", q);

        QueryIntent.QueryIntentBuilder builder = QueryIntent.builder().rawQuery(q);

        // Check for vulnerability-related intents
        if (qLower.contains("vulnerabilit") || qLower.contains("cve") || qLower.contains("security") || 
            qLower.contains("exploit") || qLower.contains("risk score") || qLower.contains("health score")) {
            
            if (qLower.contains("export")) {
                builder.intentType(IntentType.VULNERABILITY_EXPORT);
                builder.exportFormat(detectExportFormat(qLower));
            } else if (qLower.contains("fix") || qLower.contains("patch") || qLower.contains("remediat")) {
                builder.intentType(IntentType.VULNERABILITY_FIX);
                builder.fixRequested(true);
            } else if (qLower.contains("all") || qLower.contains("list") || qLower.contains("show")) {
                builder.intentType(IntentType.VULNERABILITY_LIST);
            } else if (qLower.contains("repo") || qLower.contains("in ") || qLower.contains("which repos")) {
                builder.intentType(IntentType.VULNERABILITY_BY_REPO);
            } else if (qLower.contains("statistic") || qLower.contains("summary") || qLower.contains("overview")) {
                builder.intentType(IntentType.VULNERABILITY_STATS);
            } else {
                // Default vulnerability query
                builder.intentType(IntentType.VULNERABILITY_CHECK);
            }
        }
        // Check for export intent
        else if (qLower.contains("export")) {
            builder.intentType(IntentType.EXPORT);
            builder.exportFormat(detectExportFormat(qLower));
            builder.exportScope(detectExportScope(qLower));
        }
        // Check for fix intent
        else if (qLower.contains("fix") || qLower.contains("update") || qLower.contains("upgrade") || qLower.contains("patch")) {
            builder.intentType(IntentType.FIX_REQUEST);
            builder.fixRequested(true);
        }
        // Check for list all / show all
        else if (qLower.contains("show all") || qLower.contains("list all") || qLower.matches(".*all\\s+(outdated|versions).*")) {
            builder.intentType(IntentType.LIST_ALL);
            if (qLower.contains("outdated")) {
                builder.comparisonType(ComparisonType.OUTDATED);
            } else {
                builder.comparisonType(ComparisonType.ALL);
            }
        }
        // Version comparison intents
        else if (qLower.contains("below") || qLower.contains("under") || qLower.contains("less than") || qLower.contains("older than")) {
            builder.intentType(IntentType.VERSION_COMPARE);
            builder.comparisonType(ComparisonType.BELOW);
        }
        else if (qLower.contains("above") || qLower.contains("over") || qLower.contains("greater than") || qLower.contains("newer than")) {
            builder.intentType(IntentType.VERSION_COMPARE);
            builder.comparisonType(ComparisonType.ABOVE);
        }
        else if (qLower.contains("check") || qLower.contains("where") || qLower.contains("which") || qLower.contains("find") || qLower.contains("using")) {
            builder.intentType(IntentType.VERSION_CHECK);
            builder.comparisonType(ComparisonType.ALL);
        }
        else {
            builder.intentType(IntentType.SEARCH);
        }

        // Extract package information
        Matcher coordMatcher = PACKAGE_COORD.matcher(q);
        if (coordMatcher.find()) {
            builder.groupId(coordMatcher.group(1));
            builder.artifactId(coordMatcher.group(2));
            builder.packageName(coordMatcher.group(1) + ":" + coordMatcher.group(2));
        } else {
            // Try to extract simple package name (e.g., "log4j", "spring-boot", "jackson")
            String packageName = extractPackageName(qLower);
            if (packageName != null) {
                builder.packageName(packageName);
                // Try to guess groupId:artifactId from common packages
                String[] coords = guessCoordinates(packageName);
                if (coords != null) {
                    builder.groupId(coords[0]);
                    builder.artifactId(coords[1]);
                }
            }
        }

        // Extract version if present
        Matcher versionMatcher = VERSION_PATTERN.matcher(q);
        if (versionMatcher.find()) {
            builder.version(versionMatcher.group(1));
        }

        QueryIntent intent = builder.build();
        log.info("Parsed intent: type={}, package={}, version={}, comparison={}", 
                intent.getIntentType(), intent.getPackageName(), intent.getVersion(), intent.getComparisonType());
        
        return intent;
    }

    private ExportFormat detectExportFormat(String query) {
        if (query.contains("excel") || query.contains("xlsx")) {
            return ExportFormat.EXCEL;
        } else if (query.contains("csv")) {
            return ExportFormat.CSV;
        } else if (query.contains("json")) {
            return ExportFormat.JSON;
        }
        return ExportFormat.CSV; // default
    }

    private ExportScope detectExportScope(String query) {
        if (query.contains("per repo") || query.contains("per-repo") || query.contains("separate")) {
            return ExportScope.PER_REPO;
        } else if (query.contains("combined") || query.contains("single")) {
            return ExportScope.COMBINED;
        }
        return ExportScope.COMBINED; // default
    }

    private String extractPackageName(String query) {
        // Common package patterns to look for
        String[] commonPackages = {
            "log4j", "slf4j", "logback",
            "spring-boot", "spring-framework", "spring-core", "spring-web",
            "jackson", "gson", "json",
            "junit", "testng", "mockito",
            "hibernate", "jpa",
            "commons-lang", "commons-io", "commons-collections",
            "guava", "apache"
        };

        for (String pkg : commonPackages) {
            if (query.contains(pkg)) {
                return pkg;
            }
        }

        // Try to extract any word that looks like a package name
        Matcher m = SIMPLE_NAME.matcher(query);
        while (m.find()) {
            String word = m.group(1);
            if (word.length() > 3 && !isCommonWord(word)) {
                return word;
            }
        }

        return null;
    }

    private boolean isCommonWord(String word) {
        Set<String> common = Set.of("check", "where", "which", "repos", "using", "below", "above", 
                "version", "show", "find", "export", "list", "used", "have", "with", "from");
        return common.contains(word.toLowerCase());
    }

    private String[] guessCoordinates(String packageName) {
        // Map common package names to groupId:artifactId
        Map<String, String[]> commonMappings = Map.ofEntries(
            Map.entry("log4j", new String[]{"org.apache.logging.log4j", "log4j-core"}),
            Map.entry("slf4j", new String[]{"org.slf4j", "slf4j-api"}),
            Map.entry("logback", new String[]{"ch.qos.logback", "logback-classic"}),
            Map.entry("spring-boot", new String[]{"org.springframework.boot", "spring-boot-starter"}),
            Map.entry("spring-framework", new String[]{"org.springframework", "spring-core"}),
            Map.entry("spring-core", new String[]{"org.springframework", "spring-core"}),
            Map.entry("spring-web", new String[]{"org.springframework", "spring-web"}),
            Map.entry("jackson", new String[]{"com.fasterxml.jackson.core", "jackson-databind"}),
            Map.entry("gson", new String[]{"com.google.code.gson", "gson"}),
            Map.entry("junit", new String[]{"junit", "junit"}),
            Map.entry("mockito", new String[]{"org.mockito", "mockito-core"}),
            Map.entry("hibernate", new String[]{"org.hibernate", "hibernate-core"}),
            Map.entry("guava", new String[]{"com.google.guava", "guava"}),
            Map.entry("commons-lang", new String[]{"org.apache.commons", "commons-lang3"}),
            Map.entry("commons-io", new String[]{"commons-io", "commons-io"}),
            Map.entry("commons-collections", new String[]{"org.apache.commons", "commons-collections4"})
        );

        return commonMappings.get(packageName.toLowerCase());
    }

    private QueryIntent buildUnknownIntent(String query) {
        return QueryIntent.builder()
                .rawQuery(query)
                .intentType(IntentType.UNKNOWN)
                .comparisonType(ComparisonType.ALL)
                .exportFormat(ExportFormat.NONE)
                .exportScope(ExportScope.NONE)
                .fixRequested(false)
                .build();
    }
}
