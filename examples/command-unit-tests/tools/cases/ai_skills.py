"""Prompt Skill cases against the stand-in model (/plat-ai/v1 of the stand-in). The skills are written to ai-skills/
in the work folder (in One CommandCenter a skill of the same name kept there wins)."""
import json

from helpers_platform import drop, renumber, ai_key

S = "ooa.ai_skills/"
BASE = "${standin_url}/plat-ai/v1"

SUMMARISE = {"description": "UT: one-sentence summary of a complaint.", "system": "Be brief. SUMMARISE.",
             "prompt": "Summarise this complaint.\nText: ${text}", "provider": "openai_compatible", "model": "ut-model",
             "output": "text", "temperature": 0}
CLASSIFY = {"description": "UT: category and priority of a ticket, as JSON.",
            "system": "CLASSIFY-TICKET. Reply only with JSON: category, priority.", "prompt": "Ticket: $ticket$",
            "provider": "openai_compatible", "model": "ut-model", "output": "json", "max_tokens": 200}


def _no_dollar(skill):
    """The skill as JSON text with each dollar sign written as a JSON escape (backslash u0024), so the agent does not
    fill the skill's placeholders with its own variables when it writes the file."""
    return json.dumps(skill, indent=1).replace("$", "\\u0024")


def add(ua):
    drop(ua, "")     # the samples use the sample runner's mock server ({mock}); these cases use the stand-in
    ai_key(ua)
    for v, t in (("sk_table", "Table"), ("sk_text", "String"), ("sk_json", "Any"), ("sk_ok", "Boolean"),
                 ("sk_err", "String"), ("sk_n", "Number")):
        ua.var(v, t)
    f = ua.f

    def write_skills():
        f.act("ooa.file/write_text", desc="Save the skill ut_summarise", path="ai-skills/ut_summarise.json",
              text=_no_dollar(SUMMARISE))
        f.act("ooa.file/write_text", desc="Save the skill ut_classify", path="ai-skills/ut_classify.json",
              text=_no_dollar(CLASSIFY))

    with ua.case(S + "list", "the saved skills with their descriptions",
                 "List Prompt Skills gives the skills this run can use as a table; the two skills saved in the "
                 "workspace's ai-skills folder are in it with their descriptions."):
        write_skills()
        f.act(S + "list", out={"skills": "sk_table"})
        ua.check("contains", "${sk_table}", "ut_summarise", what="the skill names")
        ua.check("contains", "${sk_table}", "category and priority", what="the descriptions")
        f.expr("sk_n", "len($sk_table)")
        ua.check("ge", "$sk_n$", 2, what="skills listed")
    with ua.case(S + "run", "a text skill filled with a value",
                 "Run Prompt Skill fills the skill's template with the values and sends it to the model; a text "
                 "skill gives back text (the stand-in model summarises with the first sentence)."):
        write_skills()
        f.act(S + "run", skill="ut_summarise", values={"text": "My parcel came two weeks late. I want a refund."},
              base_url=BASE, api_key="$ai_key$", out={"text": "sk_text", "success": "sk_ok"})
        ua.check_eq("sk_ok", True)
        ua.check_eq("sk_text", "Summary: My parcel came two weeks late.")
    with ua.case(S + "run", "a JSON skill gives a dictionary",
                 "A skill with output json gives back a Dictionary in json: an urgent invoice ticket is billing, "
                 "high priority; the tokens used are counted."):
        write_skills()
        f.act(S + "run", skill="ut_classify", values={"ticket": "URGENT: invoice INV-9 was charged twice"},
              base_url=BASE, api_key="$ai_key$", temperature=0, max_tokens=100,
              out={"json": "sk_json", "success": "sk_ok", "output_tokens": "sk_n"})
        ua.check_eq("sk_ok", True)
        f.expr("sk_text", "$sk_json.category")
        ua.check_eq("sk_text", "billing")
        f.expr("sk_text", "$sk_json.priority")
        ua.check_eq("sk_text", "high")
        ua.check("gt", "$sk_n$", 0, what="output tokens")
    with ua.case(S + "run", "a model that cannot be reached, without failing the step",
                 "With fail_on_error off, a model address that answers 404 does not fail the step: success is false "
                 "and error says why."):
        write_skills()
        f.act(S + "run", skill="ut_summarise", values={"text": "x"}, base_url="${standin_url}/plat-ai/nowhere",
              api_key="$ai_key$", fail_on_error=False, out={"success": "sk_ok", "error": "sk_err"})
        ua.check_eq("sk_ok", False)
        ua.check("notEmpty", "$sk_err$", what="the error")
    renumber(ua)
