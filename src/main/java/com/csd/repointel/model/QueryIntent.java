package com.csd.repointel.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QueryIntent {
    private String rawQuery;
    private IntentType intentType;
    private String packageName; // e.g., "log4j" or "org.apache.logging.log4j:log4j-core"
    private String groupId;
    private String artifactId;
    private String version;
    private ComparisonType comparisonType; // EXACT, BELOW, ABOVE, ALL
    private ExportFormat exportFormat; // CSV, EXCEL, JSON, NONE
    private ExportScope exportScope; // PER_REPO, COMBINED, NONE
    private boolean fixRequested;
}
