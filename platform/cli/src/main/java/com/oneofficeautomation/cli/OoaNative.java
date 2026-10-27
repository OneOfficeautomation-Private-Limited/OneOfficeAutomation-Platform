package com.oneofficeautomation.cli;

/**
 * The native executable (Q13; gradlew :cli:nativeExecutable): the commands that need no Command Center, compiled ahead
 * of time by GraalVM so they start at once and need no Java runtime, for build pipelines and quick checks. It knows
 * every toolkit's commands, so 'validate' checks any automation, but 'run' runs only the built-in toolkits; Excel,
 * Word, email, PDF, browser and Windows desktop steps need the full install (bin\ooa.bat) or a runner.
 */
public final class OoaNative {
    private OoaNative() { }

    public static void main(String[] args) {
        OoaCli c = new OoaCli(com.oneofficeautomation.controlroom.ControlRoom.VERSION)
            .footer("This program has the commands that need no Command Center. The full install",
                "(bin\\ooa.bat) has the servers, runners and administration, and runs Office,",
                "email, PDF, browser and desktop steps.");
        CoreCommands.add(c);
        System.exit(c.execute(args));
    }
}
