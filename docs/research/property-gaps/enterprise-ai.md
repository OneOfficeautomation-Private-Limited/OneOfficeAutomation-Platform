# Property gaps: enterprise applications and AI toolkits vs Automation Anywhere

Compared on 21–22 Oct 2026 against the public Automation 360 documentation (docs.automationanywhere.com, no sign-in),
action by action. AA options are paraphrased in our own words. "AA" columns describe what the AA action lets the
author set; "Had" is our action before this pass; "Added" lists the new **optional** inputs (defaults keep the old
behaviour) or outputs; "Skipped" says what we left out and why. Some AA action pages could not be fetched (short links
returned 404); where AA's options came only from package overviews or search snippets this is marked *(overview)*.

Result: 134 existing actions reviewed, 141 options added to existing actions, 21 actions added (155 in these toolkits
now). Every changed Java method keeps its earlier signature as a plain overload, so existing callers are unaffected.

Common to every AA package and not repeated per row: a **session name** for the connection. We pass the connection
on each action instead (instance URL + token, session name for Terminal and AD, connection number for SAP), so this is
not a gap.

## Terminal emulator (`ooa.terminal`, s3270 engine)

| Our action | AA options (paraphrased) | Had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| connect | Host, port, terminal type (TN3270E, TN5250E, VT100/220, ANSI, Linux, SCO), security none/SSL/TLS, model, code page, LU/device name, extended attributes, wait for prompt text with timeout, show window, cursor to start, Telnet or SSH2 (password or key file) for VT types | host, port, tls, model, session | terminal_type (tn3270e/tn3270), connect_timeout_seconds, lu_name, code_page, wait_for_input_field, wait_for_text; 3278 models | TN5250, VT, ANSI, Linux and SSH2: the x3270 engine only speaks 3270, a 5250/VT engine is a separate component. Show window: the scripting engine has no window. |
| send_text | Text (vault or variable), optional key after, delay after key, wait for text before/after with timeout | text, row, column, clear_field | press_key, timeout_seconds, delay_after_ms, wait_for_text | Wait *before* typing: use Wait for text first. |
| send_key | Key from list, delay after, wait for prompt/text before/after with timeout | key (Enter, Tab, PF, PA, Clear...) | wait_for_host, timeout_seconds, repeat, delay_after_ms, wait_for_text; keys Attn, SysReq, Newline, arrows, BackSpace, Delete, Insert, FieldEnd, Dup, FieldMark, EraseInput | Page Up/Down are VT keys (no 3270 equivalent). |
| get_screen | Get text: last line, all lines (viewport/N lines/scroll), line range | whole screen | trim_lines, from_row, to_row | Scroll-back: 3270 has no scroll-back buffer. |
| get_text | (part of Get text: line range) | row, column, length | trim | — |
| wait_for_text | Wait: text appears / disappears / screen contains text, timeout ms | text, timeout | row, column, case_sensitive, wait_until_gone, fail_on_timeout, poll_interval_ms; output found | — |
| **wait** (new) | Wait: terminal ready, cursor at / leaves position, screen blank, prompt appears | — | condition (input_field, unlock, screen_change, screen_blank, cursor_at, cursor_leaves, seconds), timeout, fail_on_timeout, row, column | "Prompt appears" is VT-only. |
| **get_cursor** (new) | (used by AA's cursor waits) | — | outputs row, column | — |
| get_all_fields | Table of index, name, value, editable, hidden | table, only_input_fields | — (already richer: position, length, numeric) | Field "name" as R3C5: we return row and column columns instead. |
| get_field | By index or by name | by position or label | index | Name as R3C5 text: position inputs do the same. |
| set_field | By index or name; value; press Enter after; another key with delay | by position or label | index, press_key, timeout_seconds | — |
| search_field | Search text (whole, case-insensitive), return index or name | text, occurrence, case_sensitive | whole_field; outputs field_index, count | — |
| set_cursor | Row, column | row, column | — | — |
| clear_screen | Session only | send_to_host | — | — |
| show_terminal / hide_terminal | Session only | session | — | No window (engine is headless). |
| set_key_mapping | Key to custom value (macro or escape sequence) | name to key | — | Escape sequences are VT-only. |
| disconnect | Session | session | — | — |

## SAP GUI (`ooa.sap`, GUI Scripting via VBScript)

| Our action | AA options (paraphrased) | Had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| log_on | Connect type automatic / SSO / SAP GUI; connection entry, client, user, password, language | system, client, user, password, language | system_type (description or connection string), multiple_logon (ignore, continue, end_other_sessions, fail), logon_path (start SAP Logon), start_timeout_seconds | "Automatic" (attach to an open session): every action already attaches by connection and session number. |
| **open_connection** (new) | Connect with SSO (entry name only) | — | system, system_type, logon_path, start_timeout_seconds | — |
| run_transaction | (AA: Set text on OK-code + Enter) | tcode | new_window, fail_on_error_message | — |
| set_field | Set text: value, append | id, value | append, press_enter | — |
| get_field | Get text (fields, labels, status bar) | id | property (text, tooltip, name, type, changeable, max_length, icon_name) | — |
| press | Click (buttons) | id | fail_on_error_message | — |
| send_key | Send virtual key from list | vkey | window (pop-ups), fail_on_error_message | — |
| get_status_bar | Get text on sbar | text, type | window; outputs message_id, message_number, parameters | — |
| read_grid | Export table (data table) | id, max_rows | technical_names | — |
| close | Disconnect | connection | — | — |
| click | Click, Left click | id | — | — |
| double_click | Double click | id, item, column | — | — |
| right_click | Right click | id, item, column, menu_item | — | — |
| click_menu | Click menu by name or by index | path of texts or id | match_by (text/index) | — |
| check_box | Check / uncheck / toggle | checked | toggle | — |
| **get_status** (new) | Get status (checked/selected) | — | id; output selected | — |
| select_radio | Select radio option | id | — | — |
| select_item | Select item by text or index (combo, tab, tree) | combo by text or key | match_by index; tab strips; tree nodes | — |
| **get_selected_item** (new) | Get selected item | — | outputs key, text, index (combo, tab strip, tree, grid rows) | — |
| expand_node | Expand by text or index | node key | match_by (key/text/index) | — |
| get_children | Get children names / text | table of id, type, name, text | — | — |
| get_item_count | Get item count | count | — | — |
| table_row_count / table_column_count | Get row / column count | count | — | — |
| **table_cell_count** (new) | Get cell count | — | count | — |
| get_table_cell / set_table_cell | Get / set table cell text | row, column, value | — | — |
| get_table_cell_index | Search text, case-sensitive, all occurrences | value, column | case_sensitive, all_occurrences; output matches | — |
| export_table | Export as CSV (path, encoding, header, append) or data table | data table | csv_path, encoding, include_header, append, delimiter; output path | — |
| **press_toolbar_button** (new) | (AA uses Click on paths; ALV toolbar buttons are not separate elements) | — | id, button_id, menu_item | — |

SAP checks compare the generated VBScript (no SAP system in the build): arguments, standard input and balanced
If/Function blocks.

## Active Directory (`ooa.active_directory`)

| Our action | AA options (paraphrased) | Had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| connect | LDAP path, secure (LDAPS), user, password, path builder | url, bind_user, password, base_dn | connect_timeout_seconds, read_timeout_seconds, follow_referrals | Object browser / path builder: a design-time UI. |
| search / run_query | Run query: filter, scope or path, names or paths | filter, attributes, base, scope, max | — | — |
| get_user | Get user property (one property) | fixed set | attributes (any extra) | — |
| set_attribute | Set user property | attribute, value | mode (replace/add/remove for multi-valued) | — |
| **update_user_details** (new) | Update user details: names, display name, description, office, phone, email... | — | first/last name, initials, display name, description, office, telephone, email, title, department, company, manager (looked up), attributes | — |
| create_user | Name, logon name, first/last name, initials, password, active, password options | names, UPN, password, enabled, must change, attributes | initials, user_description, password_never_expires | "User cannot change password" needs editing the object's security descriptor (ACL), not an attribute. |
| update_account_options | Must change password, cannot change password, never expires, disabled | enabled, never expires, must change | smart_card_required, not_delegated, password_not_required, no_kerberos_preauth, account_expires | "Cannot change password": ACL change, as above. |
| reset_password / set_password | Change user password (new password, must change) | password, must change (set_password: unlock) | reset_password: unlock | — |
| enable_user / disable_user / unlock_user / delete_user | User | username | — | — |
| rename_user / move_user | User, new name / destination | yes | — | — |
| add_to_group / remove_from_group | Several users; group by name or path | one user, group DN | more_usernames, group by name; output changed | Picking users from a server browser: design-time UI. |
| get_group_members | Get all users of a group | group, recursive | member_type (all/user/group/computer) | — |
| create_group / delete_group / rename_group | Name, scope, type | yes | — | — |
| **move_group** (new) | Move group *(overview)* | — | group, target OU | — |
| get_group_property / set_group_property | Property, value | yes | — | — |
| create/delete/rename/move_computer, get/set_computer_property | Computer name/path, OU, property | yes | — | — |
| create/delete/rename/move_ou, get/set_ou_property | OU name/path, parent, property | yes | — | — |
| disconnect | Session | yes | — | — |

## Salesforce (`ooa.salesforce`)

| Our action | AA options (paraphrased) | Had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| **authenticate** (new) | Auth type (client credentials, Control Room OAuth connection, external client app), login URL | — (token supplied) | login_url, grant_type (client_credentials/password), client_id, client_secret, username, password, security_token; outputs access_token (Credential), instance_url | Control Room OAuth connection: our vault/connector framework covers it. |
| **revoke_authentication** (new) | Revoke | — | instance_url, access_token | — |
| query | Execute SOQL | soql, max_records, api_version | include_deleted (queryAll), batch_size; output total_size | — |
| get_record | Object, id, fields | yes | — | — |
| create_record / update_record / delete_record | Object, id, field values | yes | — | — |
| upsert_by_external_id | Object, external id field and value, fields | yes | — | — |
| get_attachments | Record id, folder, overwrite | record, folder | kind (file/attachment), name_pattern, overwrite | "Assign file" (file stream variable): we work with file paths. |
| upload_attachment | Record id, file, display name, visibility | record, path, title | visibility, description; output content_document_id | — |

## ServiceNow (`ooa.servicenow`)

| Our action | AA options (paraphrased) | Had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| **authenticate** (new) | Instance, basic or OAuth *(overview)* | — (basic or token per action) | client_id, client_secret, grant_type, user, password; outputs access_token, refresh_token, expires_in | — |
| **revoke_authentication** (new) | Revoke | — | client_id, client_secret, token | — |
| query | Get multiple records: table, filter or encoded query, limit, fields, display values *(overview)* | query, fields, max | display_value (true/false/all), order_by, offset, exclude_reference_link | — |
| get_record | Table, sys_id, fields | table, sys_id | fields, display_value | — |
| create_record | Table, fields (dictionary or entry list) | fields | input_display_value; output record | Typed entry list: our Dictionary carries types. |
| update_record | Table, sys_id, fields | fields | input_display_value; output record | — |
| delete_record | Table, sys_id | yes | — | — |
| add_comment | (not in AA) | yes | — | — |
| attach_file | Add attachment: record, file | path | file_name, content_type | File stream input: we use paths. |
| list_attachments | (AA uses Get multiple on sys_attachment) | yes | — | — |
| download_attachment | Get attachment: folder, overwrite | folder | file_name, overwrite | — |
| **delete_attachment** (new) | Delete attachment | — | attachment_id | — |
| list_changed_records | (trigger support; not in AA) | yes | — | — |

## Jira (`ooa.jira`)

| Our action | AA options (paraphrased) | Had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| create_issue | Project, type, summary, description, priority, assignee email, labels, components, custom fields; outputs key, id, URL | project, type, summary, description, extra_fields | priority, assignee (email looked up), labels, components, due_date, parent_key; output url | — |
| get_issue | Issue key; output fields or dictionary | main fields | extra_fields; output raw | — |
| update_issue | Optional summary, description, priority, assignee email, labels, custom fields | fields dictionary | summary, description, priority, assignee, labels, due_date (fields now optional) | — |
| delete_issue | Issue key | key, delete_subtasks | — | — |
| search | (not in AA) | jql, max | extra_fields as columns | — |
| add_comment | Issue, plain text body; outputs id, author, created | key, text | visible_to_role, visible_to_group; outputs comment_id, author, created | — |
| get_comments | Issue, max results | yes | — | — |
| update_comment / delete_comment | Issue, comment id, body | yes | — | — |
| transition | (not in AA) | transition | comment, fields | — |
| attach_file | (not in AA) | yes | — | — |
| create_project | Key, name, type, lead, attributes | yes | — | — |
| get_projects | Project key, name/key filter, type, expand, max results, start at | all projects | query, project_type, max_results, start_at | Expand options: lead is always expanded. |
| update_project | Name, description, lead email, assignee type, category | fields dictionary | project_name, project_description, lead (looked up), assignee_type, category_id (fields now optional) | — |
| delete_project | Key | yes | — | — |

## Workday (`ooa.workday`)

| Our action | AA options (paraphrased) | Had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| **authenticate** (new) | Base URL, tenant, user and password | — (basic or token per action) | host, tenant, client_id, client_secret, refresh_token; outputs access_token, expires_in | User/password: already accepted on each action. |
| get_report | Report URL, format Simple XML or Workday XML (with WIDs); output rows | path, parameters, json/csv | formats simple_xml and workday_xml (reference ids as columns), save_to_file; output path | — |
| launch_integration | ID type (Integration System ID or WID), id, inbound with template file or outbound | integration system id | id_type (integration_system_id/wid) | Inbound input file and launch parameters: the exact Integrations web-service elements could not be confirmed from public docs; sending a guessed structure risks wrong launches. |
| get_integration_event | Event id; status details | status, percent, times, done | — | — |
| search_workers / get | (not in AA) | yes | — | — |

## Generative AI (`ooa.genai`, `LlmClient`)

| Our action | AA options (paraphrased) | Had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| prompt | Prompt AI: model, prompt, max tokens, temperature, more: top-p, N completions, stop, presence and frequency penalty, logit bias, user, suffix, echo, best-of, logprobs; Bedrock/Vertex: top-k | prompt, system, provider, model, temperature, max_tokens, PII masking | top_p, top_k, stop_sequences, presence_penalty, frequency_penalty, seed, number_of_responses, response_format (json), end_user, timeout_seconds; outputs responses, json, input_tokens, output_tokens | Logit bias, suffix, echo, best-of, logprobs: legacy completions-API options. Google Vertex and Amazon Bedrock native APIs: providers, tracked separately. |
| **chat** (new) | Chat AI: session keeps context, message, max tokens, temperature, top-p, stop, penalties | — | message, chat_session, system, provider settings, history_limit, top_p, stop_sequences, presence_penalty, frequency_penalty, timeout_seconds | — |
| **end_chat** (new) | Session end clears history | — | chat_session | — |
| summarize / classify / extract / translate / sentiment | (not in AA; task-specific prompts) | yes | — | — |

## Prompt Skills (`ooa.ai_skills`) and AI agents (`ooa.ai_agent`)

| Our action | AA options (paraphrased) | Had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| ai_skills/run | Execute skill: skill, skill inputs; outputs response, error status, success status | skill, values, connection/model overrides | temperature, max_tokens (also read from the skill file, with top_p), fail_on_error, timeout_seconds; outputs success, error, input_tokens, output_tokens | Knowledge-base grounding and file inputs: need a retrieval service. |
| ai_skills/list | (repository view) | yes | — | — |
| ai_agent/run | Agent: role, goal, action plan, end states (completed, failed, optional cancelled), input/output variables, tools (processes, tasks, agents, forms), model connection, guardrails | goal, instructions, tools, sensitive tools, limits | role, action_plan, output_fields (output dictionary), allow_cancel, max_duration_seconds, temperature; outputs outputs, status failed/cancelled | Forms as tools and Co-pilot request titles: no attended form runtime in this module. Toxicity guardrail: separate governance feature. |

## Work queues (`ooa.workload`)

| Our action | AA options (paraphrased) | Had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| add_item / add_items | Insert work item: queue, fields from the queue template | queue, data | — | Queue templates (typed columns) belong to central queues in Command Center. |
| **update_item** (new) | Update work item: queue, item id, values, status re-process or on-hold (until a time or indefinitely) | — | data, replace_data, status (unchanged/reprocess/on_hold), on_hold_until, result | — |
| **get_item** (new) | (supporting read) | — | item dictionary | — |
| get_next / complete / fail / defer / counts / list_items | (not in AA's agent-side package) | yes | — | — |

## Insights (`ooa.insights`; AA Bot Insight / Analyze)

| Our action | AA options (paraphrased) | Had | Added | Skipped and why |
| --- | --- | --- | --- | --- |
| tag_values | (Analyze Close: variables to tag) | category, values | — | — |
| **open_transaction** (new) | Analyze Open: transaction name | — | transaction, category | — |
| **close_transaction** (new) | Analyze Close: same name, variables to tag | — | transaction, values, outcome; output duration_seconds | Tagging *all* variables automatically: we record only what the author lists, so sensitive values are never captured by accident. |
| read_values | (dashboard reads) | category, since | until, transaction, max_records | — |

## Sources (public pages read)

- Terminal Emulator: docs.automationanywhere.com/r/automation-360/cloud-terminal-emulator-command and its action pages (connect, wait, get text, send key, send text, set field, set key mappings, get/search field).
- SAP: /r/automation-360/cloud-sap_action, cloud-using-sap-connect-action, cloud-export-table and the sap-package-*-action pages.
- Active Directory: /r/automation-360/cloud-active-directory, cloud-ldap-actions, cloud-ad-using-the-connect-action, user and group operation pages.
- Salesforce, ServiceNow, Jira, Workday: package overview pages and the action pages that could be fetched (jira-create-issue, jira-get-projects, servicenow-create-record, workday-launch-eib, ...).
- Generative AI, AI Skill, AI Agent, Workload, Analyze: openai-promptai-action, openai-chatai-action, anthropic-chat-ai-action, vertex-prompt-ai, ai-skills, create-manage-ai-agent, cloud-insert-work-item-action, using-update-work-item-action, cloud-analyze-command.
