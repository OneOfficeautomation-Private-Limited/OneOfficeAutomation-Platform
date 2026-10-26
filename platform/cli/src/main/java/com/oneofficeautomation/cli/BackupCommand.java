package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;

import java.nio.file.*;
import java.util.*;

/**
 * {@code ooa backup}: backups of the Command Center (see Backups). Stop the server for restore.
 * <pre>
 * ooa backup now [--data DIR]                 make a backup into the backup folder (Settings, or data\backups)
 * ooa backup list [--data DIR]                the backups there, newest first
 * ooa backup restore FILE [--replace] [--data DIR]   put a backup back (the server stopped; --replace deletes the rows first)
 * All take --db-url URL --db-user U --db-password-env VAR, as the server does (the container image passes them).
 * </pre>
 */
final class BackupCommand {
    private BackupCommand() { }

    static int run(String[] a) throws Exception {
        if (a.length == 0) { usage(); return 1; }
        Map<String, String> o = Ooa.options(Arrays.copyOfRange(a, a[0].equals("restore") ? 2 : 1, a.length));
        Path data = Ooa.dataDir(o);
        switch (a[0]) {
            case "now", "list" -> {
                Ooa.ControlRoomSetup s = Ooa.openControlRoom(o);
                try {
                    if (a[0].equals("now")) {
                        ControlRoom.User system = new ControlRoom.User("system", "system", "admin", "system", false);
                        Map<String, Object> r = s.cr().backups.backupNow(system, null);
                        System.out.println("Backup written: " + s.cr().backups.status(system).get("folder") + "\\" + r.get("file") + " (" + r.get("rows") + " rows, " + r.get("size_mb") + " MB)");
                        System.out.println("The vault key is not in it: keep a copy of master.key (or OOA_MASTER_KEY) apart from your backups.");
                    } else {
                        for (Map<String, Object> b : s.cr().backups.list()) System.out.println(b.get("created") + "  " + b.get("size_mb") + " MB  " + b.get("file"));
                    }
                } finally { s.db().close(); }
                return 0;
            }
            case "restore" -> {
                if (a.length < 2) { usage(); return 1; }
                Path file = Path.of(a[1]);
                Path cfgFile = DbConfig.defaultFile(data);
                Vault vault = new Vault(data);
                String pw = o.containsKey("db-password-env") ? System.getenv(o.get("db-password-env")) : "";
                try (Db db = o.containsKey("db-url") ? new Db(o.get("db-url"), o.getOrDefault("db-user", ""), pw == null ? "" : pw)
                        : Files.exists(cfgFile) ? Db.open(DbConfig.load(cfgFile), data, vault, DbConfig.load(cfgFile).hasSetupAccount()) : Db.h2File(data)) {
                    Map<String, Object> r = Backups.restore(file, db, data, vault, o.containsKey("replace"));
                    @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) r.get("tables");
                    long rows = t.values().stream().mapToLong(v -> ((Number) v).longValue()).sum();
                    System.out.println("Restored the backup of " + r.get("created") + ": " + rows + " rows in " + t.size() + " tables, and the repository and configuration files.");
                    System.out.println("Start the Command Center again.");
                }
                return 0;
            }
            default -> { usage(); return 1; }
        }
    }

    static void usage() { System.err.println("usage: ooa backup now | list | restore FILE [--replace]  [--data DIR] [--db-url URL --db-user U --db-password-env VAR]"); }
}
