# Cloud and business applications

**Audience:** agent builders who connect agents to cloud services and business systems: Microsoft 365, Google
Workspace, Salesforce, ServiceNow, Jira, Slack, Teams and others, web services (REST and SOAP), APIs you import yourself,
and AWS EC2 virtual machines.

## Contents

1. [How agents sign in to other systems](#1-how-agents-sign-in-to-other-systems)
2. [Keeping secrets in the vault](#2-keeping-secrets-in-the-vault)
3. [Saved connections](#3-saved-connections)
4. [Microsoft 365](#4-microsoft-365)
5. [Google Workspace](#5-google-workspace)
6. [Salesforce](#6-salesforce)
7. [ServiceNow](#7-servicenow)
8. [Jira](#8-jira)
9. [Slack and Microsoft Teams](#9-slack-and-microsoft-teams)
10. [The other business toolkits](#10-the-other-business-toolkits)
11. [REST web services](#11-rest-web-services)
12. [SOAP web services](#12-soap-web-services)
13. [Import an OpenAPI connector](#13-import-an-openapi-connector)
14. [Worked example: AWS EC2 test machine, on in the morning and off in the evening](#14-worked-example-aws-ec2-test-machine-on-in-the-morning-and-off-in-the-evening)
15. [If it goes wrong](#15-if-it-goes-wrong)
16. [Related guides](#related-guides)

---

## 1. How agents sign in to other systems

Every cloud toolkit needs some form of sign-in: an access token, an API token, a user name and password, or a key pair.
In ONEOFFICEAUTOMATION:

- the **secret** is a **Credential**: a reference to One Agent Vault, never the value itself;
- the **address** of the system (instance URL, site URL, region) is ordinary text;
- the toolkit's sign-in fields are usually the first fields of each action, so you see at once what it needs.

Some toolkits have an **Authenticate** action that exchanges a client id and secret for an access token, which later
steps use. Others take a token directly on every step.

## 2. Keeping secrets in the vault

1. In One Command Center, an administrator or folder owner creates a vault folder and a credential in **One Agent
   Vault**, for example folder `crm`, credential `salesforce`, attributes `client_id` and `client_secret`. The agent's
   runs must be allowed to use the folder (they are **consumers**).
2. In Studio, create a **Credential** variable for each secret, usually as an input: `sf_client_secret`.
3. In the step, put `$sf_client_secret` in the secret field.
4. When the agent runs, give the input the reference `crm/salesforce/client_secret`.

The value is fetched just in time, while the step runs, is masked in every log, and every use is audited.

> **Tip:** For local test runs in Studio, the reference `crm/salesforce/client_secret` is read from the environment
> variable `OOA_SECRET_CRM_SALESFORCE_CLIENT_SECRET`, set before ONEOFFICEAUTOMATION starts.

## 3. Saved connections

Toolkits whose actions all repeat the same address and sign-in show a **Connection** field at the top of their settings:
"None: fill in the address and sign-in below", the saved connections of the workspace, and **Manage connections…**.
A saved connection keeps the address and settings once, with vault references for the secrets, and every step chooses
it by name. When a connection is chosen, the fields it supplies are hidden; **Set one of them on this step instead**
shows them again.

- Local runs read saved connections from `connections.json` in your workspace.
- Runs from One Command Center use its **Connections** page (Manage > Connections).

**Manage connections…** opens the list of the workspace's saved connections, where you can add, change and delete
them; the **Connection** list on a step shows the same connections.

**One Model Connections** (for AI) are a different thing: see [AI in agents](13-ai-in-agents.md).

## 4. Microsoft 365

The **Microsoft 365** group works through Microsoft Graph. Every action takes an **Access token** (a Credential holding
a Graph access token with the permissions the action needs).

| Area | Actions (examples) |
| --- | --- |
| Outlook mail | Send email (Outlook), Find emails (Outlook), Read email (Outlook), Save email attachments (Outlook), Reply/Forward, Move, Delete, Change email status |
| Calendar | Create calendar event, Get calendar events, Update/Cancel/Delete calendar event, Respond to meeting invitation, Add/Remove event attendees, Find free meeting times |
| OneDrive / SharePoint files | Upload file, Download file, List files, Find files, Copy/Move/Rename/Delete, Create folder, Share file or folder, Create sharing link, Export file to PDF, Restore file version |
| Excel online | Get/Set range values, Get/Set cell, Set formula, worksheets, tables (Create table, Add table rows, Get table rows), Write Data Table, Create workbook |
| Teams | Post Teams message, Send chat message (Teams), Create one-on-one chat (Teams), List teams, List channels, Get channel by name |
| SharePoint lists | List SharePoint lists, Get SharePoint list items, Create/Update/Delete SharePoint list item |
| Users | Get user (Microsoft 365) |

**Mailbox** (More options on mail actions) is `me`, or another user's address when the token has application
permission. The Excel toolkit can also open workbooks on OneDrive or SharePoint directly (see
[Office files](11-working-with-office-files-email-and-documents.md)).

## 5. Google Workspace

The **Google Workspace** group takes a Google OAuth **Access token** from the vault:

- **Gmail**: Send Gmail, List Gmail messages, Read Gmail message, Reply, Forward, Move, Delete, Save Gmail attachments.
- **Drive**: List Drive files, Upload to Drive, Download from Drive, Create Drive folder, Copy/Move/Rename/Delete Drive
  item, Find Drive items, Share Drive item, permissions, Export Google file.
- **Sheets**: Read sheet range, Write to sheet, Append rows to sheet, Create spreadsheet, sheets, cells, formulas,
  formatting, Write data table.
- **Calendar**: Create, List, Update and Delete Google Calendar events.

Spreadsheets and files are named by their id (the long code in their web address).

## 6. Salesforce

1. **Authenticate**: **Grant type** (*Client credentials*, or *Password*), **Client id** and **Client secret** of your
   connected app, **User name** and **Password** for the password grant, **Login url** (for example a sandbox).
   Outputs: **access token** (a Credential) and **instance url**. Save both in variables.
2. Use them in the other actions (**Instance url**, **Access token**):
   **Query (SOQL)**, **Get record**, **Create record**, **Update record**, **Delete record**,
   **Upsert by external id**, **Get attachments**, **Upload attachment**.
3. Optionally **Revoke authentication** at the end.

Record fields go in **Fields** as a Dictionary, for example `{"LastName": "Jones", "Company": "${company}"}`.

## 7. ServiceNow

- **Instance url** is your instance address (`https://yourcompany.service-now.com`).
- **Sign in with**: *User name and password*, or *An access token* (from **Authenticate**).
- Actions: **Query records** (encoded query, fields, display values), **Get record**, **Create record**,
  **Update record**, **Delete record**, **Add comment or work note**, **Attach file**, **List attachments**,
  **Download attachment**, **Delete attachment**, **List changed records** (since a time).

One Command Center can also start agents when ServiceNow records change (a server trigger).

## 8. Jira

- **Site url** of your Jira.
- **Sign in with**: *Cloud email and API token* (Jira Cloud) or *Data Center access token*.
- Actions: **Create issue**, **Get issue**, **Search issues (JQL)**, **Update issue**, **Move issue (transition)**,
  **Add comment**, **Get comments**, **Update comment**, **Delete comment**, **Attach file**, **Delete issue**,
  **Get projects**, **Create project**, **Update project**, **Delete project**.
- **Extra fields** takes other Jira fields as a Dictionary.

## 9. Slack and Microsoft Teams

**Slack** uses a **Bot token** from the vault: **Post message** (to a channel, optionally in a thread, with files),
**Send direct message**, **Upload file**, **Update message**, **Get conversation history**, **Get channel id by name**,
**Get channel details**, **Get user details**, workspace details.

**Teams** messages are part of Microsoft 365: **Post Teams message** to a channel, or **Send chat message (Teams)**.

A typical use: at the end of a run, post "Invoices booked: ${count}, failed: ${failed}" to the team's channel.

## 10. The other business toolkits

All of them follow the same pattern (address + token from the vault):

| Group | What it covers |
| --- | --- |
| **Workday** | Workers, plus generic REST and reports (RaaS) |
| **Okta** | Users, user status, groups |
| **Box** | Files, folders, search, shared links, collaborators |
| **DocuSign** | Envelopes from documents or templates, status, recipients, signed documents, embedded signing links |
| **GitHub** | Repositories, issues, pull requests and more (**Base url** for GitHub Enterprise) |
| **Asana** | Workspaces, projects, sections, tasks, comments, attachments, tags |
| **Azure DevOps** | Work items, WIQL queries, repositories, pull requests, pipelines, builds, test plans |
| **Zendesk** | Tickets and users (your Zendesk subdomain) |
| **Snowflake**, **Databricks**, **Kafka** | Data platforms over their REST interfaces (no drivers needed) |
| **Database** | Any database with a JDBC driver: Connect, Read from (query), Insert/update/delete, Bulk insert, stored procedures, transactions |
| **FTP / SFTP** | File transfer with FTP, FTPS and SFTP |

Toolkits that are not in the standard groups (for example new or custom toolkits) appear in the Actions panel under
**More toolkits**, named after the toolkit.

## 11. REST web services

**Send request** (group **REST web service**) calls any web API:

1. **Address (URL)** and **Method** (GET, POST, PUT, PATCH, DELETE …).
2. **Sign in**: *No sign-in*, *User name and password*, or *Bearer token* (a Credential).
3. **Headers** as a Dictionary, for example `{"Accept": "application/json"}`.
4. **Body**: *None*, *JSON* (a Dictionary or List), *Form fields*, *Multipart form*, *Text* or *A file*.
5. Optional: **Use a specific proxy**, **Send a client certificate**, **Timeout seconds**; More options **Query**,
   **Fail on error**, **Allow untrusted certificates** (tests only), cookies and redirects.
6. Outputs: **status** (the HTTP code), **body** (text), **json** (the parsed answer) and **headers**.

Read values from the answer with `$response.items[0].id`. **Download file** and **Upload file** handle files.

The blueprint **Call a REST API safely** (Create > Agent from a blueprint) gives you a Try/Catch around a request that
logs and raises a clear business error when the call fails.

## 12. SOAP web services

1. **Read WSDL** with the WSDL address: it lists the operations with their SOAPAction, endpoint and a sample request
   body.
2. **Send SOAP request**: **Address (URL)**, **Version** (1.1 or 1.2), **Soap action**, **Body** (the XML), optional
   sign-in, headers, client certificate and proxy. **Xpath** picks one value out of the answer; **Output file** saves
   the whole answer.

## 13. Import an OpenAPI connector

When a system publishes an API description, import it once and call its operations by name:

1. In the top bar, open the **Theme and density** menu (sun icon) and choose **Connectors…**.
2. Under **API description**, choose an OpenAPI 3.x or Swagger 2.0 file (JSON or YAML) or a Postman v2.1 collection.
   Optionally give a **Connector name** and a **Server**.
3. Click **Import**. The toast says "Imported <name> (n operations)".
4. Click **Operations** on the connector, type in **Filter operations**, and click **Insert step** on the one you need.
   Studio adds a **Call operation** step with the connector, the operation and its parameters filled in from their
   defaults.
5. Fill in **Parameters**, **Body** and **Credential** (the secret for the API) and choose the **Auth** method.

Connectors are kept in the workspace folder `connectors/`. **← All connectors** goes back to the list; ✕ deletes a
connector ("Steps that use it will stop working").

## 14. Worked example: AWS EC2 test machine, on in the morning and off in the evening

**Goal:** a test server on AWS should run only during office hours. One agent starts it every morning, waits until it is
running, reads its public IP address and posts it to the team; the same agent stops it every evening.

The **AWS EC2** toolkit (in the Actions panel it appears under **More toolkits**, as the group **AWS EC2**; search
for `EC2` to find it quickly) has: **Connect**,
**Describe instances**, **Get instance status**, **Start instances**, **Stop instances**, **Reboot instances**,
**Terminate instances**, **Enable monitoring**, **Disable monitoring**, **Launch instances**, **Set tags**,
**Wait for state** and **Disconnect**.

### 14.1 Prepare

1. In AWS, create an access key for a user or role that may describe, start and stop the instance.
2. In One Agent Vault, store it as credential `aws/test-server` with attributes `access_key_id` and
   `secret_access_key`.
3. Note the instance id, for example `i-0abc1234def567890`, and its region, for example `eu-west-1`.

### 14.2 Build the agent

Create an agent named *Test server on-off*. Variables:

| Name | Type | Use | Default |
| --- | --- | --- | --- |
| `action` | String | input, allowed values `start, stop` | `start` |
| `instance_ids` | List | input | `["i-0abc1234def567890"]` |
| `aws_key_id` | Credential | input | |
| `aws_secret` | Credential | input | |
| `instances` | Table | | |
| `public_ip` | String | output | |

Steps:

1. **AWS EC2 > Connect**
   - **Region** `eu-west-1`
   - **Access key id** `$aws_key_id`, **Secret access key** `$aws_secret`
   - More options: **Session token** (for temporary keys from an assumed role or single sign-on), **Endpoint** (a
     private VPC endpoint; leave empty normally), **Check now** (on by default: checks the keys and region at once).
2. **Try** (so the session is always closed):
   1. **If** `$action` **equals** `start`:
      - **Then**:
        1. **Start instances**, **Instance ids**: choose `$instance_ids`.
        2. **Wait for state**, the same ids, **State** *running*; More options **Timeout seconds** `600`,
           **Check every seconds** `10`.
        3. **Describe instances**, **Instances: These ones**, the same ids; save **instances** in `$instances`.
        4. **Data table > Get value**: table `$instances`, row `0`, column `public_ip`; save **value** in `$public_ip`.
        5. **Slack > Post message** (or **Microsoft 365 > Post Teams message**): "Test server is up at ${public_ip}".
      - **Else**:
        1. **Stop instances**, the same ids. More options: **Hibernate** (the instance must be set up for it) or
           **Force** (for an instance that hangs, without a clean shutdown).
        2. **Wait for state**, **State** *stopped*.
   2. **Finally**: **AWS EC2 > Disconnect** (forgets the keys).

> **Tip:** Every EC2 action shows the **Session** field first; leave it at its default (`ec2`) unless you work with two
> regions at once, then give each **Connect** its own session name.

### 14.3 Test in Studio

1. Set the environment variables `OOA_SECRET_AWS_TEST_SERVER_ACCESS_KEY_ID` and
   `OOA_SECRET_AWS_TEST_SERVER_SECRET_ACCESS_KEY` before starting ONEOFFICEAUTOMATION (for local runs only).
2. In the Run tab give `aws_key_id` = `aws/test-server/access_key_id`, `aws_secret` =
   `aws/test-server/secret_access_key`, `action` = `start`.
3. Click **Run**. The run takes a minute or two while the machine starts; **Outputs** show `public_ip`.
4. Run again with `action` = `stop`.

**Describe instances** can also find machines by **Filters** instead of ids, for example
`{"tag:Environment": "test", "instance-state-name": "running"}`. Its table has the columns `instance_id`, `name`,
`state`, `instance_type`, `image_id`, `private_ip`, `public_ip`, `availability_zone`, `launch_time`, `monitoring` and
`tags`; its **instance_ids** output is a List you can pass straight to Start or Stop. **Get instance status** adds AWS's
system and instance health checks.

### 14.4 Schedule it

1. Check the agent in (see [Publishing to Command Center](15-publishing-to-command-center.md)).
2. In One Command Center > **Schedules**, create two **Weekly** schedules for the agent on a runner (or runner pool), in
   your time zone, with Monday to Friday ticked:
   - at 07:30 with `action` = `start`;
   - at 19:00 with `action` = `stop`;
   - both with the credential inputs as vault references.
3. Add a holiday calendar so the machine stays off on public holidays.

### 14.5 Going further

- **Launch instances** creates new machines: **Image id** (an AMI such as `ami-0abcdef1234567890`), **Instance type**
  (`t3.micro`), **Count** (1 to 100), **Name**, **Key pair**, **Subnet id**, **Security group ids**; More options
  **Tags** and **User data** (a start-up script run at first start). It returns the new **instance_ids**.
- **Set tags** adds or changes tags, for example `{"Owner": "finance-bots"}`.
- **Enable monitoring** / **Disable monitoring** switch detailed one-minute CloudWatch monitoring on or off.
- **Terminate instances** ends machines for good, and cannot be undone. Protect such a step with an **If** that checks
  a tag, and keep it out of agents that run unattended unless you are sure.

## 15. If it goes wrong

| What you see | Cause and fix |
| --- | --- |
| `VAULT.NOT_FOUND` | The credential reference is wrong, or (local runs) the `OOA_SECRET_…` variable is not set. |
| An `…AUTH_FAILED` or 401/403 error | The token expired, is wrong, or lacks permission. For EC2: "Check the access keys in the vault and the region." |
| `EC2.WAIT_TIMEOUT` | The machine did not reach the state in time. Raise **Timeout seconds**, and check the instance in the AWS console. |
| `EC2.INSTANCE_NOT_FOUND` / a bad instance id | The id is wrong, or the instance is in another region. |
| REST answers with a 4xx/5xx status and the step fails | Read **body** in the Run log; turn off **Fail on error** to handle the status yourself. Add **Retries on temporary errors** for 429 and 5xx answers. |
| An imported connector has no server | Give **Server** when importing, or set the **Base url** on the step. |
| A toolkit is missing from the Actions panel | It is switched off in One Command Center (Toolkits) or not installed. Ask your administrator. |

## Related guides

- [Building agents](03-studio-building-agents.md)
- [Office files, email and documents](11-working-with-office-files-email-and-documents.md)
- [AI in agents](13-ai-in-agents.md)
- [Publishing to Command Center](15-publishing-to-command-center.md)
- [Running and debugging](04-studio-running-and-debugging.md)
