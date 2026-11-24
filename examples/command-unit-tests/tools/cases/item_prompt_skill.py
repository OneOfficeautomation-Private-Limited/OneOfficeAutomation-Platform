"""UT Item Prompt Skill: the sample skill UT_classify_ticket run against the stand-in model. Locally it is copied to
ai-skills/ in the work folder; in One CommandCenter a skill of that name kept there wins."""
from helpers_platform import renumber, items_input, copy_item, ai_key

S = "ooa.ai_skills/"
SKILL = "UT_classify_ticket"


def add(ua):
    items_input(ua)
    ai_key(ua)
    for v, t in (("ps_json", "Any"), ("ps_text", "String"), ("ps_ok", "Boolean"), ("ps_table", "Table")):
        ua.var(v, t)
    f = ua.f
    base = "${standin_url}/plat-ai/v1"

    def install():
        copy_item(f, "prompt-skill/" + SKILL + ".json", "ai-skills/" + SKILL + ".json",
                  desc="Put the sample skill in the workspace's ai-skills folder")

    with ua.case(S + "list", "the sample skill is offered",
                 "List Prompt Skills shows the sample skill with its description."):
        install()
        f.act(S + "list", out={"skills": "ps_table"})
        ua.check("contains", "${ps_table}", SKILL, what="the skills")
    with ua.case(S + "run", "an urgent invoice ticket",
                 "Run Prompt Skill fills the sample's template (customer and ticket) and gets JSON back: billing, "
                 "high priority."):
        install()
        f.act(S + "run", skill=SKILL, values={"customer": "Contoso", "ticket": "URGENT: invoice INV-9 was charged twice"},
              base_url=base, api_key="$ai_key$", out={"json": "ps_json", "success": "ps_ok"})
        ua.check_eq("ps_ok", True)
        f.expr("ps_text", "$ps_json.category")
        ua.check_eq("ps_text", "billing")
        f.expr("ps_text", "$ps_json.priority")
        ua.check_eq("ps_text", "high")
    with ua.case(S + "run", "an ordinary delivery question",
                 "The same skill sorts a question with no invoice and nothing urgent as other, normal priority."):
        install()
        f.act(S + "run", skill=SKILL, values={"customer": "Fabrikam", "ticket": "Where is my parcel?"},
              base_url=base, api_key="$ai_key$", out={"json": "ps_json"})
        f.expr("ps_text", "$ps_json.category")
        ua.check_eq("ps_text", "other")
        f.expr("ps_text", "$ps_json.priority")
        ua.check_eq("ps_text", "normal")
    renumber(ua)
