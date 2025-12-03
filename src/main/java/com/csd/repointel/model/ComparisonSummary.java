package com.csd.repointel.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ComparisonSummary {
    private int totalRepos;
    private int matchingRepos;
    private int olderRepos;
    private int newerRepos;
    private int notFoundRepos;
    private int unknownRepos;
    private String naturalLanguageSummary;
}
