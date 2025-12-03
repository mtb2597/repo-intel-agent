package com.csd.repointel.service;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class PomPropertyResolutionTest {
    private Model parsePom(String pomXml) throws Exception {
        MavenXpp3Reader reader = new MavenXpp3Reader();
        try (var is = new ByteArrayInputStream(pomXml.getBytes(StandardCharsets.UTF_8))) {
            return reader.read(is);
        }
    }

    private Dependency getFirstDependency(Model model) {
        return model.getDependencies().get(0);
    }

    @Test
    void literalVersion() throws Exception {
        String pom = """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>g</groupId><artifactId>a</artifactId><version>1.0</version>
              <dependencies>
                <dependency>
                  <groupId>g</groupId><artifactId>a</artifactId><version>1.2.3</version>
                </dependency>
              </dependencies>
            </project>
        """;
        Model model = parsePom(pom);
        Dependency dep = getFirstDependency(model);
        String resolved = RepoScanService.resolvePropertyRecursive(model, dep.getVersion(), new java.util.HashSet<>());
        assertEquals("1.2.3", resolved);
    }

    @Test
    void singleLevelProperty() throws Exception {
        String pom = """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>g</groupId><artifactId>a</artifactId><version>1.0</version>
              <properties>
                <my.version>2.5.1</my.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>g</groupId><artifactId>a</artifactId><version>${my.version}</version>
                </dependency>
              </dependencies>
            </project>
        """;
        Model model = parsePom(pom);
        Dependency dep = getFirstDependency(model);
        String resolved = RepoScanService.resolvePropertyRecursive(model, dep.getVersion(), new java.util.HashSet<>());
        assertEquals("2.5.1", resolved);
    }

    @Test
    void nestedProperty() throws Exception {
        String pom = """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>g</groupId><artifactId>a</artifactId><version>1.0</version>
              <properties>
                <base.version>3.1.4</base.version>
                <my.version>${base.version}</my.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>g</groupId><artifactId>a</artifactId><version>${my.version}</version>
                </dependency>
              </dependencies>
            </project>
        """;
        Model model = parsePom(pom);
        Dependency dep = getFirstDependency(model);
        String resolved = RepoScanService.resolvePropertyRecursive(model, dep.getVersion(), new java.util.HashSet<>());
        assertEquals("3.1.4", resolved);
    }

    @Test
    void missingProperty() throws Exception {
        String pom = """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>g</groupId><artifactId>a</artifactId><version>1.0</version>
              <dependencies>
                <dependency>
                  <groupId>g</groupId><artifactId>a</artifactId><version>${not.defined}</version>
                </dependency>
              </dependencies>
            </project>
        """;
        Model model = parsePom(pom);
        Dependency dep = getFirstDependency(model);
        String resolved = RepoScanService.resolvePropertyRecursive(model, dep.getVersion(), new java.util.HashSet<>());
        assertEquals("${not.defined}", resolved);
    }

    @Test
    void cyclicProperty() throws Exception {
        String pom = """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>g</groupId><artifactId>a</artifactId><version>1.0</version>
              <properties>
                <a>${b}</a>
                <b>${a}</b>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>g</groupId><artifactId>a</artifactId><version>${a}</version>
                </dependency>
              </dependencies>
            </project>
        """;
        Model model = parsePom(pom);
        Dependency dep = getFirstDependency(model);
        String resolved = RepoScanService.resolvePropertyRecursive(model, dep.getVersion(), new java.util.HashSet<>());
        assertEquals("${a}", resolved);
    }

    @Test
    void sampleCaseSagaMavenPlugin() throws Exception {
        String pom = """
            <project xmlns=\"http://maven.apache.org/POM/4.0.0\">
              <modelVersion>4.0.0</modelVersion>
              <groupId>g</groupId><artifactId>a</artifactId><version>1.0</version>
              <properties>
                <saga-maven-plugin.version>1.4.2</saga-maven-plugin.version>
              </properties>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId><artifactId>saga-maven-plugin</artifactId><version>${saga-maven-plugin.version}</version>
                </dependency>
              </dependencies>
            </project>
        """;
        Model model = parsePom(pom);
        Dependency dep = getFirstDependency(model);
        String resolved = RepoScanService.resolvePropertyRecursive(model, dep.getVersion(), new java.util.HashSet<>());
        assertEquals("1.4.2", resolved);
    }
}
