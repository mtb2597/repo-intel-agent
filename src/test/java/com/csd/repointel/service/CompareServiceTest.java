package com.csd.repointel.service;

import com.csd.repointel.model.DependencyInfo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

public class CompareServiceTest {

    @Test
    void compareSingleNotFound() {
        RepoScanService scanService = new RepoScanService();
        scanService.getRepoDependencies().put("repo1", List.of());
        CompareService compareService = new CompareService(scanService);
        Map<String, String> result = compareService.compareSingle("g", "a");
        assertEquals("NOT_FOUND", result.get("repo1"));
    }

    @Test
    void compareSingleFound() {
        RepoScanService scanService = new RepoScanService();
        scanService.getRepoDependencies().put("repo1", List.of(DependencyInfo.builder().groupId("g").artifactId("a").version("1.0").build()));
        CompareService compareService = new CompareService(scanService);
        Map<String, String> result = compareService.compareSingle("g", "a");
        assertEquals("1.0", result.get("repo1"));
    }

    @Test
    void driftDetection() {
        RepoScanService scanService = new RepoScanService();
        scanService.getRepoDependencies().put("repo1", List.of(DependencyInfo.builder().groupId("g").artifactId("a").version("1.0").build()));
        scanService.getRepoDependencies().put("repo2", List.of(DependencyInfo.builder().groupId("g").artifactId("a").version("2.0").build()));
        CompareService compareService = new CompareService(scanService);
        Map<String, String> drift = compareService.drift("g", "a", "1.5");
        assertEquals("BELOW(1.0)", drift.get("repo1"));
        assertFalse(drift.containsKey("repo2"));
    }

    @Test
    void matrixBuilds() {
        RepoScanService scanService = new RepoScanService();
        scanService.getRepoDependencies().put("repo1", List.of(DependencyInfo.builder().groupId("g").artifactId("a").version("1.0").build()));
        scanService.getRepoDependencies().put("repo2", List.of(DependencyInfo.builder().groupId("g2").artifactId("a2").version("3.0").build()));
        CompareService compareService = new CompareService(scanService);
        Map<String, Map<String, String>> matrix = compareService.matrix(List.of("g:a", "g2:a2"));
        assertEquals(2, matrix.size());
        assertEquals("1.0", matrix.get("g:a").get("repo1"));
        assertEquals("NOT_FOUND", matrix.get("g:a").get("repo2"));
    }

    @Test
    void highestVersionSelected() {
        RepoScanService scanService = new RepoScanService();
        scanService.getRepoDependencies().put("repo1", List.of(
                DependencyInfo.builder().groupId("g").artifactId("a").version("1.0").build(),
                DependencyInfo.builder().groupId("g").artifactId("a").version("1.2.0").build(),
                DependencyInfo.builder().groupId("g").artifactId("a").version("").build(), // blank ignored
                DependencyInfo.builder().groupId("g").artifactId("a").version("1.1").build()
        ));
        CompareService compareService = new CompareService(scanService);
        Map<String, String> result = compareService.compareSingle("g", "a");
        assertEquals("1.2.0", result.get("repo1"));
    }
}
