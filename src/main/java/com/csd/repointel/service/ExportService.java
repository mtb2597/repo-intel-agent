package com.csd.repointel.service;

import com.csd.repointel.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 * Service to export version comparison results in various formats
 */
@Slf4j
@Service
public class ExportService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Export version comparison results as CSV (combined or per-repo)
     */
    public String exportCsv(VersionComparisonResult result, ExportScope scope) {
        if (scope == ExportScope.PER_REPO) {
            return exportCsvPerRepo(result);
        } else {
            return exportCsvCombined(result);
        }
    }

    /**
     * Export version comparison results as Excel (combined or per-repo)
     */
    public byte[] exportExcel(VersionComparisonResult result, ExportScope scope) throws IOException {
        if (scope == ExportScope.PER_REPO) {
            return exportExcelPerRepo(result);
        } else {
            return exportExcelCombined(result);
        }
    }

    /**
     * Export version comparison results as JSON
     */
    public String exportJson(VersionComparisonResult result) throws IOException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
    }

    /**
     * Export single repo data as CSV
     */
    public String exportSingleRepoCsv(RepoVersionStatus repoStatus) {
        StringBuilder sb = new StringBuilder();
        sb.append("Repository,Package,Versions,Status,Message\n");
        sb.append(escapeCsv(repoStatus.getRepoName())).append(",");
        sb.append(escapeCsv(repoStatus.getPackageName())).append(",");
        sb.append(escapeCsv(String.join(";", repoStatus.getFoundVersions()))).append(",");
        sb.append(repoStatus.getStatus()).append(",");
        sb.append(escapeCsv(repoStatus.getMessage())).append("\n");
        return sb.toString();
    }

    /**
     * Export single repo data as JSON
     */
    public String exportSingleRepoJson(RepoVersionStatus repoStatus) throws IOException {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(repoStatus);
    }

    private String exportCsvCombined(VersionComparisonResult result) {
        StringBuilder sb = new StringBuilder();
        
        // Header
        sb.append("Repository,Package,Found Versions,Status,Message\n");
        
        // Data rows
        for (RepoVersionStatus status : result.getRepoStatuses()) {
            sb.append(escapeCsv(status.getRepoName())).append(",");
            sb.append(escapeCsv(status.getPackageName())).append(",");
            sb.append(escapeCsv(String.join(";", status.getFoundVersions()))).append(",");
            sb.append(status.getStatus()).append(",");
            sb.append(escapeCsv(status.getMessage())).append("\n");
        }
        
        return sb.toString();
    }

    private String exportCsvPerRepo(VersionComparisonResult result) {
        StringBuilder sb = new StringBuilder();
        
        for (RepoVersionStatus status : result.getRepoStatuses()) {
            sb.append("=== ").append(status.getRepoName()).append(" ===\n");
            sb.append("Package,Found Versions,Status,Message\n");
            sb.append(escapeCsv(status.getPackageName())).append(",");
            sb.append(escapeCsv(String.join(";", status.getFoundVersions()))).append(",");
            sb.append(status.getStatus()).append(",");
            sb.append(escapeCsv(status.getMessage())).append("\n\n");
        }
        
        return sb.toString();
    }

    private byte[] exportExcelCombined(VersionComparisonResult result) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Version Comparison");
            
            // Create header style
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            
            // Header row
            Row headerRow = sheet.createRow(0);
            String[] headers = {"Repository", "Package", "Found Versions", "Status", "Message"};
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
            }
            
            // Data rows
            int rowNum = 1;
            for (RepoVersionStatus status : result.getRepoStatuses()) {
                Row row = sheet.createRow(rowNum++);
                row.createCell(0).setCellValue(status.getRepoName());
                row.createCell(1).setCellValue(status.getPackageName());
                row.createCell(2).setCellValue(String.join(", ", status.getFoundVersions()));
                row.createCell(3).setCellValue(status.getStatus().toString());
                row.createCell(4).setCellValue(status.getMessage());
            }
            
            // Auto-size columns
            for (int i = 0; i < headers.length; i++) {
                sheet.autoSizeColumn(i);
            }
            
            // Write to byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private byte[] exportExcelPerRepo(VersionComparisonResult result) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            
            // Create header style
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);
            
            // Create a sheet for each repository
            for (RepoVersionStatus status : result.getRepoStatuses()) {
                String sheetName = sanitizeSheetName(status.getRepoName());
                Sheet sheet = workbook.createSheet(sheetName);
                
                // Header row
                Row headerRow = sheet.createRow(0);
                String[] headers = {"Package", "Found Versions", "Status", "Message"};
                for (int i = 0; i < headers.length; i++) {
                    Cell cell = headerRow.createCell(i);
                    cell.setCellValue(headers[i]);
                    cell.setCellStyle(headerStyle);
                }
                
                // Data row
                Row dataRow = sheet.createRow(1);
                dataRow.createCell(0).setCellValue(status.getPackageName());
                dataRow.createCell(1).setCellValue(String.join(", ", status.getFoundVersions()));
                dataRow.createCell(2).setCellValue(status.getStatus().toString());
                dataRow.createCell(3).setCellValue(status.getMessage());
                
                // Auto-size columns
                for (int i = 0; i < headers.length; i++) {
                    sheet.autoSizeColumn(i);
                }
            }
            
            // Write to byte array
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            return outputStream.toByteArray();
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String sanitizeSheetName(String name) {
        // Excel sheet names can't contain: \ / ? * [ ]
        String sanitized = name.replaceAll("[\\\\/:*?\\[\\]]", "_");
        // Max length is 31 characters
        if (sanitized.length() > 31) {
            sanitized = sanitized.substring(0, 31);
        }
        return sanitized;
    }

    /**
     * Generate export metadata for UI
     */
    public Map<String, String> getExportLinks(String packageName) {
        Map<String, String> links = new LinkedHashMap<>();
        String encoded = packageName.replace(" ", "%20");
        links.put("CSV (Combined)", "/api/export/csv?package=" + encoded + "&scope=combined");
        links.put("CSV (Per Repo)", "/api/export/csv?package=" + encoded + "&scope=per_repo");
        links.put("Excel (Combined)", "/api/export/excel?package=" + encoded + "&scope=combined");
        links.put("Excel (Per Repo)", "/api/export/excel?package=" + encoded + "&scope=per_repo");
        links.put("JSON", "/api/export/json?package=" + encoded);
        return links;
    }
}
