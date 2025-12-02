package com.csd.repointel.controller;

import com.csd.repointel.service.CompareService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CompareController {

    private final CompareService compareService;

    public CompareController(CompareService compareService) { this.compareService = compareService; }

    @GetMapping("/compare")
    public Map<String, String> compare(@RequestParam String groupId, @RequestParam String artifactId) {
        return compareService.compareSingle(groupId, artifactId);
    }

    @GetMapping("/drift")
    public Map<String, String> drift(@RequestParam String groupId, @RequestParam String artifactId, @RequestParam String minVersion) {
        return compareService.drift(groupId, artifactId, minVersion);
    }

    @GetMapping(value = "/compare/table", produces = MediaType.TEXT_HTML_VALUE)
    public String compareTable(@RequestParam String groupId, @RequestParam String artifactId, @RequestParam(required = false) String minVersion) {
        return compareService.compareHtml(groupId, artifactId, minVersion);
    }

    @GetMapping(value = "/compare/matrix/table", produces = MediaType.TEXT_HTML_VALUE)
    public String matrixTable(@RequestParam String artifacts) {
        List<String> list = Arrays.asList(artifacts.split(","));
        return compareService.matrixHtml(list);
    }

    @GetMapping("/compare/matrix/csv")
    public ResponseEntity<String> matrixCsv(@RequestParam String artifacts) {
        List<String> list = Arrays.asList(artifacts.split(","));
        String csv = compareService.matrixCsv(list);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=matrix.csv")
                .contentType(MediaType.TEXT_PLAIN)
                .body(csv);
    }

    @GetMapping("/compare/matrix/xlsx")
    public ResponseEntity<byte[]> matrixXlsx(@RequestParam String artifacts) throws IOException {
        List<String> list = Arrays.asList(artifacts.split(","));
        byte[] data = compareService.matrixXlsx(list);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=matrix.xlsx")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(data);
    }

    @GetMapping("/search")
    public Map<String, ?> search(@RequestParam String keyword) {
        return compareService.search(keyword);
    }
}

