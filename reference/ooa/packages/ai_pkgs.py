"""AI command packages: ooa.genai (Generative AI) and ooa.agents (Run AI agent).
Both call platform services, never model APIs directly, so every call is
governed by the AI Gateway (FR-GAI-001..004)."""
from __future__ import annotations

import json

from ..ai.gateway import Caller
from ..sdk import ActionException, Input, Output, action, package
from ..sdk.types import to_jsonable


def _caller(ctx, purpose="genai") -> Caller:
    s = ctx.services
    return Caller(tenant=ctx.tenant_id, user=s.get("principal_user", "system"),
                  roles=tuple(s.get("principal_roles", ("Automation Developer",))), team=s.get("principal_team", "default"),
                  automation_id=s.get("automation_id"), run_id=ctx.run_id, purpose=purpose)


def _ask(ctx, system, user, connection=None, schema=None, temperature=0.0):
    gw = ctx.service("ai_gateway")
    resp = gw.chat([{"role": "system", "content": system}, {"role": "user", "content": user}],
                   caller=_caller(ctx), connection=connection, response_schema=schema, temperature=temperature)
    for w in resp.warnings:
        ctx.log.warning(w)
    return resp


_CONN = Input("connection", "String", required=False, description="Model connection name; tenant default when empty.")


@package("ooa.genai", "Generative AI", "1.0.0",
         description="Prompt language models through the AI Gateway with masking, budgets and governance logging.")
class GenAIPackage:
    @action("prompt", "Prompt", category="Generative AI", description="Send a prompt and return the model's text answer.",
            inputs=[Input("prompt", "String", description="User prompt; ${...} interpolation allowed."),
                    Input("system", "String", required=False, default="You are a precise business assistant.", description="System instructions."),
                    Input("temperature", "Number", required=False, description="0 for deterministic output."), _CONN],
            outputs=[Output("text", "String", "Model answer."), Output("cost_usd", "Number", "Cost of the call.")],
            timeout_seconds=120, capabilities=("ai",))
    def prompt(self, ctx, prompt, system="You are a precise business assistant.", temperature=None, connection=None):
        r = _ask(ctx, system, prompt, connection, temperature=float(temperature or 0))
        return {"text": r.content or "", "cost_usd": r.cost_usd}

    @action("extract_to_schema", "Extract to schema", category="Generative AI",
            description="Extract structured fields from text; output is validated against a JSON Schema.",
            inputs=[Input("text", "String", description="Source text, e.g. an email body."),
                    Input("schema", "Dictionary", description="JSON Schema of the fields to extract."),
                    Input("instructions", "String", required=False, description="Extra guidance for the extraction."), _CONN],
            outputs=[Output("result", "Dictionary", "Extracted fields.")], timeout_seconds=120, capabilities=("ai",))
    def extract_to_schema(self, ctx, text, schema, instructions=None, connection=None):
        sys_ = "Extract the requested fields. Use null for anything not stated. Reply with JSON only."
        if instructions:
            sys_ += "\n" + instructions
        r = _ask(ctx, sys_, text, connection, schema=to_jsonable(schema))
        return {"result": r.parsed}

    @action("classify", "Classify", category="Generative AI", description="Classify text into one of the given labels.",
            inputs=[Input("text", "String", description="Text to classify."),
                    Input("labels", "List", description="Candidate labels."), _CONN],
            outputs=[Output("label", "String", "Chosen label."), Output("reason", "String", "Short justification.")],
            timeout_seconds=60, capabilities=("ai",))
    def classify(self, ctx, text, labels, connection=None):
        if not labels:
            raise ActionException("No labels given", code="GENAI.NO_LABELS")
        schema = {"type": "object", "required": ["label", "reason"],
                  "properties": {"label": {"enum": list(labels)}, "reason": {"type": "string"}}}
        r = _ask(ctx, "Classify the text into exactly one label. Reply with JSON only.",
                 f"Labels: {json.dumps(list(labels))}\n\nText:\n{text}", connection, schema=schema)
        return {"label": r.parsed["label"], "reason": r.parsed["reason"]}

    @action("summarize", "Summarise", category="Generative AI", description="Summarise text in a given number of sentences.",
            inputs=[Input("text", "String", description="Text to summarise."),
                    Input("sentences", "Number", required=False, default=3, description="Target length."), _CONN],
            outputs=[Output("summary", "String", "Summary.")], timeout_seconds=120, capabilities=("ai",))
    def summarize(self, ctx, text, sentences=3, connection=None):
        r = _ask(ctx, f"Summarise in at most {int(sentences)} sentences. Keep figures exact.", text, connection)
        return {"summary": r.content or ""}


@package("ooa.agents", "AI Agents", "1.0.0", description="Run published AI agents from inside a bot.")
class AgentsPackage:
    @action("run_agent", "Run AI agent", category="AI Agents",
            description="Start an AI agent with inputs; returns its answer, status and trace (FR-GAI-004).",
            inputs=[Input("agent", "String", description="Id of the published Agent."),
                    Input("inputs", "Dictionary", required=False, description="Agent input values.")],
            outputs=[Output("status", "String", "completed, awaiting_approval, limit_reached or failed."),
                     Output("answer", "String", "Final answer."), Output("result", "Any", "Parsed answer when the agent has an output schema."),
                     Output("agent_run_id", "String", "Id for resume and audit.")],
            timeout_seconds=600, capabilities=("ai",))
    def run_agent(self, ctx, agent, inputs=None):
        rt = ctx.service("agent_runtime")
        principal = ctx.service("principal")
        res = rt.run(agent, inputs or {}, principal)
        if res.status == "failed":
            err = res.error or {}
            raise ActionException(err.get("message", "Agent failed"), code=err.get("code", "AGENT.FAILED"),
                                  suggested_fix=err.get("suggestedFix"))
        return {"status": res.status, "answer": res.answer or "", "result": res.parsed, "agent_run_id": res.run_id}
