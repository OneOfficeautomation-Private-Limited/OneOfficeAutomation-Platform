"""Browser cases in a headless Edge on the stand-in's pages.

The platform's browser samples run on their own pages ({web} = the stand-in serving the sample's files); the cases
below use the stand-in's order form ($standin_url$/dsk/web/form.html: name, colour, check box, Send button, a hidden
element, a table of people, a list of items, a download link, links to page B, and JavaScript functions add and greet)
and the list pages p1-p3 (each with a Next link but the last).
"""
from helpers_desktop import add_converted, find, fix_samples

WEB = "$standin_url$/dsk/web/"


def open_form(f, page="form.html", session=None):
    f.act("ooa.browser/open", desc="Open " + page + " in a headless Edge", url=WEB + page, browser="edge", headless=True,
          session=session)


def close(f, session=None):
    f.act("ooa.browser/close", desc="Close the browser", session=session, cont=True)


def add(ua):
    fix_samples(ua, ["ooa.browser"])
    for s in find("ooa.browser/", requires="browser"):
        add_converted(ua, s)
    for n, t in (("ut_text", "String"), ("ut_n", "Number"), ("ut_ok", "Boolean"), ("ut_any", "Any"), ("ut_table", "Table"),
                 ("ut_list", "List"), ("ut_dict", "Dictionary"), ("ut_url", "String")):
        ua.var(n, t)

    with ua.case("ooa.browser/type_text", "fill the form and send it",
                 "Set text types into a field, Select item picks an option by its text, Click presses the button; the page "
                 "then shows the thanks line, read with Get text.") as f:
        open_form(f)
        f.act("ooa.browser/type_text", desc="Type the name", selector="#name", text="Ann")
        f.act("ooa.browser/select_option", desc="Pick Green", selector="#color", option="Green")
        f.act("ooa.browser/click", desc="Press Send", selector="#go")
        f.act("ooa.browser/get_text", desc="Read the thanks line", selector="#out", out={"text": "ut_text"})
        ua.check_eq("ut_text", "Thanks Ann (Green)")
        close(f)

    with ua.case("ooa.browser/select_option", "by the option's text",
                 "Select item chooses an option of a list by its text; the selected value is read back with the element "
                 "action get_value.") as f:
        open_form(f)
        f.act("ooa.browser/select_option", desc="Pick Blue", selector="#color", option="Blue")
        f.act("ooa.browser/element_action", desc="Read the list's value", selector="#color", action_="get_value", out={"result": "ut_text"})
        ua.check_eq("ut_text", "b")
        close(f)

    with ua.case("ooa.browser/get_attribute", "a data attribute",
                 "Get attribute reads one attribute of an element.") as f:
        open_form(f)
        f.act("ooa.browser/get_attribute", desc="Read data-code of the intro", selector="#intro", attribute="data-code", out={"value": "ut_text"})
        ua.check_eq("ut_text", "F-7")
        f.act("ooa.browser/get_attribute", desc="An element that is not there", selector="#nope", attribute="id", if_not_found="continue",
              timeout_ms=1000, out={"found": "ut_ok"})
        ua.check_eq("ut_ok", False)
        close(f)

    with ua.case("ooa.browser/page_info", "title and address",
                 "Get URL and title reads the current tab's address and title.") as f:
        open_form(f)
        f.act("ooa.browser/page_info", desc="Read the address and title", out={"title": "ut_text", "url": "ut_url"})
        ua.check_eq("ut_text", "UT Order form")
        ua.check("endsWith", "$ut_url$", "/dsk/web/form.html", what="url")
        close(f)

    with ua.case("ooa.browser/navigate", "to page B and back",
                 "Go to URL loads another address in the same tab; Back, forward or refresh moves through the tab's history.") as f:
        open_form(f)
        f.act("ooa.browser/navigate", desc="Go to page B", url=WEB + "b.html")
        f.act("ooa.browser/page_info", desc="Read the title", out={"title": "ut_text"})
        ua.check_eq("ut_text", "UT Page B")
        f.act("ooa.browser/history", desc="Back", action_="back")
        f.act("ooa.browser/page_info", desc="Read the title", out={"title": "ut_text"})
        ua.check_eq("ut_text", "UT Order form")
        f.act("ooa.browser/history", desc="Refresh", action_="refresh")
        close(f)

    with ua.case("ooa.browser/extract_table", "the people table",
                 "Extract table turns an HTML table into a data table, the header row giving the column names.") as f:
        open_form(f)
        f.act("ooa.browser/extract_table", desc="Read the people table", selector="#people", out={"table": "ut_table"})
        f.act("ooa.datatable/row_count", desc="Count the rows", table="$ut_table$", out={"count": "ut_n"})
        ua.check_eq("ut_n", 2)
        f.act("ooa.datatable/get_value", desc="Bob's city", table="$ut_table$", row=1, column="City", out={"value": "ut_text"})
        ua.check_eq("ut_text", "Rome")
        close(f)

    with ua.case("ooa.browser/extract_list", "the items, two fields each",
                 "Extract list reads repeated elements into a table, one row per element and one column per field.") as f:
        open_form(f)
        f.act("ooa.browser/extract_list", desc="Read the items", selector="#items .item", fields={"Name": ".n", "Price": ".p"},
              out={"table": "ut_table", "count": "ut_n"})
        ua.check_eq("ut_n", 3)
        f.act("ooa.datatable/get_value", desc="The last price", table="$ut_table$", row=2, column="Price", out={"value": "ut_text"})
        ua.check_eq("ut_text", "7.00")
        close(f)

    with ua.case("ooa.browser/run_javascript", "an expression's value",
                 "Run JavaScript runs a script in the page and gives its value.") as f:
        open_form(f)
        f.act("ooa.browser/run_javascript", desc="Count the list items", script="document.querySelectorAll('#items li').length",
              out={"result": "ut_any"})
        ua.check("eq", "$ut_any$", 3, what="items")
        close(f)

    with ua.case("ooa.browser/call_javascript_function", "a page function with arguments",
                 "Call JavaScript function calls a function of the page with arguments and gives what it returns.") as f:
        open_form(f)
        f.act("ooa.browser/call_javascript_function", desc="greet('Ann')", function="greet", arguments=["Ann"], return_type="text",
              out={"result": "ut_text"})
        ua.check_eq("ut_text", "Hello Ann")
        f.act("ooa.browser/call_javascript_function", desc="add(2, 3)", function="add", arguments=[2, 3], return_type="number",
              out={"result": "ut_any"})
        ua.check("eq", "$ut_any$", 5, what="sum")
        close(f)

    with ua.case("ooa.browser/get_page_source", "saved to a file",
                 "Get page source gives the page's HTML and can save it to a file.") as f:
        open_form(f)
        f.act("ooa.browser/get_page_source", desc="Save the page source", path="br_source.html", out={"source": "ut_text"})
        ua.check("contains", "$ut_text$", "Order form", what="source")
        f.act("ooa.file/read_text", desc="Read the saved file", path="br_source.html", out={"text": "ut_text"})
        ua.check("contains", "$ut_text$", "function greet", what="saved file")
        close(f)

    with ua.case("ooa.browser/screenshot", "the whole page, and one element",
                 "Take screenshot saves a picture of the page or of one element.") as f:
        f.act("ooa.file/delete", desc="No old pictures", path="br_page.png", cont=True)
        open_form(f)
        f.act("ooa.browser/screenshot", desc="Picture of the whole page", path="br_page.png", full_page=True, out={"path": "ut_text"})
        ua.check("endsWith", "$ut_text$", "br_page.png", what="path")
        f.act("ooa.browser/screenshot", desc="Picture of the table", path="br_table.png", selector="#people")
        f.set("ut_cond", False)
        with f.if_({"condition": "ooa.file/exists", "args": {"path": "br_table.png"}}, desc="Is the table picture there?"):
            f.set("ut_cond", True)
        ua.check("eq", "$ut_cond$", True, what="br_table.png saved")
        close(f)

    with ua.case("ooa.browser/wait_for", "an element that appears later",
                 "Wait for element waits until an element is there (the page adds #later after 1.5 s); with any_of it says "
                 "which of several came first.") as f:
        open_form(f)
        f.act("ooa.browser/wait_for", desc="Wait for #later", selector="#later", state="visible", timeout_ms=10000)
        f.act("ooa.browser/get_text", desc="Read it", selector="#later", out={"text": "ut_text"})
        ua.check_eq("ut_text", "Arrived")
        f.act("ooa.browser/wait_for", desc="Which comes first: #nope or #title", any_of=["#nope", "#title"], timeout_ms=5000,
              out={"which": "ut_any"})
        ua.check("eq", "$ut_any$", 2, what="which (the number of the one that came)")
        f.act("ooa.browser/wait_for", desc="Wait for #hidden to stay hidden", selector="#hidden", state="hidden", timeout_ms=3000)
        close(f)

    ua.var("ut_cond", "Boolean", False)
    with ua.case("ooa.browser/element_visible", "a visible and a hidden element",
                 "Element is visible is a condition: true for the title, false for the hidden element.") as f:
        open_form(f)
        f.set("ut_cond", False)
        with f.if_({"condition": "ooa.browser/element_visible", "args": {"selector": "#title"}}, desc="Is the title visible?"):
            f.set("ut_cond", True)
        ua.check("eq", "$ut_cond$", True, what="title visible")
        with f.if_({"condition": "ooa.browser/element_visible", "args": {"selector": "#hidden"}}, desc="Is #hidden visible?"):
            f.set("ut_cond", False)
        ua.check("eq", "$ut_cond$", True, what="#hidden not visible")
        close(f)

    with ua.case("ooa.browser/element_text", "the heading's text",
                 "Element text is is a condition comparing an element's text: equals, contains, starts_with or matches.") as f:
        open_form(f)
        f.set("ut_cond", False)
        with f.if_({"condition": "ooa.browser/element_text", "args": {"selector": "#title", "text": "order", "match": "contains"}},
                   desc="Does the heading contain 'order'?"):
            f.set("ut_cond", True)
        ua.check("eq", "$ut_cond$", True, what="contains")
        with f.if_({"condition": "ooa.browser/element_text", "args": {"selector": "#title", "text": "Invoice", "match": "starts_with"}},
                   desc="Does it start with 'Invoice'?"):
            f.set("ut_cond", False)
        ua.check("eq", "$ut_cond$", True, what="not starts with")
        close(f)

    with ua.case("ooa.browser/text_on_page", "text that is there and text that is not",
                 "Page contains text is a condition on the whole page's text.") as f:
        open_form(f)
        f.set("ut_cond", False)
        with f.if_({"condition": "ooa.browser/text_on_page", "args": {"text": "Fill in the order"}}, desc="Is the intro there?"):
            f.set("ut_cond", True)
        ua.check("eq", "$ut_cond$", True, what="text there")
        with f.if_({"condition": "ooa.browser/text_on_page", "args": {"text": "Out of stock"}}, desc="Is 'Out of stock' there?"):
            f.set("ut_cond", False)
        ua.check("eq", "$ut_cond$", True, what="text missing")
        close(f)

    with ua.case("ooa.browser/element_exists", "an element that is not on the page",
                 "Element exists is false for an element the page does not have.") as f:
        open_form(f)
        f.set("ut_cond", True)
        with f.if_({"condition": "ooa.browser/element_exists", "args": {"selector": "#nope"}}, desc="Is #nope there?"):
            f.set("ut_cond", False)
        ua.check("eq", "$ut_cond$", True, what="#nope missing")
        close(f)

    with ua.case("ooa.browser/element_action", "check box, text and table cell",
                 "Element action does one of many things to an element: check a box and ask whether it is checked, set "
                 "and read a field's value, count elements, read a table cell.") as f:
        open_form(f)
        f.act("ooa.browser/element_action", desc="Tick the box", selector="#agree", action_="check")
        f.act("ooa.browser/element_action", desc="Is it ticked?", selector="#agree", action_="is_checked", out={"result": "ut_any"})
        ua.check("eq", "$ut_any$", True, what="checked")
        f.act("ooa.browser/element_action", desc="Set the name", selector="#name", action_="set_text", value="Bo")
        f.act("ooa.browser/element_action", desc="Add to the name", selector="#name", action_="append_text", value="b")
        f.act("ooa.browser/element_action", desc="Read the name", selector="#name", action_="get_value", out={"result": "ut_text"})
        ua.check_eq("ut_text", "Bob")
        f.act("ooa.browser/element_action", desc="Count the items", selector="#items li", action_="count", out={"result": "ut_any"})
        ua.check("eq", "$ut_any$", 3, what="count")
        f.act("ooa.browser/element_action", desc="Cell in row 2, column 2", selector="#people", action_="get_cell_text", row=2, column=2,
              index_from_one=True, out={"result": "ut_text"})
        ua.check_eq("ut_text", "Oslo")
        close(f)

    with ua.case("ooa.browser/switch_tab", "to the tab a link opened",
                 "Switch tab moves the session to another tab, by its title or number: the link opens page B in a new tab.") as f:
        open_form(f)
        f.act("ooa.browser/click", desc="Open page B in a new tab", selector="#newtab")
        f.act("core/delay", desc="Let the tab open", seconds=2)
        f.act("ooa.browser/switch_tab", desc="Switch to page B", title_contains="Page B", out={"title": "ut_text"})
        ua.check_eq("ut_text", "UT Page B")
        f.act("ooa.browser/close", desc="Close that tab", what="tab")
        close(f)

    with ua.case("ooa.browser/switch_tab", "by number, to a tab opened by Open browser",
                 "Switch tab by number: Open browser with open_in new_tab adds page B as a second tab of the session; tab 1 "
                 "is the form again.") as f:
        open_form(f)
        f.act("ooa.browser/open", desc="Open page B in a new tab", url=WEB + "b.html", browser="edge", headless=True, open_in="new_tab")
        f.act("ooa.browser/switch_tab", desc="Switch to page B by its title", title_contains="Page B", out={"title": "ut_text"})
        ua.check_eq("ut_text", "UT Page B")
        f.act("ooa.browser/switch_tab", desc="Switch to the first tab", index=0, out={"title": "ut_text"})
        ua.check_eq("ut_text", "UT Order form")
        close(f)

    with ua.case("ooa.browser/download", "the report link",
                 "Download file clicks a link that downloads a file and gives the saved file's path.") as f:
        f.act("ooa.folder/delete", desc="Empty download folder", path="br_dl", recursive=True, cont=True)
        open_form(f)
        f.act("ooa.browser/download", desc="Download the report", selector="#dl", folder="br_dl", timeout_ms=60000, out={"path": "ut_text"})
        ua.check("endsWith", "$ut_text$", ".csv", what="path")
        f.act("ooa.file/read_text", desc="Read it", path="$ut_text$", out={"text": "ut_text"})
        ua.check("startsWith", "$ut_text$", "id,amount", what="report")
        close(f)

    with ua.case("ooa.browser/pages", "click Next until it is gone",
                 "Each page is a loop type: the steps run on the first page, then Next is clicked, until there is no Next.") as f:
        open_form(f, "p1.html")
        f.set("ut_text", "")
        f.set("ut_n", 0)
        with f.loop(desc="Each page", iterator="ooa.browser/pages", **{"as": "ut_any"}, iterator_args={"next_selector": "#next", "max_pages": 10}):
            f.expr("ut_n", "$ut_n + 1", desc="Count the page")
            f.act("ooa.browser/page_info", desc="Read the title", out={"title": "ut_url"})
            f.set("ut_text", "${ut_text}${ut_url};", desc="Collect the title")
        ua.check_eq("ut_n", 3)
        ua.check("endsWith", "$ut_text$", "page 3;", what="titles")
        close(f)

    with ua.case("ooa.browser/list_windows", "the open browser session is listed",
                 "List windows (design time) lists the browser tabs and windows the agent could work in.") as f:
        open_form(f)
        f.act("ooa.browser/list_windows", desc="List the windows", include_desktop=False, out={"windows": "ut_list", "count": "ut_n"})
        ua.check("ge", "$ut_n$", 1, what="windows")
        close(f)

    with ua.case("ooa.browser/attach", "to a browser started for automation",
                 "Start browser for automation starts a headless Edge with a debugging port; Attach to open browser connects a "
                 "second session to it and reads its tab.") as f:
        f.act("ooa.browser/open_for_automation", desc="Start Edge with port 9341", browser="edge", port=9341, url=WEB + "b.html",
              headless=True, session="started", out={"endpoint": "ut_url", "pid": "ut_n"})
        f.act("ooa.browser/attach", desc="Attach to it", endpoint="$ut_url$", session="attached", out={"title": "ut_text"})
        ua.check_eq("ut_text", "UT Page B")
        f.act("ooa.browser/close", desc="Disconnect the attached session", session="attached")
        f.act("ooa.browser/close", desc="Close the started browser", session="started", cont=True)
        f.act("ooa.application/stop_program", desc="Stop the started Edge", process_id="$ut_n$", force=True, cont=True)
