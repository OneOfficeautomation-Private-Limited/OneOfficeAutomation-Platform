package com.oneofficeautomation.cli;

import java.nio.file.Path;

/**
 * The commands that need no Command Center, database or desktop: checking and running automations, the shared
 * conformance cases, and checking license files and releases. They are also in the native executable (OoaNative).
 */
final class CoreCommands {
    private CoreCommands() { }

    static void add(OoaCli c) {
        c.command("manifest", "Print the toolkit manifests (what Studio shows for each command).", a -> Ooa.manifest(a.has("summary")))
            .flag("--summary", "One line per toolkit: its id, version and actions.");
        c.command("validate", "Check automation files against the flow schema and the toolkits' commands.",
                a -> Ooa.validate(a.pos().toArray(String[]::new)))
            .param("FLOW", "Automation files (*.flow.json).", "1..*");
        c.command("run", "Run an automation file on this computer and print its result.", a -> Ooa.run(a.pos(0), a.all("input")))
            .param("FLOW", "The automation file.", "1")
            .repeat("-i,--input", "NAME=VALUE", "An input value (JSON, or else text); give it once per input.");
        c.command("conformance", "Run the language-neutral conformance cases.",
                a -> Ooa.conformance(Path.of(a.pos().isEmpty() ? "../contracts/conformance/cases" : a.pos(0))))
            .param("DIR", "The folder of cases (default ../contracts/conformance/cases).", "0..1");
        c.group("license", "Check license files" + (LicenseCommand.vendorBuild() ? ", and make them (vendor build)." : "; install them in Administration > Licenses."));
        c.command("license show", "Show a license file's terms and whether this release accepts its signature.", a -> LicenseCommand.show(a.pos(0)))
            .param("FILE", "The license file.", "1");
        c.group("release", "Check a release before installing it" + (LicenseCommand.vendorBuild() ? ", and sign releases (vendor build)." : "."));
        c.command("release verify", "Check a release's signed manifest and every file it lists.", a -> ReleaseCommand.verify(a.pos(0), a.get("dir")))
            .param("MANIFEST", "The signed manifest (ooa-VERSION.manifest.json).", "1")
            .opt("--dir", "DIR", "The folder with the release files (default: the manifest's folder).");
    }
}
