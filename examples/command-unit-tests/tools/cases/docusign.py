"""DocuSign cases: Connect with a saved connection (connections.json in the run folder), then envelopes, recipients,
documents, templates, embedded signing and custom fields against the stand-in, and Disconnect."""
from helpers_business import drop_samples, cred, url, vars_

BASE = url("docusign", "/restapi")
ACCOUNT = "ut-account"
TEMPLATE = "11111111-2222-3333-4444-555555555555"
DONE = "aaaaaaaa-bbbb-cccc-dddd-000000000001"          # a completed envelope the stand-in starts with
MISSING = "99999999-9999-9999-9999-999999999999"
CONN = "UT DocuSign stand-in"
CONNECTIONS = ('{"' + CONN + '": {"package": "ooa.docusign", "description": "Unit tests: the local stand-in",'
               ' "values": {"base_url": "' + BASE + '", "account_id": "' + ACCOUNT + '"},'
               ' "credentials": {"access_token": "unit_tests/docusign/token"}}}')
SIGNER = {"name": "Ann Lee", "email": "ann.lee@ut.example", "client_user_id": "ut-ann"}


def _session_steps(steps, cmd_prefix, required):
    """Steps that rely on Connect leave the required inputs empty (""): the flow check asks for every required input
    unless the step names its own connection, and the runner fills empty inputs from the Connect step's connection."""
    for s in steps:
        if not isinstance(s, dict):
            continue
        a = s.get("action", "")
        if a.startswith(cmd_prefix) and not a.endswith(("/connect", "/disconnect")):
            for k in required:
                s.setdefault("args", {}).setdefault(k, "")
        for k in ("children", "else", "finally", "default"):
            if isinstance(s.get(k), list):
                _session_steps(s[k], cmd_prefix, required)
        for k in ("elseIf", "catch", "cases"):
            for b in s.get(k) or []:
                if isinstance(b, dict):
                    _session_steps(b.get("children") or [], cmd_prefix, required)


def _draft(f, send_now=False):
    """A new envelope with one document, a signer for embedded signing and a copy recipient."""
    f.act("ooa.file/write_text", desc="Prepare the document", path="ut_docusign_contract.txt", text="Contract C-77 /sig/")
    f.act("ooa.docusign/create_envelope", desc="Create the envelope", email_subject="UT contract C-77", email_message="Please sign.",
          documents=["ut_docusign_contract.txt"], signers=[SIGNER], copy_to=[{"name": "Legal Team", "email": "legal@ut.example"}],
          sign_here_text="/sig/", send_now=send_now, out={"envelope_id": "ds_env", "status": "ds_status"})


def add(ua):
    drop_samples(ua)
    vars_(ua, "ds_conn", "ds_env", "ds_status", "ds_envelope", "ds_table", "ds_count", "ds_n", "ds_template", "ds_roles",
          "ds_url", "ds_path", "ds_exists", "ds_fields", "ds_values")

    with ua.case("ooa.docusign/connect", "sign in with a saved connection",
                 "Connect picks a saved connection (REST address, account id and access token from the vault); the later "
                 "DocuSign steps leave them empty and use it. The case saves the connection in the run folder first. "
                 "Checks the connection's name comes back.") as f:
        f.act("ooa.file/write_text", desc="Save the connection to the stand-in", path="connections.json", text=CONNECTIONS)
        f.act("ooa.docusign/connect", desc="Use the saved connection", connection=CONN, out={"connection": "ds_conn"})
        ua.check_eq("ds_conn", CONN)

    with ua.case("ooa.docusign/list_templates", "templates matching NDA",
                 "List templates gives the templates you can use; search_text keeps matching ones. Checks the one NDA template.") as f:
        f.act("ooa.docusign/list_templates", desc="Find NDA templates", search_text="NDA", out={"table": "ds_table", "count": "ds_count"})
        ua.check_eq("ds_count", 1)

    with ua.case("ooa.docusign/get_template", "roles of a template",
                 "Get template reads a template's name, roles and documents. Checks the name and the Client role.") as f:
        f.act("ooa.docusign/get_template", desc="Read the NDA template", template_id=TEMPLATE, out={"template": "ds_template", "roles": "ds_roles"})
        ua.check("eq", "$ds_template.name$", "UT Mutual NDA", what="the template name")
        ua.check("contains", "$ds_roles$", "Client", what="the roles")

    with ua.case("ooa.docusign/create_envelope", "draft from a local file",
                 "Create envelope sends local files to signers, or keeps them as a draft when send_now is off. Checks the "
                 "draft's id and status.") as f:
        _draft(f)
        ua.check("notEmpty", "$ds_env$", what="the envelope id")
        ua.check_eq("ds_status", "created")

    with ua.case("ooa.docusign/send_envelope", "send a draft",
                 "Send draft envelope sends an envelope saved as a draft. The case makes a draft, sends it and reads the status.") as f:
        _draft(f)
        f.act("ooa.docusign/send_envelope", desc="Send the draft", envelope_id="$ds_env$")
        f.act("ooa.docusign/get_envelope_status", desc="Read the status", envelope_id="$ds_env$", out={"status": "ds_status"})
        ua.check_eq("ds_status", "sent")

    with ua.case("ooa.docusign/get_envelope_status", "a completed envelope",
                 "Get envelope status says where an envelope is. Checks the made-up completed envelope and its subject.") as f:
        f.act("ooa.docusign/get_envelope_status", desc="Read the completed envelope", envelope_id=DONE,
              out={"status": "ds_status", "envelope": "ds_envelope"})
        ua.check_eq("ds_status", "completed")
        ua.check("eq", "$ds_envelope.subject$", "UT signed contract", what="the subject")

    with ua.case("ooa.docusign/get_envelope_status", "unknown envelope",
                 "An envelope id DocuSign does not know fails with DOCUSIGN.NOT_FOUND.") as f:
        ua.expect_error("DOCUSIGN.NOT_FOUND", lambda ff: ff.act("ooa.docusign/get_envelope_status", desc="Read an unknown envelope",
                                                               envelope_id=MISSING, out={"status": "ds_status"}))

    with ua.case("ooa.docusign/list_recipients", "signer and copy",
                 "List recipients gives everyone on an envelope with their progress. Checks the signer and the copy recipient.") as f:
        _draft(f)
        f.act("ooa.docusign/list_recipients", desc="List the recipients", envelope_id="$ds_env$", out={"table": "ds_table", "count": "ds_count"})
        ua.check_eq("ds_count", 2)

    with ua.case("ooa.docusign/add_recipients", "a second signer",
                 "Add recipients adds signers or copy recipients to an envelope. Checks one was added and the envelope now has three.") as f:
        _draft(f)
        f.act("ooa.docusign/add_recipients", desc="Add Bo Chan as second signer", envelope_id="$ds_env$",
              recipients=[{"name": "Bo Chan", "email": "bo.chan@ut.example", "type": "signer", "routing_order": 2}], resend=False,
              out={"count": "ds_n"})
        ua.check_eq("ds_n", 1)
        f.act("ooa.docusign/list_recipients", desc="List the recipients", envelope_id="$ds_env$", out={"count": "ds_count"})
        ua.check_eq("ds_count", 3)

    with ua.case("ooa.docusign/update_recipient", "correct a signer's email",
                 "Update recipient corrects a recipient's name or email. Checks the signer's new address in List recipients.") as f:
        _draft(f)
        f.act("ooa.docusign/update_recipient", desc="Correct signer 1's email", envelope_id="$ds_env$", recipient_id="1",
              new_email="ann.lee@corrected.ut.example", resend=False)
        f.act("ooa.docusign/list_recipients", desc="List the recipients", envelope_id="$ds_env$", out={"table": "ds_table"})
        ua.check("eq", "$ds_table.rows[0][3]$", "ann.lee@corrected.ut.example", what="the signer's email")

    with ua.case("ooa.docusign/create_signing_url", "embedded signing link",
                 "Get embedded signing link gives a short-lived link for a signer added with a client user id. Checks the link.") as f:
        _draft(f, send_now=True)
        f.act("ooa.docusign/create_signing_url", desc="Signing link for Ann Lee", envelope_id="$ds_env$", name="Ann Lee",
              email="ann.lee@ut.example", client_user_id="ut-ann", return_url="https://portal.ut.example/done", out={"url": "ds_url"})
        ua.check("contains", "$ds_url$", "Signing", what="the signing link")

    with ua.case("ooa.docusign/list_documents", "documents of an envelope",
                 "List envelope documents gives the documents inside an envelope. Checks the one contract document.") as f:
        f.act("ooa.docusign/list_documents", desc="List the documents", envelope_id=DONE, out={"table": "ds_table"})
        f.expr("ds_n", "len($ds_table.rows$)", desc="Count the rows")
        ua.check_eq("ds_n", 1)
        ua.check("eq", "$ds_table.rows[0][1]$", "contract.pdf", what="the document name")

    with ua.case("ooa.docusign/download_document", "one signed document",
                 "Download document saves one document of an envelope. Checks the file is there.") as f:
        f.act("ooa.docusign/download_document", desc="Save document 1", envelope_id=DONE, document_id="1",
              save_as="ut_docusign/contract.pdf", out={"path": "ds_path"})
        f.act("ooa.file/get_info", desc="Look for the file", path="$ds_path$", out={"exists": "ds_exists"})
        ua.check_eq("ds_exists", True)

    with ua.case("ooa.docusign/download_combined_pdf", "all documents in one PDF",
                 "Download combined PDF saves all documents as one PDF with the signing certificate. Checks the file is there.") as f:
        f.act("ooa.docusign/download_combined_pdf", desc="Save the combined PDF", envelope_id=DONE, save_as="ut_docusign/",
              include_certificate=True, out={"path": "ds_path"})
        f.act("ooa.file/get_info", desc="Look for the file", path="$ds_path$", out={"exists": "ds_exists"})
        ua.check_eq("ds_exists", True)
        ua.check("contains", "$ds_path$", ".pdf", what="the file name")

    with ua.case("ooa.docusign/download_zip", "signed documents as a ZIP",
                 "Download signed ZIP saves all documents and the certificate in one ZIP file. Checks the file is there.") as f:
        f.act("ooa.docusign/download_zip", desc="Save the ZIP", envelope_id=DONE, save_as="ut_docusign/signed.zip", out={"path": "ds_path"})
        f.act("ooa.file/get_info", desc="Look for the file", path="$ds_path$", out={"exists": "ds_exists"})
        ua.check_eq("ds_exists", True)

    with ua.case("ooa.docusign/set_custom_fields", "add an order number",
                 "Set envelope custom fields adds new fields and changes existing ones. Checks the new field is read back.") as f:
        _draft(f)
        f.act("ooa.docusign/set_custom_fields", desc="Add OrderNumber", envelope_id="$ds_env$", fields={"OrderNumber": "SO-5531"})
        f.act("ooa.docusign/set_custom_fields", desc="Change OrderNumber", envelope_id="$ds_env$", fields={"OrderNumber": "SO-5532"})
        f.act("ooa.docusign/get_custom_fields", desc="Read the fields", envelope_id="$ds_env$", out={"fields": "ds_fields"})
        ua.check("eq", "$ds_fields.OrderNumber$", "SO-5532", what="the order number")

    with ua.case("ooa.docusign/get_custom_fields", "contract number",
                 "Get envelope custom fields reads the custom fields of an envelope. Checks the made-up contract number.") as f:
        f.act("ooa.docusign/get_custom_fields", desc="Read the fields", envelope_id=DONE, out={"fields": "ds_fields"})
        ua.check("eq", "$ds_fields.ContractNumber$", "C-1042", what="the contract number")

    with ua.case("ooa.docusign/get_form_data", "values typed by the signer",
                 "Get filled-in form data reads what recipients typed into the fields. Checks the company name.") as f:
        f.act("ooa.docusign/get_form_data", desc="Read the form data", envelope_id=DONE, out={"values": "ds_values"})
        ua.check("eq", "$ds_values.Company$", "Northwind", what="the company")

    with ua.case("ooa.docusign/create_envelope_from_template", "NDA with a pre-filled field",
                 "Envelope from template fills each template role with a person and pre-fills text fields. Checks it was "
                 "sent and the field value is in the form data.") as f:
        f.act("ooa.docusign/create_envelope_from_template", desc="Send the NDA to Ann Lee", template_id=TEMPLATE,
              roles=[{"role": "Client", "name": "Ann Lee", "email": "ann.lee@ut.example"}], field_values={"ContractNumber": "C-2001"},
              email_subject="UT NDA for Ann", send_now=True, out={"envelope_id": "ds_env", "status": "ds_status"})
        ua.check_eq("ds_status", "sent")
        f.act("ooa.docusign/get_form_data", desc="Read the form data", envelope_id="$ds_env$", out={"values": "ds_values"})
        ua.check("eq", "$ds_values.ContractNumber$", "C-2001", what="the pre-filled field")

    with ua.case("ooa.docusign/resend_envelope", "remind the signers",
                 "Resend envelope sends the signing email again to recipients who have not finished. Checks the envelope "
                 "is still out for signature.") as f:
        _draft(f, send_now=True)
        f.act("ooa.docusign/resend_envelope", desc="Send the reminder", envelope_id="$ds_env$")
        f.act("ooa.docusign/get_envelope_status", desc="Read the status", envelope_id="$ds_env$", out={"status": "ds_status"})
        ua.check_eq("ds_status", "sent")

    with ua.case("ooa.docusign/list_envelopes", "sent envelopes about UT",
                 "List envelopes gives envelopes changed since a date, here only sent ones whose subject has UT. Checks "
                 "the envelope sent in this case is among them.") as f:
        _draft(f, send_now=True)
        f.act("ooa.docusign/list_envelopes", desc="List sent envelopes", changed_since="2026-01-01", status=["sent"],
              search_text="UT contract", out={"table": "ds_table", "count": "ds_count"})
        ua.check("gt", "$ds_count$", 0, what="the number of envelopes")

    with ua.case("ooa.docusign/void_envelope", "cancel an envelope",
                 "Void envelope cancels an envelope still out for signature, with a reason. Checks the status and the reason.") as f:
        _draft(f, send_now=True)
        f.act("ooa.docusign/void_envelope", desc="Void it", envelope_id="$ds_env$", reason="Wrong price")
        f.act("ooa.docusign/get_envelope_status", desc="Read the status", envelope_id="$ds_env$",
              out={"status": "ds_status", "envelope": "ds_envelope"})
        ua.check_eq("ds_status", "voided")
        ua.check("eq", "$ds_envelope.voided_reason$", "Wrong price", what="the reason")

    with ua.case("ooa.docusign/disconnect", "forget the connection",
                 "Disconnect forgets the connection Connect chose; later steps give their own address, account and token. "
                 "Checks a step with its own values still works.") as f:
        f.act("ooa.docusign/disconnect", desc="Forget the connection")
        f.act("ooa.docusign/get_envelope_status", desc="Read the completed envelope with the step's own sign-in", base_url=BASE,
              account_id=ACCOUNT, access_token=cred("docusign"), envelope_id=DONE, out={"status": "ds_status"})
        ua.check_eq("ds_status", "completed")

    _session_steps(ua.f.steps, "ooa.docusign/", ("account_id", "access_token"))
