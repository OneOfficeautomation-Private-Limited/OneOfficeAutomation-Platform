"""Cases for the XML group: the samples (fuller converter) and the session commands."""
from helpers_core import resample

ORDER = "<order><id>42</id><line sku=\"A-1\"><qty>2</qty></line><line sku=\"B-7\"><qty>1</qty></line></order>"


def add(ua):
    resample(ua)
    for n, t in (("x_xml", "String"), ("x_value", "Any"), ("x_path", "Any"), ("x_text", "String")):
        ua.var(n, t)

    with ua.case("ooa.xml/start_session", "load an order once, read and change it",
                 "Start session loads XML once under a name; later steps read and change it by naming the session. "
                 "Checks: the loaded XML, a read through the session, and the change in the XML End session gives.") as f:
        f.act("ooa.xml/start_session", desc="Load an order as session ut_order", session="ut_order", xml=ORDER,
              out={"xml": "x_xml"})
        ua.check("contains", "$x_xml$", "<id>42</id>", what="x_xml")
        f.act("ooa.xml/get_value", desc="Read the sku of the second line", session="ut_order",
              xpath="//line[2]", attribute="sku", out={"value": "x_value"})
        ua.check_eq("x_value", "B-7")
        f.act("ooa.xml/set_value", desc="Change the order id in the session", session="ut_order", xpath="//id",
              value="43")
        f.act("ooa.xml/end_session", desc="Close the session", session="ut_order", out={"xml": "x_xml"})
        ua.check("contains", "$x_xml$", "<id>43</id>", what="x_xml after the change")

    with ua.case("ooa.xml/end_session", "save the session over its file",
                 "End session closes an XML session and can save it over the file it was loaded from. Checks: the "
                 "path it gives back, and the file holds the change.") as f:
        f.act("ooa.file/write_text", desc="An XML file", path="ut_tmp/xml/order.xml", text=ORDER)
        f.act("ooa.xml/start_session", desc="Load the file as session ut_file", session="ut_file",
              path="ut_tmp/xml/order.xml")
        f.act("ooa.xml/insert_node", desc="Add a note under the order", session="ut_file", parent_xpath="/order",
              name="note", text="rush")
        f.act("ooa.xml/end_session", desc="Close it, saving over the file", session="ut_file", save_in_place=True,
              out={"path": "x_path"})
        ua.check("endsWith", "$x_path$", "order.xml", what="x_path")
        f.act("ooa.file/read_text", desc="Read the file", path="ut_tmp/xml/order.xml", out={"text": "x_text"})
        ua.check("contains", "$x_text$", "<note>rush</note>", what="the saved file")
