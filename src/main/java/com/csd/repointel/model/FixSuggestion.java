package com.csd.repointel.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FixSuggestion {
    private String repoName;
    private String packageName;
    private String currentVersion;
    private String targetVersion;
    private List<FileChange> fileChanges;
    private String previewDiff;
}
