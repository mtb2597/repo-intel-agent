package com.csd.repointel.service;

import com.csd.repointel.model.DependencyInfo;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

@Service
public class CompareService {

    private final RepoScanService repoScanService;

    public CompareService(RepoScanService repoScanService) {
        this.repoScanService = repoScanService;
    }

    public Map<String, String> compareSingle(String groupId, String artifactId) {
        Map<String, List<DependencyInfo>> repos = repoScanService.getRepoDependencies();
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : repos.entrySet()) {
            List<String> versions = entry.getValue().stream()
                    .filter(d -> groupId.equals(d.getGroupId()) && artifactId.equals(d.getArtifactId()))
                    .map(DependencyInfo::getVersion)
                    .collect(Collectors.toList());
            if (versions.isEmpty()) {
                result.put(entry.getKey(), "NOT_FOUND");
            } else {
                String best = selectHighestVersion(versions);
                result.put(entry.getKey(), best == null ? "UNKNOWN" : best);
            }
        }
        return result;
    }

    private String selectHighestVersion(List<String> versions) {
        String best = null;
        for (String v : versions) {
            if (v == null || v.isBlank()) continue; // skip unresolved property versions
            if (best == null || VersionUtil.compare(v, best) > 0) {
                best = v;
            }
        }
        return best; // may be null if all were null/blank
    }

    public Map<String, String> drift(String groupId, String artifactId, String minVersion) {
        Map<String, String> versions = compareSingle(groupId, artifactId);
        Map<String, String> drift = new LinkedHashMap<>();
        versions.forEach((repo, ver) -> {
            if ("NOT_FOUND".equals(ver)) {
                drift.put(repo, "NOT_FOUND");
            } else if (!"UNKNOWN".equals(ver) && VersionUtil.isBelow(ver, minVersion)) {
                drift.put(repo, "BELOW(" + ver + ")");
            }
        });
        return drift;
    }

    public Map<String, Map<String, String>> matrix(List<String> artifacts) {
        Map<String, Map<String, String>> matrix = new LinkedHashMap<>();
        for (String coord : artifacts) {
            String[] parts = coord.split(":");
            if (parts.length != 2) continue;
            matrix.put(coord, compareSingle(parts[0], parts[1]));
        }
        return matrix;
    }

    public String matrixCsv(List<String> artifacts) {
        Map<String, Map<String, String>> matrix = matrix(artifacts);
        List<String> repos = new ArrayList<>(repoScanService.getRepoDependencies().keySet());
        StringBuilder sb = new StringBuilder();
        sb.append("Artifact");
        for (String repo : repos) sb.append(',').append(repo);
        sb.append('\n');
        matrix.forEach((artifact, map) -> {
            sb.append(artifact);
            for (String repo : repos) {
                sb.append(',').append(map.getOrDefault(repo, "NOT_FOUND"));
            }
            sb.append('\n');
        });
        return sb.toString();
    }

    public byte[] matrixXlsx(List<String> artifacts) throws IOException {
        Map<String, Map<String, String>> matrix = matrix(artifacts);
        List<String> repos = new ArrayList<>(repoScanService.getRepoDependencies().keySet());
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("matrix");
            int rowIdx = 0;
            Row header = sheet.createRow(rowIdx++);
            header.createCell(0).setCellValue("Artifact");
            for (int i = 0; i < repos.size(); i++) {
                header.createCell(i + 1).setCellValue(repos.get(i));
            }
            for (var entry : matrix.entrySet()) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(entry.getKey());
                for (int i = 0; i < repos.size(); i++) {
                    String repo = repos.get(i);
                    row.createCell(i + 1).setCellValue(entry.getValue().getOrDefault(repo, "NOT_FOUND"));
                }
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    public String compareHtml(String groupId, String artifactId, String minVersion) {
        Map<String, String> data = compareSingle(groupId, artifactId);
        StringBuilder sb = new StringBuilder();
        sb.append("<table border='1'><tr><th>Repo</th><th>Version</th></tr>");
        data.forEach((repo, ver) -> {
            String cls = "notfound";
            if (!"NOT_FOUND".equals(ver)) {
                if (minVersion != null && VersionUtil.isBelow(ver, minVersion)) cls = "red"; else cls = "green";
            }
            sb.append("<tr><td>").append(repo).append("</td><td class='").append(cls).append("'>").append(ver).append("</td></tr>");
        });
        sb.append("</table>");
        sb.append("<style>.red{background:#fdd}.green{background:#dfd}.notfound{background:#eee;color:#666}</style>");
        return sb.toString();
    }

    public String matrixHtml(List<String> artifacts) {
        Map<String, Map<String, String>> matrix = matrix(artifacts);
        List<String> repos = new ArrayList<>(repoScanService.getRepoDependencies().keySet());
        StringBuilder sb = new StringBuilder();
        sb.append("<table border='1'><tr><th>Artifact</th>");
        for (String repo : repos) sb.append("<th>").append(repo).append("</th>");
        sb.append("</tr>");
        matrix.forEach((artifact, map) -> {
            sb.append("<tr><td>").append(artifact).append("</td>");
            for (String repo : repos) {
                String ver = map.getOrDefault(repo, "NOT_FOUND");
                sb.append("<td>").append(ver).append("</td>");
            }
            sb.append("</tr>");
        });
        sb.append("</table>");
        return sb.toString();
    }

    public Map<String, List<DependencyInfo>> search(String keyword) {
        String lower = keyword.toLowerCase(Locale.ROOT);
        return repoScanService.getRepoDependencies().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().stream()
                        .filter(d -> (d.getGroupId() != null && d.getGroupId().toLowerCase(Locale.ROOT).contains(lower))
                                || (d.getArtifactId() != null && d.getArtifactId().toLowerCase(Locale.ROOT).contains(lower)))
                        .collect(Collectors.toList())));
    }
}
