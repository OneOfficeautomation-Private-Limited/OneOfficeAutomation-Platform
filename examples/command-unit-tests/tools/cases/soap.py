"""Hand-written cases for the SOAP Web Service commands (stand-in price service at $standin_url$/cloud-soap/prices)."""
from helpers_cloud import adapt_samples, value

T = "ooa.soap/"
SVC = "$standin_url$/cloud-soap/prices"


def add(ua):
    adapt_samples(ua, ua.f.name)
    for n, t in (("soap_ops", "List"), ("soap_names", "List"), ("soap_status", "Number"), ("soap_values", "List"),
                 ("soap_text", "String"), ("soap_body", "Any"), ("soap_response", "String")):
        ua.var(n, t)

    with ua.case(T + "read_wsdl", "operations of a price service",
                 "Read WSDL reads a service description and lists its operations with SOAPAction, address and a body "
                 "to fill in. Checked: both operations and GetPrice's SOAPAction.") as f:
        f.act(T + "read_wsdl", desc="Read the price service's WSDL", wsdl=SVC + "?wsdl", timeout_seconds=30,
              out={"operations": "soap_ops", "names": "soap_names"})
        ua.check("contains", "$soap_names$", "GetPrice", what="the GetPrice operation")
        ua.check("contains", "$soap_names$", "ListSkus", what="the ListSkus operation")
        value(ua, "$soap_ops[0].soap_action", "eq", "urn:ut:prices/GetPrice", "the SOAPAction of the first operation")

    with ua.case(T + "send", "body built from the WSDL",
                 "Send SOAP request can build the request from the WSDL: the operation and its parameters. Checked: "
                 "HTTP 200 and the price read with an XPath.") as f:
        f.act(T + "send", desc="Ask the price of UT-200", wsdl=SVC + "?wsdl", operation="GetPrice", parameters={"sku": "UT-200"},
              xpath="//*[local-name()='price']", out={"status": "soap_status", "values": "soap_values", "body": "soap_body"})
        ua.check_eq("soap_status", 200)
        value(ua, "$soap_values[0]", "eq", "99.00", "the price")

    with ua.case(T + "send", "own envelope, nodes joined",
                 "Send SOAP request with a body written by hand and a SOAPAction; xpath_output joined gives the "
                 "selected nodes as one text. Checked: the three SKUs joined with a pipe.") as f:
        f.act(T + "send", desc="List the SKUs", url=SVC, soap_action="urn:ut:prices/ListSkus", version="1.1",
              body='<ListSkus xmlns="urn:ut:prices"/>', xpath="//*[local-name()='sku']", xpath_output="joined", delimiter="pipe",
              out={"text": "soap_text"})
        ua.check_eq("soap_text", "UT-100|UT-200|UT-300")

    with ua.case(T + "send", "a SOAP fault",
                 "A SOAP fault from the service fails the step with SOAP.FAULT (or with fail_on_error off gives the "
                 "status and response). Checked: both ways.") as f:
        ua.expect_error("SOAP.FAULT",lambda ff: ff.act(T + "send", desc="Ask for an unknown SKU", url=SVC, soap_action="urn:ut:prices/GetPrice",
                                                   body='<GetPrice xmlns="urn:ut:prices"><sku>NOPE</sku></GetPrice>'))
        f.act(T + "send", desc="The same, without failing", url=SVC, soap_action="urn:ut:prices/GetPrice", fail_on_error=False,
              body='<GetPrice xmlns="urn:ut:prices"><sku>NOPE</sku></GetPrice>', out={"status": "soap_status", "response": "soap_response"})
        ua.check_eq("soap_status", 500)
        ua.check("contains", "$soap_response$", "No price for SKU", what="the fault text")
