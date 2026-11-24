"""UT Item API agent: runs the sample API agent ut_api_agent_item (agents/items/api-agent) as a child agent. The child
is found in the agent repository by its id (Studio's workspace, or its live version in One CommandCenter)."""
from helpers_platform import renumber

R = "core/run_bot"
ID = "ut_api_agent_item"


def add(ua):
    for v, t in (("api_total", "Number"), ("api_cur", "String"), ("api_summary", "String"), ("api_status", "String"),
                 ("api_error", "String"), ("api_outputs", "Dictionary")):
        ua.var(v, t)
    f = ua.f
    with ua.case(R, "inputs in, outputs mapped back",
                 "Run Agent runs the API agent with a quantity, unit price and currency, and maps its outputs to this "
                 "agent's variables: total 37.5, currency in capitals, a summary line."):
        f.act(R, automation=ID, inputs={"quantity": 3, "unit_price": 12.5, "currency": "usd"},
              output_map={"total": "api_total", "currency": "api_cur", "summary": "api_summary"},
              timeout_seconds=120, out={"status": "api_status"})
        ua.check_eq("api_status", "success")
        ua.check("eq", "$api_total$", 37.5, what="total")
        ua.check_eq("api_cur", "USD")
        ua.check_eq("api_summary", "3 x 12.5 = 37.50 USD")
    with ua.case(R, "the default currency",
                 "Without a currency the API agent uses its default (eur), returned as EUR in the outputs."):
        f.act(R, automation=ID, inputs={"quantity": 2, "unit_price": 0.5}, out={"outputs": "api_outputs"})
        f.expr("api_cur", "$api_outputs.currency")
        ua.check_eq("api_cur", "EUR")
        f.expr("api_total", "$api_outputs.total")
        ua.check("eq", "$api_total$", 1, what="total")
    with ua.case(R, "its business error, without failing this agent",
                 "A quantity of 0 makes the API agent throw BUSINESS.BAD_QUANTITY; with fail_on_error off this step "
                 "carries on with status failed and the child's error."):
        f.act(R, automation=ID, inputs={"quantity": 0, "unit_price": 5}, fail_on_error=False,
              out={"status": "api_status", "error": "api_error"})
        ua.check_eq("api_status", "failed")
        ua.check("contains", "$api_error$", "BAD_QUANTITY", what="the child's error")
    renumber(ua)
