package com.oneofficeautomation.cli;

import com.oneofficeautomation.sdk.OoaException;
import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.ParseResult;

import java.util.*;

/**
 * The ooa command line, read by picocli: every command, its options and their help ("ooa --help", "ooa db configure
 * --help"), unknown options refused with the nearest known one suggested, and a shell completion script. The commands
 * are declared in code ({@link CoreCommands}, {@link FullCommands}) rather than with annotations, so the native
 * executable needs no reflection for them. A command gets what it was given as {@link Args}.
 */
final class OoaCli {

    /**
     * What one command was given: its name ("db configure"), options by long name without dashes (a switch is "true")
     * and the positional values.
     */
    record Args(String command, Map<String, String> o, Map<String, List<String>> all, List<String> pos) {
        boolean has(String option) { return o.containsKey(option); }
        String get(String option) { return o.get(option); }
        /** Every value of an option given more than once (-i a=1 -i b=2). */
        List<String> all(String option) { return all.getOrDefault(option, List.of()); }
        String pos(int i) { return i < pos.size() ? pos.get(i) : null; }
    }

    interface Handler { int run(Args a) throws Exception; }

    private final CommandSpec root;

    OoaCli(String version) {
        root = spec("ooa", "ONEOFFICEAUTOMATION: build, run and manage automations.", null);
        // declared here, not with picocli's mixin, which reads annotations (the native executable has none of them)
        root.version("ONEOFFICEAUTOMATION " + version);
        root.addOption(OptionSpec.builder("-V", "--version").versionHelp(true).description("Show the version.").build());
        root.usageMessage().footer("", "Run 'ooa COMMAND --help' for a command's options.");
        command("completion", "Print a completion script for bash and zsh: source <(ooa completion)",
            a -> { System.out.print(AutoComplete.bash("ooa", new CommandLine(root))); return 0; });
    }

    /** The Java program: every command. */
    static OoaCli full() {
        OoaCli c = new OoaCli(com.oneofficeautomation.controlroom.ControlRoom.VERSION);
        CoreCommands.add(c);
        FullCommands.add(c);
        return c;
    }

    /** Lines shown at the end of "ooa --help". */
    OoaCli footer(String... lines) {
        List<String> all = new ArrayList<>(List.of(root.usageMessage().footer()));
        all.add("");
        all.addAll(List.of(lines));
        root.usageMessage().footer(all.toArray(String[]::new));
        return this;
    }

    /** A group of commands, such as "db" or "agent"; on its own it shows its commands. */
    OoaCli group(String path, String description) {
        add(path, spec(last(path), description, null));
        return this;
    }

    /** A command, such as "run" or "db configure", with what it does in one line. */
    Cmd command(String path, String description, Handler h) {
        CommandSpec s = spec(last(path), description, h);
        add(path, s);
        return new Cmd(s);
    }

    /** The options and positional values of one command. */
    static final class Cmd {
        private final CommandSpec spec;
        private Cmd(CommandSpec spec) { this.spec = spec; }

        /** An option with a value: names such as "--port" or "-i,--input". */
        Cmd opt(String names, String label, String description) { return add(names, label, description, String.class, false, "1"); }
        Cmd required(String names, String label, String description) { return add(names, label, description, String.class, true, "1"); }
        /** An option whose value must be a whole number. */
        Cmd number(String names, String label, String description) { return add(names, label, description, Integer.class, false, "1"); }
        /** A switch without a value. */
        Cmd flag(String names, String description) { return add(names, null, description, Boolean.class, false, "0"); }
        /** An option that may be given more than once. */
        Cmd repeat(String names, String label, String description) {
            spec.addOption(OptionSpec.builder(names.split(",")).paramLabel(label).description(description)
                .type(List.class).auxiliaryTypes(String.class).arity("1").build());
            return this;
        }

        /** Positional values: arity "1" (one, required), "0..1" (optional) or "1..*" (one or more). */
        Cmd param(String label, String description, String arity) {
            spec.addPositional(PositionalParamSpec.builder().paramLabel(label).description(description).arity(arity).required(!arity.startsWith("0"))
                .type(arity.endsWith("*") ? List.class : String.class).auxiliaryTypes(String.class).build());
            return this;
        }

        /** Everything after the command is handed on as it is (programs started by the browser or the service manager). */
        Cmd passThrough(String label, String description) {
            param(label, description, "0..*");
            spec.parser().unmatchedOptionsArePositionalParams(true);
            return this;
        }

        private Cmd add(String names, String label, String description, Class<?> type, boolean required, String arity) {
            OptionSpec.Builder b = OptionSpec.builder(names.split(",")).description(description).type(type).arity(arity).required(required);
            if (label != null) b.paramLabel(label);
            spec.addOption(b.build());
            return this;
        }
    }

    /** Runs the command line and returns the exit code: 0 done, 1 wrong use (with the help shown), 2 the command failed. */
    int execute(String... args) {
        CommandLine cl = commandLine();
        cl.setExecutionStrategy(this::dispatch);
        cl.setExecutionExceptionHandler((e, line, parsed) -> {
            if (!(e instanceof OoaException)) throw e;
            line.getErr().println("error: " + e);
            return 2;
        });
        return cl.execute(args);
    }

    /** What a command line gives its command, without running it; a wrong one throws picocli's ParameterException. */
    Args parse(String... args) {
        ParseResult last = commandLine().parseArgs(args);
        while (last.hasSubcommand()) last = last.subcommand();
        return args(last);
    }

    private CommandLine commandLine() {
        CommandLine cl = new CommandLine(root);
        cl.setExpandAtFiles(false);              // "@file" is a value like any other
        cl.setOverwrittenOptionsAllowed(true);   // an option given twice: the last one counts, as before
        return cl;
    }

    private int dispatch(ParseResult parsed) {
        Integer help = CommandLine.executeHelpRequest(parsed);
        if (help != null) return help;
        ParseResult last = parsed;
        while (last.hasSubcommand()) last = last.subcommand();
        CommandLine line = last.commandSpec().commandLine();
        if (!(last.commandSpec().userObject() instanceof Handler h)) {
            line.usage(line.getErr());
            return 1;
        }
        try {
            return h.run(args(last));
        } catch (Exception e) {
            throw new CommandLine.ExecutionException(line, String.valueOf(e.getMessage()), e);
        }
    }

    static Args args(ParseResult r) {
        Map<String, String> o = new LinkedHashMap<>();
        Map<String, List<String>> all = new LinkedHashMap<>();
        for (OptionSpec os : r.matchedOptions()) {
            if (os.usageHelp() || os.versionHelp()) continue;
            String name = os.longestName().replaceFirst("^-+", "");
            List<String> values = os.arity().max() == 0 ? List.of("true") : List.copyOf(os.stringValues());
            all.put(name, values);
            if (!values.isEmpty()) o.put(name, values.get(values.size() - 1));
        }
        List<String> pos = new ArrayList<>();
        for (PositionalParamSpec p : r.commandSpec().positionalParameters()) if (r.hasMatchedPositional(p)) pos.addAll(p.stringValues());
        return new Args(r.commandSpec().qualifiedName(" ").replaceFirst("^ooa ", ""), o, all, pos);
    }

    private static CommandSpec spec(String name, String description, Handler h) {
        CommandSpec s = h == null ? CommandSpec.create() : CommandSpec.wrapWithoutInspection(h);
        s.name(name).exitCodeOnInvalidInput(1);
        s.usageMessage().description(description);
        s.addOption(OptionSpec.builder("-h", "--help").usageHelp(true).description("Show this help.").build());
        return s;
    }

    private void add(String path, CommandSpec s) {
        CommandSpec parent = root;
        String[] names = path.split(" ");
        for (int i = 0; i < names.length - 1; i++) {
            CommandLine sub = parent.subcommands().get(names[i]);
            if (sub == null) throw new IllegalStateException("Declare the group '" + names[i] + "' before " + path);
            parent = sub.getCommandSpec();
        }
        parent.addSubcommand(s.name(), new CommandLine(s));
    }

    private static String last(String path) { return path.substring(path.lastIndexOf(' ') + 1); }
}
