package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** X8: a WSDL's SOAP operations, with request bodies built from its schema (inline and imported). */
class WsdlTest {

    static final String TYPES_XSD = """
        <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:acme:types" xmlns:t="urn:acme:types" elementFormDefault="qualified">
          <xsd:complexType name="Party"><xsd:sequence><xsd:element name="Name" type="xsd:string"/></xsd:sequence></xsd:complexType>
          <xsd:complexType name="Customer"><xsd:complexContent><xsd:extension base="t:Party">
            <xsd:sequence><xsd:element name="Vat" type="xsd:string" minOccurs="0"/></xsd:sequence></xsd:extension></xsd:complexContent></xsd:complexType>
        </xsd:schema>""";

    static final String WSDL_DOC = """
        <definitions xmlns="http://schemas.xmlsoap.org/wsdl/" xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/" xmlns:soap12="http://schemas.xmlsoap.org/wsdl/soap12/"
            xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:tns="urn:acme:orders" xmlns:t="urn:acme:types" targetNamespace="urn:acme:orders">
          <types>
            <xsd:schema targetNamespace="urn:acme:orders" elementFormDefault="qualified">
              <xsd:import namespace="urn:acme:types" schemaLocation="types.xsd"/>
              <xsd:element name="CreateOrder"><xsd:complexType><xsd:sequence>
                <xsd:element name="Customer" type="t:Customer"/>
                <xsd:element name="Line" maxOccurs="unbounded"><xsd:complexType><xsd:sequence>
                  <xsd:element name="Sku" type="xsd:string"/><xsd:element name="Qty" type="xsd:int"/></xsd:sequence></xsd:complexType></xsd:element>
              </xsd:sequence></xsd:complexType></xsd:element>
              <xsd:element name="CreateOrderResponse"><xsd:complexType><xsd:sequence><xsd:element name="Id" type="xsd:string"/></xsd:sequence></xsd:complexType></xsd:element>
            </xsd:schema>
          </types>
          <message name="CreateOrderIn"><part name="parameters" element="tns:CreateOrder"/></message>
          <message name="CreateOrderOut"><part name="parameters" element="tns:CreateOrderResponse"/></message>
          <message name="PingIn"><part name="text" type="xsd:string"/></message>
          <portType name="Orders">
            <operation name="CreateOrder"><input message="tns:CreateOrderIn"/><output message="tns:CreateOrderOut"/></operation>
            <operation name="Ping"><input message="tns:PingIn"/></operation>
          </portType>
          <binding name="Orders11" type="tns:Orders"><soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
            <operation name="CreateOrder"><soap:operation soapAction="urn:acme:orders/CreateOrder"/><input><soap:body use="literal"/></input></operation>
            <operation name="Ping"><soap:operation soapAction="urn:acme:orders/Ping" style="rpc"/><input><soap:body use="literal" namespace="urn:acme:ping"/></input></operation>
          </binding>
          <binding name="Orders12" type="tns:Orders"><soap12:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
            <operation name="CreateOrder"><soap12:operation soapAction="urn:acme:orders/CreateOrder"/><input><soap12:body use="literal"/></input></operation>
          </binding>
          <service name="OrderService">
            <port name="P11" binding="tns:Orders11"><soap:address location="https://erp.example.com/orders"/></port>
            <port name="P12" binding="tns:Orders12"><soap12:address location="https://erp.example.com/orders12"/></port>
          </service>
        </definitions>""";

    @Test @SuppressWarnings("unchecked")
    void listsOperationsWithBodiesToFillIn() throws Exception {
        Path w = PackagesTest.tmp();
        Files.writeString(w.resolve("types.xsd"), TYPES_XSD);
        Files.writeString(w.resolve("orders.wsdl"), WSDL_DOC);
        Map<String, Object> r = new SoapPackage().readWsdl(AiGapsTest.ctx(w, new ArrayList<>()), "orders.wsdl", null, null, 10, false);
        assertEquals(List.of("CreateOrder", "Ping"), r.get("names"));
        List<Map<String, Object>> ops = (List<Map<String, Object>>) r.get("operations");
        assertEquals(3, ops.size(), "two operations over SOAP 1.1, one over 1.2");
        Map<String, Object> create = ops.get(0);
        assertEquals("urn:acme:orders/CreateOrder", create.get("soap_action"));
        assertEquals("https://erp.example.com/orders", create.get("endpoint"));
        assertEquals("1.1", create.get("version"));
        String body = (String) create.get("body_template");
        assertTrue(body.startsWith("<ns:CreateOrder xmlns:ns=\"urn:acme:orders\">"), body);
        // the imported type, its base type's fields first, then its own (optional) one
        assertTrue(body.contains("<ns:Customer><ns:Name xmlns:ns=\"urn:acme:types\">?</ns:Name><!-- optional --><ns:Vat xmlns:ns=\"urn:acme:types\">?</ns:Vat></ns:Customer>"), body);
        assertTrue(body.contains("<!-- repeats --><ns:Line><ns:Sku>?</ns:Sku><ns:Qty>?</ns:Qty></ns:Line>"), body);
        XmlSupport.parse(body.replace("?", "x"), true);   // well-formed once filled in

        Map<String, Object> ping = ops.get(1);
        assertEquals("rpc", ping.get("style"));
        assertEquals("<ns:Ping xmlns:ns=\"urn:acme:ping\"><text>?</text></ns:Ping>", ping.get("body_template"));
        assertEquals("1.2", ops.get(2).get("version"));
        assertEquals("https://erp.example.com/orders12", ops.get(2).get("endpoint"));

        Files.writeString(w.resolve("not.wsdl"), "<html/>");
        assertEquals("SOAP.BAD_WSDL", assertThrows(ActionException.class, () -> new SoapPackage().readWsdl(AiGapsTest.ctx(w, new ArrayList<>()), "not.wsdl", null, null, 10, false)).code());
    }
}
