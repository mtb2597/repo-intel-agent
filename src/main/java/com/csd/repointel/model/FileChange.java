package com.csd.repointel.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FileChange {
    private String filePath;
    private String originalContent;
    private String modifiedContent;
    private String diff;
}
