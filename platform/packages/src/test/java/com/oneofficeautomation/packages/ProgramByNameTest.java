package com.oneofficeautomation.packages;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Item 8 (21 Nov 2026): Open program/file and Start program find installed apps by name alone, as Win+R does. */
@EnabledOnOs(OS.WINDOWS)
class ProgramByNameTest {
    @Test void aProgramOnThePathStaysAsTyped() {
        assertEquals("notepad.exe", ApplicationPackage.resolveProgram("notepad.exe"));
        assertEquals("cmd", ApplicationPackage.resolveProgram("cmd"));
    }

    @Test void aPathIsNeverLookedUp() {
        assertEquals("C:\\Tools\\app.exe", ApplicationPackage.resolveProgram("C:\\Tools\\app.exe"));
        assertEquals("tools/app.exe", ApplicationPackage.resolveProgram("tools/app.exe"));
    }

    @Test void anAppOnlyInAppPathsIsFoundByItsNameWithOrWithoutExe() {
        String excel = ApplicationPackage.resolveProgram("excel.exe");
        assumeTrue(!excel.equals("excel.exe"), "Excel is not installed here");
        assertTrue(Files.isRegularFile(Path.of(excel)), excel);
        assertTrue(excel.toLowerCase().endsWith("excel.exe"), excel);
        assertEquals(excel, ApplicationPackage.resolveProgram("excel"));
    }

    @Test void edgeIsFoundTheSameWay() {
        String edge = ApplicationPackage.resolveProgram("msedge.exe");
        assumeTrue(!edge.equals("msedge.exe"), "Edge is not registered here");
        assertTrue(edge.toLowerCase().endsWith("msedge.exe"), edge);
    }

    @Test void anUnknownNameIsReturnedAsGiven() {
        assertEquals("no-such-program-ooa.exe", ApplicationPackage.resolveProgram("no-such-program-ooa.exe"));
    }
}
