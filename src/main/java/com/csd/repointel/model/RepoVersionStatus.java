package com.csd.repointel.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RepoVersionStatus {
    private String repoName;
    private String packageName;
    private List<String> foundVersions;
    private VersionStatus status; // MATCHES, OLDER, NEWER, NOT_FOUND, UNKNOWN
    private String statusEmoji; // ✅, ⚠️, ⬆️, ❌, ❓
    private String message; // Human-readable message
}
