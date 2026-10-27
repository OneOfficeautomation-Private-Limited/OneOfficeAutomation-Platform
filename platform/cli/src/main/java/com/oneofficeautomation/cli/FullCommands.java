package com.oneofficeautomation.cli;

import java.nio.file.Path;

/**
 * The commands of the full install (bin\ooa.bat): the servers, runners, database, HTTPS, backups, services, toolkits
 * and the desktop assistant. The vendor build adds making licenses and signing releases.
 */
final class FullCommands {
    private FullCommands() { }

    static final String DATA = "The Command Center data folder (default: ~/OOA Command Center).";

    static void add(OoaCli c) {
        database(c.command("server", "Start everything on this computer: the Command Center, Agent Studio (/studio) and a runner.", a -> Ooa.server(a.o()))
            .number("--port", "N", "The port (default 8720).")
            .opt("--host", "HOST", "The address to listen on (default 127.0.0.1, this computer only).")
            .opt("--workspace", "DIR", "Agent Studio's workspace (default: ~/OOA Workspace).")
            .flag("--no-agent", "Do not start the runner for this computer.")
            .flag("--no-browser", "Do not open the browser.")
            .opt("--agent-config", "FILE", "The local runner's registration (default: ~/.ooa-agent/local-agent.json).")
            .opt("--agent-workspace", "DIR", "The local runner's workspace (default: ~/OOA Agent Workspace)."));
        c.command("studio", "Start Agent Studio on its own (http://localhost:8710).", a -> Ooa.studio(a.o()))
            .opt("--workspace", "DIR", "The workspace (default: ~/OOA Workspace).")
            .number("--port", "N", "The port (default 8710).");
        database(c.command("control-room", "Start the Command Center (http://localhost:8720).", a -> Ooa.controlRoom(a.o()))
            .number("--port", "N", "The port (default 8720).")
            .opt("--host", "HOST", "The address to listen on (default 127.0.0.1; 0.0.0.0 for other machines, with HTTPS)."));
        database(c.command("reset-admin", "Print a new one-time password for a user who forgot theirs. Stop the server first.", a -> Ooa.resetAdmin(a.o()))
            .opt("--user", "NAME", "The user (default admin)."));

        c.group("agent", "Register and run a runner (Bot Agent) on this computer.");
        network(c.command("agent register", "Register this computer as a runner with a Command Center.", a -> Ooa.agent("register", a.o()))
            .required("--url", "URL", "The Command Center, such as https://commandcenter:8720.")
            .required("--key", "KEY", "A registration key (Devices > Registration keys).")
            .opt("--name", "NAME", "The runner's name (default: this computer's name).")
            .opt("--config", "FILE", "Where the registration is saved (default: ~/.ooa-agent/agent.json)."));
        c.command("agent start", "Run the registered runner until stopped.", a -> Ooa.agent("start", a.o())).opt("--config", "FILE", "The registration.")
            .opt("--workspace", "DIR", "The runner's workspace (default: ~/OOA Agent Workspace).");
        network(c.command("agent network", "Show or change the runner's proxy and trusted certificate.", a -> Ooa.agent("network", a.o()))
            .opt("--config", "FILE", "The registration."));
        c.command("agent move", "Point the runner at the Command Center's new address, keeping its registration.", a -> Ooa.agent("move", a.o()))
            .required("--url", "URL", "The new address.").opt("--ca", "FILE", "The certificate to trust for it.").opt("--config", "FILE", "The registration.");
        c.command("agent diagnose", "Find why the runner cannot reach or work with its Command Center.", a -> Ooa.agent("diagnose", a.o()))
            .opt("--config", "FILE", "The registration.").opt("--workspace", "DIR", "The runner's workspace.");
        c.command("agent install-task", "Start the runner when you sign in to Windows (for runners that need a desktop).", a -> Ooa.agent("install-task", a.o()))
            .opt("--config", "FILE", "The registration.").opt("--workspace", "DIR", "The runner's workspace.");
        c.command("agent uninstall-task", "Stop starting the runner at sign-in.", a -> Ooa.agent("uninstall-task", a.o()));

        c.group("db", "Set up and look after the Command Center database (docs/DATABASE.md).");
        db(c, "configure", "Save the database settings: SQL Server or PostgreSQL, the server and the accounts.")
            .opt("--engine", "ENGINE", "sqlserver (default) or postgresql.")
            .opt("--host", "HOST", "The database server.")
            .number("--port", "N", "Its port (default 1433, or 5432 for PostgreSQL).")
            .opt("--instance", "NAME", "A named SQL Server instance.")
            .opt("--database", "NAME", "The database (default ooa_command_center).")
            .opt("--user", "NAME", "The runtime account the server signs in with.")
            .opt("--setup-user", "NAME", "The account that creates and upgrades the schema.")
            .flag("--windows-auth", "Sign in to SQL Server with Windows (the service account, or a gMSA).")
            .opt("--windows-login", "DOMAIN\\NAME", "The Windows account, for the rights script.")
            .opt("--tls", "MODE", "Encrypted connection: required (default) or off.")
            .opt("--tls-ca", "FILE", "The certificate to trust for the database server.")
            .flag("--trust-any-certificate", "Accept any server certificate (tests only).")
            .number("--pool-max", "N", "Connections at most (default 20).")
            .number("--timeout-seconds", "N", "How long to wait for a connection (default 30).");
        db(c, "set-password", "Store a database password, encrypted with the vault key.")
            .flag("--setup", "The setup account's password, not the runtime account's.")
            .opt("--from-env", "VAR", "Read it from this environment variable instead of asking.");
        db(c, "test", "Connect with the runtime account and report the version, encryption and rights.");
        db(c, "init", "Create the database and schema, and give the runtime account its rights.");
        db(c, "migrate", "Upgrade the schema after an update.");
        db(c, "script", "Print the schema SQL for a database administrator.").opt("--engine", "ENGINE", "sqlserver, postgresql or h2.");
        db(c, "copy", "Copy the data of a built-in (H2) Command Center into the configured database.")
            .required("--from", "DIR", "The data folder of the built-in Command Center.")
            .flag("--replace", "Delete the rows already there first.");

        if (LicenseCommand.vendorBuild()) {
            c.command("license ui", "Open the license tool (vendor build).", a -> LicenseCommand.vendor("ui", a.pos())).passThrough("ARGS", "--key FILE");
            c.command("license keygen", "Make the vendor key pair (vendor build).", a -> LicenseCommand.vendor("keygen", a.pos())).passThrough("ARGS", "--out DIR");
            c.command("license issue", "Make a signed license file (vendor build).", a -> LicenseCommand.vendor("issue", a.pos()))
                .passThrough("ARGS", "--key FILE --customer NAME --expires YYYY-MM-DD [seats] --out FILE");
            c.command("release sign", "Sign a release folder's manifest (vendor build).", a -> ReleaseCommand.sign(a.o()))
                .required("--dir", "DIR", "The release folder.").required("--version", "V", "The release version.")
                .required("--key", "FILE", "The vendor private key.").opt("--out", "FILE", "The manifest to write.");
        }

        c.command("release page", "Write the public releases page from a repository's GitHub releases.", a -> ReleasePage.run(a.o()))
            .required("--repo", "OWNER/NAME", "The release repository, such as VirupakshaPrabhu/OOA_Customer_Build.")
            .required("--out", "DIR", "Where to write index.html and releases.json.")
            .opt("--token-env", "VAR", "The environment variable with a GitHub token (for a private repository).")
            .opt("--api", "URL", "The GitHub API address (default https://api.github.com).");

        c.group("region", "Run the SaaS service: a region with its tenants (docs/SAAS.md).");
        c.command("region start", "Start a region: every tenant on one port, the host name picking the tenant.", a -> RegionCommand.start(a.o()))
            .opt("--data", "DIR", "The region's data folder, with config/ooa-region.properties.")
            .opt("--host", "HOST", "The address to listen on (default 0.0.0.0; TLS ends at the load balancer).")
            .number("--port", "N", "The port (default 8720).");
        c.command("region token", "Make an operator or directory token, and the SHA-256 for the settings file.", a -> RegionCommand.token());
        c.group("region tenant", "The region's tenants, through its operator API.");
        for (String verb : new String[] {"list", "create", "seats", "suspend", "resume", "delete"}) {
            OoaCli.Cmd t = c.command("region tenant " + verb, switch (verb) {
                    case "list" -> "List the region's tenants.";
                    case "create" -> "Make a tenant and print its first administrator's one-time password.";
                    case "seats" -> "Change a tenant's seats (none: no limit).";
                    case "suspend" -> "Stop a tenant: its people and runners are refused, its data stays.";
                    case "resume" -> "Start a suspended tenant again.";
                    default -> "Delete a suspended tenant with all its data, for good.";
                }, a -> RegionCommand.tenant(verb, a.o(), a.pos()))
                .required("--url", "URL", "The region's own address, such as https://in.example.com.")
                .required("--token-env", "VAR", "The environment variable with the operator token.");
            if (verb.equals("create")) t.required("--slug", "NAME", "The address name: NAME.REGION-DOMAIN.").required("--name", "COMPANY", "The customer's name.")
                .opt("--admin-email", "ADDRESS", "The customer administrator's e-mail address.").opt("--seats", "KIND=N,...", "Seats, such as developer=2,unattended_runner=5.");
            else if (verb.equals("seats")) t.param("NAME", "The tenant's address name.", "1").opt("--seats", "KIND=N,...", "Seats, such as developer=2,unattended_runner=5; leave out for no limit.");
            else if (!verb.equals("list")) t.param("NAME", "The tenant's address name.", "1");
        }
        c.group("directory", "The global directory of a SaaS service with several regions.");
        c.command("directory start", "Start the directory (config/ooa-directory.properties lists the regions).", a -> RegionCommand.directory(a.o()))
            .opt("--data", "DIR", "Its data folder.").opt("--host", "HOST", "The address to listen on (default 0.0.0.0).").number("--port", "N", "The port (default 8720).");

        c.command("check", "Check whether this computer is ready for a Command Center.", a -> CheckCommand.run(a.o()))
            .opt("--data", "DIR", DATA).number("--port", "N", "The port it will use (default 8720).");

        c.group("backup", "Back up and restore the Command Center.");
        database(c.command("backup now", "Make a backup into the backup folder.", a -> BackupCommand.run("now", a.o(), null)));
        database(c.command("backup list", "List the backups, newest first.", a -> BackupCommand.run("list", a.o(), null)));
        database(c.command("backup restore", "Put a backup back. Stop the server first.", a -> BackupCommand.run("restore", a.o(), a.pos(0)))
            .param("FILE", "The backup file.", "1").flag("--replace", "Delete the rows already there first."));

        c.group("tls", "HTTPS for the Command Center. Stop the server first; it uses the settings at its next start.");
        tls(c, "configure", "Use the company's certificate (a .pfx or .p12 with its private key); then set-password.")
            .required("--pfx", "FILE", "The certificate file.");
        tls(c, "set-password", "Store the certificate file's password, encrypted with the vault key.")
            .opt("--from-env", "VAR", "Read it from this environment variable instead of asking.");
        tls(c, "self-signed", "Make a certificate for tests (browsers warn; runners need --ca).")
            .opt("--host", "NAME", "The server's name (default: this computer's).").number("--days", "N", "How long it is valid (default 825).");
        tls(c, "show", "Show the certificate in use: its names and validity.");
        tls(c, "export-cert", "Save the certificate without its key, for runners (agent register --ca) and browsers.")
            .required("--out", "FILE", "The .cer file to write.");
        tls(c, "off", "Back to plain HTTP (the settings file is renamed, not deleted).");

        c.group("toolkit", "Make and check signed custom toolkits.");
        c.command("toolkit keygen", "Make a publisher key pair.", a -> ToolkitCommand.run("keygen", a.o()))
            .required("--publisher", "NAME", "The publisher's name (letters, digits, . _ -).").opt("--out", "DIR", "Where to write the keys (default: here).");
        c.command("toolkit sign", "Sign a toolkit jar.", a -> ToolkitCommand.run("sign", a.o()))
            .required("--jar", "FILE", "The toolkit jar.").required("--class", "NAME", "Its toolkit class.")
            .required("--key", "FILE", "The publisher's private key.").opt("--out", "FILE", "The signed jar (default: the same file).");
        c.command("toolkit inspect", "Show a toolkit jar's id, publisher, actions and whether its signature matches.", a -> ToolkitCommand.run("inspect", a.o()))
            .required("--jar", "FILE", "The toolkit jar.");

        c.command("assistant", "Start the desktop assistant in the notification area, to run your automations.", a -> Assistant.run(a.o()))
            .opt("--url", "URL", "The Command Center (asked the first time).").flag("--sign-out", "Forget the saved sign-in.");

        c.group("service", "Run the Command Center or a runner as a Windows service.");
        service(c, "install", "Install the service (an administrator prompt appears).")
            .number("--port", "N", "The port (default 8720).")
            .opt("--host", "HOST", "The address to listen on.")
            .opt("--data", "DIR", DATA)
            .opt("--workspace", "DIR", "The workspace.")
            .opt("--agent-config", "FILE", "The runner's registration (agent mode).")
            .opt("--account", "USER", "The account the service runs as (default LocalSystem).")
            .opt("--password-env", "VAR", "The environment variable with that account's password.")
            .flag("--no-start", "Install without starting it.");
        service(c, "uninstall", "Remove the service.");
        service(c, "start", "Start the service.");
        service(c, "stop", "Stop the service.");
        service(c, "status", "Show whether the service is installed and running.");
        c.command("service-run", "Started by the Windows service manager.", a -> WindowsService.serviceRun(a.pos().toArray(String[]::new)))
            .passThrough("MODE ARGS", "server, control-room or agent, and its options.");

        c.command("browser-connector", "The browser extension's link to this computer (shows its status).", a -> BrowserConnector.run("status", null));
        for (String verb : new String[] {"install", "status", "uninstall"})
            c.command("browser-connector " + verb, switch (verb) {
                case "install" -> "Register the connector with Edge and Chrome for this Windows user.";
                case "status" -> "Show whether Edge and Chrome can reach the connector.";
                default -> "Remove the connector's registration.";
            }, a -> BrowserConnector.run(verb, a.get("home") == null ? null : Path.of(a.get("home"))))
                .opt("--home", "DIR", "The install folder (default: this program's).");
        c.command("native-host", "Started by the browser for the extension.", a -> NativeHost.main(a.pos().toArray(String[]::new)))
            .passThrough("ARGS", "What the browser passes.");
    }

    /** The Command Center's data folder and database, for the commands that open it. */
    static OoaCli.Cmd database(OoaCli.Cmd c) {
        return c.opt("--data", "DIR", DATA)
            .opt("--db-url", "URL", "A JDBC address instead of the configured database.")
            .opt("--db-user", "NAME", "Its account.")
            .opt("--db-password-env", "VAR", "The environment variable with that account's password.")
            .opt("--db-config", "FILE", "Database settings other than DIR/config/ooa-db.properties.");
    }

    static OoaCli.Cmd network(OoaCli.Cmd c) {
        return c.opt("--ca", "FILE", "A certificate to trust for the Command Center (its own, or the company's CA).")
            .opt("--proxy", "PROXY", "system (default), none, or http://host:port.")
            .opt("--proxy-user", "NAME", "The proxy account.")
            .opt("--proxy-password-env", "VAR", "The environment variable with the proxy password.");
    }

    static OoaCli.Cmd db(OoaCli c, String verb, String description) {
        return c.command("db " + verb, description, a -> DbCommand.run(verb, a.o()))
            .opt("--data", "DIR", DATA).opt("--config", "FILE", "The settings file (default DIR/config/ooa-db.properties).");
    }

    static OoaCli.Cmd tls(OoaCli c, String verb, String description) {
        return c.command("tls " + verb, description, a -> TlsCommand.run(verb, a.o())).opt("--data", "DIR", DATA);
    }

    static OoaCli.Cmd service(OoaCli c, String verb, String description) {
        return c.command("service " + verb, description, a -> WindowsService.run(verb, a.o()))
            .opt("--mode", "MODE", "server (default), control-room or agent.")
            .opt("--name", "NAME", "The service name (default 'OOA Command Center', or 'OOA Runner').");
    }
}
