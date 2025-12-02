package com.csd.repointel.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RepoScanResult {
    private String repoName;
    private String url;
    private List<DependencyInfo> dependencies;
    private boolean success;
    private String error;
    private String jdkVersion;  // Detected JDK version from pom.xml
}

