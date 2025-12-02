package com.csd.repointel.service;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

public final class VersionUtil {
    private VersionUtil() {}

    public static int compare(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        ArtifactVersion av = new DefaultArtifactVersion(a);
        ArtifactVersion bv = new DefaultArtifactVersion(b);
        return av.compareTo(bv);
    }

    public static boolean isBelow(String version, String minVersion) {
        if (minVersion == null || version == null) return false;
        return compare(version, minVersion) < 0;
    }
}

