package com.csd.repointel.model;

public enum VersionStatus {
    MATCHES,    // ✅ Same version
    OLDER,      // ⚠️ Below requested version
    NEWER,      // ⬆️ Above requested version
    NOT_FOUND,  // ❌ Package not found
    UNKNOWN     // ❓ Version cannot be determined
}
