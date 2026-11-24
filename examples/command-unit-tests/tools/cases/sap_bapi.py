"""Hand-written cases for the SAP BAPI commands. They need the SAP Java Connector (JCo) and an SAP test system (client
800 of a sandbox with the standard demo company code 1000); not available on this computer, so the agent is built and
validated only. The sign-in values are made up and read from the vault."""
from helpers_cloud import adapt_samples, value, cred

T = "ooa.sap_bapi/"


def add(ua):
    adapt_samples(ua, ua.f.name)
    for n, t in (("sap_system", "Dictionary"), ("sap_params", "List"), ("sap_required", "Dictionary"), ("sap_table", "Table"),
                 ("sap_names", "List"), ("sap_count", "Number"), ("sap_rows", "Number"), ("sap_struct", "Dictionary"),
                 ("sap_ok", "Boolean"), ("sap_msgs", "List"), ("sap_value", "Any")):
        ua.var(n, t)

    with ua.case(T + "connect", "application server and password",
                 "Connect signs in to an SAP system through the Java Connector (application server, system number, client, "
                 "user and password from the vault). Checked: the system it reports is client 800.") as f:
        f.act(T + "connect", desc="Sign in to the SAP sandbox", host="sap-sandbox.ut.test", system_number="00", client="800",
              user="UT_RPA", password=cred("sap/password"), language="EN", out={"system": "sap_system"})
        value(ua, "$sap_system.client", "eq", "800", "the client")

    with ua.case(T + "search_functions", "company code BAPIs",
                 "Find functions searches the remote-enabled functions by a name pattern. Checked: the company code "
                 "detail BAPI is found.") as f:
        f.act(T + "search_functions", desc="Find company code BAPIs", pattern="BAPI_COMPANYCODE*", max_rows=50, language="EN",
              out={"names": "sap_names", "count": "sap_count"})
        ua.check("contains", "$sap_names$", "BAPI_COMPANYCODE_GETDETAIL", what="the detail BAPI")

    with ua.case(T + "get_function_info", "parameters of a BAPI",
                 "Get function details lists a function's parameters and the values that must be given. Checked: "
                 "COMPANYCODEID is a required import.") as f:
        f.act(T + "get_function_info", desc="Details of the company code BAPI", function="BAPI_COMPANYCODE_GETDETAIL",
              out={"parameters": "sap_params", "required_values": "sap_required"})
        ua.check("contains", "$sap_required$", "COMPANYCODEID", what="the required import")

    with ua.case(T + "call_function", "company code details",
                 "Run BAPI / function calls a remote-enabled function with imports and gives back exports, tables and the "
                 "RETURN messages. Checked: success and the company name of 1000.") as f:
        f.act(T + "call_function", desc="Read company code 1000", function="BAPI_COMPANYCODE_GETDETAIL",
              imports={"COMPANYCODEID": "1000"}, return_structure="COMPANYCODE_DETAIL",
              out={"structure": "sap_struct", "success": "sap_ok", "messages": "sap_msgs"})
        ua.check_eq("sap_ok", True)
        value(ua, "$sap_struct.COMP_CODE", "eq", "1000", "the company code in the details")

    with ua.case(T + "read_table", "company codes",
                 "Read table reads rows of an SAP table with chosen fields and a WHERE condition. Checked: company code "
                 "1000 is read.") as f:
        f.act(T + "read_table", desc="Read company code 1000 from T001", table="T001", fields=["BUKRS", "BUTXT", "WAERS"],
              where="BUKRS = '1000'", max_rows=10, out={"table": "sap_table", "row_count": "sap_rows"})
        ua.check_eq("sap_rows", 1)
        value(ua, "$sap_table.rows[0][0]", "eq", "1000", "the company code")

    with ua.case(T + "commit", "keep a change",
                 "Commit makes the changes of the BAPIs called before it permanent (BAPI_TRANSACTION_COMMIT). A test "
                 "bank is created first without committing. Checked: no error message in the commit's answer.") as f:
        f.act(T + "call_function", desc="Create a test bank", function="BAPI_BANK_CREATE", commit=False, fail_on_error=False,
              imports={"BANK_CTRY": "DE", "BANK_KEY": "99900001", "BANK_ADDRESS": {"BANK_NAME": "UT Test Bank", "CITY": "Berlin"}},
              out={"success": "sap_ok"})
        f.act(T + "commit", desc="Commit it", wait=True, out={"messages": "sap_msgs"})
        ua.check("notContains", "$sap_msgs$", "E", what="error messages")

    with ua.case(T + "rollback", "drop a change",
                 "Roll back drops the changes of the BAPIs called since the last commit. Checked: the bank created "
                 "before the rollback cannot be read back.") as f:
        f.act(T + "call_function", desc="Create another test bank", function="BAPI_BANK_CREATE", commit=False, fail_on_error=False,
              imports={"BANK_CTRY": "DE", "BANK_KEY": "99900002", "BANK_ADDRESS": {"BANK_NAME": "UT Rolled Back", "CITY": "Bonn"}})
        f.act(T + "rollback", desc="Roll it back")
        f.act(T + "read_table", desc="Look for the bank", table="BNKA", fields=["BANKL"], where="BANKL = '99900002'",
              out={"row_count": "sap_rows"})
        ua.check_eq("sap_rows", 0)

    with ua.case(T + "disconnect", "close the connection",
                 "Disconnect closes the SAP connection. Checked: a step after it finds no connection.") as f:
        f.act(T + "disconnect", desc="Close the connection")
        ua.expect_error("SAP_BAPI.NO_SESSION", lambda ff: ff.act(T + "read_table", desc="This needs the closed connection", table="T001"))
