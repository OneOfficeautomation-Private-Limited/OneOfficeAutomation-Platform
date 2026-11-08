# Input help in command fields: One AgentStudio compared with the reference product

Checked on 2026-11-08. Our side: every field of every command in the current build (`ooa manifest`: 101 toolkits,
1,286 commands, 7,099 fields), sorted by kind, and the control One AgentStudio shows for each kind
(`studio/StepForm.tsx`, `ValueSources.tsx`, `VariablesPanel.tsx`, `Pickers.tsx`). Reference side: the reference
product's editor on the user's Community Edition account, read-only, through its demo package (one sample action per
field kind) in an empty scratch bot that was never saved. Behaviour is summarised in our own words.

## Field kinds

| Kind | Fields | Commands | One AgentStudio today | Reference product |
| --- | --- | --- | --- | --- |
| Text | 3,023 | 1,060 | Text box; `{x}` button or Ctrl+Space lists agent variables, System values and Shared Global values | Expression box; an `(x)` button, and typing `$` opens the list at once |
| Number | 897 | 509 | Same as text, calculations allowed | Same as text |
| Yes/no | 767 | 503 | Yes / No dropdown only | False / True / Variable switch |
| Credential | 692 | 557 | Text box; `{x}` lists credential variables only | Credential (pick locker, credential, attribute from the vault) / Variable / Plain text switch |
| Fixed choice | 461 | 349 | Dropdown | Dropdown or radio buttons, no variable (same as ours) |
| List, dictionary, table | 459 | 329 | Text box with `{x}`; a typed-in value is JSON | Literal value (an editor for items or pairs) / Variable switch |
| Session | 339 | 339 | Text box with `{x}` | Session name box (same idea) |
| File | 289 | 228 | Text box with `{x}` | Variable / Control Room file (Choose…) / Desktop file (Browse…) switch |
| Date/time | 96 | 58 | Text box | Date and time / Variable switch, with a calendar |
| Folder or path | 64 | 61 | Text box | Browse for a folder |
| Window | 12 | 12 | Window editor for browser steps, text elsewhere | Window list from the open windows, with refresh |

Both products offer "create a new variable" from a field, keystroke and screen-area pickers, and a captured object
editor; those are not gaps.

## Observation list

**Status (2026-11-08):** items 1 to 9 are built: the folder tree in Export and Import (`console/RepoTools.tsx`), `$` and
`$@` in every field with the variable list (`studio/ValueSources.tsx`, `VariablesPanel.tsx`), and the field controls by
kind (`studio/FieldKinds.tsx`, with Studio's `GET /api/fs/list` and `GET /api/cr/credentials`). Item 10 stays open.

| # | Where | What is missing | Proposed fix | Size |
| --- | --- | --- | --- | --- |
| 1 | Build > Automation: Export / Import | Export shows a flat list of agents; Import takes the target folder as typed text | Folder tree with checkboxes in Export (a folder ticks all it holds, partial state, search filters the tree); folder tree to pick Import's target | Medium (UI only) |
| 2 | Studio: every text, number and expression field (3,920 fields) | Typing `$` does not open the variable list (only `{x}` or Ctrl+Space do); no quick way to the global values | Open the existing list when `$` is typed, narrowing as you type; `$@` opens it at Shared Global values (inserting `$global.NAME`) | Small to medium |
| 3 | Studio: yes/no fields (767 in 503 commands) | No way to use a variable; a variable set elsewhere shows as blank | Yes / No / Variable switch; the Variable side lists Boolean variables | Small (one control) |
| 4 | Studio: file fields (289 in 228 commands) | No Browse; no pick from files kept in One CommandCenter | File / Variable switch with Browse… (a file on the runner's computer through Studio) and a pick from repository files (Automation > files) | Medium |
| 5 | Studio: folder and path fields (64 in 61 commands) | No folder Browse | Browse… for a folder, as in 4 | Small once 4 exists |
| 6 | Studio: credential fields (692 in 557 commands) | Only credential variables; picking a vault credential means making a variable first | Credential / Variable / Text switch; Credential picks credential and attribute from the vault and makes the mapped variable in one go | Medium |
| 7 | Studio: date/time fields (96 in 58 commands) | No date picker | Date and time / Variable switch with a calendar | Small |
| 8 | Studio: list, dictionary and table fields (459 in 329 commands) | A typed-in value has to be JSON | Value / Variable switch; Value uses the list and key-value editors already used for variable defaults | Medium |
| 9 | Studio: window fields outside the browser (12 commands) | Window title typed by hand | Window list from the open windows on the runner, with refresh (as the capture "where" list already does) | Small |
| 10 | Reference product's global values | Could not be seen: the account has no global values, and making one would save in the reference product | Checked from its documentation when 2 is built | - |

Items 3 to 9 are one change each to the shared field control, so every command with that kind of field gets it at
once. Fixed-choice fields (dropdowns) behave the same in both products and need no change.

## Commands per item

The commands each item reaches, by toolkit (from the current manifest).

### File fields (no Browse): 228 commands in 64 toolkits

- **File** (`ooa.file`, 19): `copy`, `create_shortcut`, `create_symbolic_link`, `create_temp_file`, `delete`, `from_base64`, `get_info`, `get_name`, `get_parent`, `list`, `move`, `print_files`, `read_text`, `read_variables`, `to_base64`, `unzip`, `wait_for_file`, `write_text`, `zip`
- **PDF** (`ooa.pdf`, 16): `decrypt`, `encrypt`, `extract_area_text`, `extract_images`, `extract_tables`, `extract_text`, `fill_form`, `get_properties`, `merge`, `page_count`, `read_form`, `read_text_ocr`, `read_xps_text`, `render_page`, `split`, `to_images`
- **Folder** (`ooa.folder`, 9): `copy`, `create`, `create_shortcut`, `create_symbolic_link`, `delete`, `list_subfolders`, `move`, `open_folder`, `rename_folder`
- **XML** (`ooa.xml`, 9): `delete_nodes`, `get_nodes`, `get_value`, `insert_node`, `save_file`, `set_value`, `to_dictionary`, `validate`, `xpath_function`
- **Browser** (`ooa.browser`, 8): `download`, `download_url`, `find_broken_links`, `get_page_source`, `open`, `open_for_automation`, `run_javascript`, `screenshot`
- **Hash, encryption and encoding** (`ooa.crypto`, 8): `decrypt_file`, `encrypt_file`, `hash_file`, `rsa_decrypt`, `rsa_encrypt`, `rsa_generate_keys`, `rsa_sign`, `rsa_verify`
- **Microsoft 365** (`ooa.microsoft365`, 8): `add_event_attachment`, `download_file`, `export_to_pdf`, `save_all_mail_attachments`, `save_mail_as_eml`, `save_mail_as_msg`, `save_mail_attachments`, `upload_file`
- **PowerPoint** (`ooa.powerpoint`, 7): `create_presentation`, `extract_text`, `find_replace_picture`, `insert_picture`, `open`, `save`, `save_as`
- **Scripts** (`ooa.script`, 7): `run_javascript`, `run_javascript_function`, `run_powershell`, `run_python`, `run_python_function`, `run_vbscript`, `run_vbscript_function`
- **Word (no Office needed)** (`ooa.word`, 7): `create_document`, `extract_text`, `find_replace_picture`, `insert_picture`, `open`, `save`, `save_as`
- **Excel (no Office needed)** (`ooa.excel`, 6): `append_workbook`, `append_worksheet`, `create_workbook`, `open`, `save`, `save_as`
- **FTP / SFTP** (`ooa.file_transfer`, 6): `connect`, `download`, `download_folder`, `sync_folder`, `upload`, `upload_folder`
- **Google Workspace** (`ooa.google`, 6): `drive_download`, `drive_download_export`, `drive_upload`, `gmail_save_all_attachments`, `gmail_save_attachments`, `gmail_save_message_as_eml`
- **PGP** (`ooa.pgp`, 6): `create_keys`, `decrypt`, `encrypt`, `import_key`, `sign`, `verify`
- **Excel (desktop, needs Excel)** (`ooa.excel_desktop`, 5): `convert`, `export_pdf`, `refresh_all`, `run_macro`, `update_links`
- **Image recognition** (`ooa.image`, 5): `click_image`, `compare_images`, `find_in_image`, `find_on_screen`, `wait_for_image`
- **Application** (`ooa.application`, 4): `open_file`, `open_session`, `run_program`, `start_program`
- **AWS AI services** (`ooa.aws_ai`, 4): `rekognition_detect_labels`, `rekognition_detect_text`, `textract_analyze_document`, `textract_detect_text`
- **Email** (`ooa.email`, 4): `save_all_attachments`, `save_attachments`, `save_email_as_eml`, `save_email_as_msg`
- **PowerPoint (desktop, needs PowerPoint)** (`ooa.powerpoint_desktop`, 4): `insert_file`, `insert_video`, `paste`, `run_macro`
- **Azure AI services** (`ooa.azure_ai`, 3): `document_analyze`, `document_read`, `vision_analyze`
- **Database** (`ooa.database`, 3): `bulk_insert`, `connect`, `export_to_file`
- **Mouse, keyboard and screen** (`ooa.desktop`, 3): `capture_screen`, `prompt_file`, `prompt_folder`
- **DocuSign** (`ooa.docusign`, 3): `download_combined_pdf`, `download_document`, `download_zip`
- **Google Cloud AI** (`ooa.google_ai`, 3): `documentai_process`, `vision_labels`, `vision_ocr`
- **JSON** (`ooa.json`, 3): `read_file`, `validate`, `write_file`
- **Knowledge Q&A** (`ooa.knowledge`, 3): `ask`, `build_index`, `search`
- **OCR** (`ooa.ocr`, 3): `find_text_in_image`, `read_image`, `read_with_cloud`
- **REST Web Service** (`ooa.rest`, 3): `download_file`, `request`, `upload_file`
- **SAP GUI** (`ooa.sap`, 3): `export_table`, `log_on`, `open_connection`
- **Asana** (`ooa.asana`, 2): `download_attachment`, `upload_attachment`
- **Box** (`ooa.box`, 2): `download_file`, `upload_file`
- **Communications mining** (`ooa.comms`, 2): `discover_topics`, `route_messages`
- **CSV/TXT** (`ooa.csv`, 2): `read`, `write`
- **Data store** (`ooa.data`, 2): `download_file`, `upload_file`
- **Databricks** (`ooa.databricks`, 2): `download_file`, `upload_file`
- **GitHub** (`ooa.github`, 2): `get_file`, `put_file`
- **Document processing** (`ooa.idp`, 2): `extract_with_examples`, `save_example`
- **Log to file** (`ooa.log`, 2): `log_variables`, `write`
- **Process mining** (`ooa.mining`, 2): `check_conformance`, `discover_process`
- **Salesforce** (`ooa.salesforce`, 2): `get_attachments`, `upload_attachment`
- **ServiceNow** (`ooa.servicenow`, 2): `attach_file`, `download_attachment`
- **Play sound** (`ooa.sound`, 2): `play`, `play_media`
- **Web goal agent** (`ooa.web_agent`, 2): `run`, `start_session`
- **Workday** (`ooa.workday`, 2): `get_report`, `launch_eib`
- **Zendesk** (`ooa.zendesk`, 2): `download_attachment`, `upload_attachment`
- **Prompt Skills** (`ooa.ai_skills`, 1): `run`
- **Azure DevOps** (`ooa.azure_devops`, 1): `attach_file_to_work_item`
- **Coded agents** (`ooa.coded`, 1): `run_python_agent`
- **Data Table** (`ooa.datatable`, 1): `write_to_file`
- **Document Automation** (`ooa.documents`, 1): `send`
- **.NET, Java and COM** (`ooa.dotnet`, 1): `call_java_method`
- **Exchange on-premises (EWS)** (`ooa.exchange`, 1): `save_attachments`
- **Interactive forms** (`ooa.forms`, 1): `display`
- **Fuzzy Match** (`ooa.fuzzy`, 1): `best_match`
- **Jira** (`ooa.jira`, 1): `attach_file`
- **Outlook (desktop, needs Outlook)** (`ooa.outlook_desktop`, 1): `save_attachments`
- **Printer** (`ooa.printer`, 1): `print_file`
- **Business rules** (`ooa.rules`, 1): `evaluate_decision_table`
- **SAP BAPI** (`ooa.sap_bapi`, 1): `connect`
- **Snowflake** (`ooa.snowflake`, 1): `export_results`
- **SOAP Web Service** (`ooa.soap`, 1): `send`
- **Terminal emulator (TN3270, TN5250, VT over SSH)** (`ooa.terminal`, 1): `connect`
- **Window** (`ooa.window`, 1): `capture`

### Folder or path fields (no Browse): 61 commands in 16 toolkits

- **Email** (`ooa.email`, 15): `delete`, `delete_all`, `folder_exists`, `forward`, `get`, `list`, `mark`, `move`, `move_all`, `reply`, `reply_all`, `save_all_attachments`, `save_attachments`, `save_email_as_eml`, `save_email_as_msg`
- **Microsoft 365** (`ooa.microsoft365`, 11): `copy_item`, `create_folder`, `delete_all_mail`, `excel_copy_range`, `find_items`, `list_files`, `list_mail`, `move_all_mail`, `move_item`, `move_mail`, `save_all_mail_attachments`
- **FTP / SFTP** (`ooa.file_transfer`, 9): `change_folder`, `connect`, `create_folder`, `delete_files`, `delete_folder`, `download_folder`, `list`, `sync_folder`, `upload_folder`
- **JSON** (`ooa.json`, 6): `delete_node`, `get_node`, `get_node_array`, `insert_node`, `to_table`, `update_node`
- **Data store** (`ooa.data`, 3): `delete_file`, `download_file`, `upload_file`
- **Excel (no Office needed)** (`ooa.excel`, 3): `copy_range`, `create_pivot_table`, `text_to_columns`
- **Azure DevOps** (`ooa.azure_devops`, 2): `create_pipeline`, `update_pipeline`
- **Exchange on-premises (EWS)** (`ooa.exchange`, 2): `list`, `move`
- **GitHub** (`ooa.github`, 2): `get_file`, `put_file`
- **Outlook (desktop, needs Outlook)** (`ooa.outlook_desktop`, 2): `list`, `move`
- **Active Directory** (`ooa.active_directory`, 1): `build_dn`
- **Desktop objects** (`ooa.desktop_ui`, 1): `legacy_object_action`
- **Generative AI** (`ooa.genai`, 1): `generate_image`
- **Google Workspace** (`ooa.google`, 1): `sheets_copy_range`
- **Java applications (Swing/AWT)** (`ooa.java_apps`, 1): `object_action`
- **Workday** (`ooa.workday`, 1): `get`

### Credential fields (no vault picker): 557 commands in 56 toolkits

- **Microsoft 365** (`ooa.microsoft365`, 101): `add_attendees`, `add_event_attachment`, `cancel_event`, `check_permissions`, `clear_event_details`, `copy_item`, `create_channel`, `create_chat`, `create_event`, `create_folder`, `create_list_item`, `create_sharing_link`, `create_task`, `delete_all_mail`, `delete_event`, `delete_item`, `delete_list_item`, `delete_mail`, `download_file`, `excel_add_table_rows`, `excel_add_worksheet`, `excel_autofit_columns`, `excel_autofit_rows`, `excel_clear_range`, `excel_copy_range`, `excel_copy_worksheet`, `excel_create_table`, `excel_create_workbook`, `excel_delete_range`, `excel_delete_table`, `excel_delete_worksheet`, `excel_find`, `excel_find_next_empty_cell`, `excel_format_range`, `excel_get_cell`, `excel_get_cell_format`, `excel_get_range`, `excel_get_table_rows`, `excel_get_worksheets`, `excel_hide_worksheet`, `excel_insert_range`, `excel_list_tables`, `excel_read_column`, `excel_read_formula`, `excel_read_range`, `excel_read_row`, `excel_rename_table`, `excel_rename_worksheet`, `excel_set_cell`, `excel_set_formula`, `excel_set_range`, `excel_show_worksheet`, `excel_used_range`, `excel_write_data_table`, `excel_write_range`, `export_to_pdf`, `find_items`, `find_meeting_times`, `forward_mail`, `get_channel_by_name`, `get_channel_details`, `get_channel_messages`, `get_item_info`, `get_list_items`, `get_mail`, `get_team_by_name`, `get_team_details`, `get_user`, `list_channels`, `list_events`, `list_files`, `list_lists`, `list_mail`, `list_permissions`, `list_plans`, `list_tasks`, `list_teams`, `mail_folder_exists`, `mark_mail`, `move_all_mail`, `move_item`, `move_mail`, `remove_attendees`, `rename_item`, `reply_all_mail`, `reply_mail`, `reply_to_message`, `respond_to_event`, `restore_version`, `save_all_mail_attachments`, `save_mail_as_eml`, `save_mail_as_msg`, `save_mail_attachments`, `send_chat_message`, `send_mail`, `share_item`, `teams_message`, `update_event`, `update_list_item`, `update_task`, `upload_file`
- **Google Workspace** (`ooa.google`, 72): `calendar_create_event`, `calendar_delete_event`, `calendar_list_events`, `calendar_update_event`, `docs_append_text`, `docs_create_document`, `docs_read_text`, `docs_replace_text`, `drive_check_permission`, `drive_copy_file`, `drive_copy_folder`, `drive_create_folder`, `drive_create_permission`, `drive_delete_item`, `drive_delete_permission`, `drive_download`, `drive_download_export`, `drive_find_items`, `drive_get_file_info`, `drive_list`, `drive_list_permissions`, `drive_move_item`, `drive_rename_item`, `drive_update_permission`, `drive_upload`, `gmail_delete_all`, `gmail_delete_message`, `gmail_forward_message`, `gmail_get_message`, `gmail_label_exists`, `gmail_list_messages`, `gmail_mark_message`, `gmail_move_all`, `gmail_move_message`, `gmail_reply_all`, `gmail_reply_message`, `gmail_save_all_attachments`, `gmail_save_attachments`, `gmail_save_message_as_eml`, `gmail_send`, `sheets_add_sheet`, `sheets_append`, `sheets_autofit_columns`, `sheets_autofit_rows`, `sheets_clear_range`, `sheets_copy_range`, `sheets_create_spreadsheet`, `sheets_delete_columns`, `sheets_delete_range`, `sheets_delete_rows`, `sheets_delete_sheet`, `sheets_find_cells`, `sheets_format_cells`, `sheets_get_cell`, `sheets_get_cell_color`, `sheets_get_range`, `sheets_hide_sheet`, `sheets_insert_columns`, `sheets_insert_range`, `sheets_insert_rows`, `sheets_list_sheets`, `sheets_read`, `sheets_read_column`, `sheets_read_formula`, `sheets_read_row`, `sheets_rename_sheet`, `sheets_row_count`, `sheets_set_cell`, `sheets_set_formula`, `sheets_show_sheet`, `sheets_write`, `sheets_write_data_table`
- **Azure DevOps** (`ooa.azure_devops`, 44): `add_pull_request_comment`, `add_test_cases_to_suite`, `add_work_item_comment`, `attach_file_to_work_item`, `complete_pull_request`, `create_pipeline`, `create_pull_request`, `create_test_case`, `create_test_plan`, `create_test_run`, `create_test_suite`, `create_work_item`, `delete_pipeline`, `delete_test_plan`, `delete_test_suite`, `delete_work_item`, `get_build`, `get_pipeline`, `get_pipeline_run`, `get_project`, `get_pull_request`, `get_test_plan`, `get_test_run`, `get_work_item`, `list_builds`, `list_iteration_work_items`, `list_iterations`, `list_pipeline_runs`, `list_pipelines`, `list_projects`, `list_pull_requests`, `list_repositories`, `list_test_plans`, `list_test_points`, `list_work_item_comments`, `query_work_items`, `queue_build`, `remove_test_cases_from_suite`, `run_pipeline`, `update_pipeline`, `update_test_plan`, `update_test_point`, `update_test_suite`, `update_work_item`
- **Asana** (`ooa.asana`, 41): `add_comment`, `add_followers`, `add_tag_to_task`, `add_task_to_project`, `add_task_to_section`, `create_project`, `create_section`, `create_subtask`, `create_tag`, `create_task`, `create_team`, `delete_attachment`, `delete_comment`, `delete_project`, `delete_task`, `download_attachment`, `duplicate_task`, `get_comment`, `get_project`, `get_task`, `get_team`, `get_user`, `list_attachments`, `list_comments`, `list_projects`, `list_sections`, `list_subtasks`, `list_tags`, `list_tasks`, `list_teams`, `list_user_teams`, `list_users`, `list_workspaces`, `remove_followers`, `remove_tag_from_task`, `search_tasks`, `update_comment`, `update_project`, `update_task`, `update_team`, `upload_attachment`
- **Zendesk** (`ooa.zendesk`, 23): `add_comment`, `add_tags`, `create_organization`, `create_ticket`, `create_user`, `delete_ticket`, `download_attachment`, `find_users`, `get_organization`, `get_ticket`, `get_user`, `list_comments`, `list_groups`, `list_organizations`, `list_ticket_fields`, `list_tickets`, `list_view_tickets`, `list_views`, `search_tickets`, `update_organization`, `update_ticket`, `update_user`, `upload_attachment`
- **GitHub** (`ooa.github`, 21): `add_issue_comment`, `close_issue`, `create_branch`, `create_issue`, `create_pull_request`, `create_release`, `get_file`, `get_issue`, `get_latest_release`, `get_pull_request`, `get_repository`, `get_workflow_run`, `list_branches`, `list_issues`, `list_pull_requests`, `list_repositories`, `list_workflow_runs`, `merge_pull_request`, `put_file`, `run_workflow`, `update_issue`
- **DocuSign** (`ooa.docusign`, 20): `add_recipients`, `create_envelope`, `create_envelope_from_template`, `create_signing_url`, `download_combined_pdf`, `download_document`, `download_zip`, `get_custom_fields`, `get_envelope_status`, `get_form_data`, `get_template`, `list_documents`, `list_envelopes`, `list_recipients`, `list_templates`, `resend_envelope`, `send_envelope`, `set_custom_fields`, `update_recipient`, `void_envelope`
- **Box** (`ooa.box`, 16): `add_collaborator`, `copy_file`, `create_folder`, `create_shared_link`, `delete_file`, `delete_folder`, `download_file`, `get_file_info`, `list_collaborators`, `list_folder`, `move_file`, `remove_collaborator`, `remove_shared_link`, `rename_file`, `search`, `upload_file`
- **Jira** (`ooa.jira`, 15): `add_comment`, `attach_file`, `create_issue`, `create_project`, `delete_comment`, `delete_issue`, `delete_project`, `get_comments`, `get_issue`, `get_projects`, `search`, `transition`, `update_comment`, `update_issue`, `update_project`
- **Databricks** (`ooa.databricks`, 14): `cancel_run`, `cancel_statement`, `delete_file`, `download_file`, `execute_sql`, `fetch_results`, `get_run`, `get_statement_status`, `list_clusters`, `list_jobs`, `run_job`, `start_cluster`, `terminate_cluster`, `upload_file`
- **Okta** (`ooa.okta`, 14): `activate_user`, `add_user_to_group`, `create_user`, `deactivate_user`, `delete_user`, `get_user`, `list_groups`, `list_user_groups`, `list_users`, `remove_user_from_group`, `reset_password`, `suspend_user`, `unsuspend_user`, `update_user`
- **PDF** (`ooa.pdf`, 14): `decrypt`, `encrypt`, `extract_area_text`, `extract_images`, `extract_tables`, `extract_text`, `fill_form`, `get_properties`, `page_count`, `read_form`, `read_text_ocr`, `render_page`, `split`, `to_images`
- **Generative AI** (`ooa.genai`, 13): `chat`, `classify`, `detect_language`, `detect_pii`, `entities`, `extract`, `generate_image`, `key_phrases`, `prompt`, `prompt_with_images`, `sentiment`, `summarize`, `translate`
- **ServiceNow** (`ooa.servicenow`, 13): `add_comment`, `attach_file`, `authenticate`, `create_record`, `delete_attachment`, `delete_record`, `download_attachment`, `get_record`, `list_attachments`, `list_changed_records`, `query`, `revoke_authentication`, `update_record`
- **Snowflake** (`ooa.snowflake`, 11): `cancel_statement`, `execute_statement`, `export_results`, `fetch_results`, `get_statement_status`, `insert_rows`, `list_columns`, `list_databases`, `list_schemas`, `list_tables`, `list_warehouses`
- **Excel (no Office needed)** (`ooa.excel`, 10): `append_workbook`, `append_worksheet`, `create_workbook`, `open`, `protect_sheet`, `protect_workbook`, `save`, `save_as`, `unprotect_sheet`, `unprotect_workbook`
- **Salesforce** (`ooa.salesforce`, 10): `authenticate`, `create_record`, `delete_record`, `get_attachments`, `get_record`, `query`, `revoke_authentication`, `update_record`, `upload_attachment`, `upsert_by_external_id`
- **Slack** (`ooa.slack`, 10): `get_channel_details`, `get_channel_id_by_name`, `get_conversation_history`, `get_user_details`, `get_workspace_details`, `get_workspace_id_by_name`, `post_message`, `send_direct_message`, `update_message`, `upload_file`
- **Workday** (`ooa.workday`, 7): `authenticate`, `get`, `get_integration_event`, `get_report`, `launch_eib`, `launch_integration`, `search_workers`
- **Kafka** (`ooa.kafka`, 6): `commit_offsets`, `create_topic`, `get_topic`, `list_topics`, `produce_message`, `read_messages`
- **Windows services** (`ooa.services`, 6): `get_status`, `pause`, `restart`, `resume`, `start`, `stop`
- **Hash, encryption and encoding** (`ooa.crypto`, 5): `decrypt_file`, `decrypt_text`, `encrypt_file`, `encrypt_text`, `hmac`
- **Email** (`ooa.email`, 5): `connect`, `forward`, `reply`, `reply_all`, `send`
- **Excel (desktop, needs Excel)** (`ooa.excel_desktop`, 5): `convert`, `export_pdf`, `refresh_all`, `run_macro`, `update_links`
- **Network** (`ooa.network`, 5): `snmp_get`, `snmp_get_next`, `snmp_send_trap`, `snmp_set`, `snmp_walk`
- **PGP** (`ooa.pgp`, 5): `create_keys`, `decrypt`, `encrypt`, `import_key`, `sign`
- **Active Directory** (`ooa.active_directory`, 4): `connect`, `create_user`, `reset_password`, `set_password`
- **AI screen (vision)** (`ooa.ai_screen`, 4): `click_described`, `find_on_screen`, `read_described`, `type_described`
- **PowerPoint** (`ooa.powerpoint`, 4): `create_presentation`, `open`, `save`, `save_as`
- **Word (no Office needed)** (`ooa.word`, 4): `create_document`, `open`, `save`, `save_as`
- **Knowledge Q&A** (`ooa.knowledge`, 3): `ask`, `build_index`, `search`
- **REST Web Service** (`ooa.rest`, 3): `download_file`, `request`, `upload_file`
- **File** (`ooa.file`, 2): `unzip`, `zip`
- **Document processing** (`ooa.idp`, 2): `classify_document`, `extract_with_examples`
- **MCP tools** (`ooa.mcp`, 2): `call_tool`, `list_tools`
- **SOAP Web Service** (`ooa.soap`, 2): `read_wsdl`, `send`
- **Web goal agent** (`ooa.web_agent`, 2): `run`, `start_session`
- **AI Agents** (`ooa.ai_agent`, 1): `run`
- **Prompt Skills** (`ooa.ai_skills`, 1): `run`
- **Application** (`ooa.application`, 1): `write_session`
- **AWS AI services** (`ooa.aws_ai`, 1): `connect`
- **AWS EC2** (`ooa.aws_ec2`, 1): `connect`
- **Azure AI services** (`ooa.azure_ai`, 1): `connect`
- **Azure VMs** (`ooa.azure_vm`, 1): `connect`
- **Browser** (`ooa.browser`, 1): `download_url`
- **Custom Connectors** (`ooa.connector`, 1): `call_operation`
- **Windows Credential Manager** (`ooa.credential_manager`, 1): `write_credential`
- **Database** (`ooa.database`, 1): `connect`
- **Mouse, keyboard and screen** (`ooa.desktop`, 1): `type_text`
- **Exchange on-premises (EWS)** (`ooa.exchange`, 1): `connect`
- **FTP / SFTP** (`ooa.file_transfer`, 1): `connect`
- **Google Cloud AI** (`ooa.google_ai`, 1): `connect`
- **SAP GUI** (`ooa.sap`, 1): `log_on`
- **SAP BAPI** (`ooa.sap_bapi`, 1): `connect`
- **Terminal emulator (TN3270, TN5250, VT over SSH)** (`ooa.terminal`, 1): `connect`
- **Credential vault** (`ooa.vault`, 1): `save_credential`

### Yes/no fields (no variable choice): 503 commands in 84 toolkits

- **Google Workspace** (`ooa.google`, 33): `calendar_create_event`, `calendar_delete_event`, `calendar_update_event`, `docs_append_text`, `docs_replace_text`, `drive_create_permission`, `drive_delete_item`, `drive_download`, `drive_download_export`, `drive_find_items`, `drive_upload`, `gmail_delete_all`, `gmail_delete_message`, `gmail_forward_message`, `gmail_get_message`, `gmail_list_messages`, `gmail_move_message`, `gmail_reply_all`, `gmail_reply_message`, `gmail_save_all_attachments`, `gmail_save_attachments`, `gmail_save_message_as_eml`, `gmail_send`, `sheets_copy_range`, `sheets_find_cells`, `sheets_format_cells`, `sheets_get_cell`, `sheets_get_range`, `sheets_list_sheets`, `sheets_read`, `sheets_row_count`, `sheets_write`, `sheets_write_data_table`
- **Microsoft 365** (`ooa.microsoft365`, 32): `clear_event_details`, `create_event`, `delete_all_mail`, `delete_item`, `delete_mail`, `download_file`, `excel_copy_range`, `excel_create_table`, `excel_find`, `excel_format_range`, `excel_hide_worksheet`, `excel_read_range`, `excel_write_data_table`, `export_to_pdf`, `find_items`, `find_meeting_times`, `get_mail`, `list_mail`, `move_all_mail`, `reply_all_mail`, `reply_mail`, `reply_to_message`, `respond_to_event`, `save_all_mail_attachments`, `save_mail_as_eml`, `save_mail_as_msg`, `save_mail_attachments`, `send_chat_message`, `send_mail`, `share_item`, `teams_message`, `update_event`
- **Excel (no Office needed)** (`ooa.excel`, 22): `add_sheet`, `close`, `create_table`, `create_workbook`, `filter`, `find`, `format_cells`, `get_all_cells`, `get_range`, `get_sheet_names`, `get_table_range`, `open`, `protect_sheet`, `protect_workbook`, `remove_duplicates`, `replace`, `save_as`, `sheet_count`, `sort_range`, `text_to_columns`, `unprotect_workbook`, `write_table`
- **Work queues** (`ooa.workload`, 16): `add_item`, `add_items`, `complete`, `copy_items`, `counts`, `defer`, `delete_items`, `fail`, `get_item`, `get_next`, `list_items`, `move_items`, `tag_item`, `unlock_item`, `untag_item`, `update_item`
- **Email** (`ooa.email`, 15): `delete`, `delete_all`, `forward`, `get`, `list`, `mark`, `move`, `move_all`, `reply`, `reply_all`, `save_all_attachments`, `save_attachments`, `save_email_as_eml`, `save_email_as_msg`, `send`
- **File** (`ooa.file`, 14): `copy`, `create_shortcut`, `create_symbolic_link`, `create_temp_file`, `delete`, `from_base64`, `list`, `move`, `print_files`, `read_variables`, `unzip`, `wait_for_file`, `write_text`, `zip`
- **Data Table** (`ooa.datatable`, 13): `aggregate`, `column_count`, `column_to_list`, `filter`, `find`, `from_csv_text`, `lookup`, `replace_values`, `row_count`, `sort`, `to_csv_text`, `to_html`, `write_to_file`
- **Mouse, keyboard and screen** (`ooa.desktop`, 13): `capture_screen`, `clipboard_get`, `clipboard_set`, `message_box`, `mouse_click`, `prompt_file`, `prompt_folder`, `prompt_password`, `prompt_value`, `prompt_yes_no`, `show_callout`, `type_text`, `wait_for_input`
- **Generative AI** (`ooa.genai`, 13): `chat`, `classify`, `detect_language`, `detect_pii`, `entities`, `extract`, `generate_image`, `key_phrases`, `prompt`, `prompt_with_images`, `sentiment`, `summarize`, `translate`
- **Browser** (`ooa.browser`, 11): `click`, `download_url`, `element_action`, `extract_table`, `find_broken_links`, `history`, `list_windows`, `open`, `open_for_automation`, `screenshot`, `type_text`
- **Exchange on-premises (EWS)** (`ooa.exchange`, 11): `connect`, `delete`, `forward`, `get`, `list`, `mark`, `move`, `reply`, `reply_all`, `save_attachments`, `send`
- **Terminal emulator (TN3270, TN5250, VT over SSH)** (`ooa.terminal`, 11): `clear_screen`, `connect`, `get_all_fields`, `get_screen`, `get_text`, `search_field`, `send_key`, `send_text`, `wait`, `wait_for_prompt`, `wait_for_text`
- **Word (no Office needed)** (`ooa.word`, 11): `add_paragraph`, `add_table`, `close`, `create_document`, `format_text`, `insert_text`, `open`, `read_tables`, `read_text`, `replace_text`, `save_as`
- **Active Directory** (`ooa.active_directory`, 10): `connect`, `create_computer`, `create_user`, `delete_ou`, `get_group_members`, `rename_group`, `rename_user`, `reset_password`, `set_password`, `update_account_options`
- **Asana** (`ooa.asana`, 10): `add_comment`, `create_task`, `delete_project`, `download_attachment`, `list_projects`, `list_tasks`, `search_tasks`, `update_comment`, `update_project`, `update_task`
- **PowerPoint** (`ooa.powerpoint`, 10): `close`, `create_presentation`, `extract_text`, `find_replace_picture`, `find_replace_text`, `format_text`, `insert_table`, `insert_text`, `open`, `save_as`
- **Box** (`ooa.box`, 9): `add_collaborator`, `copy_file`, `create_folder`, `create_shared_link`, `delete_file`, `delete_folder`, `download_file`, `move_file`, `upload_file`
- **PDF** (`ooa.pdf`, 9): `decrypt`, `encrypt`, `extract_tables`, `extract_text`, `fill_form`, `get_properties`, `merge`, `read_text_ocr`, `split`
- **SAP GUI** (`ooa.sap`, 9): `check_box`, `expand_node`, `export_table`, `get_table_cell_index`, `press`, `read_grid`, `run_transaction`, `send_key`, `set_field`
- **String** (`ooa.string`, 9): `compare`, `count_occurrences`, `extract_text`, `find`, `random_string`, `replace`, `split`, `to_locale_number`, `to_number`
- **Azure DevOps** (`ooa.azure_devops`, 8): `complete_pull_request`, `create_pull_request`, `create_test_run`, `delete_pipeline`, `delete_test_plan`, `delete_work_item`, `list_iterations`, `list_test_plans`
- **Date and time** (`ooa.datetime`, 7): `difference`, `is_after`, `is_before`, `is_equal`, `next_working_day`, `previous_working_day`, `working_days_between`
- **FTP / SFTP** (`ooa.file_transfer`, 7): `connect`, `delete_folder`, `download`, `download_folder`, `sync_folder`, `upload`, `upload_folder`
- **JSON** (`ooa.json`, 7): `get_node`, `insert_node`, `stringify`, `to_table`, `update_node`, `validate`, `write_file`
- **List** (`ooa.list`, 7): `add_item`, `filter`, `index_of`, `join`, `remove_item`, `select`, `sort`
- **Scripts** (`ooa.script`, 7): `run_javascript`, `run_javascript_function`, `run_powershell`, `run_python`, `run_python_function`, `run_vbscript`, `run_vbscript_function`
- **System** (`ooa.system`, 7): `empty_recycle_bin`, `list_processes`, `lock`, `log_off`, `restart`, `set_screen_resolution`, `shut_down`
- **Application** (`ooa.application`, 6): `close_session`, `read_session`, `run_program`, `start_program`, `stop_program`, `write_session`
- **DocuSign** (`ooa.docusign`, 6): `add_recipients`, `create_envelope`, `create_envelope_from_template`, `download_combined_pdf`, `set_custom_fields`, `update_recipient`
- **Folder** (`ooa.folder`, 6): `copy`, `create`, `create_shortcut`, `create_symbolic_link`, `delete`, `move`
- **Core** (`core`, 5): `loop`, `run_bot`, `switch`, `throw`, `wait_until`
- **Azure VMs** (`ooa.azure_vm`, 5): `connect`, `create_disk_snapshot`, `restart_vm`, `start_vm`, `stop_vm`
- **Boolean** (`ooa.boolean`, 5): `compare`, `from_text`, `invert`, `to_number`, `to_text`
- **Database** (`ooa.database`, 5): `bulk_insert`, `call_procedure`, `connect`, `export_to_file`, `list_tables`
- **Databricks** (`ooa.databricks`, 5): `delete_file`, `download_file`, `run_job`, `start_cluster`, `upload_file`
- **.NET, Java and COM** (`ooa.dotnet`, 5): `call_com_method`, `call_dotnet_method`, `call_java_method`, `run_csharp`, `run_vbnet`
- **Excel (desktop, needs Excel)** (`ooa.excel_desktop`, 5): `convert`, `export_pdf`, `refresh_all`, `run_macro`, `update_links`
- **Outlook (desktop, needs Outlook)** (`ooa.outlook_desktop`, 5): `forward`, `list`, `mark`, `reply`, `send`
- **ServiceNow** (`ooa.servicenow`, 5): `add_comment`, `create_record`, `download_attachment`, `query`, `update_record`
- **Window** (`ooa.window`, 5): `capture`, `close_all`, `hide`, `show`, `wait_for`
- **AWS EC2** (`ooa.aws_ec2`, 4): `connect`, `detach_volume`, `get_instance_status`, `stop_instances`
- **Hash, encryption and encoding** (`ooa.crypto`, 4): `base64_encode`, `decrypt_file`, `encrypt_file`, `hmac`
- **Desktop objects** (`ooa.desktop_ui`, 4): `object_action`, `pick_object`, `record`, `wait_for_object`
- **Dictionary** (`ooa.dictionary`, 4): `get`, `merge`, `put`, `remove`
- **OCR** (`ooa.ocr`, 4): `click_text`, `read_image`, `read_screen`, `read_url`
- **Open Excel and Word files (needs Office)** (`ooa.office_attach`, 4): `excel_read_range`, `excel_write_range`, `word_append_text`, `word_replace_text`
- **Okta** (`ooa.okta`, 4): `activate_user`, `create_user`, `deactivate_user`, `reset_password`
- **PowerPoint (desktop, needs PowerPoint)** (`ooa.powerpoint_desktop`, 4): `insert_file`, `insert_video`, `paste`, `run_macro`
- **Windows services** (`ooa.services`, 4): `pause`, `resume`, `start`, `stop`
- **Slack** (`ooa.slack`, 4): `get_channel_id_by_name`, `get_conversation_history`, `post_message`, `send_direct_message`
- **Zendesk** (`ooa.zendesk`, 4): `add_comment`, `create_ticket`, `create_user`, `download_attachment`
- **Interactive forms** (`ooa.forms`, 3): `display`, `validate`, `wait_for_event`
- **Fuzzy Match** (`ooa.fuzzy`, 3): `best_match`, `fuzzy_lookup`, `similarity`
- **Image recognition** (`ooa.image`, 3): `click_image`, `wait_for_image`, `wait_for_screen_change`
- **Kafka** (`ooa.kafka`, 3): `create_topic`, `list_topics`, `read_messages`
- **PGP** (`ooa.pgp`, 3): `create_keys`, `decrypt`, `encrypt`
- **REST Web Service** (`ooa.rest`, 3): `download_file`, `request`, `upload_file`
- **AWS AI services** (`ooa.aws_ai`, 2): `connect`, `textract_analyze_document`
- **Azure AI services** (`ooa.azure_ai`, 2): `document_analyze`, `vision_analyze`
- **Custom Connectors** (`ooa.connector`, 2): `call_operation`, `import_connector`
- **CSV/TXT** (`ooa.csv`, 2): `read`, `write`
- **Data store** (`ooa.data`, 2): `query_records`, `upload_file`
- **GitHub** (`ooa.github`, 2): `create_pull_request`, `create_release`
- **Document processing** (`ooa.idp`, 2): `classify_document`, `extract_with_examples`
- **Jira** (`ooa.jira`, 2): `delete_issue`, `update_issue`
- **Knowledge Q&A** (`ooa.knowledge`, 2): `ask`, `build_index`
- **Log to file** (`ooa.log`, 2): `log_variables`, `write`
- **Salesforce** (`ooa.salesforce`, 2): `get_attachments`, `query`
- **SAP BAPI** (`ooa.sap_bapi`, 2): `call_function`, `commit`
- **SOAP Web Service** (`ooa.soap`, 2): `read_wsdl`, `send`
- **Play sound** (`ooa.sound`, 2): `play`, `play_media`
- **Web goal agent** (`ooa.web_agent`, 2): `run`, `start_session`
- **AI Agents** (`ooa.ai_agent`, 1): `run`
- **Prompt Skills** (`ooa.ai_skills`, 1): `run`
- **Windows Credential Manager** (`ooa.credential_manager`, 1): `delete_credential`
- **Document Automation** (`ooa.documents`, 1): `send`
- **Google Cloud AI** (`ooa.google_ai`, 1): `documentai_process`
- **Environment locks** (`ooa.locks`, 1): `acquire`
- **MCP tools** (`ooa.mcp`, 1): `call_tool`
- **Number** (`ooa.number`, 1): `format`
- **Printer** (`ooa.printer`, 1): `remove_printer`
- **Business rules** (`ooa.rules`, 1): `evaluate_decision_table`
- **Snowflake** (`ooa.snowflake`, 1): `export_results`
- **XML** (`ooa.xml`, 1): `save_file`

### Date/time fields (no date picker): 58 commands in 15 toolkits

- **Date and time** (`ooa.datetime`, 17): `add`, `add_working_days`, `convert_time_zone`, `difference`, `end_of`, `format`, `get_part`, `is_after`, `is_before`, `is_equal`, `is_leap_year`, `is_working_day`, `next_working_day`, `previous_working_day`, `start_of`, `subtract`, `working_days_between`
- **Microsoft 365** (`ooa.microsoft365`, 12): `create_event`, `create_sharing_link`, `create_task`, `delete_all_mail`, `find_meeting_times`, `get_channel_messages`, `list_events`, `list_mail`, `move_all_mail`, `save_all_mail_attachments`, `update_event`, `update_task`
- **Google Workspace** (`ooa.google`, 8): `calendar_create_event`, `calendar_list_events`, `calendar_update_event`, `drive_create_permission`, `gmail_delete_all`, `gmail_list_messages`, `gmail_move_all`, `gmail_save_all_attachments`
- **Email** (`ooa.email`, 4): `delete_all`, `list`, `move_all`, `save_all_attachments`
- **File** (`ooa.file`, 3): `copy`, `delete`, `move`
- **Folder** (`ooa.folder`, 3): `copy`, `delete`, `rename_folder`
- **Azure DevOps** (`ooa.azure_devops`, 2): `create_test_plan`, `update_test_plan`
- **Work queues** (`ooa.workload`, 2): `defer`, `update_item`
- **Box** (`ooa.box`, 1): `create_shared_link`
- **Currency** (`ooa.currency`, 1): `convert`
- **DocuSign** (`ooa.docusign`, 1): `list_envelopes`
- **Exchange on-premises (EWS)** (`ooa.exchange`, 1): `list`
- **FTP / SFTP** (`ooa.file_transfer`, 1): `download_folder`
- **Insights** (`ooa.insights`, 1): `read_values`
- **Outlook (desktop, needs Outlook)** (`ooa.outlook_desktop`, 1): `list`

### List, dictionary and table fields (no editor for typed-in values): 329 commands in 73 toolkits

- **Data Table** (`ooa.datatable`, 32): `add_column_from_list`, `add_row`, `aggregate`, `change_column_type`, `clear`, `column_count`, `column_name`, `column_names`, `column_to_list`, `delete_column`, `delete_row`, `filter`, `find`, `from_records`, `get_value`, `insert_column`, `insert_row`, `join`, `lookup`, `merge`, `remove_duplicates`, `remove_empty_rows`, `rename_column`, `replace_values`, `row_count`, `set_value`, `sort`, `sum_column`, `to_csv_text`, `to_html`, `to_records`, `write_to_file`
- **Browser** (`ooa.browser`, 19): `call_javascript_function`, `click`, `close`, `download`, `element_action`, `extract_list`, `extract_table`, `get_attribute`, `get_page_source`, `get_text`, `history`, `list_windows`, `open`, `run_javascript`, `screenshot`, `select_option`, `test_object`, `type_text`, `wait_for`
- **List** (`ooa.list`, 17): `add_item`, `append_list`, `clear`, `filter`, `get_item`, `index_of`, `intersect`, `join`, `remove_duplicates`, `remove_item`, `reverse`, `select`, `set_item`, `shuffle`, `size`, `sort`, `subtract`
- **Microsoft 365** (`ooa.microsoft365`, 15): `create_event`, `create_list_item`, `create_task`, `excel_create_workbook`, `excel_write_data_table`, `excel_write_range`, `forward_mail`, `get_list_items`, `reply_all_mail`, `reply_mail`, `send_chat_message`, `send_mail`, `teams_message`, `update_event`, `update_list_item`
- **AWS EC2** (`ooa.aws_ec2`, 14): `create_snapshot`, `create_volume`, `describe_instances`, `disable_monitoring`, `enable_monitoring`, `get_instance_status`, `launch_instances`, `list_snapshots`, `reboot_instances`, `set_tags`, `start_instances`, `stop_instances`, `terminate_instances`, `wait_for_state`
- **Window** (`ooa.window`, 13): `activate`, `capture`, `close`, `close_all`, `get_info`, `hide`, `maximize`, `minimize`, `move_resize`, `restore`, `set_title`, `type_in_background`, `wait_for`
- **Azure DevOps** (`ooa.azure_devops`, 11): `add_test_cases_to_suite`, `create_pull_request`, `create_test_case`, `create_test_run`, `create_work_item`, `query_work_items`, `queue_build`, `remove_test_cases_from_suite`, `run_pipeline`, `update_test_point`, `update_work_item`
- **Work queues** (`ooa.workload`, 11): `add_item`, `add_items`, `complete`, `copy_items`, `delete_items`, `get_next`, `list_items`, `move_items`, `tag_item`, `untag_item`, `update_item`
- **Active Directory** (`ooa.active_directory`, 10): `add_to_group`, `create_user`, `get_computer_property`, `get_group_property`, `get_ou_property`, `get_user`, `remove_from_group`, `run_query`, `search`, `update_user_details`
- **Google Workspace** (`ooa.google`, 9): `docs_replace_text`, `gmail_forward_message`, `gmail_reply_all`, `gmail_reply_message`, `gmail_send`, `sheets_append`, `sheets_create_spreadsheet`, `sheets_write`, `sheets_write_data_table`
- **Date and time** (`ooa.datetime`, 8): `add`, `add_working_days`, `difference`, `is_working_day`, `next_working_day`, `previous_working_day`, `subtract`, `working_days_between`
- **Zendesk** (`ooa.zendesk`, 8): `add_comment`, `add_tags`, `create_organization`, `create_ticket`, `create_user`, `update_organization`, `update_ticket`, `update_user`
- **Dictionary** (`ooa.dictionary`, 7): `get`, `keys`, `merge`, `put`, `remove`, `size`, `values`
- **Generative AI** (`ooa.genai`, 7): `chat`, `classify`, `detect_pii`, `entities`, `extract`, `prompt`, `prompt_with_images`
- **Scripts** (`ooa.script`, 7): `run_javascript`, `run_javascript_function`, `run_powershell`, `run_python`, `run_python_function`, `run_vbscript`, `run_vbscript_function`
- **Asana** (`ooa.asana`, 6): `add_followers`, `create_task`, `duplicate_task`, `remove_followers`, `update_project`, `update_task`
- **Database** (`ooa.database`, 6): `bulk_insert`, `call_procedure`, `connect`, `execute`, `export_to_file`, `query`
- **Desktop objects** (`ooa.desktop_ui`, 6): `find_objects`, `get_object_properties`, `get_parent`, `get_table`, `object_action`, `wait_for_object`
- **Jira** (`ooa.jira`, 6): `create_issue`, `get_issue`, `search`, `transition`, `update_issue`, `update_project`
- **XML** (`ooa.xml`, 6): `delete_nodes`, `get_nodes`, `get_value`, `insert_node`, `set_value`, `xpath_function`
- **.NET, Java and COM** (`ooa.dotnet`, 5): `call_com_method`, `call_dotnet_method`, `call_java_method`, `run_csharp`, `run_vbnet`
- **ServiceNow** (`ooa.servicenow`, 5): `create_record`, `get_record`, `list_changed_records`, `query`, `update_record`
- **Core** (`core`, 4): `loop`, `run_bot`, `set_many`, `throw`
- **DocuSign** (`ooa.docusign`, 4): `add_recipients`, `create_envelope`, `create_envelope_from_template`, `set_custom_fields`
- **Email** (`ooa.email`, 4): `forward`, `reply`, `reply_all`, `send`
- **Document processing** (`ooa.idp`, 4): `classify_document`, `extract_by_rules`, `extract_with_examples`, `save_example`
- **Log to file** (`ooa.log`, 4): `add_log_fields`, `log_to_command_center`, `log_variables`, `remove_log_fields`
- **Salesforce** (`ooa.salesforce`, 4): `create_record`, `get_record`, `update_record`, `upsert_by_external_id`
- **Application** (`ooa.application`, 3): `open_session`, `run_program`, `start_program`
- **Currency** (`ooa.currency`, 3): `convert`, `get_rate`, `list_currencies`
- **Data store** (`ooa.data`, 3): `create_record`, `query_records`, `update_record`
- **Excel (no Office needed)** (`ooa.excel`, 3): `create_pivot_table`, `remove_duplicates`, `write_table`
- **Interactive forms** (`ooa.forms`, 3): `assign`, `display`, `dynamic_area`
- **GitHub** (`ooa.github`, 3): `create_issue`, `run_workflow`, `update_issue`
- **Kafka** (`ooa.kafka`, 3): `create_topic`, `produce_message`, `read_messages`
- **PDF** (`ooa.pdf`, 3): `fill_form`, `merge`, `read_form`
- **REST Web Service** (`ooa.rest`, 3): `download_file`, `request`, `upload_file`
- **SAP BAPI** (`ooa.sap_bapi`, 3): `call_function`, `connect`, `read_table`
- **Slack** (`ooa.slack`, 3): `post_message`, `send_direct_message`, `upload_file`
- **Snowflake** (`ooa.snowflake`, 3): `execute_statement`, `export_results`, `insert_rows`
- **Azure AI services** (`ooa.azure_ai`, 2): `language_pii`, `vision_analyze`
- **Communications mining** (`ooa.comms`, 2): `discover_topics`, `route_messages`
- **Databricks** (`ooa.databricks`, 2): `execute_sql`, `run_job`
- **Fuzzy Match** (`ooa.fuzzy`, 2): `best_match`, `fuzzy_lookup`
- **Insights** (`ooa.insights`, 2): `close_transaction`, `tag_values`
- **Process mining** (`ooa.mining`, 2): `check_conformance`, `discover_process`
- **Okta** (`ooa.okta`, 2): `create_user`, `update_user`
- **Workday** (`ooa.workday`, 2): `get_report`, `launch_integration`
- **AI Agents** (`ooa.ai_agent`, 1): `run`
- **Prompt Skills** (`ooa.ai_skills`, 1): `run`
- **Azure VMs** (`ooa.azure_vm`, 1): `create_disk_snapshot`
- **Coded agents** (`ooa.coded`, 1): `run_python_agent`
- **Custom Connectors** (`ooa.connector`, 1): `call_operation`
- **CSV/TXT** (`ooa.csv`, 1): `write`
- **DLL** (`ooa.dll`, 1): `call`
- **Excel (desktop, needs Excel)** (`ooa.excel_desktop`, 1): `run_macro`
- **Exchange on-premises (EWS)** (`ooa.exchange`, 1): `send`
- **File** (`ooa.file`, 1): `print_files`
- **Google Cloud AI** (`ooa.google_ai`, 1): `vision_ocr`
- **Interrupt handlers** (`ooa.interrupts`, 1): `add_rule`
- **JSON** (`ooa.json`, 1): `validate`
- **Knowledge Q&A** (`ooa.knowledge`, 1): `build_index`
- **Long-running agents** (`ooa.longrunning`, 1): `create_task`
- **MCP tools** (`ooa.mcp`, 1): `call_tool`
- **Network** (`ooa.network`, 1): `snmp_send_trap`
- **Outlook (desktop, needs Outlook)** (`ooa.outlook_desktop`, 1): `send`
- **PowerPoint** (`ooa.powerpoint`, 1): `insert_table`
- **PowerPoint (desktop, needs PowerPoint)** (`ooa.powerpoint_desktop`, 1): `run_macro`
- **Business rules** (`ooa.rules`, 1): `evaluate_decision_table`
- **SOAP Web Service** (`ooa.soap`, 1): `send`
- **Terminal emulator (TN3270, TN5250, VT over SSH)** (`ooa.terminal`, 1): `set_key_mapping`
- **Web goal agent** (`ooa.web_agent`, 1): `run`
- **Word (no Office needed)** (`ooa.word`, 1): `add_table`

### Window fields: 12 commands in 4 toolkits

- **Mouse, keyboard and screen** (`ooa.desktop`, 7): `capture_screen`, `mouse_click`, `mouse_move`, `mouse_scroll`, `press_keys`, `show_callout`, `type_text`
- **SAP GUI** (`ooa.sap`, 3): `click_menu`, `get_status_bar`, `send_key`
- **Image recognition** (`ooa.image`, 1): `wait_for_screen_change`
- **OCR** (`ooa.ocr`, 1): `read_screen`
