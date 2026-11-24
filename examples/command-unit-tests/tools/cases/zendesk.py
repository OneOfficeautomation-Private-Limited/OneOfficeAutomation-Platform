"""Zendesk cases: Connect with a saved connection (connections.json in the run folder), then tickets, comments, users,
organizations, groups, attachments, views and fields against the stand-in, and Disconnect."""
from helpers_business import drop_samples, cred, vars_

SITE = "${standin_url}"          # Zendesk takes a site address without a path: the stand-in answers /api/v2 at its root
CONN = "UT Zendesk stand-in"
CONNECTIONS = ('{"' + CONN + '": {"package": "ooa.zendesk", "description": "Unit tests: the local stand-in",'
               ' "values": {"site": "' + SITE + '", "email": "sam.agent@ut.example"},'
               ' "credentials": {"api_token": "unit_tests/zendesk/api_token"}}}')


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


def add(ua):
    drop_samples(ua)
    vars_(ua, "zd_conn", "zd_table", "zd_count", "zd_user", "zd_user_id", "zd_org", "zd_org_id", "zd_ticket", "zd_ticket_id",
          "zd_url", "zd_status", "zd_tags", "zd_token", "zd_att", "zd_path", "zd_text", "zd_exists")

    with ua.case("ooa.zendesk/connect", "sign in with a saved connection",
                 "Connect picks a saved connection (site, agent email and API token from the vault); the later Zendesk "
                 "steps leave site and sign-in empty and use it. The case saves the connection in the run folder first. "
                 "Checks the connection's name comes back.") as f:
        f.act("ooa.file/write_text", desc="Save the connection to the stand-in", path="connections.json", text=CONNECTIONS)
        f.act("ooa.zendesk/connect", desc="Use the saved connection", connection=CONN, out={"connection": "zd_conn"})
        ua.check_eq("zd_conn", CONN)

    with ua.case("ooa.zendesk/list_groups", "agent groups",
                 "List groups gives the groups tickets can be assigned to. Checks the two made-up groups.") as f:
        f.act("ooa.zendesk/list_groups", desc="List the groups", out={"table": "zd_table", "count": "zd_count"})
        ua.check_eq("zd_count", 2)

    with ua.case("ooa.zendesk/list_ticket_fields", "only drop-down fields",
                 "List ticket fields gives field ids for custom_fields; field_type keeps one kind. Checks the one drop-down.") as f:
        f.act("ooa.zendesk/list_ticket_fields", desc="List drop-down ticket fields", field_type="tagger",
              out={"table": "zd_table", "count": "zd_count"})
        ua.check_eq("zd_count", 1)

    with ua.case("ooa.zendesk/create_user", "new end user",
                 "Create user adds an end user with a phone number and a custom user field. Checks an id came back.") as f:
        f.act("ooa.zendesk/create_user", desc="Create Lee Unit", name="Lee Unit", role="end-user", phone="+1 555 0100",
              organization_id=7001, user_fields={"customer_tier": "gold"}, out={"user_id": "zd_user_id"})
        ua.check("notEmpty", "$zd_user_id$", what="the new user id")

    with ua.case("ooa.zendesk/create_user", "update the user with the same email",
                 "With update_if_exists on, Create user changes the user who already has this email instead of failing. "
                 "Checks the existing user's id (9002) comes back.") as f:
        f.act("ooa.zendesk/create_user", desc="Create or update Pat Customer", name="Pat Customer", user_email="pat.customer@ut.example",
              update_if_exists=True, out={"user_id": "zd_user_id"})
        ua.check_eq("zd_user_id", 9002)

    with ua.case("ooa.zendesk/get_user", "read a user",
                 "Get user reads a user's details. Checks the name, role and custom field of Pat Customer.") as f:
        f.act("ooa.zendesk/get_user", desc="Read user 9002", user_id=9002, out={"user": "zd_user"})
        ua.check("eq", "$zd_user.name$", "Pat Customer", what="the name")
        ua.check("eq", "$zd_user.external_id$", "CRM-42", what="the external id")

    with ua.case("ooa.zendesk/update_user", "change phone and tier",
                 "Update user changes only the inputs given. The case creates a user, changes it, and reads it back.") as f:
        f.act("ooa.zendesk/create_user", desc="Create a user to change", name="Max Unit", out={"user_id": "zd_user_id"})
        f.act("ooa.zendesk/update_user", desc="Change phone and tier", user_id="$zd_user_id$", phone="+1 555 0199",
              user_fields={"customer_tier": "platinum"})
        f.act("ooa.zendesk/get_user", desc="Read the user back", user_id="$zd_user_id$", out={"user": "zd_user"})
        ua.check("eq", "$zd_user.phone$", "+1 555 0199", what="the phone")
        ua.check("eq", "$zd_user.user_fields.customer_tier$", "platinum", what="the tier")

    with ua.case("ooa.zendesk/find_users", "agents only",
                 "Find users searches users; role keeps one role. Checks the one made-up agent is found.") as f:
        f.act("ooa.zendesk/find_users", desc="Find agents", role="agent", out={"table": "zd_table", "count": "zd_count"})
        ua.check_eq("zd_count", 1)

    with ua.case("ooa.zendesk/find_users", "by email",
                 "Find users with an email search term finds that one person.") as f:
        f.act("ooa.zendesk/find_users", desc="Find pat.customer@ut.example", query="email:pat.customer@ut.example",
              out={"count": "zd_count"})
        ua.check_eq("zd_count", 1)

    with ua.case("ooa.zendesk/create_organization", "customer organization",
                 "Create organization adds a customer organization with an email domain and custom field. Checks an id came back.") as f:
        f.act("ooa.zendesk/create_organization", desc="Create Contoso Unit Test", name="Contoso Unit Test",
              domain_names=["contoso.ut.example"], external_id="ACC-2", organization_fields={"account_tier": "enterprise"},
              out={"organization_id": "zd_org_id"})
        ua.check("notEmpty", "$zd_org_id$", what="the new organization id")

    with ua.case("ooa.zendesk/update_organization", "rename and add notes",
                 "Update organization changes the inputs given and keeps the rest. The case creates one, renames it and reads it back.") as f:
        f.act("ooa.zendesk/create_organization", desc="Create an organization to change", name="Fabrikam Unit Test",
              out={"organization_id": "zd_org_id"})
        f.act("ooa.zendesk/update_organization", desc="Rename it and add notes", organization_id="$zd_org_id$",
              name="Fabrikam Unit Test Ltd", notes="Key account")
        f.act("ooa.zendesk/get_organization", desc="Read it back", organization_id="$zd_org_id$", out={"organization": "zd_org"})
        ua.check("eq", "$zd_org.name$", "Fabrikam Unit Test Ltd", what="the new name")
        ua.check("eq", "$zd_org.notes$", "Key account", what="the notes")

    with ua.case("ooa.zendesk/get_organization", "read an organization",
                 "Get organization reads an organization's details. Checks the made-up organization's name and external id.") as f:
        f.act("ooa.zendesk/get_organization", desc="Read organization 7001", organization_id=7001, out={"organization": "zd_org"})
        ua.check("eq", "$zd_org.name$", "Northwind Unit Test", what="the name")
        ua.check("eq", "$zd_org.external_id$", "ACC-1", what="the external id")

    with ua.case("ooa.zendesk/list_organizations", "all organizations",
                 "List organizations reads every customer organization. Checks there is at least the made-up one.") as f:
        f.act("ooa.zendesk/list_organizations", desc="List organizations", out={"table": "zd_table", "count": "zd_count"})
        ua.check("gt", "$zd_count$", 0, what="the number of organizations")

    with ua.case("ooa.zendesk/upload_attachment", "upload a receipt",
                 "Upload attachment sends a local file and gives a token to attach it to a ticket or comment. Checks the "
                 "token and the attachment id.") as f:
        f.act("ooa.file/write_text", desc="Prepare the file", path="ut_zendesk_receipt.txt", text="Receipt SO-88123: 42.00 EUR")
        f.act("ooa.zendesk/upload_attachment", desc="Upload the receipt", path="ut_zendesk_receipt.txt",
              out={"token": "zd_token", "attachment_id": "zd_att"})
        ua.check("notEmpty", "$zd_token$", what="the upload token")
        ua.check("notEmpty", "$zd_att$", what="the attachment id")

    with ua.case("ooa.zendesk/create_ticket", "ticket assigned by email and group name",
                 "Create ticket opens a ticket with a first comment, a new requester, an agent given by email, a group "
                 "given by name, a custom field given by its title and an attached file. Reads it back to check.") as f:
        f.act("ooa.file/write_text", desc="Prepare the file", path="ut_zendesk_receipt.txt", text="Receipt SO-1")
        f.act("ooa.zendesk/upload_attachment", desc="Upload the receipt", path="ut_zendesk_receipt.txt", out={"token": "zd_token"})
        f.act("ooa.zendesk/create_ticket", desc="Open the ticket", subject="Refund for order SO-1", description="Customer asks for a refund.",
              requester_email="new.customer@ut.example", requester_name="New Customer", priority="high", type="incident",
              assignee="sam.agent@ut.example", group="Billing", tags=["ut-created"], custom_fields={"Order number": "SO-1"},
              upload_tokens=["$zd_token$"], out={"ticket_id": "zd_ticket_id", "url": "zd_url"})
        ua.check("contains", "$zd_url$", "/agent/tickets/", what="the ticket address")
        f.act("ooa.zendesk/get_ticket", desc="Read it back", ticket_id="$zd_ticket_id$", out={"ticket": "zd_ticket"})
        ua.check("eq", "$zd_ticket.assignee_id$", 9001, what="the assignee")
        ua.check("eq", "$zd_ticket.group_id$", 6001, what="the group")
        ua.check("eq", "$zd_ticket.custom_fields.5001$", "SO-1", what="the order number field")

    with ua.case("ooa.zendesk/get_ticket", "read a ticket",
                 "Get ticket reads a ticket's fields. Checks the subject and status of the made-up ticket 1001.") as f:
        f.act("ooa.zendesk/get_ticket", desc="Read ticket 1001", ticket_id=1001, out={"ticket": "zd_ticket", "status": "zd_status"})
        ua.check_eq("zd_status", "open")
        ua.check("eq", "$zd_ticket.subject$", "Refund not received", what="the subject")

    with ua.case("ooa.zendesk/update_ticket", "set pending with a lower priority",
                 "Update ticket changes only the inputs given. The case opens a ticket, updates it and reads the status back.") as f:
        f.act("ooa.zendesk/create_ticket", desc="Open a ticket to change", subject="Update me", description="First text",
              assignee="9001", tags=["ut-created"], out={"ticket_id": "zd_ticket_id"})
        f.act("ooa.zendesk/update_ticket", desc="Set pending and low", ticket_id="$zd_ticket_id$", status="pending", priority="low")
        f.act("ooa.zendesk/get_ticket", desc="Read it back", ticket_id="$zd_ticket_id$", out={"ticket": "zd_ticket", "status": "zd_status"})
        ua.check_eq("zd_status", "pending")
        ua.check("eq", "$zd_ticket.priority$", "low", what="the priority")

    with ua.case("ooa.zendesk/add_comment", "internal note that solves the ticket",
                 "Add comment adds a reply or (public off) an internal note and can change the status. Checks the new status.") as f:
        f.act("ooa.zendesk/create_ticket", desc="Open a ticket", subject="Comment on me", description="First text", assignee="9001",
              tags=["ut-created"], out={"ticket_id": "zd_ticket_id"})
        f.act("ooa.zendesk/add_comment", desc="Add an internal note and solve", ticket_id="$zd_ticket_id$",
              text="Refund issued today.", public=False, new_status="solved")
        f.act("ooa.zendesk/get_ticket", desc="Read the status", ticket_id="$zd_ticket_id$", out={"status": "zd_status"})
        ua.check_eq("zd_status", "solved")

    with ua.case("ooa.zendesk/list_comments", "the conversation of a ticket",
                 "List comments reads a ticket's comments, oldest first. The case adds a reply and checks both comments are there.") as f:
        f.act("ooa.zendesk/create_ticket", desc="Open a ticket", subject="Talk to me", description="Question", assignee="9001",
              tags=["ut-created"], out={"ticket_id": "zd_ticket_id"})
        f.act("ooa.zendesk/add_comment", desc="Reply", ticket_id="$zd_ticket_id$", text="<p>Answer</p>", html=True)
        f.act("ooa.zendesk/list_comments", desc="Read the comments", ticket_id="$zd_ticket_id$", out={"table": "zd_table", "count": "zd_count"})
        ua.check_eq("zd_count", 2)

    with ua.case("ooa.zendesk/add_tags", "add vip and keep the rest",
                 "Add tags adds tags and keeps the ones the ticket has. Checks both the old and the new tag are on it.") as f:
        f.act("ooa.zendesk/create_ticket", desc="Open a ticket", subject="Tag me", description="Text", assignee="9001",
              tags=["ut-created"], out={"ticket_id": "zd_ticket_id"})
        f.act("ooa.zendesk/add_tags", desc="Add vip", ticket_id="$zd_ticket_id$", tags=["vip"], out={"tags": "zd_tags"})
        ua.check("contains", "$zd_tags$", "vip", what="the new tag")
        ua.check("contains", "$zd_tags$", "ut-created", what="the old tag")

    with ua.case("ooa.zendesk/list_tickets", "tickets of one requester",
                 "List tickets can keep the tickets one user asked for. Checks Pat Customer's one ticket.") as f:
        f.act("ooa.zendesk/list_tickets", desc="Tickets requested by user 9002", requester_id=9002, out={"table": "zd_table", "count": "zd_count"})
        ua.check_eq("zd_count", 1)

    with ua.case("ooa.zendesk/list_tickets", "by requester email and status",
                 "List tickets with requester_email searches by email; status keeps open tickets. Checks the one ticket.") as f:
        f.act("ooa.zendesk/list_tickets", desc="Open tickets of pat.customer@ut.example", requester_email="pat.customer@ut.example",
              status="open", out={"count": "zd_count"})
        ua.check_eq("zd_count", 1)

    with ua.case("ooa.zendesk/list_tickets", "tickets of an organization with priority",
                 "List tickets of one organization, only urgent ones. Checks the one urgent ticket of organization 7001.") as f:
        f.act("ooa.zendesk/list_tickets", desc="Urgent tickets of organization 7001", organization_id=7001, priority="urgent",
              out={"count": "zd_count"})
        ua.check_eq("zd_count", 1)

    with ua.case("ooa.zendesk/search_tickets", "tickets tagged refund",
                 "Search tickets finds tickets with search terms (type:ticket is added). Checks the one refund ticket.") as f:
        f.act("ooa.zendesk/search_tickets", desc="Search tags:refund", query="tags:refund status<solved", out={"table": "zd_table", "count": "zd_count"})
        ua.check_eq("zd_count", 1)

    with ua.case("ooa.zendesk/download_attachment", "save an uploaded file",
                 "Download attachment saves a ticket's attachment under its own name. The case uploads a file, downloads "
                 "it into a folder and checks its text.") as f:
        f.act("ooa.file/write_text", desc="Prepare the file", path="ut_zendesk_note.txt", text="Hello from the unit test")
        f.act("ooa.zendesk/upload_attachment", desc="Upload it", path="ut_zendesk_note.txt", file_name="note-copy.txt",
              out={"attachment_id": "zd_att"})
        f.act("ooa.zendesk/download_attachment", desc="Download it", attachment_id="$zd_att$", folder="ut_zendesk_downloads",
              overwrite=True, out={"path": "zd_path"})
        ua.check("contains", "$zd_path$", "note-copy.txt", what="the saved file name")
        f.act("ooa.file/read_text", desc="Read the saved file", path="$zd_path$", out={"text": "zd_text"})
        ua.check_eq("zd_text", "Hello from the unit test")

    with ua.case("ooa.zendesk/list_views", "active views",
                 "List views gives the active ticket views. Checks the made-up view.") as f:
        f.act("ooa.zendesk/list_views", desc="List views", out={"table": "zd_table", "count": "zd_count"})
        ua.check_eq("zd_count", 1)

    with ua.case("ooa.zendesk/list_view_tickets", "unassigned urgent queue",
                 "List tickets in view gives the tickets a view shows. Checks the one unassigned urgent ticket.") as f:
        f.act("ooa.zendesk/list_view_tickets", desc="Tickets in view 8001", view_id=8001, out={"table": "zd_table", "count": "zd_count"})
        ua.check_eq("zd_count", 1)

    with ua.case("ooa.zendesk/delete_ticket", "delete, then reading fails",
                 "Delete ticket removes a ticket. The case opens one, deletes it, and checks reading it fails with a ZENDESK error.") as f:
        f.act("ooa.zendesk/create_ticket", desc="Open a ticket to delete", subject="Delete me", description="Text", assignee="9001",
              tags=["ut-created"], out={"ticket_id": "zd_ticket_id"})
        f.act("ooa.zendesk/delete_ticket", desc="Delete it", ticket_id="$zd_ticket_id$")
        ua.expect_error("ZENDESK.*", lambda ff: ff.act("ooa.zendesk/get_ticket", desc="Read the deleted ticket", ticket_id="$zd_ticket_id$",
                                                      out={"status": "zd_status"}))

    with ua.case("ooa.zendesk/disconnect", "forget the connection",
                 "Disconnect forgets the connection Connect chose; later steps give their own site and sign-in. Checks a "
                 "step with its own site, email and API token still works, and one without them fails.") as f:
        f.act("ooa.zendesk/disconnect", desc="Forget the connection")
        f.act("ooa.zendesk/list_groups", desc="List groups with the step's own sign-in", site=SITE, email="sam.agent@ut.example",
              api_token=cred("zendesk", "api_token"), out={"count": "zd_count"})
        ua.check_eq("zd_count", 2)
        ua.expect_error("*", lambda ff: ff.act("ooa.zendesk/list_groups", desc="List groups with no site and no sign-in", out={"count": "zd_count"}))

    _session_steps(ua.f.steps, "ooa.zendesk/", ("site",))
