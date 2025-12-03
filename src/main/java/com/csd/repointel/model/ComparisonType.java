package com.csd.repointel.model;

public enum ComparisonType {
    EXACT,      // Check exact version
    BELOW,      // Check versions below X
    ABOVE,      // Check versions above X
    ALL,        // Show all versions
    OUTDATED    // Show all outdated versions
}
