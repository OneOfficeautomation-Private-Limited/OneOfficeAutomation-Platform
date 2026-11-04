package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CorePackage;
import com.oneofficeautomation.sdk.PackageRegistry;
import com.oneofficeautomation.sdk.PackageSigning;

import java.util.List;
import java.util.Set;

/** First-party packages, signed with the first-party publisher key. */
public final class FirstPartyPackages {
    private FirstPartyPackages() {}

    public static final List<Class<?>> ALL = List.of(CorePackage.class, StringPackage.class, NumberPackage.class, DateTimePackage.class,
            ListPackage.class, DictionaryPackage.class, DataTablePackage.class, JsonPackage.class, CsvPackage.class, FilePackage.class,
            FolderPackage.class, RestPackage.class, DatabasePackage.class, CryptoPackage.class, SystemPackage.class,
            XmlPackage.class, LogPackage.class, BooleanPackage.class, ApplicationPackage.class, ScriptPackage.class, NetworkPackage.class,
            SoapPackage.class, ActiveDirectoryPackage.class, PrinterPackage.class, ServicesPackage.class, DesktopPackage.class, InterruptsPackage.class, ImagePackage.class,
            Microsoft365Package.class, GooglePackage.class, SalesforcePackage.class, ServiceNowPackage.class, JiraPackage.class, SlackPackage.class,
            WorkdayPackage.class, SoundPackage.class, RunContextPackage.class, GlobalValuesPackage.class, ExcelDesktopPackage.class, PowerPointDesktopPackage.class, OfficeAttachPackage.class, OutlookDesktopPackage.class,
            OcrPackage.class, PgpPackage.class, GenAiPackage.class, AiSkillsPackage.class, AiAgentPackage.class, KnowledgePackage.class,
            WorkloadPackage.class, InsightsPackage.class, TerminalPackage.class, SapPackage.class, SapBapiPackage.class, ConnectorPackage.class,
            FuzzyPackage.class, CurrencyPackage.class, TriggerLoopPackage.class,
            GitHubPackage.class, AzureDevOpsPackage.class, AsanaPackage.class, ZendeskPackage.class,
            OktaPackage.class, BoxPackage.class, DocuSignPackage.class, SnowflakePackage.class, DatabricksPackage.class, KafkaPackage.class, FormsPackage.class, Ec2Package.class, AzureVmPackage.class,
            AwsAiPackage.class, AzureAiPackage.class, GoogleAiPackage.class, ExchangePackage.class, DocumentAutomationPackage.class);

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
