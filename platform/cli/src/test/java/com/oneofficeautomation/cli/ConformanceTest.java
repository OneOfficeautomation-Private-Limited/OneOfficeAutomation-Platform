package com.oneofficeautomation.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Java Runner must pass the same language-neutral suite as the Python reference. */
class ConformanceTest {
    @Test void javaRunnerPassesConformanceSuite() throws Exception {
        Path cases = Path.of(System.getProperty("ooa.contracts"), "conformance", "cases");
        assertEquals(0, Ooa.conformance(cases), "see test output for failing cases");
    }
}
