package com.csd.repointel.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class ChatResponse {
    private ChatIntent intent;
    private String summary;
    private String link; // related API link
    private Map<String, Object> data; // dynamic payload
}

