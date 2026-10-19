package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.*;
import com.oneofficeautomation.sdk.*;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.util.*;

/**
 * AI agents that reason toward a goal and call your published bots as tools. Guardrails: only the listed bots,
 * arguments checked against each bot's input variables, sensitive bots need a person's approval, step and token
 * limits, Credential inputs never exposed to the model, and every model call in the AI governance log.
 */
@OoaPackage(id = "ooa.ai_agent", name = "AI Agents", version = "1.0.0", description = "Run an AI agent that works toward a goal using your agents as tools, with approvals and limits.")
public final class AiAgentPackage {

    record Tool(String name, String botId, Map<String, Object> flow, Map<String, Object> schema, boolean sensitive) {}

    @SuppressWarnings("unchecked")
    static Tool tool(AutomationRepository repo, String botId, boolean sensitive) {
        Map<String, Object> flow = repo.get(botId, "production");
        Object desc = flow.get("description");
        if (!(desc instanceof String d) || d.isBlank())
            throw new ActionException("AGENT.TOOL_UNDOCUMENTED", "Agent '" + botId + "' has no description", "Add a description in Agent settings; the AI agent reads it to decide when to call this agent.");
        Map<String, Object> props = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();
        for (Object o : (List<Object>) flow.getOrDefault("variables", List.of())) {
            Map<String, Object> v = (Map<String, Object>) o;
            if (!"input".equals(v.get("direction"))) continue;
            if ("Credential".equals(v.get("type"))) {
                if (Boolean.TRUE.equals(v.get("required"))) throw new ActionException("AGENT.TOOL_NEEDS_SECRET", "Agent '" + botId + "' needs a Credential input, which a model must never supply",
                        "Read the credential inside the agent instead of taking it as an input.");
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>(SchemaTypes.forType(OoaType.fromFlowName(String.valueOf(v.get("type")))));
            if (v.get("description") != null) s.put("description", v.get("description"));
            if (v.get("enum") != null) s.put("enum", v.get("enum"));
            props.put((String) v.get("name"), s);
            if (Boolean.TRUE.equals(v.get("required")) && !v.containsKey("default")) required.add(v.get("name"));
        }
        Map<String, Object> schema = new LinkedHashMap<>(Map.of("type", "object", "properties", props, "required", required));
        String name = botId.replaceAll("[^A-Za-z0-9_-]", "_");
        return new Tool(name.length() > 64 ? name.substring(0, 64) : name, botId, flow, schema, sensitive);
    }

    /** Checks required arguments and rejects unknown ones; returns an error text for the model, or null when valid. */
    @SuppressWarnings("unchecked")
    static String checkArgs(Tool t, Map<String, Object> args) {
        Map<String, Object> props = (Map<String, Object>) t.schema().get("properties");
        for (Object r : (List<Object>) t.schema().get("required")) if (!args.containsKey(String.valueOf(r))) return "Missing required argument '" + r + "'.";
        for (String k : args.keySet()) if (!props.containsKey(k)) return "Unknown argument '" + k + "'. Allowed: " + props.keySet() + ".";
        return null;
    }

    static boolean approve(ActionContext ctx, Tool t, Map<String, Object> args) {
        if (GraphicsEnvironment.isHeadless()) return false;
        final boolean[] ok = {false};
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> ok[0] = JOptionPane.showConfirmDialog(null,
                    "The AI agent wants to run the sensitive agent '" + t.botId() + "' with:\n" + JsonLite.write(args) + "\n\nAllow it?",
                    "ONEOFFICEAUTOMATION: approval needed", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION);
        } catch (Exception e) { return false; }
        return ok[0];
    }

    @SuppressWarnings("unchecked")
    @Action(id = "run", name = "Run AI agent", category = "AI Agents", timeoutSeconds = 1800, capabilities = {"network", "ai"},
            description = "Give an agent a goal and a set of agents it may use; it plans, calls the agents and returns its final answer and a trace.")
    @Output(name = "answer", type = OoaType.STRING, description = "The agent's final answer.")
    @Output(name = "status", type = OoaType.STRING, description = "completed, limit_reached or stopped.")
    @Output(name = "trace", type = OoaType.LIST, description = "Each model turn and tool call, for review and audit.")
    @Output(name = "tokens", type = OoaType.NUMBER, description = "Total tokens used.")
    public Map<String, Object> run(ActionContext ctx,
            @Input(name = "goal", type = OoaType.STRING, description = "What the agent should achieve, e.g. the customer email to handle.") String goal,
            @Input(name = "instructions", type = OoaType.STRING, required = false, defaultValue = "\"You are a careful business assistant. Use the tools to act; never invent results.\"", description = "Role, policy and rules for the agent.") String instructions,
            @Input(name = "tools", type = OoaType.LIST, description = "Bot ids the agent may call, e.g. [\"lookup_order\", \"check_refund_policy\"].") List<Object> toolIds,
            @Input(name = "sensitive_tools", type = OoaType.LIST, required = false, description = "Agent ids that need a person's approval before each call (e.g. issue_refund).") List<Object> sensitiveIds,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible"}, description = GenAiPackage.D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = GenAiPackage.D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base,
            @Input(name = "max_steps", type = OoaType.NUMBER, required = false, defaultValue = "8", description = "Maximum model turns.") int maxSteps,
            @Input(name = "max_tokens_total", type = OoaType.NUMBER, required = false, defaultValue = "60000", description = "Stop when this many tokens have been used.") int maxTotal,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = GenAiPackage.D_MASK) boolean mask) {
        AutomationRepository repo = ctx.service("repository", AutomationRepository.class);
        BotInvoker invoker = ctx.service("bot_invoker", BotInvoker.class);
        Set<String> sensitive = new HashSet<>();
        if (sensitiveIds != null) for (Object o : sensitiveIds) sensitive.add(Values.text(o));
        Map<String, Tool> tools = new LinkedHashMap<>();
        for (Object o : toolIds) { Tool t = tool(repo, Values.text(o), sensitive.contains(Values.text(o))); tools.put(t.name(), t); }
        if (tools.isEmpty()) throw new ActionException("AGENT.NO_TOOLS", "Give the agent at least one agent to use");
        List<Map<String, Object>> toolSpecs = new ArrayList<>();
        for (Tool t : tools.values())
            toolSpecs.add(Map.of("name", t.name(), "description", t.flow().get("description") + (t.sensitive() ? " (Needs human approval; may be denied.)" : ""), "input_schema", t.schema()));
        LlmClient.Config cfg = ModelConnections.config(ctx, connection, provider, model, key, base, 0, 2048, mask);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", instructions + "\nRules: act only through the tools; use only facts from the goal and tool results; "
                + "if a tool is denied, do not retry it; when finished, reply with the final answer and no tool calls."));
        messages.add(Map.of("role", "user", "content", goal));
        List<Object> trace = new ArrayList<>();
        int tokens = 0;
        for (int step = 1; step <= maxSteps; step++) {
            ctx.checkCancelled();
            if (tokens >= maxTotal) return result("limit_reached", "Stopped: token limit of " + maxTotal + " reached.", trace, tokens);
            LlmClient.Reply r = LlmClient.chat(ctx, cfg, messages, toolSpecs, false, "agent");
            tokens += r.inTokens() + r.outTokens();
            List<Object> calls = new ArrayList<>();
            for (LlmClient.ToolCall tc : r.toolCalls()) calls.add(Map.of("id", tc.id(), "name", tc.name(), "args", tc.args()));
            Map<String, Object> asst = new LinkedHashMap<>();
            asst.put("role", "assistant");
            asst.put("content", r.text());
            asst.put("tool_calls", calls);
            messages.add(asst);
            trace.add(Out.of("type", "model", "step", step, "text", r.text(), "tool_calls", r.toolCalls().stream().map(LlmClient.ToolCall::name).toList()));
            if (r.toolCalls().isEmpty()) return result("completed", r.text() == null ? "" : r.text(), trace, tokens);
            for (LlmClient.ToolCall tc : r.toolCalls()) {
                Tool t = tools.get(tc.name());
                String content;
                if (t == null) {
                    content = JsonLite.write(Map.of("status", "rejected", "error", "Tool '" + tc.name() + "' is not available. Use one of " + tools.keySet() + "."));
                    trace.add(Map.of("type", "guardrail", "message", "rejected unknown tool " + tc.name()));
                } else if (checkArgs(t, tc.args()) != null) {
                    content = JsonLite.write(Map.of("status", "rejected", "error", checkArgs(t, tc.args())));
                    trace.add(Map.of("type", "guardrail", "message", "invalid arguments for " + t.botId()));
                } else if (t.sensitive() && !approve(ctx, t, tc.args())) {
                    content = JsonLite.write(Map.of("status", "denied", "error", "A person did not approve this action. Do not retry; explain the outcome."));
                    trace.add(Map.of("type", "approval", "tool", t.botId(), "status", "denied"));
                    ctx.warn("Sensitive agent " + t.botId() + " was not approved");
                } else {
                    if (t.sensitive()) trace.add(Map.of("type", "approval", "tool", t.botId(), "status", "approved"));
                    RunResult res = invoker.invoke(t.botId(), tc.args());
                    Map<String, Object> payload = res.ok() ? Out.of("status", "success", "outputs", Values.toJsonable(res.outputs()))
                            : Out.of("status", res.status(), "error", Values.toJsonable(res.error()));
                    content = JsonLite.write(payload);
                    trace.add(Out.of("type", "tool", "tool", t.botId(), "arguments", tc.args(), "status", res.status(), "run_id", res.runId()));
                }
                if (content.length() > 8000) content = content.substring(0, 8000) + "...(truncated)";
                messages.add(Map.of("role", "tool", "tool_call_id", tc.id(), "name", tc.name(), "content", content));
            }
        }
        return result("limit_reached", "Stopped after " + maxSteps + " steps without a final answer.", trace, tokens);
    }

    private static Map<String, Object> result(String status, String answer, List<Object> trace, int tokens) {
        return Out.of("answer", answer, "status", status, "trace", trace, "tokens", BigDecimal.valueOf(tokens));
    }
}
