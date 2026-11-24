"""AI agent cases against the stand-in model (/plat-ai/v1). The tool is the sample API agent ut_api_agent_item
(agents/items/api-agent), found in the agent repository: run in Studio or One CommandCenter (ooa run has no repository)."""
from helpers_platform import renumber, ai_key

A = "ooa.ai_agent/run"
BASE = "${standin_url}/plat-ai/v1"
TOOL = "ut_api_agent_item"
GOAL = 'Work out the total of 3 units at 12.5 eur. TOOL_ARGS: {"quantity": 3, "unit_price": 12.5, "currency": "eur"}'


def add(ua):
    ai_key(ua)
    for v, t in (("ag_answer", "String"), ("ag_status", "String"), ("ag_outputs", "Dictionary"), ("ag_trace", "List"),
                 ("ag_n", "Number"), ("ag_text", "String"), ("ag_tokens", "Number")):
        ua.var(v, t)
    f = ua.f
    common = dict(provider="openai_compatible", model="ut-model", base_url=BASE, api_key="$ai_key$", mask_pii=False)
    with ua.case(A, "the model calls an agent as its tool, then answers",
                 "Run AI agent gives the model a goal and the agents it may use. The stand-in model calls UT API "
                 "agent with the quantity and price, then answers with what the tool returned; the trace has the "
                 "tool call."):
        f.act(A, goal=GOAL, tools=[TOOL], max_steps=4, **common,
              out={"answer": "ag_answer", "status": "ag_status", "trace": "ag_trace", "tokens": "ag_tokens"})
        ua.check_eq("ag_status", "completed")
        ua.check("contains", "$ag_answer$", "37.5", what="the answer")
        ua.check("contains", "${ag_trace}", TOOL, what="the trace")
        ua.check("gt", "$ag_tokens$", 0, what="tokens used")
    with ua.case(A, "output fields come back as a dictionary",
                 "With output fields, the agent must finish with those values: total and currency, taken from the "
                 "tool's outputs (37.5, EUR)."):
        f.act(A, goal=GOAL, tools=[TOOL], max_steps=4, role="Accounts payable clerk",
              action_plan="Call the tool once, then report.", instructions="Use the tools; never invent figures.",
              output_fields={"total": "the total as a number", "currency": "the currency code"},
              toxicity_guardrail="log", blocked_terms=["password"], max_duration_seconds=120, **common,
              out={"outputs": "ag_outputs", "status": "ag_status"})
        ua.check_eq("ag_status", "completed")
        f.expr("ag_n", "$ag_outputs.total")
        ua.check("eq", "$ag_n$", 37.5, what="total")
        f.expr("ag_text", "$ag_outputs.currency")
        ua.check_eq("ag_text", "EUR")
    with ua.case(A, "no tools: the model's answer as it is",
                 "Without tools the agent passes the model's answer on as it is (the stand-in says it has no fixed "
                 "answer), with status completed."):
        f.act(A, goal="Say hello.", tools=[], max_steps=2, **common, out={"answer": "ag_answer", "status": "ag_status"})
        ua.check_eq("ag_status", "completed")
        ua.check("startsWith", "$ag_answer$", "UT stand-in model", what="the answer")
    renumber(ua)
