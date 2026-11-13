package com.oneofficeautomation.modules.docauto;

import com.oneofficeautomation.controlroom.Vault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Improvement list 13 Nov 2026, item 9: One CommandCenter finds this computer's DocFlow, records its address and token
 * when nothing says where DocFlow is, and starts it only when it is the DocFlow One CommandCenter is pointed at.
 */
class DocFlowLauncherTest {
    @TempDir Path dir;

    Path docflow(String port, String token) throws Exception {
        Path home = dir.resolve("DocFlow");
        Files.createDirectories(home.resolve("config"));
        Files.createDirectories(home.resolve("windows"));
        Files.writeString(home.resolve("windows").resolve("start.ps1"), "# start");
        Files.writeString(home.resolve("config").resolve("local.env"), "﻿# settings\nDOCAUTO_PORT=" + port + "\nDOCAUTO_API_TOKEN=" + token + "\nDOCAUTO_MANAGED=true\n");
        return home;
    }

    @Test void findsDocFlowNextToTheProgramOrWhereTheEnvironmentSays() throws Exception {
        Path home = docflow("8095", "t0k3n");
        Path data = Files.createDirectories(dir.resolve("data"));
        assertEquals(home, DocFlowLauncher.findHome(data, k -> null, dir));                          // the installer's DocFlow folder
        assertEquals(home, DocFlowLauncher.findHome(data, Map.of("OOA_DOCFLOW_HOME", home.toString())::get, null));
        Path found = DocFlowLauncher.findHome(data, k -> null, dir.resolve("a").resolve("b").resolve("c"));
        assertTrue(found == null || !found.startsWith(dir), "only a folder with DocFlow's settings and start script counts");
    }

    @Test void recordsTheAddressAndTokenWhenNothingSaysWhereDocFlowIs() throws Exception {
        Path home = docflow("8095", "t0k3n");
        Path data = Files.createDirectories(dir.resolve("data"));
        Vault vault = new Vault(data);
        assertEquals("http://127.0.0.1:8095", DocFlowLauncher.configure(data, vault, home, "", k -> null));
        assertEquals("http://127.0.0.1:8095", DocFlowConfig.url(data));
        assertEquals("t0k3n", DocFlowConfig.token(data, vault));
        assertTrue(Files.readString(DocFlowConfig.file(data)).contains("home="), "where DocFlow is, to start it again next time");
        assertFalse(Files.readString(DocFlowConfig.file(data)).contains("t0k3n"), "the token is kept encrypted");
    }

    @Test void startsOnlyTheDocFlowOneCommandCenterIsPointedAt() throws Exception {
        Path home = docflow("8095", "t0k3n");
        Path data = Files.createDirectories(dir.resolve("data"));
        Vault vault = new Vault(data);
        assertEquals("http://127.0.0.1:8095", DocFlowLauncher.configure(data, vault, home, "http://localhost:8095/", k -> null), "Settings names this computer's DocFlow");
        assertNull(DocFlowLauncher.configure(data, vault, home, "https://docflow.example.com", k -> null), "Settings names another server: nothing is started here");
        assertNull(DocFlowLauncher.configure(data, vault, home, "", Map.of("OOA_DOCFLOW_URL", "https://docflow.example.com")::get));
        assertFalse(Files.exists(DocFlowConfig.file(data)), "nothing recorded while something else says where DocFlow is");
    }

    @Test void aDocFlowWithoutATokenIsNotManaged() throws Exception {
        Path home = docflow("8085", "");
        Path data = Files.createDirectories(dir.resolve("data"));
        assertNull(DocFlowLauncher.configure(data, new Vault(data), home, "", k -> null));
    }

    @Test void nothingAnswersAtAnAddressWithNoService() {
        assertFalse(DocFlowLauncher.answers("http://127.0.0.1:1"));
    }
}
