package com.csd.repointel.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class VersionComparisonResult {
    private String packageName; // e.g., "log4j" or "org.apache.logging.log4j:log4j-core"
    private String requestedVersion;
    private List<RepoVersionStatus> repoStatuses;
    private ComparisonSummary summary;
}
