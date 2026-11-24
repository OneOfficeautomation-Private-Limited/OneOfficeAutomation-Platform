"""A small SOAP 1.1 price service with its WSDL, for the "UT SOAP Web Service" agent.

    GET  /cloud-soap/prices?wsdl     the WSDL (operations GetPrice and ListSkus)
    POST /cloud-soap/prices          GetPrice(sku) -> price, currency; ListSkus() -> sku list; unknown sku -> SOAP fault
"""
import re

PRICES = {"UT-100": ("12.50", "EUR"), "UT-200": ("99.00", "EUR"), "UT-300": ("7.25", "USD")}

WSDL = """<?xml version="1.0" encoding="utf-8"?>
<definitions xmlns="http://schemas.xmlsoap.org/wsdl/" xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
  xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:ut:prices" targetNamespace="urn:ut:prices" name="PriceService">
  <types>
    <xs:schema targetNamespace="urn:ut:prices" elementFormDefault="qualified">
      <xs:element name="GetPrice"><xs:complexType><xs:sequence><xs:element name="sku" type="xs:string"/></xs:sequence></xs:complexType></xs:element>
      <xs:element name="GetPriceResponse"><xs:complexType><xs:sequence><xs:element name="price" type="xs:decimal"/><xs:element name="currency" type="xs:string"/></xs:sequence></xs:complexType></xs:element>
      <xs:element name="ListSkus"><xs:complexType><xs:sequence/></xs:complexType></xs:element>
      <xs:element name="ListSkusResponse"><xs:complexType><xs:sequence><xs:element name="sku" type="xs:string" maxOccurs="unbounded"/></xs:sequence></xs:complexType></xs:element>
    </xs:schema>
  </types>
  <message name="GetPriceIn"><part name="parameters" element="tns:GetPrice"/></message>
  <message name="GetPriceOut"><part name="parameters" element="tns:GetPriceResponse"/></message>
  <message name="ListSkusIn"><part name="parameters" element="tns:ListSkus"/></message>
  <message name="ListSkusOut"><part name="parameters" element="tns:ListSkusResponse"/></message>
  <portType name="PricePort">
    <operation name="GetPrice"><input message="tns:GetPriceIn"/><output message="tns:GetPriceOut"/></operation>
    <operation name="ListSkus"><input message="tns:ListSkusIn"/><output message="tns:ListSkusOut"/></operation>
  </portType>
  <binding name="PriceBinding" type="tns:PricePort">
    <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
    <operation name="GetPrice"><soap:operation soapAction="urn:ut:prices/GetPrice"/><input><soap:body use="literal"/></input><output><soap:body use="literal"/></output></operation>
    <operation name="ListSkus"><soap:operation soapAction="urn:ut:prices/ListSkus"/><input><soap:body use="literal"/></input><output><soap:body use="literal"/></output></operation>
  </binding>
  <service name="PriceService"><port name="PricePort" binding="tns:PriceBinding"><soap:address location="%s"/></port></service>
</definitions>
"""


def env(body):
    return ('<?xml version="1.0" encoding="utf-8"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">'
            '<soap:Body>%s</soap:Body></soap:Envelope>' % body)


def handle(h, m, path, q):
    if path != "/cloud-soap/prices":
        raise KeyError(path)
    if m == "GET":
        return h.send(200, WSDL % ("http://" + h.headers.get("Host", "127.0.0.1") + "/cloud-soap/prices"), ctype="text/xml")
    xml = h.raw().decode("utf-8", "replace")
    if re.search(r"<(?:\w+:)?ListSkus\b", xml):
        return h.send(200, env('<ListSkusResponse xmlns="urn:ut:prices">%s</ListSkusResponse>' % "".join("<sku>%s</sku>" % s for s in PRICES)), ctype="text/xml")
    sku = re.search(r"<(?:\w+:)?sku>([^<]*)<", xml)
    sku = sku.group(1).strip() if sku else ""
    if sku not in PRICES:
        return h.send(500, env("<soap:Fault><faultcode>soap:Client</faultcode><faultstring>No price for SKU '%s'</faultstring></soap:Fault>" % sku), ctype="text/xml")
    p, c = PRICES[sku]
    return h.send(200, env('<GetPriceResponse xmlns="urn:ut:prices"><price>%s</price><currency>%s</currency></GetPriceResponse>' % (p, c)), ctype="text/xml")


ROUTES = [("/cloud-soap/", handle, None)]
