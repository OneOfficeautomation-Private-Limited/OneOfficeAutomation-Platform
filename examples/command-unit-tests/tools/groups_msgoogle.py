"""Microsoft 365, Google Workspace and Active Directory unit-test agents (helper msgoogle)."""

M365_MAIL = ["send_mail", "list_mail", "get_mail", "mark_mail", "move_mail", "move_all_mail", "delete_mail", "delete_all_mail", "forward_mail",
             "reply_mail", "reply_all_mail", "save_mail_attachments", "save_all_mail_attachments", "save_mail_as_eml", "save_mail_as_msg",
             "mail_folder_exists", "emails", "create_event", "list_events", "update_event", "cancel_event", "delete_event", "respond_to_event",
             "add_attendees", "remove_attendees", "clear_event_details", "add_event_attachment", "find_meeting_times", "calendar_events",
             "connect", "disconnect"]
M365_TEAMS = ["list_teams", "get_team_by_name", "get_team_details", "list_channels", "get_channel_by_name", "get_channel_details", "create_channel",
              "get_channel_messages", "reply_to_message", "teams_message", "send_chat_message", "create_chat", "get_user", "teams", "channels",
              "channel_messages", "chats", "chat_messages", "list_plans", "list_tasks", "create_task", "update_task"]
M365_FILES = ["upload_file", "download_file", "list_files", "copy_item", "move_item", "rename_item", "delete_item", "create_folder", "find_items",
              "get_item_info", "list_permissions", "check_permissions", "share_item", "create_sharing_link", "export_to_pdf", "restore_version",
              "list_lists", "get_list_items", "create_list_item", "update_list_item", "delete_list_item", "sharepoint_connect", "create_list",
              "update_list", "delete_list", "add_list_column", "list_attachments", "add_attachment", "download_attachments", "delete_attachment"]

GROUPS = {
    "m365_mail": {"id": "ut_m365_mail", "name": "UT Microsoft 365 Outlook mail and calendar", "toolkits": ["ooa.microsoft365"], "only": M365_MAIL,
                  "needs": "standin", "about": "Unit tests of the Microsoft 365 Outlook mail and calendar commands, and the Connect session, against the local Graph stand-in."},
    "m365_excel": {"id": "ut_m365_excel", "name": "UT Microsoft 365 Excel online", "toolkits": ["ooa.microsoft365"], "only": ["excel_"],
                   "needs": "standin", "about": "Unit tests of the Microsoft 365 Excel online commands, including the Open workbook session, against the local Graph stand-in."},
    "m365_teams": {"id": "ut_m365_teams", "name": "UT Microsoft 365 Teams and Planner", "toolkits": ["ooa.microsoft365"], "only": M365_TEAMS,
                   "needs": "standin", "about": "Unit tests of the Microsoft 365 Teams, chat and Planner commands against the local Graph stand-in."},
    "m365_files": {"id": "ut_m365_files", "name": "UT Microsoft 365 OneDrive and SharePoint", "toolkits": ["ooa.microsoft365"], "only": M365_FILES,
                   "needs": "standin", "about": "Unit tests of the Microsoft 365 OneDrive, SharePoint file and SharePoint list commands against the local Graph and SharePoint stand-ins."},
    "m365_entra": {"id": "ut_m365_entra", "name": "UT Microsoft 365 Users and groups", "toolkits": ["ooa.microsoft365"], "only": ["entra_"],
                   "needs": "standin", "about": "Unit tests of the Microsoft 365 Entra ID user and group commands against the local Graph stand-in."},
    "google_gmail": {"id": "ut_google_gmail", "name": "UT Google Workspace Gmail", "toolkits": ["ooa.google"], "only": ["gmail_", "connect", "disconnect"],
                     "needs": "standin", "about": "Unit tests of the Google Workspace Gmail commands, and Connect, against the local Google stand-in."},
    "google_drive": {"id": "ut_google_drive", "name": "UT Google Workspace Drive", "toolkits": ["ooa.google"], "only": ["drive_"],
                     "needs": "standin", "about": "Unit tests of the Google Workspace Drive commands against the local Google stand-in."},
    "google_sheets": {"id": "ut_google_sheets", "name": "UT Google Workspace Sheets", "toolkits": ["ooa.google"], "only": ["sheets_"],
                      "needs": "standin", "about": "Unit tests of the Google Workspace Sheets commands, including the Open spreadsheet session, against the local Google stand-in."},
    "google_calendar_docs": {"id": "ut_google_calendar_docs", "name": "UT Google Workspace Calendar and Docs", "toolkits": ["ooa.google"], "only": ["calendar_", "docs_"],
                             "needs": "standin", "about": "Unit tests of the Google Workspace Calendar and Docs commands against the local Google stand-in."},
    "active_directory": {"id": "ut_active_directory", "name": "UT Active Directory", "toolkits": ["ooa.active_directory"], "needs": "ldap",
                         "about": "Unit tests of every Active Directory command against a local in-memory LDAP directory (standin/msg_ldap, port in input ldap_url)."},
}
