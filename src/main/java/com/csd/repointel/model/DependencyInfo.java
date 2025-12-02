package com.csd.repointel.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DependencyInfo {
    private String groupId;
    private String artifactId;
    private String version;
    private String scope; // may be null
}

