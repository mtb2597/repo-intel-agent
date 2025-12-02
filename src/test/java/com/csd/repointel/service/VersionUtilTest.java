package com.csd.repointel.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class VersionUtilTest {

    @Test
    void compareVersions() {
        assertTrue(VersionUtil.compare("1.0.0", "1.0.1") < 0);
        assertTrue(VersionUtil.compare("2.0.0", "1.9.9") > 0);
        assertEquals(0, VersionUtil.compare("1.0", "1.0"));
    }

    @Test
    void belowLogic() {
        assertTrue(VersionUtil.isBelow("1.0.0", "1.1.0"));
        assertFalse(VersionUtil.isBelow("1.1.0", "1.1.0"));
        assertFalse(VersionUtil.isBelow("1.2.0", "1.1.0"));
    }
}

