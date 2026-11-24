# Command unit tests: results

Local run of 24 Nov 2026 (`ooa run`, stand-in on 127.0.0.1:8791, LDAP test directory, SQL Server test database).

| Agent | Needs | Commands covered | Cases | Result |
|---|---|---|---|---|
| UT AI Agents | commandcenter | 1/1 | 3 | not run: needs One CommandCenter services |
| UT AI screen (vision) | desktop | 4/4 | 4 | not run: drives the screen: run when the PC is free |
| UT AWS AI services | standin | 11/11 | 15 | PASS 15 passed, 0 failed |
| UT AWS EC2 | standin | 19/19 | 27 | PASS 27 passed, 0 failed |
| UT Active Directory | ldap | 39/39 | 39 | PASS 39 passed, 0 failed |
| UT Application | local | 8/8 | 13 | PASS 13 passed, 0 failed |
| UT Application (windows) | desktop | 2/2 | 3 | not run: drives the screen: run when the PC is free |
| UT Asana | standin | 43/43 | 44 | PASS 44 passed, 0 failed |
| UT Azure AI services | standin | 8/8 | 10 | PASS 10 passed, 0 failed |
| UT Azure DevOps | standin | 53/53 | 57 | PASS 57 passed, 0 failed |
| UT Azure VMs | standin | 8/8 | 14 | PASS 14 passed, 0 failed |
| UT Boolean | local | 10/10 | 14 | PASS 14 passed, 0 failed |
| UT Box | standin | 19/19 | 20 | PASS 20 passed, 0 failed |
| UT Browser | standin | 30/30 | 38 | FAIL 37 passed, 1 failed — FAIL  ooa.browser/switch_tab: to the tab a link opened: No matching tab among 1 open tab(s) |
| UT Browser (capture) | desktop | 3/3 | 3 | not run: drives the screen: run when the PC is free |
| UT Business rules | local | 1/1 | 4 | PASS 4 passed, 0 failed |
| UT CSV TXT | local | 5/5 | 7 | PASS 7 passed, 0 failed |
| UT Coded agents | local | 1/1 | 4 | PASS 4 passed, 0 failed |
| UT Communications mining | local | 2/2 | 2 | PASS 2 passed, 0 failed |
| UT Convert | local | 1/1 | 5 | PASS 5 passed, 0 failed |
| UT Core | local | 26/26 | 66 | FAIL 64 passed, 2 failed — FAIL  core/run_bot: run the UT Convert agent (needs One CommandCenter): No agent repository available for child agents / FAIL  core/agent_succeeded: UT Convert succeeds (needs One CommandCenter): This run cannot start child agents |
| UT Credential Manager | local | 5/5 | 7 | PASS 7 passed, 0 failed |
| UT Credential vault | commandcenter | 3/3 | 7 | not run: needs One CommandCenter services |
| UT Currency | local | 3/3 | 3 | PASS 3 passed, 0 failed |
| UT Custom Connectors | standin | 5/5 | 5 | PASS 5 passed, 0 failed |
| UT DLL | local | 3/3 | 4 | PASS 4 passed, 0 failed |
| UT Data Table | local | 39/39 | 48 | PASS 48 passed, 0 failed |
| UT Data store | local | 9/9 | 10 | PASS 10 passed, 0 failed |
| UT Database | database | 13/13 | 20 | PASS 20 passed, 0 failed |
| UT Databricks | standin | 17/17 | 19 | PASS 19 passed, 0 failed |
| UT Date and time | local | 23/23 | 39 | PASS 39 passed, 0 failed |
| UT Desktop objects | desktop | 12/12 | 12 | not run: drives the screen: run when the PC is free |
| UT Dictionary | local | 11/11 | 13 | PASS 13 passed, 0 failed |
| UT DocuSign | standin | 22/22 | 23 | PASS 23 passed, 0 failed |
| UT Document Automation | commandcenter | 7/7 | 9 | not run: needs One CommandCenter services |
| UT Document Extraction | local | 4/4 | 6 | PASS 4 passed, 0 failed |
| UT DotNet | local | 11/11 | 13 | PASS 13 passed, 0 failed |
| UT Email | mailserver | 20/20 | 25 | not run: needs a test mail server |
| UT Environment locks | local | 4/4 | 9 | PASS 9 passed, 0 failed |
| UT Excel Cells | local | 43/43 | 47 | PASS 47 passed, 0 failed |
| UT Excel Desktop | office | 5/5 | 6 | PASS 6 passed, 0 failed |
| UT Excel Workbooks | local | 34/34 | 43 | PASS 43 passed, 0 failed |
| UT Exchange (EWS) | standin | 20/20 | 30 | PASS 30 passed, 0 failed |
| UT FTP and SFTP | standin | 17/17 | 22 | PASS 22 passed, 0 failed |
| UT File | local | 27/27 | 41 | PASS 41 passed, 0 failed |
| UT Folder | local | 13/13 | 24 | PASS 24 passed, 0 failed |
| UT Fuzzy Match | local | 4/4 | 4 | PASS 4 passed, 0 failed |
| UT Generative AI | standin | 16/16 | 25 | PASS 25 passed, 0 failed |
| UT GitHub | standin | 24/24 | 26 | PASS 26 passed, 0 failed |
| UT Google Cloud AI | standin | 7/7 | 9 | PASS 9 passed, 0 failed |
| UT Google Workspace Calendar and Docs | standin | 8/8 | 8 | PASS 8 passed, 0 failed |
| UT Google Workspace Connect | commandcenter | 2/2 | 2 | not run: needs One CommandCenter services |
| UT Google Workspace Drive | standin | 17/17 | 17 | PASS 17 passed, 0 failed |
| UT Google Workspace Gmail | standin | 16/16 | 18 | PASS 18 passed, 0 failed |
| UT Google Workspace Sheets | standin | 35/35 | 35 | PASS 35 passed, 0 failed |
| UT Hash and encrypt | local | 14/14 | 14 | PASS 14 passed, 0 failed |
| UT Image | local | 8/8 | 10 | PASS 10 passed, 0 failed |
| UT Insights | local | 4/4 | 5 | PASS 5 passed, 0 failed |
| UT Interactive forms | desktop | 20/20 | 20 | not run: drives the screen: run when the PC is free |
| UT Interrupt handlers | local | 5/5 | 5 | PASS 5 passed, 0 failed |
| UT Item AI agent | commandcenter | 0/0 | 2 | not run: needs One CommandCenter services |
| UT Item API agent | commandcenter | 0/0 | 3 | not run: needs One CommandCenter services |
| UT Item Document type | standin | 0/0 | 2 | PASS 2 passed, 0 failed |
| UT Item Form | desktop | 0/0 | 3 | not run: drives the screen: run when the PC is free |
| UT Item Object repository | standin | 0/0 | 3 | PASS 3 passed, 0 failed |
| UT Item PowerShell script | local | 0/0 | 3 | PASS 3 passed, 0 failed |
| UT Item Prompt Skill | standin | 0/0 | 3 | PASS 3 passed, 0 failed |
| UT Item Python script | local | 0/0 | 4 | PASS 4 passed, 0 failed |
| UT Item Workflow | commandcenter | 0/0 | 2 | not run: needs One CommandCenter services |
| UT JSON | local | 14/14 | 17 | PASS 17 passed, 0 failed |
| UT Java apps | desktop | 4/4 | 4 | not run: drives the screen: run when the PC is free |
| UT Jira | standin | 17/17 | 19 | PASS 19 passed, 0 failed |
| UT Jobs and alerts | commandcenter | 4/4 | 6 | not run: needs One CommandCenter services |
| UT Kafka | standin | 8/8 | 8 | PASS 8 passed, 0 failed |
| UT Knowledge Q and A | standin | 3/3 | 4 | not run: standin |
| UT List | local | 19/19 | 22 | PASS 22 passed, 0 failed |
| UT Log to file | local | 5/5 | 6 | PASS 6 passed, 0 failed |
| UT Long-running agents | commandcenter | 8/8 | 13 | not run: needs One CommandCenter services |
| UT MCP tools | standin | 2/2 | 2 | PASS 2 passed, 0 failed |
| UT Microsoft 365 Excel online | standin | 42/42 | 42 | PASS 42 passed, 0 failed |
| UT Microsoft 365 OneDrive and SharePoint | standin | 30/30 | 30 | PASS 30 passed, 0 failed |
| UT Microsoft 365 Outlook mail and calendar | standin | 31/31 | 31 | PASS 31 passed, 0 failed |
| UT Microsoft 365 Teams and Planner | standin | 22/22 | 22 | PASS 22 passed, 0 failed |
| UT Microsoft 365 Users and groups | standin | 11/11 | 11 | PASS 11 passed, 0 failed |
| UT Mouse and keyboard | desktop | 21/21 | 23 | not run: drives the screen: run when the PC is free |
| UT Network | local | 9/9 | 12 | PASS 12 passed, 0 failed |
| UT Number | local | 13/13 | 21 | PASS 21 passed, 0 failed |
| UT OCR | local | 10/10 | 12 | PASS 11 passed, 0 failed |
| UT Office Attachments | local | 9/9 | 12 | PASS 12 passed, 0 failed |
| UT Okta | standin | 19/19 | 20 | PASS 20 passed, 0 failed |
| UT Outlook desktop | desktop | 18/18 | 18 | not run: drives the screen: run when the PC is free |
| UT PDF | local | 17/17 | 22 | PASS 22 passed, 0 failed |
| UT PGP | local | 6/6 | 6 | PASS 6 passed, 0 failed |
| UT Play sound | desktop | 4/4 | 5 | not run: drives the screen: run when the PC is free |
| UT PowerPoint | local | 17/17 | 17 | PASS 17 passed, 0 failed |
| UT PowerPoint Desktop | office | 4/4 | 7 | PASS 7 passed, 0 failed |
| UT Printer | local | 5/5 | 6 | PASS 6 passed, 0 failed |
| UT Process mining | local | 2/2 | 3 | PASS 3 passed, 0 failed |
| UT Prompt Skills | standin | 2/2 | 4 | PASS 4 passed, 0 failed |
| UT REST Web Service | standin | 3/3 | 14 | PASS 14 passed, 0 failed |
| UT Run context | local | 2/2 | 2 | PASS 2 passed, 0 failed |
| UT SAP BAPI | sap | 8/8 | 8 | not run: needs SAP |
| UT SAP GUI | sap | 30/30 | 30 | not run: needs SAP |
| UT SOAP Web Service | standin | 2/2 | 5 | PASS 5 passed, 0 failed |
| UT Salesforce | standin | 14/14 | 20 | PASS 20 passed, 0 failed |
| UT Scripts | local | 14/14 | 20 | PASS 20 passed, 0 failed |
| UT ServiceNow | standin | 14/14 | 18 | PASS 18 passed, 0 failed |
| UT Shared values | local | 4/4 | 10 | PASS 10 passed, 0 failed |
| UT Slack | standin | 17/17 | 18 | PASS 18 passed, 0 failed |
| UT Snowflake | standin | 17/17 | 18 | PASS 18 passed, 0 failed |
| UT String | local | 36/36 | 55 | PASS 55 passed, 0 failed |
| UT System | local | 15/15 | 17 | PASS 17 passed, 0 failed |
| UT Terminal emulator | standin | 18/18 | 19 | PASS 19 passed, 0 failed |
| UT Terminal emulator (viewer) | desktop | 1/1 | 1 | not run: drives the screen: run when the PC is free |
| UT Trigger Loop | local | 6/6 | 8 | PASS 8 passed, 0 failed |
| UT Trigger Loop (screen) | desktop | 4/4 | 9 | not run: drives the screen: run when the PC is free |
| UT Web goal agent | standin | 3/3 | 3 | PASS 3 passed, 0 failed |
| UT Window | desktop | 19/19 | 24 | not run: drives the screen: run when the PC is free |
| UT Windows Services | local | 7/7 | 9 | PASS 9 passed, 0 failed |
| UT Word | local | 16/16 | 17 | PASS 17 passed, 0 failed |
| UT Work queues | local | 18/18 | 24 | PASS 24 passed, 0 failed |
| UT Workday | standin | 8/8 | 11 | PASS 11 passed, 0 failed |
| UT XML | local | 12/12 | 13 | PASS 13 passed, 0 failed |
| UT Zendesk | standin | 25/25 | 29 | PASS 29 passed, 0 failed |
