package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CorePackage;
import com.oneofficeautomation.sdk.PackageRegistry;
import com.oneofficeautomation.sdk.PackageSigning;
import com.oneofficeautomation.sdk.ToolkitGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/** First-party packages, signed with the first-party publisher key. */
public final class FirstPartyPackages {
    private FirstPartyPackages() {}

    /** The core toolkits, in this module (the groups in platform/toolkits/<group> are found with ServiceLoader). */
    public static final List<Class<?>> CORE = List.of(
            CorePackage.class, StringPackage.class, NumberPackage.class, DateTimePackage.class, ListPackage.class, DictionaryPackage.class,
            DataTablePackage.class, JsonPackage.class, CsvPackage.class, FilePackage.class, FolderPackage.class, RestPackage.class,
            DatabasePackage.class, CryptoPackage.class, SystemPackage.class, XmlPackage.class, LogPackage.class, BooleanPackage.class,
            ApplicationPackage.class, ScriptPackage.class, NetworkPackage.class, SoapPackage.class, PrinterPackage.class, ServicesPackage.class,
            DesktopPackage.class, InterruptsPackage.class, ImagePackage.class, SoundPackage.class, RunContextPackage.class,
            GlobalValuesPackage.class, VaultPackage.class, ExcelDesktopPackage.class, PowerPointDesktopPackage.class, OfficeAttachPackage.class,
            OutlookDesktopPackage.class, OcrPackage.class, PgpPackage.class, GenAiPackage.class, WorkloadPackage.class, InsightsPackage.class,
            TerminalPackage.class, ConnectorPackage.class, FuzzyPackage.class, CurrencyPackage.class, TriggerLoopPackage.class, FormsPackage.class,
            AzureAiPackage.class, GoogleAiPackage.class, DocumentAutomationPackage.class, JobsPackage.class, EnvironmentLocksPackage.class,
            LongRunningPackage.class, DotNetPackage.class, McpPackage.class, RulesPackage.class, IdpPackage.class, CommsPackage.class,
            DataStorePackage.class, CodedAgentPackage.class);

    /** Every first-party toolkit: the core ones, then each group on the class path in the order of its id. */
    public static final List<Class<?>> ALL = all();

    private static List<Class<?>> all() {
        List<Class<?>> out = new ArrayList<>(CORE);
        List<ToolkitGroup> groups = new ArrayList<>();
        for (ToolkitGroup g : ServiceLoader.load(ToolkitGroup.class, FirstPartyPackages.class.getClassLoader())) groups.add(g);
        groups.sort(Comparator.comparing(ToolkitGroup::id));
        for (ToolkitGroup g : groups) for (Class<?> c : g.toolkits()) if (!out.contains(c)) out.add(c);
        return List.copyOf(out);
    }
    /** Optional packages in other modules, installed when their module is on the classpath. */
    public static final List<String> OPTIONAL = List.of(
            "com.oneofficeautomation.packages.office.ExcelPackage",
            "com.oneofficeautomation.packages.office.WordPackage",
            "com.oneofficeautomation.packages.office.PowerPointPackage",
            "com.oneofficeautomation.packages.email.EmailPackage",
            "com.oneofficeautomation.packages.pdf.PdfPackage",
            "com.oneofficeautomation.packages.browser.BrowserPackage",
            "com.oneofficeautomation.packages.browser.WebAgentPackage",
            "com.oneofficeautomation.packages.transfer.FileTransferPackage",
            "com.oneofficeautomation.packages.windows.WindowPackage",
            "com.oneofficeautomation.packages.windows.DllPackage",
            "com.oneofficeautomation.packages.windows.DesktopUiPackage", "com.oneofficeautomation.packages.windows.JavaAppsPackage",
            "com.oneofficeautomation.packages.windows.CredentialManagerPackage");

    /** Development registry: signs with an ephemeral first-party key. Release builds ship pre-signed JARs. */
    public static PackageRegistry registry(Set<String> allowedPublishers) {
        PackageSigning.PublisherKey key = PackageSigning.PublisherKey.generate("oneofficeautomation");
        PackageRegistry reg = new PackageRegistry(allowedPublishers);
        reg.trust(key);
        for (Class<?> c : ALL) reg.install(c, PackageSigning.sign(c, key));
        for (String name : OPTIONAL) {
            try {
                Class<?> c = Class.forName(name);
                reg.install(c, PackageSigning.sign(c, key));
            } catch (ClassNotFoundException | LinkageError ignored) {
                // module not on the classpath in this distribution
            }
        }
        return reg;
    }

    public static PackageRegistry registry() { return registry(null); }

    /**
     * Lets toolkits prepare slow first-use work in the background when a host that runs bots starts (the Bot Agent,
     * One AgentStudio): each optional toolkit class on the classpath with a public static warmUp() has it called, e.g.
     * PDF builds the computer's font list. Returns at once; never fails.
     */
    public static void warmUp() {
        for (String name : OPTIONAL) {
            try { Class.forName(name).getMethod("warmUp").invoke(null); }
            catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
                // module not on the classpath, or a toolkit with nothing to prepare
            }
        }
    }
}
