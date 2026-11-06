package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.*;
import com.oneofficeautomation.sdk.*;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

/**
 * AI agents that reason toward a goal and call your published bots as tools. Guardrails: only the listed bots,
 * arguments checked against each bot's input variables, sensitive bots need a person's approval, step and token
 * limits, Credential inputs never exposed to the model, and every model call in the AI governance log.
 */
@OoaPackage(id = "ooa.ai_agent", name = "AI Agents", version = "1.0.0", description = "Run an AI agent that works toward a goal using your agents as tools, with approvals and limits.")
public final class AiAgentPackage {

    record Tool(String name, String botId, Map<String, Object> flow, Map<String, Object> schema, boolean sensitive) {}

    /** A form the agent may show to the person at the runner to ask for information or a decision. */
    record FormTool(String name, Path path, String title, String description, Map<String, Object> schema) {}

    /** Shows a form and waits for the person; returns the display result (values, button, submitted). Replaceable in tests. */
    interface FormAsker { Map<String, Object> ask(ActionContext ctx, FormTool form, Map<String, Object> values, double timeoutSeconds); }

    static volatile FormAsker formAsker = (ctx, form, values, timeout) ->
            new FormsPackage().display(ctx, form.path().toString(), "agent-" + form.name(), form.title(), values, "until_done", timeout, true, 560, 640, true);

    /** Turns a form from the form designer into a tool: its input fields become optional arguments (values to fill in first). */
    @SuppressWarnings("unchecked")
    static FormTool formTool(ActionContext ctx, String formPath) {
        Path p = ctx.resolvePath(formPath);
        if (!java.nio.file.Files.isRegularFile(p)) throw new ActionException("AGENT.FORM_NOT_FOUND", "Form not found: " + p, "Choose a .form.json file made in the form designer.");
        Map<String, Object> form;
        try { form = (Map<String, Object>) JsonLite.parse(java.nio.file.Files.readString(p)); }
        catch (Exception e) { throw new ActionException("AGENT.BAD_FORM", "Not a form from the form designer: " + p.getFileName(), "Open it in the form designer and save it again."); }
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map<String, Object> fld : FormsPackage.inputs(form)) {
            Map<String, Object> s = new LinkedHashMap<>();
            String type = String.valueOf(fld.get("type"));
            s.put("type", switch (type) { case "number" -> "number"; case "checkbox" -> "boolean"; default -> "string"; });
            Object label = fld.get("label") != null ? fld.get("label") : fld.get("id");
            s.put("description", "Value to fill in first for '" + label + "'");
            if (fld.get("options") instanceof List<?> opts && !opts.isEmpty() && (type.equals("dropdown") || type.equals("radio"))) s.put("enum", opts.stream().map(String::valueOf).toList());
            props.put(String.valueOf(fld.get("id")), s);
        }
        String id = String.valueOf(form.getOrDefault("id", p.getFileName().toString().replace(".form.json", "")));
        String title = String.valueOf(form.getOrDefault("name", id));
        String name = ("ask_" + id).replaceAll("[^A-Za-z0-9_-]", "_");
        String desc = (form.get("description") instanceof String d && !d.isBlank() ? d.strip() + " " : "")
                + "Shows the form '" + title + "' to the person at the computer and returns what they enter (values) and how they finished (submitted, or the button pressed). "
                + "Use it to ask for missing information or a decision; pass values you already know to fill in first.";
        return new FormTool(name.length() > 64 ? name.substring(0, 64) : name, p, title, desc, Map.of("type", "object", "properties", props, "required", List.of()));
    }

    @SuppressWarnings("unchecked")
    static Tool tool(AutomationRepository repo, String botId, boolean sensitive) {
        Map<String, Object> flow = repo.get(botId, "production");
        Object desc = flow.get("description");
        if (!(desc instanceof String d) || d.isBlank())
            throw new ActionException("AGENT.TOOL_UNDOCUMENTED", "Agent '" + botId + "' has no description", "Add a description under Properties in One AgentStudio (with no step selected); the AI agent reads it to decide when to call this agent.");
        Map<String, Object> props = new LinkedHashMap<>();
        List<Object> required = new ArrayList<>();
        for (Object o : (List<Object>) flow.getOrDefault("variables", List.of())) {
            Map<String, Object> v = (Map<String, Object>) o;
            if (!java.util.Arrays.asList("input", "in_out").contains(v.get("direction"))) continue;
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

    /**
     * Asks the person at the desktop to allow a sensitive agent. Nobody answering counts as "no": the question closes
     * after ooa.approval.timeoutSeconds (default 300), so an unattended runner never waits forever. Headless or
     * unattended runs: denied at once, as nobody is there to answer.
     */
    /**
     * A sensitive tool's approval: the person at the desktop when there is one; otherwise, with approvers named and One
     * CommandCenter there, a task in their Task inbox that the agent waits for (approval_timeout_seconds; no answer is no).
     */
    @SuppressWarnings("unchecked")
    static boolean approve(ActionContext ctx, String what, Map<String, Object> args, Extras extras, List<Object> trace) {
        boolean nobody = GraphicsEnvironment.isHeadless() || "unattended".equals(System.getProperty("ooa.runMode"));
        if (!nobody) return approve(ctx, new Tool(what, what, Map.of(), Map.of(), true), args);
        if (extras.approvers() == null || extras.approvers().isBlank()) return false;
        java.util.function.Function<Map<String, Object>, Map<String, Object>> cc;
        try { cc = ctx.service("command_center", java.util.function.Function.class); }
        catch (OoaException e) { cc = null; }
        if (cc == null) { ctx.warn("No One CommandCenter to ask for approval of " + what); return false; }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tool", what);
        for (var e : args.entrySet()) if (!(e.getValue() instanceof Map) && !(e.getValue() instanceof List)) data.put(e.getKey(), e.getValue());
        Map<String, Object> created = cc.apply(Map.of("call", "create_task", "args", Map.of("kind", "approval", "title", "AI agent: allow " + what + "?",
                "description", "An AI agent wants to use " + what + " with: " + JsonLite.write(args), "assignee", extras.approvers().strip(), "data", data)));
        String task = String.valueOf(created.get("task_id"));
        trace.add(Out.of("type", "approval", "tool", what, "status", "asked", "task_id", task));
        long until = System.nanoTime() + Math.max(30, extras.approvalTimeout()) * 1_000_000_000L;
        long pause = Long.getLong("ooa.approval.pollMillis", 5000);
        while (System.nanoTime() < until) {
            ctx.checkCancelled();
            Map<String, Object> st = cc.apply(Map.of("call", "task_status", "args", Map.of("task_id", task)));
            if ("completed".equals(st.get("status"))) {
                boolean yes = "approved".equals(st.get("outcome"));
                if (!yes) ctx.warn("The approver rejected " + what + (st.get("comment") == null ? "" : ": " + st.get("comment")));
                return yes;
            }
            if (!Set.of("open", "claimed").contains(String.valueOf(st.get("status")))) return false;
            try { Thread.sleep(pause); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        ctx.warn("No answer in the Task inbox about " + what + " in time");
        return false;
    }

    /** The search_knowledge tool: the best passages from each index, numbered, with their sources. */
    @SuppressWarnings("unchecked")
    static String searchKnowledge(ActionContext ctx, List<Path> indexes, Map<String, Object> args, List<Object> trace) {
        String q = args.get("query") instanceof String s ? s.strip() : "";
        if (q.isEmpty()) return JsonLite.write(Map.of("status", "rejected", "error", "Give a query."));
        List<Object> found = new ArrayList<>();
        KnowledgePackage kp = new KnowledgePackage();
        for (Path ix : indexes) {
            try { found.addAll((List<Object>) kp.search(ctx, ix.toString(), q, 4, null, null).get("results")); }
            catch (ActionException e) { found.add(Map.of("source", ix.getFileName().toString(), "error", e.getMessage())); }
        }
        trace.add(Out.of("type", "knowledge", "query", q, "passages", found.size()));
        return JsonLite.write(Map.of("status", "success", "passages", found));
    }

    static boolean approve(ActionContext ctx, Tool t, Map<String, Object> args) {
        if (GraphicsEnvironment.isHeadless() || "unattended".equals(System.getProperty("ooa.runMode"))) return false;
        int timeout = Integer.getInteger("ooa.approval.timeoutSeconds", 300);
        if (timeout <= 0) return false;
        final boolean[] ok = {false};
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                JOptionPane pane = new JOptionPane("The AI agent wants to run the sensitive agent '" + t.botId() + "' with:\n" + JsonLite.write(args)
                        + "\n\nAllow it? (No answer within " + timeout + " seconds means no.)", JOptionPane.WARNING_MESSAGE, JOptionPane.YES_NO_OPTION);
                javax.swing.JDialog dialog = pane.createDialog("ONEOFFICEAUTOMATION: approval needed");
                dialog.setAlwaysOnTop(true);
                javax.swing.Timer timer = new javax.swing.Timer(timeout * 1000, e -> dialog.dispose());
                timer.setRepeats(false);
                timer.start();
                dialog.setVisible(true);
                timer.stop();
                ok[0] = Integer.valueOf(JOptionPane.YES_OPTION).equals(pane.getValue());
            });
        } catch (Exception e) { return false; }
        if (!ok[0]) ctx.warn("No approval for " + t.botId() + " (refused or no answer in time)");
        return ok[0];
    }

    @SuppressWarnings("unchecked")
    @Action(id = "run", name = "Run AI agent", category = "AI Agents", timeoutSeconds = 1800, capabilities = {"network", "ai"},
            description = "Give an agent a goal and a set of agents it may use; it plans, calls the agents and returns its final answer and a trace.",
            panel = "goal, instructions, tools, [Let the agent ask a person with forms: forms, form_timeout_seconds], (Model to use: A saved connection = connection / Set up here = provider, model, api_key, base_url, aws_access_key_id, aws_region) | sensitive_tools, role, action_plan, output_fields, allow_cancel, [Toxicity guardrail: toxicity_guardrail, toxicity_level, blocked_terms], max_steps, max_tokens_total, max_duration_seconds, temperature, mask_pii, [Ask approvers in the Task inbox when nobody is at the runner: approvers, approval_timeout_seconds], knowledge_indexes, [Use an MCP server's tools: mcp_server_url, mcp_token, mcp_tools]")
    @Output(name = "answer", type = OoaType.STRING, description = "The agent's final answer.")
    @Output(name = "status", type = OoaType.STRING, description = "completed, limit_reached, stopped or blocked (by the toxicity guardrail); with output fields also failed or cancelled, as the agent decided.")
    @Output(name = "outputs", type = OoaType.DICTIONARY, description = "The output fields asked for (empty when none were).")
    @Output(name = "trace", type = OoaType.LIST, description = "Each model turn and tool call, for review and audit.")
    @Output(name = "tokens", type = OoaType.NUMBER, description = "Total tokens used.")
    public Map<String, Object> run(ActionContext ctx,
            @Input(name = "goal", type = OoaType.STRING, description = "What the agent should achieve, e.g. the customer email to handle.") String goal,
            @Input(name = "instructions", type = OoaType.STRING, required = false, defaultValue = "\"You are a careful business assistant. Use the tools to act; never invent results.\"", description = "Role, policy and rules for the agent.") String instructions,
            @Input(name = "tools", type = OoaType.LIST, required = false, description = "Ids of the agents it may call, e.g. [\"lookup_order\", \"check_refund_policy\"].") List<Object> toolIds,
            @Input(name = "sensitive_tools", type = OoaType.LIST, required = false, description = "Agent ids that need a person's approval before each call (e.g. issue_refund).") List<Object> sensitiveIds,
            @Input(name = "provider", type = OoaType.STRING, required = false, defaultValue = "\"openai\"", enumValues = {"openai", "azure_openai", "anthropic", "openai_compatible", "huggingface", "aws_bedrock_sagemaker", "aws_bedrock", "google_vertex"}, description = GenAiPackage.D_PROVIDER) String provider,
            @Input(name = "connection", type = OoaType.STRING, required = false, description = ModelConnections.D_CONNECTION) String connection,
            @Input(name = "model", type = OoaType.STRING, required = false, description = GenAiPackage.D_MODEL) String model,
            @Input(name = "api_key", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_KEY) Object key,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = GenAiPackage.D_BASE) String base,
            @Input(name = "max_steps", type = OoaType.NUMBER, required = false, defaultValue = "8", description = "Maximum model turns.") int maxSteps,
            @Input(name = "max_tokens_total", type = OoaType.NUMBER, required = false, defaultValue = "60000", description = "Stop when this many tokens have been used.") int maxTotal,
            @Input(name = "mask_pii", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = GenAiPackage.D_MASK) boolean mask,
            @Input(name = "aws_access_key_id", type = OoaType.CREDENTIAL, required = false, description = GenAiPackage.D_AWS_KEY_ID) Object awsKeyId,
            @Input(name = "aws_region", type = OoaType.STRING, required = false, description = GenAiPackage.D_AWS_REGION) String awsRegion,
            @Input(name = "role", type = OoaType.STRING, required = false, example = "Accounts payable clerk", description = "Who the agent acts as; added in front of the instructions.") String role,
            @Input(name = "action_plan", type = OoaType.STRING, required = false, description = "The order in which to use the tools and when to stop or ask a person, in plain words.") String plan,
            @Input(name = "output_fields", type = OoaType.DICTIONARY, required = false, example = "{\"refund_amount\": \"amount refunded as a number\"}",
                    description = "Values the agent must return, name to description; they come back in outputs. Plain text answer when empty.") Map<String, Object> outputFields,
            @Input(name = "allow_cancel", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Let the agent end with status cancelled when the goal should not be pursued (with output fields only).") boolean allowCancel,
            @Input(name = "max_duration_seconds", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Stop with limit_reached after this many seconds; no time limit when 0.") int maxSeconds,
            @Input(name = "temperature", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "0 = consistent, higher = more creative.") double temperature,
            @Input(name = "forms", type = OoaType.LIST, required = false, example = "[\"Forms/Approve refund.form.json\"]",
                    description = "Forms from the form designer the agent may show to the person at the runner, as tools, to ask for information or a decision (attended runners).") List<Object> forms,
            @Input(name = "form_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", min = 10, max = 86400,
                    description = "How long a form waits for the person; no answer is reported to the agent as not answered.") double formTimeout,
            @Input(name = "toxicity_guardrail", type = OoaType.STRING, required = false, defaultValue = "\"off\"", enumValues = {"off", "log", "block"},
                    description = "Check the goal, the model's answers and the arguments it gives tools for toxic language (threats, self-harm, hate, insults, swearing): "
                            + "log records it in the trace; block stops the agent with status blocked.") String toxicityAction,
            @Input(name = "toxicity_level", type = OoaType.STRING, required = false, defaultValue = "\"medium\"", enumValues = {"low", "medium", "high"},
                    description = "From which level the guardrail acts: high only threats, self-harm and hate; medium also insults and explicit content; low also swearing.") String toxicityLevel,
            @Input(name = "blocked_terms", type = OoaType.LIST, required = false, description = "Your own words or phrases the guardrail treats as high, e.g. competitor names or internal code names.") List<Object> blockedTerms,
            @Input(name = "approvers", type = OoaType.STRING, required = false, example = "role:supervisor",
                    description = "When nobody is at the runner, a sensitive tool's approval goes to the Task inbox of these people (user:NAME or role:NAME, separated by commas). Empty: denied at once.") String approvers,
            @Input(name = "approval_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "600", min = 30, max = 1700,
                    description = "How long to wait for an answer in the Task inbox; no answer means no.") int approvalTimeout,
            @Input(name = "knowledge_indexes", type = OoaType.LIST, required = false, example = "[\"Knowledge/policies.kidx\"]",
                    description = "Knowledge indexes (Knowledge Q&A > Build index) the agent searches itself for facts, with a search_knowledge tool.") List<Object> knowledge,
            @Input(name = "mcp_server_url", type = OoaType.STRING, required = false, example = "https://mcp.example.com/mcp", description = "An MCP server whose tools the agent may use (Streamable HTTP).") String mcpUrl,
            @Input(name = "mcp_token", type = OoaType.CREDENTIAL, required = false, description = "The MCP server's bearer token, when it needs one.") Object mcpToken,
            @Input(name = "mcp_tools", type = OoaType.LIST, required = false, description = "Which of the server's tools it may use (names); empty: all. Name them in sensitive_tools as mcp:NAME to need approval.") List<Object> mcpTools) {
        return runAgent(ctx, goal, instructions, toolIds, sensitiveIds, provider, connection, model, key, base, maxSteps, maxTotal, mask, awsKeyId, awsRegion,
                role, plan, outputFields, allowCancel, maxSeconds, temperature, forms, formTimeout, Toxicity.setting(toxicityAction, toxicityLevel, blockedTerms, "AGENT"),
                new Extras(approvers, approvalTimeout, knowledge, mcpUrl, McpPackage.secret(mcpToken), mcpTools));
    }

    /** Before approvers, knowledge and MCP (G44): the same agent without them. */
    public Map<String, Object> run(ActionContext ctx, String goal, String instructions, List<Object> toolIds, List<Object> sensitiveIds, String provider, String connection,
                                   String model, Object key, String base, int maxSteps, int maxTotal, boolean mask, Object awsKeyId, String awsRegion,
                                   String role, String plan, Map<String, Object> outputFields, boolean allowCancel, int maxSeconds, double temperature,
                                   List<Object> forms, double formTimeout, String toxicityAction, String toxicityLevel, List<Object> blockedTerms) {
        return runAgent(ctx, goal, instructions, toolIds, sensitiveIds, provider, connection, model, key, base, maxSteps, maxTotal, mask, awsKeyId, awsRegion,
                role, plan, outputFields, allowCancel, maxSeconds, temperature, forms, formTimeout, Toxicity.setting(toxicityAction, toxicityLevel, blockedTerms, "AGENT"), Extras.NONE);
    }

    /** G44: approvals through the Task inbox, knowledge indexes the agent searches, and an MCP server's tools. */
    record Extras(String approvers, int approvalTimeout, List<Object> knowledge, String mcpUrl, String mcpToken, List<Object> mcpTools) {
        static final Extras NONE = new Extras(null, 600, null, null, null, null);
    }
    static final String KNOWLEDGE_TOOL = "search_knowledge";

    public Map<String, Object> run(ActionContext ctx, String goal, String instructions, List<Object> toolIds, List<Object> sensitiveIds, String provider, String connection,
                                   String model, Object key, String base, int maxSteps, int maxTotal, boolean mask, Object awsKeyId, String awsRegion,
                                   String role, String plan, Map<String, Object> outputFields, boolean allowCancel, int maxSeconds, double temperature) {
        return run(ctx, goal, instructions, toolIds, sensitiveIds, provider, connection, model, key, base, maxSteps, maxTotal, mask, awsKeyId, awsRegion,
                role, plan, outputFields, allowCancel, maxSeconds, temperature, null, 600, "off", "medium", null);
    }

    /** Earlier signature, kept for callers written before role, plan, outputs and limits. */
    public Map<String, Object> run(ActionContext ctx, String goal, String instructions, List<Object> toolIds, List<Object> sensitiveIds, String provider, String connection,
                                   String model, Object key, String base, int maxSteps, int maxTotal, boolean mask, Object awsKeyId, String awsRegion) {
        return run(ctx, goal, instructions, toolIds, sensitiveIds, provider, connection, model, key, base, maxSteps, maxTotal, mask, awsKeyId, awsRegion, null, null, null, false, 0, 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runAgent(ActionContext ctx, String goal, String instructions, List<Object> toolIds, List<Object> sensitiveIds, String provider, String connection,
                                         String model, Object key, String base, int maxSteps, int maxTotal, boolean mask, Object awsKeyId, String awsRegion,
                                         String role, String plan, Map<String, Object> outputFields, boolean allowCancel, int maxSeconds, double temperature,
                                         List<Object> formPaths, double formTimeout, Toxicity.Setting toxic, Extras extras) {
        if (maxSeconds < 0) throw new ActionException("AGENT.BAD_OPTION", "max_duration_seconds cannot be negative");
        long deadline = maxSeconds > 0 ? System.nanoTime() + maxSeconds * 1_000_000_000L : Long.MAX_VALUE;
        boolean structured = outputFields != null && !outputFields.isEmpty();
        StringBuilder extra = new StringBuilder();
        if (role != null && !role.isBlank()) extra.append("Your role: ").append(role.strip()).append("\n");
        if (plan != null && !plan.isBlank()) extra.append("Action plan: ").append(plan.strip()).append("\n");
        if (structured) {
            List<String> states = new ArrayList<>(List.of("completed", "failed"));
            if (allowCancel) states.add("cancelled");
            extra.append("When finished, reply with one JSON object and no tool calls, with keys \"status\" (one of ").append(String.join(", ", states))
                 .append("), \"answer\" (a short summary), and \"outputs\" holding exactly these keys: ").append(JsonLite.write(outputFields)).append(". Use null for values you could not find.\n");
        }
        AutomationRepository repo = ctx.service("repository", AutomationRepository.class);
        BotInvoker invoker = ctx.service("bot_invoker", BotInvoker.class);
        Set<String> sensitive = new HashSet<>();
        if (sensitiveIds != null) for (Object o : sensitiveIds) sensitive.add(Values.text(o));
        Map<String, Tool> tools = new LinkedHashMap<>();
        if (toolIds != null) for (Object o : toolIds) { Tool t = tool(repo, Values.text(o), sensitive.contains(Values.text(o))); tools.put(t.name(), t); }
        Map<String, FormTool> forms = new LinkedHashMap<>();
        if (formPaths != null) for (Object o : formPaths) { FormTool ft = formTool(ctx, Values.text(o)); if (tools.containsKey(ft.name())) throw new ActionException("AGENT.BAD_OPTION", "A tool and a form are both named " + ft.name()); forms.put(ft.name(), ft); }
        List<Path> indexes = new ArrayList<>();
        if (extras.knowledge() != null) for (Object o : extras.knowledge()) {
            Path ip = ctx.resolvePath(Values.text(o));
            if (!java.nio.file.Files.isRegularFile(ip)) throw new ActionException("AGENT.KNOWLEDGE_NOT_FOUND", "Knowledge index not found: " + ip, "Build it with Knowledge Q&A > Build index.");
            indexes.add(ip);
        }
        McpClient mcp = null;
        Map<String, Map<String, Object>> mcpTools = new LinkedHashMap<>();   // tool name for the model -> the server's tool
        if (extras.mcpUrl() != null && !extras.mcpUrl().isBlank()) {
            mcp = new McpClient(extras.mcpUrl(), extras.mcpToken(), java.time.Duration.ofSeconds(120));
            mcp.initialize();
            Set<String> allowed = new HashSet<>();
            if (extras.mcpTools() != null) for (Object o : extras.mcpTools()) allowed.add(Values.text(o).strip());
            for (Map<String, Object> mt : mcp.tools()) {
                String n = String.valueOf(mt.get("name"));
                if (!allowed.isEmpty() && !allowed.contains(n)) continue;
                String local = ("mcp_" + n.replaceAll("[^A-Za-z0-9_-]", "_"));
                if (local.length() > 64) local = local.substring(0, 64);
                if (tools.containsKey(local) || forms.containsKey(local)) throw new ActionException("AGENT.BAD_OPTION", "Two tools are both named " + local);
                mcpTools.put(local, mt);
            }
            if (!allowed.isEmpty() && mcpTools.size() < allowed.size()) ctx.warn("The MCP server does not offer every tool named in mcp_tools");
        }
        if (tools.isEmpty() && forms.isEmpty() && indexes.isEmpty() && mcpTools.isEmpty())
            throw new ActionException("AGENT.NO_TOOLS", "Give the agent at least one agent, form, knowledge index or MCP tool to use");
        List<Map<String, Object>> toolSpecs = new ArrayList<>();
        for (Tool t : tools.values())
            toolSpecs.add(Map.of("name", t.name(), "description", t.flow().get("description") + (t.sensitive() ? " (Needs human approval; may be denied.)" : ""), "input_schema", t.schema()));
        for (FormTool ft : forms.values()) toolSpecs.add(Map.of("name", ft.name(), "description", ft.description(), "input_schema", ft.schema()));
        if (!indexes.isEmpty()) toolSpecs.add(Map.of("name", KNOWLEDGE_TOOL, "description", "Search the company's documents for passages that answer a question. Use it for facts and rules; cite what you use.",
                "input_schema", Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string", "description", "The question or words to look for.")), "required", List.of("query"))));
        for (var e : mcpTools.entrySet()) {
            boolean sens = sensitive.contains("mcp:" + e.getValue().get("name"));
            toolSpecs.add(Map.of("name", e.getKey(), "description", String.valueOf(e.getValue().getOrDefault("description", "")) + (sens ? " (Needs human approval; may be denied.)" : ""),
                    "input_schema", e.getValue().get("inputSchema") instanceof Map<?, ?> sch ? sch : Map.of("type", "object")));
        }
        List<Object> trace0 = new ArrayList<>();
        if (toxic != null && toxic.on()) {
            Toxicity.Rating g = Toxicity.check(ctx, toxic, goal, "goal", "AGENT.TOXIC", true);
            if (g != null && g.atLeast(toxic.level())) {
                trace0.add(Out.of("type", "guardrail", "message", "toxicity in the goal: " + g.level(), "categories", g.categories()));
                if (toxic.action().equals("block")) return result("blocked", "The goal was not acted on: the toxicity guardrail rated it " + g.level() + " (" + String.join(", ", g.categories()) + ").", trace0, 0);
            }
        }
        LlmClient.Config cfg = ModelConnections.config(ctx, connection, provider, model, key, base, temperature, 2048, mask, awsKeyId, awsRegion);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", (instructions == null ? "" : instructions) + "\n" + extra + "Rules: act only through the tools; use only facts from the goal and tool results; "
                + "if a tool is denied, do not retry it; when finished, reply with the final answer and no tool calls."));
        messages.add(Map.of("role", "user", "content", goal));
        List<Object> trace = new ArrayList<>(trace0);
        int tokens = 0;
        for (int step = 1; step <= maxSteps; step++) {
            ctx.checkCancelled();
            if (tokens >= maxTotal) return result("limit_reached", "Stopped: token limit of " + maxTotal + " reached.", trace, tokens);
            if (System.nanoTime() > deadline) return result("limit_reached", "Stopped: time limit of " + maxSeconds + " s reached.", trace, tokens);
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
            if (toxic != null && toxic.on()) {  // the answer and the arguments the model wants to pass on
                StringBuilder said = new StringBuilder(r.text() == null ? "" : r.text());
                for (LlmClient.ToolCall tc : r.toolCalls()) said.append('\n').append(JsonLite.write(tc.args()));
                Toxicity.Rating a = Toxicity.check(ctx, toxic, said.toString(), "model's answer", "AGENT.TOXIC", true);
                if (a != null && a.atLeast(toxic.level())) {
                    trace.add(Out.of("type", "guardrail", "message", "toxicity in the model's answer: " + a.level(), "categories", a.categories()));
                    if (toxic.action().equals("block"))
                        return result("blocked", "Stopped by the toxicity guardrail: the model's answer rated " + a.level() + " (" + String.join(", ", a.categories()) + ").", trace, tokens);
                }
            }
            if (r.toolCalls().isEmpty()) {
                if (!structured) return result("completed", r.text() == null ? "" : r.text(), trace, tokens);
                return structuredResult(r.text(), outputFields, allowCancel, trace, tokens);
            }
            for (LlmClient.ToolCall tc : r.toolCalls()) {
                Tool t = tools.get(tc.name());
                FormTool ft = forms.get(tc.name());
                String content;
                if (t == null && ft == null && tc.name().equals(KNOWLEDGE_TOOL) && !indexes.isEmpty()) content = searchKnowledge(ctx, indexes, tc.args(), trace);
                else if (t == null && ft == null && mcpTools.containsKey(tc.name())) {
                    Map<String, Object> mt = mcpTools.get(tc.name());
                    String server = String.valueOf(mt.get("name"));
                    if (sensitive.contains("mcp:" + server) && !approve(ctx, "mcp:" + server, tc.args(), extras, trace)) {
                        content = JsonLite.write(Map.of("status", "denied", "error", "A person did not approve this action. Do not retry; explain the outcome."));
                        trace.add(Map.of("type", "approval", "tool", "mcp:" + server, "status", "denied"));
                    } else {
                        Map<String, Object> res;
                        try { res = mcp.call(server, tc.args()); }
                        catch (ActionException e) { res = Map.of("is_error", true, "text", e.getMessage()); }
                        content = JsonLite.write(Out.of("status", Boolean.TRUE.equals(res.get("is_error")) ? "error" : "success", "text", res.get("text"), "result", res.get("structured")));
                        trace.add(Out.of("type", "tool", "tool", "mcp:" + server, "arguments", tc.args(), "status", Boolean.TRUE.equals(res.get("is_error")) ? "error" : "success"));
                    }
                }
                else if (t == null && ft != null) content = askForm(ctx, ft, tc.args(), formTimeout, trace);
                else if (t == null) {
                    content = JsonLite.write(Map.of("status", "rejected", "error", "Tool '" + tc.name() + "' is not available. Use one of " + tools.keySet() + "."));
                    trace.add(Map.of("type", "guardrail", "message", "rejected unknown tool " + tc.name()));
                } else if (checkArgs(t, tc.args()) != null) {
                    content = JsonLite.write(Map.of("status", "rejected", "error", checkArgs(t, tc.args())));
                    trace.add(Map.of("type", "guardrail", "message", "invalid arguments for " + t.botId()));
                } else if (t.sensitive() && !approve(ctx, t.botId(), tc.args(), extras, trace)) {
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

    /** Shows a form to the person and reports what happened to the model; unattended runners have nobody to ask. */
    @SuppressWarnings("unchecked")
    static String askForm(ActionContext ctx, FormTool ft, Map<String, Object> args, double timeout, List<Object> trace) {
        Map<String, Object> props = (Map<String, Object>) ft.schema().get("properties");
        Map<String, Object> values = new LinkedHashMap<>();
        for (var e : args.entrySet()) if (props.containsKey(e.getKey())) values.put(e.getKey(), e.getValue());
        if (formAsker == null || ((GraphicsEnvironment.isHeadless() || "unattended".equals(System.getProperty("ooa.runMode"))) && formAsker == DEFAULT_ASKER)) {
            trace.add(Out.of("type", "form", "form", ft.title(), "status", "unavailable"));
            return JsonLite.write(Map.of("status", "unavailable", "error", "Nobody is at this runner to fill in the form. Do not retry; finish without this information."));
        }
        Map<String, Object> r;
        try { r = formAsker.ask(ctx, ft, values, timeout); }
        catch (ActionException e) {
            trace.add(Out.of("type", "form", "form", ft.title(), "status", "failed", "error", e.code()));
            return JsonLite.write(Map.of("status", "failed", "error", e.getMessage()));
        }
        String button = String.valueOf(r.getOrDefault("button", ""));
        boolean submitted = Boolean.TRUE.equals(r.get("submitted"));
        String status = submitted ? "submitted" : button.equals("timeout") ? "not_answered" : button.equals("closed") ? "closed" : "button";
        trace.add(Out.of("type", "form", "form", ft.title(), "status", status, "button", button));
        Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("status", status);
        if (!button.isEmpty()) reply.put("button", button);
        if (submitted || status.equals("button")) reply.put("values", Values.toJsonable(r.get("values")));
        if (status.equals("not_answered")) reply.put("note", "The person did not answer in time. Do not retry the form.");
        if (status.equals("closed")) reply.put("note", "The person closed the form without submitting it.");
        return JsonLite.write(reply);
    }

    static final FormAsker DEFAULT_ASKER = formAsker;

    private static Map<String, Object> result(String status, String answer, List<Object> trace, int tokens) {
        return Out.of("answer", answer, "status", status, "trace", trace, "tokens", BigDecimal.valueOf(tokens), "outputs", new LinkedHashMap<>());
    }

    /** Reads the final JSON answer: status (an allowed end state), answer, and exactly the requested output fields. */
    static Map<String, Object> structuredResult(String text, Map<String, Object> fields, boolean allowCancel, List<Object> trace, int tokens) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (String f : fields.keySet()) outputs.put(f, null);
        Object j;
        try { j = LlmClient.parseJson(text); }
        catch (ActionException e) {
            trace.add(Map.of("type", "guardrail", "message", "final answer was not the requested JSON"));
            return Out.of("answer", text == null ? "" : text, "status", "failed", "trace", trace, "tokens", BigDecimal.valueOf(tokens), "outputs", outputs);
        }
        Map<?, ?> m = j instanceof Map<?, ?> mm ? mm : Map.of();
        String status = String.valueOf(m.get("status")).toLowerCase(Locale.ROOT);
        if (!(status.equals("completed") || status.equals("failed") || (allowCancel && status.equals("cancelled")))) status = "failed";
        if (m.get("outputs") instanceof Map<?, ?> o) for (String f : fields.keySet()) outputs.put(f, o.get(f));
        Object answer = m.get("answer");
        return Out.of("answer", answer == null ? "" : Values.text(answer), "status", status, "trace", trace, "tokens", BigDecimal.valueOf(tokens), "outputs", outputs);
    }
}
