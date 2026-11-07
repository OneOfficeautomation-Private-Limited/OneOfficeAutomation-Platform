package com.oneofficeautomation.cli;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.FlowValidator;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G74: AA Task Bots and UiPath workflows turned into OOA agents that validate and run, with what is left to do. */
class MigrateTest {
    @TempDir Path w;

    static final String AA = """
        {"nodes": [
          {"uid": "1", "commandName": "comment", "packageName": "Comment", "attributes": [{"name": "comment", "value": {"type": "STRING", "string": "Invoice check"}}]},
          {"uid": "2", "commandName": "assign", "packageName": "String", "returnTo": {"type": "VARIABLE", "variableName": "greeting"},
           "attributes": [{"name": "sourceString", "value": {"type": "STRING", "expression": "Hello $customer$!"}}]},
          {"uid": "3", "commandName": "if", "packageName": "If",
           "attributes": [{"name": "sourceValue", "value": {"type": "VARIABLE", "variableName": "amount"}}, {"name": "operator", "value": {"type": "STRING", "string": "GREATER_THAN"}},
                          {"name": "targetValue", "value": {"type": "NUMBER", "number": "1000"}}],
           "children": [{"uid": "4", "commandName": "assign", "packageName": "String", "returnTo": {"type": "VARIABLE", "variableName": "band"},
                         "attributes": [{"name": "sourceString", "value": {"type": "STRING", "string": "high"}}]}],
           "branches": [{"commandName": "else", "children": [{"uid": "5", "commandName": "assign", "packageName": "String", "returnTo": {"type": "VARIABLE", "variableName": "band"},
                         "attributes": [{"name": "sourceString", "value": {"type": "STRING", "string": "low"}}]}]}]},
          {"uid": "6", "commandName": "loop", "packageName": "Loop", "attributes": [{"name": "iterator", "value": {"type": "ITERATOR", "iteratorName": "times",
             "attributes": [{"name": "times", "value": {"type": "NUMBER", "number": "3"}}]}}],
           "children": [{"uid": "7", "commandName": "assign", "packageName": "Number", "returnTo": {"type": "VARIABLE", "variableName": "count"},
                         "attributes": [{"name": "sourceNumber", "value": {"type": "NUMBER", "expression": "$count$ + 1"}}]}]},
          {"uid": "8", "commandName": "openSpreadsheet", "packageName": "Excel_MS", "attributes": [{"name": "filePath", "value": {"type": "FILE", "string": "file:///C:/Data/In%20voices.xlsx"}}]},
          {"uid": "9", "commandName": "messageBox", "packageName": "MessageBox", "disabled": true, "attributes": []}
        ],
        "variables": [{"name": "customer", "type": "STRING", "input": true, "defaultValue": {"type": "STRING", "string": "ACME"}},
                      {"name": "amount", "type": "NUMBER", "input": true, "defaultValue": {"type": "NUMBER", "number": "1500"}},
                      {"name": "greeting", "type": "STRING", "output": true}, {"name": "band", "type": "STRING", "output": true},
                      {"name": "count", "type": "NUMBER", "output": true, "defaultValue": {"type": "NUMBER", "number": "0"}}],
        "packages": []}
        """;

    static final String XAML = """
        <Activity x:Class="Main" xmlns="http://schemas.microsoft.com/netfx/2009/xaml/activities" xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml"
                  xmlns:ui="http://schemas.uipath.com/workflow/activities">
          <x:Members>
            <x:Property Name="in_Name" Type="InArgument(x:String)" />
            <x:Property Name="out_Message" Type="OutArgument(x:String)" />
          </x:Members>
          <Sequence DisplayName="Main Sequence">
            <Sequence.Variables><Variable x:TypeArguments="x:Int32" Name="tries" Default="0" /></Sequence.Variables>
            <ui:Comment Text="Greets the customer" />
            <Assign><Assign.To><OutArgument x:TypeArguments="x:String">[out_Message]</OutArgument></Assign.To>
              <Assign.Value><InArgument x:TypeArguments="x:String">["Hi"]</InArgument></Assign.Value></Assign>
            <If Condition="[tries &lt; 1]">
              <If.Then><Sequence><ui:LogMessage Level="Info" Message="[out_Message]" /></Sequence></If.Then>
              <If.Else><ui:LogMessage Level="Info" Message="Never" /></If.Else>
            </If>
            <Delay Duration="00:00:00.01" />
            <ui:TypeInto DisplayName="Type name" Text="[in_Name]" />
          </Sequence>
        </Activity>
        """;

    @Test @SuppressWarnings("unchecked")
    void anAaBotBecomesAnAgentThatValidatesAndRuns() {
        Migrate.Result r = Migrate.fromAa((Map<String, Object>) JsonLite.parse(AA), "Invoice check");
        Map<String, Object> agent = r.agent();
        assertEquals("invoice_check", agent.get("id"));
        assertEquals(List.of(), new FlowValidator(FirstPartyPackages.registry(), null).validate(agent), JsonLite.write(agent));
        assertEquals(1, r.todo(), "the Excel command is left for a person");
        assertEquals(Map.of("Excel_MS.openSpreadsheet", 1), r.todoByCommand());
        assertTrue(JsonLite.write(agent).contains("TODO migrate: Excel_MS.openSpreadsheet"));
        assertTrue(JsonLite.write(agent).contains("C:/Data/In voices.xlsx"), "file:// addresses become paths");
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("disabled")));
        RunResult run = Runner.builder(FirstPartyPackages.registry()).workDir(w).build().run(agent, Map.of());
        assertEquals("success", run.status(), String.valueOf(run));
        assertEquals("Hello ACME!", run.outputs().get("greeting"));
        assertEquals("high", run.outputs().get("band"));
        assertEquals("3", String.valueOf(run.outputs().get("count")));
    }

    @Test void aUiPathWorkflowBecomesAnAgentThatValidatesAndRuns() throws Exception {
        Migrate.Result r = Migrate.fromUiPath(XAML, "Main");
        Map<String, Object> agent = r.agent();
        assertEquals(List.of(), new FlowValidator(FirstPartyPackages.registry(), null).validate(agent), JsonLite.write(agent));
        assertEquals(Map.of("TypeInto", 1), r.todoByCommand());
        RunResult run = Runner.builder(FirstPartyPackages.registry()).workDir(w).build().run(agent, Map.of("in_Name", "Asha"));
        assertEquals("success", run.status(), String.valueOf(run));
        assertEquals("Hi", run.outputs().get("out_Message"));
    }

    @Test void theCommandWritesAgentsAndAReport() throws Exception {
        Path in = Files.createDirectories(w.resolve("aa"));
        Files.writeString(in.resolve("Invoice check.json"), AA);
        Files.writeString(in.resolve("broken.json"), "{ not json");
        assertEquals(0, Migrate.run("aa", in.toString(), w.resolve("out").toString()));
        assertTrue(Files.exists(w.resolve("out/Invoice check.flow.json")));
        String report = Files.readString(w.resolve("out/migration-report.json"));
        assertTrue(report.contains("\"todo\":1") && report.contains("broken.json"), report);
    }
}
