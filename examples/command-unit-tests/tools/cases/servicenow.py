"""ServiceNow cases: a session opened by Authenticate, then records, comments, attachments and polling against the
stand-in; at the end the session is closed, a step signs in with user and password, and a spare token is revoked."""
from helpers_business import drop_samples, cred, url, vars_

INSTANCE = url("servicenow")
PRINTER = "5a000000000000000000000000000001"
LOG_ATTACHMENT = "7c000000000000000000000000000001"


def _new_incident(f, text):
    f.act("ooa.servicenow/create_record", desc="Create an incident for this case", table="incident",
          fields={"short_description": text, "assignment_group": "UT Created", "priority": "4"}, out={"sys_id": "sn_id"})


def add(ua):
    drop_samples(ua)
    vars_(ua, "sn_token", "sn_refresh", "sn_expires", "sn_table", "sn_count", "sn_rec", "sn_id", "sn_number", "sn_att",
          "sn_files", "sn_path", "sn_text", "sn_latest", "sn_closed", "sn_spare")

    with ua.case("ooa.servicenow/authenticate", "user and password through the OAuth registry",
                 "Authenticate gets an OAuth access token for a user (password grant) and keeps it, with the instance "
                 "address, as the ServiceNow session for the next steps. Checks the token lifetime and refresh token.") as f:
        f.act("ooa.servicenow/authenticate", desc="Sign in to the stand-in instance", instance_url=INSTANCE, grant_type="password",
              client_id="ut-oauth-client", client_secret=cred("servicenow", "client_secret"), user="ut.agent",
              password=cred("servicenow", "password"), out={"access_token": "sn_token", "refresh_token": "sn_refresh", "expires_in": "sn_expires"})
        ua.check_eq("sn_expires", 1799)
        ua.check("notEmpty", "$sn_refresh$", what="the refresh token")

    with ua.case("ooa.servicenow/query", "encoded query plus a filter, newest first",
                 "Query records returns the records an encoded query finds; filter adds field = value conditions and "
                 "order_by sorts. The step has no address or sign-in: the session gives them. Checks two incidents.") as f:
        f.act("ooa.servicenow/query", desc="Service Desk incidents", table="incident", query="numberSTARTSWITHINC01",
              filter={"assignment_group": "Service Desk"}, fields=["number", "short_description", "caller_id"], order_by="-number",
              out={"table": "sn_table", "count": "sn_count"})
        ua.check_eq("sn_count", 2)

    with ua.case("ooa.servicenow/query", "paging with offset and max_records",
                 "max_records and offset read a part of the matching records. Checks one record comes back when the "
                 "first of the three is skipped and one is asked for.") as f:
        f.act("ooa.servicenow/query", desc="Second incident only", table="incident", query="numberSTARTSWITHINC01^ORDERBYnumber",
              offset=1, max_records=1, display_value="all", api_version="v2", out={"count": "sn_count"})
        ua.check_eq("sn_count", 1)

    with ua.case("ooa.servicenow/get_record", "display values",
                 "Get record reads one record by sys_id; with display values the caller is a name, not an id. "
                 "Checks the caller's name.") as f:
        f.act("ooa.servicenow/get_record", desc="Read the printer incident", table="incident", sys_id=PRINTER,
              out={"record": "sn_rec"})
        ua.check("eq", "$sn_rec.caller_id$", "Asha Patel", what="the caller")

    with ua.case("ooa.servicenow/get_record", "stored and display values together",
                 "With display_value all, each field gives its stored value and a dv_ field with the display value. "
                 "Checks both for the caller.") as f:
        f.act("ooa.servicenow/get_record", desc="Read the printer incident with both values", table="incident", sys_id=PRINTER,
              display_value="all", fields=["number", "caller_id"], api_version="v1", out={"record": "sn_rec"})
        ua.check("eq", "$sn_rec.caller_id$", "6b000000000000000000000000000001", what="the caller's sys_id")
        ua.check("eq", "$sn_rec.dv_caller_id$", "Asha Patel", what="the caller's name")

    with ua.case("ooa.servicenow/create_record", "an incident with a caller given by name",
                 "Create record makes a record and returns its sys_id and number; with input_display_value the caller "
                 "is given by name and the instance looks it up. Checks the number and the caller.") as f:
        f.act("ooa.servicenow/create_record", desc="Create an incident", table="incident", input_display_value=True,
              fields={"short_description": "UT keyboard missing keys", "caller_id": "Asha Patel", "assignment_group": "UT Created"},
              out={"sys_id": "sn_id", "number": "sn_number", "record": "sn_rec"})
        ua.check("contains", "$sn_number$", "INC0", what="the number")
        ua.check("eq", "$sn_rec.caller_id$", "6b000000000000000000000000000001", what="the caller found by name")

    with ua.case("ooa.servicenow/update_record", "raise the priority",
                 "Update record changes fields and gives back the record as it is now. Checks the new priority.") as f:
        _new_incident(f, "UT monitor flickers")
        f.act("ooa.servicenow/update_record", desc="Make it priority 1", table="incident", sys_id="$sn_id$",
              fields={"priority": "1"}, out={"record": "sn_rec"})
        ua.check("eq", "$sn_rec.priority$", "1", what="the priority")

    with ua.case("ooa.servicenow/add_comment", "a visible comment",
                 "Add comment or work note adds a comment the caller can see. Checks the comment is on the record.") as f:
        _new_incident(f, "UT laptop slow")
        f.act("ooa.servicenow/add_comment", desc="Comment for the caller", table="incident", sys_id="$sn_id$",
              text="We are replacing the disk today.")
        f.act("ooa.servicenow/get_record", desc="Read the comments", table="incident", sys_id="$sn_id$", fields=["comments"], out={"record": "sn_rec"})
        ua.check("eq", "$sn_rec.comments$", "We are replacing the disk today.", what="the comment")

    with ua.case("ooa.servicenow/add_comment", "an internal work note",
                 "With internal on, the text is a work note only agents see. Checks the work note is on the record.") as f:
        _new_incident(f, "UT badge reader broken")
        f.act("ooa.servicenow/add_comment", desc="Work note for the team", table="incident", sys_id="$sn_id$",
              text="Vendor ticket V-778 raised.", internal=True)
        f.act("ooa.servicenow/get_record", desc="Read the work notes", table="incident", sys_id="$sn_id$", fields=["work_notes"], out={"record": "sn_rec"})
        ua.check("eq", "$sn_rec.work_notes$", "Vendor ticket V-778 raised.", what="the work note")

    with ua.case("ooa.servicenow/attach_file", "attach a text file",
                 "Attach file uploads a local file to a record and returns the attachment's sys_id. Checks the "
                 "record then has one attachment.") as f:
        _new_incident(f, "UT scanner error")
        f.act("ooa.file/write_text", desc="Prepare a file", path="ut_sn_scan.txt", text="error 0x42 on page 3")
        f.act("ooa.servicenow/attach_file", desc="Attach it", table="incident", sys_id="$sn_id$", path="ut_sn_scan.txt",
              file_name="scanner-error.txt", content_type="text/plain", out={"attachment_id": "sn_att"})
        ua.check("notEmpty", "$sn_att$", what="the attachment id")
        f.act("ooa.servicenow/list_attachments", desc="List the record's attachments", table="incident", sys_id="$sn_id$",
              out={"count": "sn_count"})
        ua.check_eq("sn_count", 1)

    with ua.case("ooa.servicenow/list_attachments", "one attachment by its id",
                 "List attachments can find one attachment by its sys_id. Checks it is found.") as f:
        f.act("ooa.servicenow/list_attachments", desc="Find the printer log", attachment_id=LOG_ATTACHMENT,
              out={"table": "sn_table", "count": "sn_count"})
        ua.check_eq("sn_count", 1)

    with ua.case("ooa.servicenow/list_attachments", "by encoded query, and save them",
                 "List attachments can search the attachment table with an encoded query and save every file found "
                 "in the same step. Checks one file is found and saved with its text.") as f:
        f.act("ooa.servicenow/list_attachments", desc="Find and save printer-log.txt", attachment_query="file_name=printer-log.txt",
              limit=10, download=True, download_folder="ut_sn_saved", out={"count": "sn_count", "files": "sn_files"})
        ua.check_eq("sn_count", 1)
        f.act("ooa.file/read_text", desc="Read the saved file", path="ut_sn_saved/printer-log.txt", out={"text": "sn_text"})
        ua.check_eq("sn_text", "paper jam in tray2")

    with ua.case("ooa.servicenow/download_attachment", "save an attachment",
                 "Download attachment saves an attachment under its own name in a folder. Checks the file's text.") as f:
        f.act("ooa.servicenow/download_attachment", desc="Save the printer log", attachment_id=LOG_ATTACHMENT,
              folder="ut_sn_downloads", out={"path": "sn_path"})
        f.act("ooa.file/read_text", desc="Read it", path="$sn_path$", out={"text": "sn_text"})
        ua.check_eq("sn_text", "paper jam in tray2")

    with ua.case("ooa.servicenow/delete_attachment", "attach, then delete",
                 "Delete attachment removes an attachment by its sys_id. Checks the record has none afterwards.") as f:
        _new_incident(f, "UT camera blurry")
        f.act("ooa.file/write_text", desc="Prepare a file", path="ut_sn_photo.txt", text="photo placeholder")
        f.act("ooa.servicenow/attach_file", desc="Attach it", table="incident", sys_id="$sn_id$", path="ut_sn_photo.txt",
              out={"attachment_id": "sn_att"})
        f.act("ooa.servicenow/delete_attachment", desc="Delete the attachment", attachment_id="$sn_att$")
        f.act("ooa.servicenow/list_attachments", desc="List the record's attachments", table="incident", sys_id="$sn_id$",
              out={"count": "sn_count"})
        ua.check_eq("sn_count", 0)

    with ua.case("ooa.servicenow/delete_record", "delete, then it cannot be read",
                 "Delete record removes a record. Checks reading it afterwards fails.") as f:
        _new_incident(f, "UT duplicate ticket")
        f.act("ooa.servicenow/delete_record", desc="Delete it", table="incident", sys_id="$sn_id$")
        ua.expect_error("SERVICENOW.*", lambda ff: ff.act("ooa.servicenow/get_record", desc="Try to read it", table="incident",
                                                         sys_id="$sn_id$", out={"record": "sn_rec"}))

    with ua.case("ooa.servicenow/list_changed_records", "changed since a time",
                 "List changed records returns records updated after a point in time, oldest first, and the newest "
                 "change time to poll from next. Checks two records and the latest time.") as f:
        f.act("ooa.servicenow/list_changed_records", desc="Incidents changed since 5 November", table="incident",
              since="2026-11-05 00:00:00", query="numberSTARTSWITHINC01", fields=["number", "short_description"],
              out={"table": "sn_table", "count": "sn_count", "latest": "sn_latest"})
        ua.check_eq("sn_count", 2)
        ua.check_eq("sn_latest", "2026-11-12 08:15:00")

    with ua.case("ooa.servicenow/end_session", "forget the session, then sign in on the step",
                 "End session forgets the ServiceNow session; later steps give their own address and sign-in (here "
                 "user and password). Checks a session was open, then queries with basic sign-in.") as f:
        f.act("ooa.servicenow/end_session", desc="Close the ServiceNow session", out={"closed": "sn_closed"})
        ua.check_eq("sn_closed", True)
        f.act("ooa.servicenow/query", desc="Query with the step's own sign-in", instance_url=INSTANCE, user="ut.agent",
              password=cred("servicenow", "password"), table="incident", query="number=INC0100002", out={"count": "sn_count"})
        ua.check_eq("sn_count", 1)

    with ua.case("ooa.servicenow/revoke_authentication", "a revoked token no longer works",
                 "Revoke authentication makes a token unusable. The case signs in for a spare token, revokes it and "
                 "checks a query with it is refused.") as f:
        f.act("ooa.servicenow/authenticate", desc="Get a spare token", instance_url=INSTANCE, grant_type="client_credentials",
              client_id="ut-oauth-client", client_secret=cred("servicenow", "client_secret"), session="sn_spare",
              out={"access_token": "sn_spare"})
        f.act("ooa.servicenow/revoke_authentication", desc="Revoke the spare token", instance_url=INSTANCE, client_id="ut-oauth-client",
              client_secret=cred("servicenow", "client_secret"), token="$sn_spare$")
        ua.expect_error("SERVICENOW.*", lambda ff: ff.act("ooa.servicenow/query", desc="Query with the revoked token",
                                                         instance_url=INSTANCE, access_token="$sn_spare$", table="incident",
                                                         out={"count": "sn_count"}))
        f.act("ooa.servicenow/end_session", desc="Close the spare session", session="sn_spare")
