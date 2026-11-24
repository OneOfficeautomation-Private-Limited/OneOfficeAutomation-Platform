"""Web goal agent cases: a headless browser on the stand-in's order form and the stand-in's scripted model
($standin_url$/v1, OpenAI-compatible), which looks at the page and calls the agent's finish tool at once."""

WEB = "$standin_url$/dsk/web/"


def start(f):
    f.act("ooa.web_agent/start_session", desc="Open the order form in a headless Edge", url=WEB + "form.html", browser="edge",
          headless=True, session="goal")


def add(ua):
    for n, t in (("ut_ok", "Boolean"), ("ut_text", "String"), ("ut_n", "Number"), ("ut_list", "List")):
        ua.var(n, t)

    with ua.case("ooa.web_agent/start_session", "a session the goal agent can use",
                 "Start session opens a browser on an address for the goal agent; the normal Browser commands can read it too.") as f:
        start(f)
        f.act("ooa.browser/page_info", desc="Read the page title", session="goal", out={"title": "ut_text"})
        ua.check_eq("ut_text", "UT Order form")
        f.act("ooa.web_agent/end_session", desc="Close the session", session="goal")

    with ua.case("ooa.web_agent/run", "a goal reached with the scripted model",
                 "Run gives the model a goal and the page; it acts step by step until it calls finish. The scripted model "
                 "finishes at once and reports what it read.") as f:
        start(f)
        f.act("ooa.web_agent/run", desc="Ask for the form's title", session="goal", goal="Tell me the title of the order form page",
              provider="openai_compatible", base_url="$standin_url$/v1", model="qa-scripted", max_steps=3, timeout_seconds=120,
              out={"success": "ut_ok", "answer": "ut_text", "steps": "ut_list"})
        ua.check_eq("ut_ok", True)
        ua.check("contains", "$ut_text$", "QA scripted model", what="answer")
        f.act("ooa.web_agent/end_session", desc="Close the session", session="goal")

    with ua.case("ooa.web_agent/end_session", "the session is gone afterwards",
                 "End session closes the goal agent's browser; reading the page after it fails.") as f:
        start(f)
        f.act("ooa.web_agent/end_session", desc="Close the session", session="goal")
        ua.expect_error("*", lambda ff: ff.act("ooa.browser/page_info", desc="Read after closing", session="goal"))
