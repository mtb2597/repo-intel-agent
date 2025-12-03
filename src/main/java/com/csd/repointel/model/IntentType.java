package com.csd.repointel.model;

public enum IntentType {
    VERSION_CHECK,              // "Check where X is used"
    VERSION_COMPARE,            // "Which repos use X below/above version Y"
    EXPORT,                     // "Export results"
    FIX_REQUEST,                // "Fix/update version"
    LIST_ALL,                   // "Show all versions"
    SEARCH,                     // General keyword search
    VULNERABILITY_LIST,         // "Show all vulnerabilities"
    VULNERABILITY_BY_REPO,      // "Which repos have vulnerabilities"
    VULNERABILITY_CHECK,        // "Check vulnerabilities for package"
    VULNERABILITY_FIX,          // "Fix vulnerabilities"
    VULNERABILITY_EXPORT,       // "Export vulnerability report"
    VULNERABILITY_STATS,        // "Show vulnerability statistics"
    UNKNOWN
}
