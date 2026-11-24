"""Cases for the JSON group: the samples (fuller converter) and the session commands."""
from helpers_core import resample


def add(ua):
    resample(ua)
    for n, t in (("js_value", "Any"), ("js_sku", "Any"), ("js_path", "Any"), ("js_text", "String")):
        ua.var(n, t)

    with ua.case("ooa.json/start_session", "load an order once, read and change it",
                 "Start session loads JSON once under a name; later steps read and change it by naming the session "
                 "instead of passing the value along. Checks: the loaded value, a read through the session and a "
                 "change seen by End session.") as f:
        f.act("ooa.json/start_session", desc="Load an order as session ut_order", session="ut_order",
              value={"order": {"id": 42, "items": [{"sku": "A-1", "qty": 2}]}}, out={"value": "js_value"})
        ua.check("contains", "${js_value}", "A-1", what="js_value")
        f.act("ooa.json/get_node", desc="Read the first sku from the session", session="ut_order",
              path="order.items[0].sku", out={"result": "js_sku"})
        ua.check_eq("js_sku", "A-1")
        f.act("ooa.json/update_node", desc="Change the quantity in the session", session="ut_order",
              path="order.items[0].qty", node=5)
        f.act("ooa.json/end_session", desc="Close the session", session="ut_order", out={"value": "js_value"})
        ua.check("contains", "${js_value}", '"qty":5', what="js_value after the change")

    with ua.case("ooa.json/end_session", "save the session to a file",
                 "End session closes a JSON session and can first save its document to a file. Checks: the path it "
                 "gives back, and the file holds the changed document.") as f:
        f.act("ooa.json/start_session", desc="Load a small document as session ut_cfg", session="ut_cfg",
              value={"mode": "test"})
        f.act("ooa.json/insert_node", desc="Add a key in the session", session="ut_cfg", path="retries", node=3)
        f.act("ooa.json/end_session", desc="Close it, saving to a file", session="ut_cfg",
              save_to="ut_tmp/json/cfg.json", out={"path": "js_path"})
        ua.check("endsWith", "$js_path$", "cfg.json", what="js_path")
        f.act("ooa.file/read_text", desc="Read the saved file", path="ut_tmp/json/cfg.json", out={"text": "js_text"})
        ua.check("contains", "$js_text$", '"retries":3', what="the saved file")
