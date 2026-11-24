"""UT Item AI agent: runs the sample AI agent ut_ai_agent_item (agents/items/ai-agent) as a child agent. It uses the
stand-in model and the sample API agent as its tool, so both must be in the agent repository (Studio's workspace, or
live versions in One CommandCenter)."""
from helpers_platform import renumber

R = "core/run_bot"
ID = "ut_ai_agent_item"


def add(ua):
    for v, t in (("ai_answer", "String"), ("ai_status", "String"), ("ai_outputs", "Dictionary"), ("ai_trace", "List"),
                 ("ai_n", "Number"), ("ai_text", "String"), ("ai_run", "String")):
        ua.var(v, t)
    f = ua.f
    with ua.case(R, "the AI agent uses its tool and fills its output fields",
                 "Run Agent runs the AI agent with a request; the model calls UT API agent and the AI agent returns "
                 "status completed, the output fields total 37.5 and currency EUR, and a trace with the tool call."):
        f.act(R, automation=ID,
              inputs={"request": 'Total for 3 units at 12.5 eur. TOOL_ARGS: {"quantity": 3, "unit_price": 12.5, "currency": "eur"}',
                      "standin_url": "$standin_url$"},
              output_map={"answer": "ai_answer", "status": "ai_status", "outputs": "ai_outputs", "trace": "ai_trace"},
              timeout_seconds=300, out={"status": "ai_run"})
        ua.check_eq("ai_run", "success")
        ua.check_eq("ai_status", "completed")
        f.expr("ai_n", "$ai_outputs.total")
        ua.check("eq", "$ai_n$", 37.5, what="total")
        f.expr("ai_text", "$ai_outputs.currency")
        ua.check_eq("ai_text", "EUR")
        ua.check("contains", "${ai_trace}", "ut_api_agent_item", what="the trace")
    with ua.case(R, "a request the tool refuses",
                 "With a quantity of 0 the tool fails with its business error; the AI agent still ends (the stand-in "
                 "model reports what the tool said), and no total comes back."):
        f.act(R, automation=ID,
              inputs={"request": 'Total for none. TOOL_ARGS: {"quantity": 0, "unit_price": 12.5}', "standin_url": "$standin_url$"},
              output_map={"outputs": "ai_outputs", "status": "ai_status"}, timeout_seconds=300)
        f.expr("ai_text", "$ai_outputs.total")
        ua.check("empty", "$ai_text$", what="total")
    renumber(ua)
