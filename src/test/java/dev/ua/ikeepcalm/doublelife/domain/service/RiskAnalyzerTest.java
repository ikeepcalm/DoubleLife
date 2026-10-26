package dev.ua.ikeepcalm.doublelife.domain.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskAnalyzerTest {

    @Test
    void selfTargetedCommands() {
        assertTrue(RiskAnalyzer.isSelfTargeted("/give Steve diamond 64", "steve"));
        assertTrue(RiskAnalyzer.isSelfTargeted("/effect give @s speed", "Steve"));
        assertFalse(RiskAnalyzer.isSelfTargeted("/give Alex diamond 64", "Steve"));
        assertTrue(RiskAnalyzer.isSelfTargeted("/give @a[distance=..5] diamond", "Steve"));
        assertFalse(RiskAnalyzer.isSelfTargeted("/steve", "Steve"));
    }
}
