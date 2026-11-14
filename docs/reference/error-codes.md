# Error codes

Every error the platform raises, with what it means and what to do. A step's error shows its code (for example
`EXCEL.IO_ERROR`), which a Try block's Catch can match; the run log and the console show the message and the fix.

Generated from the code by `docs/research/tools/make-error-catalogue.py`; do not edit by hand.

- **What it means** gives up to three of the messages the code is raised with (… stands for a value: a file, a name, a number).
- **What to do** is the suggested fix. A command error without its own fix gets the one for its kind of code
  (CommandFixes); an error One CommandCenter sends without one gets the one for its code where the API answers (ServerFixes).
- An internal error of One CommandCenter shows a reference such as `ERR-…`: give it to an administrator, who finds the details beside it in the server log.

1639 codes in 151 areas.

Areas: [ACTION](#action), [AD](#ad), [AGENT](#agent), [AI](#ai), [ALERT](#alert), [APP](#app), [APPLICATION](#application), [ASANA](#asana), [ASSISTANT](#assistant), [AWS_AI](#aws_ai), [AZURE_AI](#azure_ai), [AZURE_DEVOPS](#azure_devops), [AZURE_VM](#azure_vm), [BOOLEAN](#boolean), [BOX](#box), [BROWSER](#browser), [CALENDAR](#calendar), [CHILD](#child), [CLI](#cli), [CLIPBOARD](#clipboard), [CODE](#code), [COMMAND](#command), [COMMS](#comms), [CONNECTION](#connection), [CONNECTOR](#connector), [CR](#cr), [CREDMAN](#credman), [CRYPTO](#crypto), [CSV](#csv), [CURRENCY](#currency), [DATA](#data), [DATABRICKS](#databricks), [DATATABLE](#datatable), [DATETIME](#datetime), [DB](#db), [DEBUG](#debug), [DELAY](#delay), [DESKTOP](#desktop), [DESKTOP_UI](#desktop_ui), [DICT](#dict), [DLL](#dll), [DOCS](#docs), [DOCUSIGN](#docusign), [DOTNET](#dotnet), [EC2](#ec2), [EMAIL](#email), [ENCODING](#encoding), [EVENTS](#events), [EXCEL](#excel), [EXCEL_DESKTOP](#excel_desktop), [EXCEL_OPEN](#excel_open), [EXCHANGE](#exchange), [EXPR](#expr), [FILE](#file), [FLOW](#flow), [FOLDER](#folder), [FORMS](#forms), [FUZZY](#fuzzy), [GITHUB](#github), [GLOBALS](#globals), [GOOGLE](#google), [GOOGLE_AI](#google_ai), [GOTO](#goto), [HTTP](#http), [IDP](#idp), [IMAGE](#image), [INSIGHTS](#insights), [INTERRUPT](#interrupt), [JAVA](#java), [JAVA_APPS](#java_apps), [JIRA](#jira), [JOBS](#jobs), [JSON](#json), [KAFKA](#kafka), [KNOWLEDGE](#knowledge), [LDAP](#ldap), [LICENSE](#license), [LIST](#list), [LOCK](#lock), [LOG](#log), [LONGRUN](#longrun), [LOOP](#loop), [M365](#m365), [MAIL](#mail), [MCP](#mcp), [MINING](#mining), [MOVE](#move), [NETWORK](#network), [NUMBER](#number), [OBJECT](#object), [OCR](#ocr), [OFFICE](#office), [OFFICE_ATTACH](#office_attach), [OKTA](#okta), [OUTLOOK](#outlook), [PDF](#pdf), [PGP](#pgp), [PKG](#pkg), [POWERPOINT_DESKTOP](#powerpoint_desktop), [PPT](#ppt), [PRINTER](#printer), [PROCESS](#process), [QUEUE](#queue), [RELEASE](#release), [REPO](#repo), [REST](#rest), [RULES](#rules), [RUN](#run), [RUN_CONTEXT](#run_context), [SAAS](#saas), [SALESFORCE](#salesforce), [SAP](#sap), [SAP_BAPI](#sap_bapi), [SCRIPT](#script), [SERVICE](#service), [SERVICENOW](#servicenow), [SERVICES](#services), [SESSION](#session), [SLACK](#slack), [SNMP](#snmp), [SNOWFLAKE](#snowflake), [SOAP](#soap), [SOUND](#sound), [SSO](#sso), [STATE](#state), [STEP](#step), [STRING](#string), [STUDIO](#studio), [SUBFLOW](#subflow), [SWITCH](#switch), [SYSTEM](#system), [TABLE](#table), [TEMPLATES](#templates), [TERMINAL](#terminal), [TRANSFER](#transfer), [TRIGGER](#trigger), [UI](#ui), [VAR](#var), [VAULT](#vault), [VISION](#vision), [WAIT](#wait), [WEBAGENT](#webagent), [WF](#wf), [WINDOW](#window), [WORD](#word), [WORD_OPEN](#word_open), [WORKDAY](#workday), [WORKSPACE](#workspace), [XML](#xml), [ZENDESK](#zendesk), [ZIP](#zip)

## ACTION

| Code | What it means | What to do | Where |
|---|---|---|---|
| `ACTION.INPUT_INVALID` | Input '…' cannot be converted to …<br>Input '…' must be a whole number, got …<br>Input '…' is too large: … | Give a value of the type the input expects.<br>Round the value first (Number > Round), or check the calculation that produced it.<br>Give a smaller number. | runner |
| `ACTION.INPUT_MISSING` | Input '…' resolved to empty | … | runner |
| `ACTION.NOT_IN_THIS_PROGRAM` | …/… cannot run in this program: … | Run the automation with the full install (bin\ooa.bat run) or on a runner. | runner |
| `ACTION.OUTPUT_MISSING` | Action did not return output '…' | Update the toolkit; it did not return an output it declares. | runner |
| `ACTION.UNEXPECTED` | …/… raised …: …<br>Cannot call …/…: … | Run the step again; if it keeps happening, report it to the toolkit's publisher with the run id.<br>Update the toolkit; if it keeps happening, report it with the run log.<br>Report this to the toolkit publisher with the run id. | runner |

## AD

| Code | What it means | What to do | Where |
|---|---|---|---|
| `AD.ACCESS_DENIED` | Not allowed: … | The bind account needs permission for this change. | toolkits/microsoft |
| `AD.ALREADY_EXISTS` | An object with that name already exists: … | Pick another name or container. | toolkits/microsoft |
| `AD.AMBIGUOUS` | More than one … is named '…' | Give the distinguished name instead. | toolkits/microsoft |
| `AD.AUTH_FAILED` | Directory sign-in failed: … | Check the bind user and password. | toolkits/microsoft |
| `AD.BAD_ATTRIBUTE` | Invalid attribute name '…' | Use the attribute's LDAP name, for example mail or telephoneNumber.<br>Example: description, telephoneNumber or managedBy. | toolkits/microsoft |
| `AD.BAD_DATE` | Invalid date '…' | Use yyyy-MM-dd, e.g. 2026-12-31, or never. | toolkits/microsoft |
| `AD.BAD_DN` | Not a distinguished name: '…'<br>No domain to build the distinguished name in<br>'…' is not a domain name | Example: OU=Staff,DC=corp,DC=example,DC=com.<br>Give domain, e.g. corp.example.com, or start the path with it.<br>Use the DNS name, e.g. corp.example.com. | toolkits/microsoft |
| `AD.BAD_EMAIL` | Not an email address: '…' | Example: jdoe@corp.example.com. | toolkits/microsoft |
| `AD.BAD_FILTER` | Invalid LDAP filter: … | Example: (&(objectClass=user)(sAMAccountName=jdoe)) | toolkits/microsoft |
| `AD.BAD_MODE` | Unknown mode '…' | Use replace, add or remove. | toolkits/microsoft |
| `AD.BAD_NAME` | The … name is empty<br>The … name is longer than 64 characters<br>No … given | Fill in the name.<br>Shorten the name to 64 characters or fewer.<br>Shorten the initials to 6 characters or fewer. | toolkits/microsoft |
| `AD.BAD_OPTION` | StartTLS is for ldap:// addresses; ldaps:// is already encrypted<br>name_kind is CN or OU, not '…'<br>path_kind is auto, ou or cn, not '…' | Turn StartTLS off, or use ldap://.<br>Set name_kind to CN or OU.<br>Set path_kind to auto, ou or cn. | toolkits/microsoft |
| `AD.BAD_SECURITY_DESCRIPTOR` | The user's security descriptor could not be read | Check that the server is Active Directory. | toolkits/microsoft |
| `AD.BAD_TARGET` | Unknown rename target '…' | Use object, sign_in or both. | toolkits/microsoft |
| `AD.BAD_TIMEOUT` | Timeouts must be 1-600 s (connect) and 1-3600 s (read) | Set the connect timeout between 1 and 600 seconds and the read timeout between 1 and 3600 seconds. | toolkits/microsoft |
| `AD.BAD_TYPE` | Unknown member type '…' | Use all, user, group or computer. | toolkits/microsoft |
| `AD.BAD_URL` | '…' is not an LDAP address<br>The address has no server name | Example: ldaps://dc01.corp.example.com:636 or LDAP://dc01/DC=corp,DC=com.<br>Example: ldaps://dc01.corp.example.com:636. | toolkits/microsoft |
| `AD.CERTIFICATE_NOT_TRUSTED` | The domain controller's certificate is not trusted: … | Add the company CA's certificate to the Windows certificate store (Trusted Root), or give it in ca_file. | toolkits/microsoft |
| `AD.ERROR` | … | Check the connection (server, port, user and password) and that the runner can reach the domain controller. | toolkits/microsoft |
| `AD.NOT_EMPTY` | The object still contains other objects: … | Move or delete what is inside first, or use the recursive option. | toolkits/microsoft |
| `AD.NO_BASE_DN` | Say where searches start (base_dn) | Example: DC=corp,DC=example,DC=com, or put it in the address: LDAP://dc01/DC=corp,DC=com. | toolkits/microsoft |
| `AD.NO_CHANGES` | No details to change | Fill at least one input. | toolkits/microsoft |
| `AD.NO_SECURITY_DESCRIPTOR` | Cannot read the permissions of … | The bind account needs permission to read and change permissions; this option works on Active Directory only. | toolkits/microsoft |
| `AD.NO_VALUE` | Give the value to … | Fill in Value. | toolkits/microsoft |
| `AD.TLS_FAILED` | StartTLS failed: … | Check that the server offers StartTLS and that its certificate is trusted (ca_file). | toolkits/microsoft |
| `AD.UNREACHABLE` | Cannot reach the directory: … | Check the server name, port and network. | toolkits/microsoft |
| `AD.USER_NOT_FOUND` | User '…' not found | Check the user name (sAMAccountName or UPN). | toolkits/microsoft |
| `AD.USE_RESET_PASSWORD` | Use Reset password to change passwords | Use the Reset password step instead. | toolkits/microsoft |

## AGENT

| Code | What it means | What to do | Where |
|---|---|---|---|
| `AGENT.AUTOLOGON_FAILED` | Windows refused the change (error …) | Run the command as an administrator; check the account and password. | agent |
| `AGENT.BAD_CREDENTIALS` | Windows did not accept the user name or password | Check them in My devices > Device credentials, then check again. | agent |
| `AGENT.BAD_FORM` | Not a form from the form designer: … | Open it in the form designer and save it again. | toolkits/ai |
| `AGENT.BAD_INPUT` | Give the Windows user with --user<br>Not a Windows account name: …<br>No device credentials were given | Give the Windows user with --user.<br>Write the account as DOMAIN\name or name.<br>Enter them in My account > My devices > Device credentials. | agent |
| `AGENT.BAD_OPTION` | max_duration_seconds cannot be negative<br>A tool and a form are both named …<br>Two tools are both named … | Set max_duration_seconds to 0 (no limit) or a positive number of seconds.<br>Give the tool or the form another name; the agent tells them apart by name.<br>Give one of the two tools another name; the agent tells them apart by name. | toolkits/ai |
| `AGENT.BAD_PROXY` | The proxy is system, none, windows or http://host:port, not …<br>The proxy setting windows works on Windows runners only | Set the proxy to system, none, windows (Windows sign-in to the proxy) or http://host:port.<br>Use system, none or http://host:port on this computer. | agent |
| `AGENT.BAD_RESPONSE` | Command Center returned HTTP …<br>One CommandCenter returned HTTP … | Check One CommandCenter's address in the runner's settings and that the runner can reach it (network, proxy, firewall). | agent |
| `AGENT.BAD_URL` | One CommandCenter address starts with https:// (or http://) | Give One CommandCenter's address starting with https:// (or http://). | agent |
| `AGENT.CA` | Cannot use the certificate file …: … | Export it on One CommandCenter with: ooa tls export-cert --out ooa.cer | agent |
| `AGENT.FORM_NOT_FOUND` | Form not found: … | Choose a .form.json file made in the form designer. | toolkits/ai |
| `AGENT.HTTP_` | One CommandCenter answered HTTP … for … | Check One CommandCenter's address in the runner's settings and that the runner can reach it (network, proxy, firewall). | agent |
| `AGENT.INTERRUPTED` | Interrupted | Try again; if it keeps happening, an administrator can look in the server log. | agent |
| `AGENT.KNOWLEDGE_NOT_FOUND` | Knowledge index not found: … | Build it with Knowledge Q&A > Build index. | toolkits/ai |
| `AGENT.LOGON_FAILED` | Windows could not sign the account in (error …) | Check the account on this computer. | agent |
| `AGENT.LOGON_NOT_ALLOWED` | This account may not sign in to this computer | Give it the right to sign in locally on this computer. | agent |
| `AGENT.MOVE_REFUSED` | One CommandCenter at … did not accept this runner: … | Copy or restore the old One CommandCenter's data there first (its runners come with it), or register this runner again | agent |
| `AGENT.NOT_ADMIN` | Windows refused: run this from a Command Prompt or PowerShell opened as administrator | Open Command Prompt or PowerShell with Run as administrator and run the command again. | agent |
| `AGENT.NOT_REGISTERED` | This runner is not registered (no …) | Run: ooa agent register --url <One CommandCenter URL> --key <registration key><br>Register the runner first: ooa agent register --url <One CommandCenter address> --key <registration key>. | agent |
| `AGENT.NO_TOOLS` | Give the agent at least one agent, form, knowledge index or MCP tool to use | Add at least one tool in the step: an agent it may run, a form, a knowledge index or an MCP server. | toolkits/ai |
| `AGENT.PROXY_AUTH` | The proxy asks for a sign-in Windows cannot give for you (only Basic) | Set proxy_user and proxy_password with the proxy http://host:port instead of windows. | agent |
| `AGENT.PROXY_PASSWORD` | The proxy password file cannot be read: …<br>The environment variable … is not set<br>The proxy password cannot be read on this computer | Set it again: ooa agent network --proxy-user USER --proxy-password-env VAR<br>Set the environment variable, then start the runner again.<br>Store the proxy password again with ooa agent proxy. | agent, cli |
| `AGENT.SESSION_FAILED` | Windows did not keep the credential for …: … | Run the command as an administrator; check the account and password. | agent |
| `AGENT.STOPPED` | …The agent was stopped by a Stop agent step… | Nothing to fix: a Stop agent step ended the run on purpose. | runner |
| `AGENT.TOOL_NEEDS_SECRET` | Agent '…' needs a Credential input, which a model must never supply | Read the credential inside the agent instead of taking it as an input. | toolkits/ai |
| `AGENT.TOOL_UNDOCUMENTED` | Agent '…' has no description | Add a description under Properties in OneAgentStudio (with no step selected); the AI agent reads it to decide when to call this agent. | toolkits/ai |
| `AGENT.TOO_DEEP` | Agents calling agents calling agents are nested too deeply | Simplify the agent's tools. | cli |
| `AGENT.UNREACHABLE` | Cannot reach One CommandCenter at …: …<br>Cannot reach Command Center at …: … | Check One CommandCenter's address in the runner's settings and that the runner can reach it (network, proxy, firewall).<br>Check the URL and network; the agent keeps retrying. | agent |
| `AGENT.UPDATE` | One CommandCenter sent an empty file list<br>Unexpected file name …<br>The downloaded … does not match its checksum; update stopped | Try the update again later; if it keeps failing, install the runner again from One CommandCenter. | agent |

## AI

| Code | What it means | What to do | Where |
|---|---|---|---|
| `AI.AUTH_FAILED` | Google did not return an access token | Check that the service account is enabled and may use Vertex AI. | packages |
| `AI.BAD_CONNECTIONS` | Send {default, connections: [...]}<br>Each connection is an object<br>Connection names are unique and use letters, digits, . _ - (got '…') | Reload the page and save the model connections again.<br>Give each connection its own name of letters, digits, . _ and -.<br>Choose the API key's credential in the connection. | cli, packages |
| `AI.BAD_IMAGE` | Pictures must be .png, .jpg, .gif or .webp: … | Use a .png, .jpg, .gif or .webp picture. | packages |
| `AI.BAD_JSON` | The model did not return valid JSON | Retry, lower the temperature or use a stronger model. | packages |
| `AI.BAD_KEY` | The service-account key is not valid JSON<br>The service-account private key cannot be used: … | Use the service account's JSON key file as Google Cloud downloads it.<br>Use the JSON key file Google created for the service account. | packages |
| `AI.BAD_OPTION` | history_limit must be between 2 and 500<br>number_of_images must be between 1 and 4<br>size is WIDTHxHEIGHT, for example 1024x1024 | Set history_limit between 2 and 500.<br>Set number_of_images between 1 and 4.<br>Write the size as width x height, for example 1024x1024. | packages, toolkits/ai |
| `AI.BAD_PROVIDER` | Unknown provider for '…'<br>Unknown provider '…' | Use ….<br>… | cli, packages |
| `AI.BAD_REGION` | '…' is not an AWS region name | For example eu-west-1. | packages |
| `AI.BAD_RESPONSE` | The SageMaker endpoint answered in a format this client does not understand<br>The model returned … vectors for … texts<br>The model returned no vector | Use a container with the messages API (TGI 1.4+ or LMI), or one that returns generated_text.<br>Try again; if it keeps happening, check the model's name and that it supports this request. | packages |
| `AI.BAD_SESSION` | Invalid chat session name '…' | Use letters, digits, spaces, . - and _. | packages |
| `AI.BAD_SKILL` | Invalid skill name '…'<br>Skill '…' needs at least a "prompt" text<br>Skill '…' is not valid JSON: … | Use letters, digits, - and _.<br>Add a prompt text to the skill: the instruction the model follows.<br>Correct the skill's JSON (a missing comma or quote is the usual cause), or recreate it in Settings > AI skills. | toolkits/ai |
| `AI.BAD_URL` | Invalid SageMaker URL: …<br>Invalid AWS URL: … | Check the region, endpoint name and base_url.<br>Check the region, model and base_url. | packages |
| `AI.BUDGET_REACHED` | … | An administrator raises the budget in Model Connections, or the budget starts again next month. | packages |
| `AI.CALL_LIMIT` | This run reached the limit of … calls through the model connection '…' | An administrator raises Calls a run in Model Connections. | packages |
| `AI.CONTENT_FLAGGED` | The model's answer was flagged by the content check……: …, … | Rephrase the prompt, or have a person review this case. | packages |
| `AI.DISABLED` | AI is switched off in One CommandCenter | Ask an administrator to switch it on (Administration > Settings > AI). | packages |
| `AI.EMPTY` | The model returned no text | Retry or raise max_tokens. | packages, toolkits/ai |
| `AI.EMPTY_MESSAGE` | The message is empty | Fill in the message to send. | packages |
| `AI.EMPTY_PROMPT` | Describe the picture | Describe the picture to make. | packages |
| `AI.IMAGE_NOT_FOUND` | Picture not found: … | Check the path; the file may have been moved or renamed. | packages |
| `AI.IMAGE_TOO_LARGE` | Pictures are at most 20 MB: … | Make the picture smaller than 20 MB. | packages |
| `AI.KEY_IN_FILE` | API keys are never stored in connections | Use api_key_env (an environment variable name) or a vault credential reference. | cli |
| `AI.KEY_REFUSED` | Only the model connection's own key can be read here | Nothing to do; only the model connection reads its own key. | modules/one-chat |
| `AI.LABEL_NOT_ALLOWED` | The model answered '…', which is not one of … | Retry or describe the labels more clearly. | packages |
| `AI.MODEL_NOT_ALLOWED` | …A model set on the action…The model connection '…'… is not allowed by the AI guardrails '…name…' | Use one of these model connections: …, ……. | packages |
| `AI.NOT_SUPPORTED` | Picture generation is not available for …<br>Embeddings are not available for … | Use openai, azure_openai, openai_compatible, google_vertex or aws_bedrock.<br>Use openai, azure_openai, openai_compatible (Ollama and similar), google_vertex or aws_bedrock. | packages |
| `AI.NO_ACCESS_KEY` | The AWS access key id from the vault is required | Map aws_access_key_id to a Credential, or store the key as ACCESS_KEY_ID:SECRET in one Credential. | packages |
| `AI.NO_CONNECTION` | No One Model Connection is available to you<br>The model connection … is not available to you<br>There is no model connection named '…' | An administrator adds one in One Model Connections, or allows your role on one.<br>Ask an administrator to allow your role on it.<br>Create it under Model Connections, or pick another name. | modules/one-chat, packages |
| `AI.NO_ENDPOINT` | Azure OpenAI needs base_url (your resource endpoint)<br>Vertex AI needs base_url: the location's API root | Example: https://myresource.openai.azure.com<br>For example https://europe-west4-aiplatform.googleapis.com/v1/projects/my-project/locations/europe-west4<br>Fill in base_url with your Azure OpenAI resource's endpoint, for example https://NAME.openai.azure.com. | packages |
| `AI.NO_IMAGE` | The model returned no picture | The provider may have refused the description; change it and retry. | packages |
| `AI.NO_IMAGES` | Give at least one picture | Add at least one picture. | packages |
| `AI.NO_KEY` | An API key from the vault is required<br>A Hugging Face access token from the vault is required<br>The AWS secret access key from the vault is required | Choose the API key's credential in the step's connection (Manage > Credentials holds it).<br>Map api_key to a Credential variable.<br>Map api_key to a Credential holding your hf_... token. | packages |
| `AI.NO_LABELS` | Give at least one label | Add at least one label to choose from. | packages |
| `AI.NO_MODEL` | No model chosen<br>Skill '…' names no model | Pick a Model Connection, or set provider and model on this action.<br>Add "model" or "connection" to the skill, or set the model input. | packages, toolkits/ai |
| `AI.NO_MODERATION` | The content check uses an OpenAI-style moderation endpoint; … has none | Turn the content check off for this connection. | packages |
| `AI.NO_REGION` | The AWS region is required | Set aws_region, for example eu-west-1. | packages |
| `AI.NO_VAULT` | No vault is available for the connection's API key | Run the agent where a vault is available (in One CommandCenter, or with the Private workspace's vault set up). | packages |
| `AI.SECRET_IN_PROMPT` | A Credential cannot be put into a prompt | Pass non-secret values only. | packages |
| `AI.SKILL_NOT_FOUND` | Prompt Skill '…' not found | Create ai-skills/….json in the workspace. | toolkits/ai |
| `AI.TOO_MANY_IMAGES` | Send at most 10 pictures at a time | Send at most 10 pictures in one step. | packages |
| `AI.WORKDIR` | Cannot prepare the AI call: … | Check that the server's data folder has space and that the service account may read and write there. | modules/one-chat |

## ALERT

| Code | What it means | What to do | Where |
|---|---|---|---|
| `ALERT.NO_MESSAGE` | The alert has no message | Say what happened, e.g. the invoice and why it needs a person.<br>Say what happened and what a person should do. | modules/insights, packages |
| `ALERT.TOO_MANY` | This run has already raised … alerts | Raise one alert for many items, or look at why the agent raises so many. | modules/insights |

## APP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `APP.PUBLISHER_GONE` | The person who published the app …name… is no longer active | Ask an app maker to publish it again. | modules/low-code |

## APPLICATION

| Code | What it means | What to do | Where |
|---|---|---|---|
| `APPLICATION.BAD_VARIABLE_NAME` | Invalid environment variable name '…' | Use letters, digits and underscores. | packages |
| `APPLICATION.BAD_WINDOW_STATE` | Unknown window_state '…' | Use normal, minimized, maximized or hidden. | packages |
| `APPLICATION.NO_LOCATION` | Choose the program or file to open | Browse for it, or type its path. | packages |
| `APPLICATION.NO_TARGET` | Give a process id or a program name | Fill in the process id or the program's name (for example notepad.exe). | packages |
| `APPLICATION.OPEN_FAILED` | … | Check a default application is set for this file type. | packages |
| `APPLICATION.UNSAFE_ARGUMENT` | Argument … has a line break, which … would run as a new command<br>Argument … ('…...…') contains & \| < > ^ or a quote, which … reads as a command operator, so it could run another command | Pass multi-line data through input or a file.<br>Pass data through input or environment variables (read them as %NAME% in the script), or set allow_shell_operators to true when the operator is intended. | packages |

## ASANA

| Code | What it means | What to do | Where |
|---|---|---|---|
| `ASANA.AMBIGUOUS_NAME` | … …s are named '…' | Use the id from its link. | toolkits/enterprise |
| `ASANA.BAD_FORMAT` | Unknown comment format '…' | Use plain or html. | toolkits/enterprise |
| `ASANA.BAD_ID` | Invalid … id '…'<br>Give the …<br>Give the section | Use the numeric id from the item's link, e.g. 1208000000000001.<br>Its name or id.<br>Its id, or Project name/Section name. | toolkits/enterprise |
| `ASANA.BAD_INCLUDE` | Cannot copy '…' | Use notes, assignee, subtasks, attachments, tags, followers, projects or dates. | toolkits/enterprise |
| `ASANA.BAD_USER` | Invalid user '…' | Use a numeric user id, an email address, or me. | toolkits/enterprise |
| `ASANA.CONFIRM_REQUIRED` | The project was not deleted because confirm is false | Set confirm to true. | toolkits/enterprise |
| `ASANA.MISSING_FILTER` | No project, section or assignee given | Fill in project_id, section_id, or assignee together with workspace_id. | toolkits/enterprise |
| `ASANA.MISSING_LOCATION` | No project, parent task or workspace given | Fill in project_id, parent_task_id, or workspace_id for a task outside projects. | toolkits/enterprise |
| `ASANA.NOTHING_TO_UPDATE` | No changes were given<br>Nothing to change | Fill in at least one field to change.<br>Fill in text or pinned.<br>Fill in at least one field. | toolkits/enterprise |
| `ASANA.NOT_FOUND` | No … named '…'<br>No section named '…' in the project | Check the name, or use its id from its link.<br>Check the name (see List sections), or use its id. | toolkits/enterprise |
| `ASANA.NO_DOWNLOAD` | Attachment … has no download address (links to Google Drive, Box and similar are not files) | Open the link from the task instead. | toolkits/enterprise |
| `ASANA.PREMIUM_REQUIRED` | … | This feature needs a paid Asana plan for the workspace. | toolkits/enterprise |

## ASSISTANT

| Code | What it means | What to do | Where |
|---|---|---|---|
| `ASSISTANT.BAD_INPUT` | '…' is not a number | Give a number. | cli |
| `ASSISTANT.EMPTY` | Describe what the agent should do | Describe what the agent should do in a sentence or two. | cli |
| `ASSISTANT.INTERRUPTED` | Interrupted | Try again; if it keeps happening, look in the runner's or Studio's log for this code. | cli |
| `ASSISTANT.KEY` | Cannot … the API key for this Windows user<br>Cannot run PowerShell: …<br>Interrupted | Run Studio as the Windows user who stored the key, or store the key again in Settings > AI.<br>Check that PowerShell can run for this Windows user.<br>Try again; if it keeps happening, look in the runner's or Studio's log for this code. | cli |
| `ASSISTANT.NOT_JSON` | The model did not answer with steps<br>The model's answer was not valid JSON | Rephrase the request and try again.<br>Try again; smaller requests work better. | cli |
| `ASSISTANT.UNREACHABLE` | Cannot reach One CommandCenter at …: … | Check the address and the network. | cli |

## AWS_AI

| Code | What it means | What to do | Where |
|---|---|---|---|
| `AWS_AI.BAD_LANGUAGE` | '…' is not a language code | Use a code such as en, es, de or zh-TW. | toolkits/cloud-data |
| `AWS_AI.BAD_REGION` | '…' is not an AWS region | Use a region code such as us-east-1, eu-west-1 or ap-south-1. | toolkits/cloud-data |
| `AWS_AI.BAD_RESPONSE` | The identity check did not answer with an AWS account | Check the region or endpoint, and the proxy between this computer and AWS. | toolkits/cloud-data |
| `AWS_AI.NOTHING_TO_READ` | Neither forms nor tables is ticked | Tick forms, tables or both; use Read text from a scan for text only. | toolkits/cloud-data |
| `AWS_AI.NO_ACCESS_KEY` | The access key id from the vault is required | Map access_key_id to a Credential. | toolkits/cloud-data |
| `AWS_AI.NO_SECRET_KEY` | The secret access key from the vault is required | Map secret_access_key to a Credential. | toolkits/cloud-data |

## AZURE_AI

| Code | What it means | What to do | Where |
|---|---|---|---|
| `AZURE_AI.ANALYSIS_FAILED` | … could not analyse the document…… (…)…: … | Check the file is a readable PDF, picture or Office file, and the page list. | packages |
| `AZURE_AI.ANALYSIS_TIMEOUT` | The analysis had not finished after … s (status …unknown…) | Raise timeout_seconds, or send fewer pages. | packages |
| `AZURE_AI.BAD_CREDENTIAL_TYPE` | Unknown credential_type '…' | Use key or access_token. | packages |
| `AZURE_AI.BAD_ENDPOINT` | '…' is not an https address | Copy the endpoint from the resource's Keys and Endpoint page in the Azure portal. | packages |
| `AZURE_AI.BAD_FEATURE` | Unknown feature '…' | Use any of …, …. | packages |
| `AZURE_AI.BAD_LANGUAGE` | '…' is not a language code<br>'…' is not a locale | Use a code such as en, de, fr or zh-Hans.<br>Use a locale such as en-US or de-DE.<br>Use a code such as en, de or fr. | packages |
| `AZURE_AI.BAD_MODEL` | Unknown model '…' | Use prebuilt-invoice, prebuilt-receipt or prebuilt-layout. | packages |
| `AZURE_AI.BAD_PAGES` | '…' is not a page list | Use page numbers and ranges such as 1-3,5. | packages |
| `AZURE_AI.BAD_RESPONSE` | Azure AI Language answered without a result<br>… accepted the document but gave no Operation-Location to follow<br>The result address … is not on the resource's host; the key is not sent there | Check the endpoint is an Azure AI Language or multi-service resource.<br>Check the endpoint is a Document Intelligence or multi-service resource.<br>Check the endpoint and any proxy in between. | packages |
| `AZURE_AI.BAD_URL` | Invalid address: … | Check the endpoint given to Connect. | packages |
| `AZURE_AI.DOCUMENT_ERROR` | Azure AI Language could not read the text: ……: … | Check the text and the language code. | packages |
| `AZURE_AI.NO_KEY` | The resource key from the vault is required | Map credential to a Credential. | packages |

## AZURE_DEVOPS

| Code | What it means | What to do | Where |
|---|---|---|---|
| `AZURE_DEVOPS.AMBIGUOUS_TESTER` | … users match the tester '…' | Use the person's sign-in email instead of the display name. | toolkits/devops |
| `AZURE_DEVOPS.AUTH_FAILED` | Azure DevOps did not accept the personal access token | Check the token in the vault is valid, not expired, and has the needed scopes. | toolkits/devops |
| `AZURE_DEVOPS.BAD_AUTOMATION_STATUS` | Unknown automation status '…' | Use Not Automated, Planned or Automated. | toolkits/devops |
| `AZURE_DEVOPS.BAD_DATE` | The … '…' is not a date | Use a date such as 2026-11-01 or 2026-11-01T09:00:00Z. | toolkits/devops |
| `AZURE_DEVOPS.BAD_DATES` | The end date … is before the start date … | Choose an end date on or after the start date. | toolkits/devops |
| `AZURE_DEVOPS.BAD_ID` | '…' is not a work item id<br>'…' is not a build id | Use whole numbers such as 101.<br>Use the build's id, a whole number such as 77. | toolkits/devops |
| `AZURE_DEVOPS.BAD_OUTCOME` | Unknown outcome '…' | Use passed, failed, blocked, notApplicable or active. | toolkits/devops |
| `AZURE_DEVOPS.BAD_PRIORITY` | Priority … is out of range | Use a whole number from 1 (highest) to 4 (lowest). | toolkits/devops |
| `AZURE_DEVOPS.BAD_REPOSITORY_ID` | '…' is not a repository id | Use the repository's id (a GUID) from List repositories. | toolkits/devops |
| `AZURE_DEVOPS.BAD_RESULTS` | The results need an outcome column and a result_id or test_point_id column | Columns: result_id, outcome, comment, duration_seconds, error_message. | toolkits/devops |
| `AZURE_DEVOPS.BAD_STAGE` | A stage name in stages_to_skip is empty | List the stage names as written in the YAML, e.g. ["DeployProd"]. | toolkits/devops |
| `AZURE_DEVOPS.CONFIRM_REQUIRED` | The test plan was not deleted because confirm is false<br>The pipeline was not deleted because confirm is false | Set confirm to true. | toolkits/devops |
| `AZURE_DEVOPS.MISSING_QUERY` | A query suite needs a query | Fill in a WIQL query. | toolkits/devops |
| `AZURE_DEVOPS.MISSING_REQUIREMENT` | A requirement suite needs requirement_id | Fill in the work item id. | toolkits/devops |
| `AZURE_DEVOPS.MISSING_TEST_CASES` | No test case ids given | Give one or more work item ids. | toolkits/devops |
| `AZURE_DEVOPS.NOTHING_TO_UPDATE` | No changes were given<br>Nothing to change<br>Neither an outcome nor a tester was given | Fill in at least one field to change.<br>Fill in at least one field.<br>Fill in name, parent_suite_id or query. | toolkits/devops |
| `AZURE_DEVOPS.NOT_ACTIVE` | Pull request … is …status… | Only active pull requests can be completed. | toolkits/devops |
| `AZURE_DEVOPS.NOT_FOUND` | Run … has no result for test point …test_point_id…<br>Pipeline … was not found | Check the test point id.<br>Check the id with List pipelines. | toolkits/devops |
| `AZURE_DEVOPS.NOT_YAML` | Pipeline … is a classic pipeline, not a YAML one | Only YAML pipelines can be pointed at another YAML file or repository. | toolkits/devops |
| `AZURE_DEVOPS.TESTER_NOT_FOUND` | No user matches the tester '…' | Use the person's sign-in email as shown in Azure DevOps. | toolkits/devops |

## AZURE_VM

| Code | What it means | What to do | Where |
|---|---|---|---|
| `AZURE_VM.AUTH_FAILED` | …<br>The sign-in answer has no access token | Check the tenant id, client id and the client secret in the vault (it may have expired).<br>Check the sign-in address and the app registration. | toolkits/microsoft |
| `AZURE_VM.BAD_CLIENT_ID` | '…' is not an application (client) id | Copy the Application (client) id, a GUID, from the app registration. | toolkits/microsoft |
| `AZURE_VM.BAD_CLOUD` | Unknown Azure cloud '…' | Use public, us_government or china. | toolkits/microsoft |
| `AZURE_VM.BAD_MODE` | Unknown stop mode '…' | Use deallocate or power_off. | toolkits/microsoft |
| `AZURE_VM.BAD_RESOURCE_GROUP` | '…' is not a resource group name | Resource group names have 1-90 letters, digits, - _ . ( ) and do not end in a dot. | toolkits/microsoft |
| `AZURE_VM.BAD_RESPONSE` | The next page is on another server (…)<br>Unexpected disk id '…'<br>… answered with a web page instead of JSON (often a proxy or sign-in page): … | Check the Resource Manager address.<br>Check the disk id: copy it from the disk's Properties in the Azure portal (it starts with /subscriptions/).<br>Check the Azure addresses, proxy and network. | toolkits/microsoft |
| `AZURE_VM.BAD_SUBSCRIPTION` | '…' is not a subscription id | Subscription ids are GUIDs, e.g. from the Subscriptions page of the Azure portal. | toolkits/microsoft |
| `AZURE_VM.BAD_TENANT` | '…' is not a tenant id | Use the directory (tenant) id, a GUID, or the tenant's domain such as contoso.onmicrosoft.com. | toolkits/microsoft |
| `AZURE_VM.BAD_URL` | Invalid URL: … | Check the Resource Manager address. | toolkits/microsoft |
| `AZURE_VM.NOT_MANAGED_DISK` | The OS disk of '…' is not a managed disk | Only managed disks can be snapshotted this way. | toolkits/microsoft |
| `AZURE_VM.NO_DISK` | …Give a VM name or a disk name, not both…No VM or disk named | Give vm_name to copy the VM's OS disk, or disk_name for a named disk. | toolkits/microsoft |
| `AZURE_VM.NO_SECRET` | The client secret from the vault is required | Map client_secret to a Credential. | toolkits/microsoft |
| `AZURE_VM.OPERATION_FAILED` | Azure reports the operation …<br>Azure reports the operation ……: … | Check the activity log in the Azure portal. | toolkits/microsoft |
| `AZURE_VM.TIMEOUT` | No answer from … within 60 s | Try again later. | toolkits/microsoft |
| `AZURE_VM.UNREACHABLE` | Cannot reach …: … | Check the network, proxy and the Azure addresses. | toolkits/microsoft |
| `AZURE_VM.WAIT_TIMEOUT` | Azure had not finished after … s | Raise timeout_seconds, or check the operation in the Azure portal. | toolkits/microsoft |

## BOOLEAN

| Code | What it means | What to do | Where |
|---|---|---|---|
| `BOOLEAN.BAD_OPERATOR` | Unknown operator '…' | Use equal or not_equal. | packages |
| `BOOLEAN.INVALID` | '…' is not a yes/no value | Use true/false, yes/no or 1/0. | packages |

## BOX

| Code | What it means | What to do | Where |
|---|---|---|---|
| `BOX.ALREADY_EXISTS` | An item named '…' is already in folder …<br>A file named '…' is already in folder … | Pick another name or turn on reuse_existing.<br>Turn on replace_existing to upload a new version.<br>Turn on overwrite to add the copy as a new version. | toolkits/enterprise |
| `BOX.BAD_ID` | Invalid … id '…'<br>The top-level folder cannot be deleted | Box ids are numbers; open the item in Box and copy the number at the end of the address. The root folder is 0.<br>Choose a sub-folder. | toolkits/enterprise |
| `BOX.BAD_ITEM_TYPE` | Item type must be file or folder, not '…' | Choose file or folder. | toolkits/enterprise |
| `BOX.FILE_EXISTS` | A file already exists at … | Choose another save_as path or turn on overwrite. | toolkits/enterprise |
| `BOX.NO_COLLABORATOR` | Give an email address or a group id | Fill email or group_id. | toolkits/enterprise |
| `BOX.UPLOAD_NOT_READY` | Box has not finished putting the file together | Try the upload again later. | toolkits/enterprise |

## BROWSER

| Code | What it means | What to do | Where |
|---|---|---|---|
| `BROWSER.BAD_ACTION` | Unknown element action '…' | Choose one of the actions the step offers. | packages-browser |
| `BROWSER.BAD_FUNCTION` | Invalid JavaScript function name '…' | Give a name such as calculateTotal or app.utils.calculateTotal. | packages-browser |
| `BROWSER.BAD_INDEX` | select_by_index needs a whole number | Give the option's position as a whole number, from 0. | packages-browser |
| `BROWSER.BAD_INPUT` | upload_file needs the file path(s) in value<br>Then wait for … needs wait_selector<br>Each column needs a name and an XPath | Fill in value with the file's path (several separated by \|).<br>Give the selector of the element to wait for.<br>For example {"Name": "td[1]", "Link": ".//a/@href"}. | packages-browser |
| `BROWSER.BAD_MATCH` | Property … has an unknown match rule '…' | Use one of … | packages-browser |
| `BROWSER.BAD_MODIFIER` | Unknown modifier key '…' | Use CTRL, SHIFT, ALT or WIN, joined with +. | packages-browser |
| `BROWSER.BAD_OBJECT` | The captured object has no …<br>No property is selected for the search<br>The index must be 0 or more, or -1 for the last match | Capture the element again, or search by properties.<br>Tick at least one property in the captured object, or search by CSS or XPath.<br>Set the index to 0 or more, or -1 for the last match. | packages-browser |
| `BROWSER.BAD_PORT` | The port must be between 1 and 65535 | Use a port between 1 and 65535 (Chrome's usual debugging port is 9222). | packages-browser |
| `BROWSER.BAD_RETURN` | The function returned '…', not a number<br>The function did not return a list<br>The function did not return a dictionary | Make the script's function return the type the step's Return as setting expects.<br>Choose one of the return types the step offers. | packages-browser |
| `BROWSER.BAD_WINDOW` | Unknown window source '…'<br>The window must be a dictionary such as {"source": "browser", "title": "..."}<br>Unknown window match rule '…' | Use browser, application or variable.<br>Give the window as a dictionary, for example {"source": "browser", "title": "Orders"}.<br>Use one of … | packages-browser |
| `BROWSER.BAD_XPATH` | An XPath of the columns or rows is not valid: … | Check row_xpath and each column's XPath. | packages-browser |
| `BROWSER.CONNECTOR_NOT_CONNECTED` | …No browser…The … browser… is not connected to ONEOFFICEAUTOMATION<br>Lost the connection to the browser: … | Open the browser with the ONEOFFICEAUTOMATION Browser Connector extension loaded (run: ooa browser-connector install), or use a browser the agent starts itself.<br>Check that the browser is still open and its OOA extension is turned on, then run the step again. | packages-browser |
| `BROWSER.DOWNLOAD_FAILED` | The server answered … for …<br>Downloading … failed: … | Check the address, and that the site lets the runner download the file (it may need a sign-in).<br>Check the address and the network. | packages-browser |
| `BROWSER.ERROR` | … failed: …<br>Checking a link failed: …<br>… | Check that the browser is still open and the page is the expected one, then run the step again.<br>Check the link's address and that the runner can reach it.<br>Check that the browser is still open and its OOA extension is turned on, then run the step again. | packages-browser |
| `BROWSER.FILE_ERROR` | Cannot save …: …<br>Cannot write …: …<br>Cannot read …: … | Check the folder is writable.<br>Check that the file exists, is not open in another program, and that the runner's account may read it.<br>Check that the screenshot's folder exists and that the runner's account may write there. | packages-browser |
| `BROWSER.FILE_EXISTS` | The file … already exists | Turn on overwrite, or choose another name. | packages-browser |
| `BROWSER.FILE_NOT_FOUND` | File not found: …<br>Cannot read the script file …: … | Check the path; the file may have been moved or renamed.<br>Check the path (a .js file in the workspace). | packages-browser |
| `BROWSER.FILE_TOO_LARGE` | Files over 50 MB in all cannot go through the browser connector | Use Open browser with the built-in browser for large uploads. | packages-browser |
| `BROWSER.HISTORY_TOO_SHORT` | The tab has only … page(s) to go back to, not … | Lower the steps, or turn off error_if_too_many. | packages-browser |
| `BROWSER.INDEX_OUT_OF_RANGE` | … element(s) match, so index … does not exist | Lower the index or loosen the properties. | packages-browser |
| `BROWSER.NOTHING_PICKED` | No element was picked<br>Capture was cancelled in the browser (Esc) | Click an element in the browser window before closing it.<br>Capture again and click the element instead of pressing Esc.<br>Click an element in the browser window. | packages-browser |
| `BROWSER.NOT_A_FRAME` | The captured element is not an iframe | Capture the iframe itself. | packages-browser |
| `BROWSER.NOT_A_TABLE` | … is not a table and contains no table rows | Capture the table itself (or an element inside it). | packages-browser |
| `BROWSER.NOT_FOUND` | No element matches … | Check the selector. | packages-browser |
| `BROWSER.NOT_INSTALLED` | The browser could not be started: …<br>Downloading … took longer than 15 minutes<br>Playwright could not download …: …exit code … | Use browser edge or chrome (installed), or allow Playwright to download Chromium.<br>Check the network or proxy, or use browser edge or chrome.<br>Allow the runner to reach playwright.azureedge.net (or set HTTPS_PROXY), or use browser edge or chrome, which are installed. | packages-browser |
| `BROWSER.NOT_LISTENING` | The browser started but did not open debugging port … within … ms<br>No browser answers on … | If this profile folder is already open in a browser without a debugging port, close that window, or choose another profile folder.<br>A browser started the usual way has no debugging port. Start it with open_for_automation, or start it yourself with --remote-debugging-port=9222 and a separate --user-data-dir.<br>Close other windows that use the profile …, or set keep_open to false. | packages-browser |
| `BROWSER.NOT_SUPPORTED` | This action is not available for a tab driven through the browser connector<br>Running a script inside an iframe is not available for a tab driven through the browser connector | Use the Capture element action, or open the browser with engine playwright for this agent.<br>Open the browser with engine playwright, or run the script on the main page. | packages-browser |
| `BROWSER.NOT_VISIBLE` | … has no position because it is not visible | Scroll the element into view or wait until it shows, then try again. | packages-browser |
| `BROWSER.NO_DOWNLOAD` | Clicking … did not start a download within … s | Check the element starts a download (not a new page or tab), or raise start_timeout_ms. | packages-browser |
| `BROWSER.NO_ELEMENT` | Give the captured object or a selector<br>Give the captured item or a selector<br>Give the captured Next button or its selector | Use Capture in OneAgentStudio, or type a selector.<br>Use Pick or Capture in OneAgentStudio, or type a selector.<br>Capture the element (Capture in the step), or fill in its selector. | packages-browser |
| `BROWSER.NO_FIELDS` | Name at least one field to read from each item | Fields: {"Name": "h3", "Price": ".price"} | packages-browser |
| `BROWSER.NO_TAB` | No matching tab among … open tab(s) | Check the tab number or title text. | packages-browser |
| `BROWSER.OBJECT_NOT_FOUND` | No element matches the captured object … within … ms | Open the step's captured object and loosen the properties (contains, wildcard), use Test to check it, or recapture. | packages-browser |
| `BROWSER.PAGE_TIMEOUT` | The page did not finish loading within … ms | Raise wait_for_page_ms, or set if_not_found to continue. | packages-browser |
| `BROWSER.PORT_IN_USE` | Port … is used by another program that is not a browser<br>Port … is used by another program | Choose another port.<br>Close that program, or set keep_open to false. | packages-browser |
| `BROWSER.SIGN_IN_FAILED` | The server refused the sign-in (…) | Check the user name and password, or the agent user's Windows access. | packages-browser |
| `BROWSER.STILL_VISIBLE` | … is still on the page after … ms | Raise timeout_ms. | packages-browser |
| `BROWSER.TIMEOUT` | … timed out: …<br>No element matches … within … ms<br>The page did not finish loading after the click | Check the selector matches an element on the page, or raise timeout_ms.<br>Raise timeout_ms.<br>Raise timeout_ms, or check the elements. | packages-browser |
| `BROWSER.VALUE_REQUIRED` | Give the script to run, or a script file<br>The action '…' needs a value<br>The action '…' needs a row and a column (1 for the first) | Fill in Script, or Script file.<br>Fill in Value for this action.<br>Fill in row and column, from 1. | packages-browser |
| `BROWSER.WINDOW_NOT_FOUND` | No open tab is titled '…'… or at ……<br>The browser at … has no open window<br>No open tab is titled '…'…… or at … | Choose the window again from the list; it may have been closed or navigated.<br>Open a window in that browser, or start it with an Open browser step first.<br>Open or attach the browser first (open, attach or open_for_automation), or check the variable holds the session name. | packages-browser |

## CALENDAR

| Code | What it means | What to do | Where |
|---|---|---|---|
| `CALENDAR.NOT_FOUND` | There is no holiday calendar '…' in One CommandCenter | Check the name under Manage > Schedules > Holiday calendars, or give the holidays and weekend on the step. | control-room |

## CHILD

| Code | What it means | What to do | Where |
|---|---|---|---|
| `CHILD.BAD_REPEAT` | repeat must be 1 or more, got … | Use 1 to run the child once. | runner |
| `CHILD.BAD_TIMEOUT` | timeout_seconds must be more than 0 | Leave it empty for no limit. | runner |
| `CHILD.DEPTH` | Child agent nesting is too deep | Keep child agent chains under … levels; check for recursion. | runner |
| `CHILD.OUTPUT_MISSING` | Child agent '…' has no output '…' | Outputs: …, …. | runner |

## CLI

| Code | What it means | What to do | Where |
|---|---|---|---|
| `CLI.BAD_INPUT` | A registration key is needed: this computer has no registration One CommandCenter accepts<br>--input expects key=value, got '…'<br>--seats is KIND=COUNT,KIND=COUNT, such as developer=2,unattended_runner=5 | Create one in One CommandCenter > Runners > Register a runner, and pass it with --key.<br>Write each input as --input name=value.<br>Write seats as KIND=COUNT pairs separated by commas, for example developer=2,unattended_runner=5. | cli |
| `CLI.USAGE` | Migrate from aa or uipath | Run ooa migrate aa <folder> or ooa migrate uipath <folder>. | cli |

## CLIPBOARD

| Code | What it means | What to do | Where |
|---|---|---|---|
| `CLIPBOARD.BUSY` | Clipboard unavailable: … | Close programs that hold the clipboard (some remote-desktop tools do), then run the step again. | packages |

## CODE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `CODE.BAD_CALL` | Name the command as toolkit/command, such as ooa.excel/read_range<br>Coded agents cannot start coded agents through ooa.call | Write the command as toolkit/command, for example ooa.excel/read_range.<br>Use a Run agent step for the other coded agent instead. | packages, runner |
| `CODE.BAD_ENTRY` | The function name '…' is not allowed | Use a Python function name: letters, digits and _, not starting with a digit. | packages |
| `CODE.ENDED` | The Python process ended (exit code …) before main returned | Read the run's log. | packages |
| `CODE.IO` | Lost the connection to the Python process: … | Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it. | packages |
| `CODE.NO_CODE` | Give the module's file or its code | Fill in either Module file or Code. | packages |
| `CODE.NO_RUNNER` | Toolkit commands can only be called while the agent runs | Call toolkit commands only from code that the agent runs. | packages |
| `CODE.TOOLKIT_NOT_LISTED` | The code called …, but … is not in the step's Toolkits it may call | Add … to Toolkits it may call. | packages |

## COMMAND

| Code | What it means | What to do | Where |
|---|---|---|---|
| `COMMAND.BAD_PATTERN` | wait_for is not a valid regular expression: … | Fix the expression, or set match_type to text. | packages |
| `COMMAND.EXITED` | '…' ended as it started<br>The program of command session '…' has ended (exit code …)<br>Cannot write to command session '…': the program has ended or stopped reading | Check the program and its arguments.<br>Read the session's output to see why, or open the session again. | packages |
| `COMMAND.NO_SESSION` | No command session '…' is open | Add an Open command session step with this session name before this step. | packages |

## COMMS

| Code | What it means | What to do | Where |
|---|---|---|---|
| `COMMS.EMPTY` | The messages file is empty<br>There are no messages | Check that the messages file has rows below its header line.<br>Check that the step gets messages to sort. | packages |
| `COMMS.NO_COLUMN` | Name at least one text column<br>The messages have no column '…' | Name the column that holds the message text.<br>Columns: … | packages |
| `COMMS.NO_MESSAGES` | Give the messages as a CSV file or a table | Fill in either Messages file (CSV) or Messages table. | packages |
| `COMMS.NO_RULES` | Give at least one queue with keywords<br>Queue '…' has no keywords | Add at least one queue with the keywords that send a message to it.<br>Add keywords to the queue the message names. | packages |
| `COMMS.NO_WORDS` | The messages have too few words in common to group | Lower min_word_count, or give more messages. | packages |
| `COMMS.TOO_LARGE` | At most 500,000 messages at a time | Send the messages in parts of at most 500,000. | packages |

## CONNECTION

| Code | What it means | What to do | Where |
|---|---|---|---|
| `CONNECTION.INVALID` | connections.json is not valid JSON: … | Fix the file in the workspace. | runner |
| `CONNECTION.NOT_FOUND` | There is no saved connection named '…' | Create it under Connections (One CommandCenter or OneAgentStudio), or choose another connection on the step. | runner |
| `CONNECTION.SIGN_IN_FAILED` | Cannot sign the sign-in request: …<br>Connection '…' could not reach its sign-in server: …<br>Connection '…' could not sign in (HTTP …)…message……: …message…… | Check the private key in the connection (PEM or the JSON key file as the service gave it).<br>Check the token address and that the runner can reach it.<br>Check the App id, the installation id and the key. | runner |
| `CONNECTION.SIGN_IN_INCOMPLETE` | The sign-in's private key: …<br>Connection '…': … | Put the key (PEM, or Google's JSON key file) in the vault and pick it in the connection.<br>… | runner |
| `CONNECTION.WRONG_TOOLKIT` | Connection '…' is for …, not … | Choose a connection made for …. | runner |

## CONNECTOR

| Code | What it means | What to do | Where |
|---|---|---|---|
| `CONNECTOR.AWS_SETTINGS` | AWS signing needs a region and a service | Set auth.region and auth.service (for example us-east-1 and execute-api). | packages |
| `CONNECTOR.BAD_BODY` | … takes form fields: give body as a dictionary | Give the body as a dictionary of form fields, for example {"name": "Ana"}. | packages |
| `CONNECTOR.BAD_DEFINITION` | … is not a connector definition<br>… is not valid JSON: … | Remove the file from the connectors folder, or make it again with Import connector. | packages |
| `CONNECTOR.BAD_JSON` | The file is not valid JSON: … | Correct the JSON (the message names where it breaks), or download the description again. | packages |
| `CONNECTOR.BAD_NAME` | Invalid connector name '…' | Use letters, digits, - and _ (up to 60 characters, starting with a letter or digit). | packages |
| `CONNECTOR.BAD_REF` | Reference … does not point to anything in the document | Fix the reference, or bundle external files into one document before importing. | packages |
| `CONNECTOR.BAD_REQUEST` | Cannot build the request: … | Check base_url, header names and header values. | packages |
| `CONNECTOR.BAD_YAML` | The file is not valid YAML: … | Correct the YAML (the message names where it breaks), or download the description again. | packages |
| `CONNECTOR.CREDENTIAL_REQUIRED` | credential must be a Credential from the vault | Map credential to a Credential variable; secrets are never typed into steps. | packages |
| `CONNECTOR.EMPTY_SPEC` | Send {text: <file content>, name?, server?}<br>The API description is empty<br>Give spec_file or spec_text | Choose the API description file again.<br>Give an OpenAPI or Swagger description (JSON or YAML) that is not empty.<br>Fill in either Spec file or Spec text. | cli, packages |
| `CONNECTOR.EXISTS` | A connector named '…' already exists | Choose another name, or allow replacing it. | packages |
| `CONNECTOR.FILE_NOT_FOUND` | Cannot read file for '…': …<br>… uploads a file; cannot read … | Give the path of an existing file.<br>Give the path of an existing file as body. | packages |
| `CONNECTOR.HTTP_` | …: … | … | packages |
| `CONNECTOR.IO` | Cannot create folder for …: …<br>Cannot list connectors: …<br>Cannot read …: … | Check that the output folder may be created and that the runner's account may write there.<br>Check that the connectors folder exists and that the runner's account may read it.<br>Check that the file exists, is not open in another program, and that the runner's account may read it. | packages |
| `CONNECTOR.MISSING_BODY` | … needs a request body (…contentType…) | Set body on this step. | packages |
| `CONNECTOR.MISSING_BODY_FIELD` | … body needs …, … | Add them to body. | packages |
| `CONNECTOR.MISSING_PARAMETER` | … needs …, …<br>… path still has a placeholder: … | Add them to parameters, e.g. {"… \(.*……": ...}.<br>Give every path parameter. | packages |
| `CONNECTOR.NOT_FOUND` | There is no connector named '…' | Check the connector's name in Manage connectors.<br>Import the API description first (Connectors in OneAgentStudio, or the import_connector action). | cli, packages |
| `CONNECTOR.NO_CREDENTIAL` | This operation needs … | Map credential to a vault Credential and set the non-secret fields in auth. | packages |
| `CONNECTOR.NO_OPERATIONS` | The API description has no operations | Check that the file lists paths or requests. | packages |
| `CONNECTOR.NO_SERVER` | Connector '…' has no absolute server URL…… (…)… | Set base_url on this step, or edit "server" in the connector definition. | packages |
| `CONNECTOR.OAUTH_FAILED` | The token endpoint refused the client credentials: …<br>The token endpoint answered without an access_token | Check the client id, client secret, scope and token URL.<br>Check the token URL. | packages |
| `CONNECTOR.SPEC_NOT_FOUND` | Cannot read …: … | Check the file path. | packages |
| `CONNECTOR.TIMEOUT` | No response within … s from … | Try again later or raise timeout_seconds. | packages |
| `CONNECTOR.UNKNOWN_FORMAT` | The file is not an OpenAPI, Swagger or Postman document | Use an OpenAPI 3.x or Swagger 2.0 document, or a Postman v2.1 collection export. | packages |
| `CONNECTOR.UNKNOWN_OPERATION` | Connector '…' has no operation '…' | …Use one of: …, …Use List operations to see the … operation ids. | packages |
| `CONNECTOR.UNKNOWN_PARAMETER` | … has no parameter '…' | …This operation takes no parameters; put request data in body.…Use one of: …, …. Request data goes in body. | packages |
| `CONNECTOR.UNKNOWN_SCHEME` | The connector has no auth scheme '…'<br>Operation refers to an unknown auth scheme '…' | Use one of: …auth…id…<br>Add the sign-in scheme the operation names to the connector's description, or choose another operation. | packages |
| `CONNECTOR.UNREACHABLE` | Cannot reach …: … | Check the server URL, proxy and network access. | packages |
| `CONNECTOR.UNSUPPORTED_AUTH` | Auth scheme '…id…' (…) is not supported | …note…note…Choose another scheme with auth.scheme, or add headers yourself. | packages |
| `CONNECTOR.UNSUPPORTED_VERSION` | Unsupported spec version …openapi…swagger… | Use OpenAPI 3.x or Swagger 2.0. | packages |
| `CONNECTOR.YAML_UNSUPPORTED` | YAML files cannot be read here because no YAML parser is installed<br>The installed YAML parser is not compatible: … | Convert the spec to JSON (most API tools can export JSON), or import it from OneAgentStudio, which can read YAML.<br>Convert the spec to JSON and import it again. | packages |

## CR

| Code | What it means | What to do | Where |
|---|---|---|---|
| `CR.AGENT_TAMPERED` | Version … of … was changed after check-in (checksum mismatch) | Check the audit trail, then check the agent in again from OneAgentStudio. | control-room |
| `CR.APPROVAL_REQUIRED` | Production versions need approval | Ask for approval; another person with the approval right decides. | control-room |
| `CR.BACKUP` | The backup failed: …<br>Reading … failed: …<br>… is not a ONEOFFICEAUTOMATION backup | Check the backup folder exists and the server may write to it.<br>Check that the database is running; then run the backup again.<br>Choose a backup file made by ONEOFFICEAUTOMATION (Administration > Settings > Data > Backups). | control-room |
| `CR.BAD_BUNDLE` | This is not an agent bundle<br>The bundle was made by a newer One CommandCenter<br>The bundle's checksum does not match: it was changed or damaged | Export one with Lifecycle > Export in One CommandCenter.<br>Update this One CommandCenter first.<br>Export the bundle again from the source One CommandCenter; do not change it on the way. | control-room |
| `CR.BAD_INPUT` | The address is a web address, for example https://ooa-dr.example.com<br>The Teams webhook is an https:// address<br>Set the Teams webhook first | Give the address people and runners use at this site.<br>Keep its address in the vault and give the reference.<br>Ask an administrator to set the push server in Settings. | cli, control-room, conversation, modules/ai-services, modules/coe, modules/document-automation, modules/governance, modules/insights, modules/integration, modules/low-code, modules/one-chat, modules/orchestration, modules/runner-management, modules/testing, packages |
| `CR.BAD_ITEM` | …: the field '…' is required<br>…: the field '…' must be a number, not '…'<br>…: the field '…' must be true or false, not '…' | Fill in the field the message names.<br>Give a number in the field the message names.<br>Give true or false in the field the message names. | control-room |
| `CR.BAD_JSON` | Request body is not valid JSON<br>Expected a JSON object<br>The callback body is not valid JSON | Send the request body as JSON (Content-Type: application/json).<br>Send a JSON object ({ ... }), not a list or a single value.<br>Send the callback body as JSON. | control-room |
| `CR.BAD_PATH` | Invalid agent repository path '…'<br>The copy keeps the kind of file: …<br>Invalid folder '…' | Use folders and a name ending in .flow.json, e.g. Finance/Invoice approval.flow.json<br>Keep the same ending in the copy's name (.flow.json for an agent, .form.json for a form).<br>Use names with letters, digits, spaces and . _ ( ) -, separated by /. | control-room |
| `CR.BAD_REGISTRATION_KEY` | The registration key is wrong, expired or already used | Create a new key in One CommandCenter > Runner Machines.<br>Make a new one with Connect to my computer, or Register a runner. | control-room |
| `CR.BAD_SCHEDULE` | No next run could be computed<br>kind must be every, daily, weekly, monthly or cron<br>every_minutes must be at least 1 | Correct the schedule in Manage > Scheduling and save it again.<br>Set the kind to every, daily, weekly, monthly or cron.<br>Set every_minutes to 1 or more. | control-room |
| `CR.BAD_TRIGGER` | Trigger … is not a runner trigger<br>Not a supported JSON path: …<br>… | Choose a trigger of the kind This runner watches in Manage > Triggers.<br>Examples: $.items[*], $.data.results, value<br>Correct the trigger's settings the message names. | control-room, modules/document-automation |
| `CR.CONFLICT` | Another user has the e-mail address …<br>There is already an agent at …<br>Runner … is the default runner of several people | Use another e-mail address, or change the other user's address first.<br>Choose another name or folder, or delete the agent that is there first.<br>Give them other default runners first, or keep it multi-user. | control-room, modules/ai-services, modules/governance, modules/low-code, modules/orchestration |
| `CR.COPY_TO_PRIVATE` | Copies are made in your Private workspace | Use Copy to Private on the agent in OneAgentStudio's Public tab, change it there, then check it in. | control-room |
| `CR.CSRF` | Missing request header X-OOA-Request | Send the header X-OOA-Request: 1 with every change request; the console does this itself, so reload the page. | control-room |
| `CR.DB_CONFIG` | A Windows account is DOMAIN\name (a gMSA ends in $): …<br>… may hold only letters, digits, '.', '-' and '_': …<br>Cannot write …: … | Write the account as DOMAIN\name (a gMSA ends in $).<br>Use only letters, digits, '.', '-' and '_'.<br>Check that you may write the configuration folder (run the command as an administrator). | cli, control-room |
| `CR.DB_COPY` | Copying … failed: …<br>Reading … failed: … | Check that the new database is reachable and empty, then copy again.<br>Check that the old database is reachable, then copy again. | cli |
| `CR.DB_ERROR` | Cannot remove schema …: …<br>Database error: …\n… | Check that the database user may drop the schema, and that nothing else is using it.<br>Check that the database is running and reachable; the server log has the full error. | control-room |
| `CR.DB_NEWER` | The database holds schema version … of module …, made by a newer release of it; this one knows version …<br>The database holds schema version …, made by a newer release; this release knows version … | Install the module's newer release, or restore a backup taken before the upgrade<br>Start the release that upgraded the database, or restore a backup taken before the upgrade | control-room |
| `CR.DB_SETUP` | Cannot create the database with …: …<br>Cannot give … access: … | The setup account needs the right to create databases (dbcreator on SQL Server)<br>Create the login … on the server first (a DBA task) | cli |
| `CR.DB_UNAVAILABLE` | Cannot open the database: …<br>Cannot create the test database: …<br>Cannot create schema …: … | Check the database settings (config\ooa-db.properties or --db-url), the user and password, and that the database server is running.<br>Check that the test database (H2) can be created in the data folder.<br>Check that the database user may create schemas (CREATE on the database). | control-room |
| `CR.DB_UPGRADE_NEEDED` | Module … needs schema version … (the database has …); the account … may not change tables<br>The database schema is at version … and this release needs …; the account … may not change tables | Run: ooa db migrate (it signs in with the setup account) | control-room |
| `CR.DELETED` | Agent … was deleted<br>A deleted agent is at … | Recover it in the agent repository (Deleted agents) to use it again.<br>Recover it first (Deleted agents), or use another name.<br>Recover or rename it first. | control-room |
| `CR.DEVICE_DISABLED` | That runner is disabled<br>The runner of the run is disabled | Enable the runner in Manage > Runners, or choose another runner.<br>Enable the runner in Manage > Runners, or let the run go on on another runner. | control-room |
| `CR.DR_NOT_READY` | This site cannot take over yet: … failed | … | cli |
| `CR.DR_RECORD` | Could not write the record …: … | Check the data folder can be written. | cli |
| `CR.DUPLICATE_ID` | Agent id '…' is already used by …path…<br>Agent id '…bot_id…' is already used here by …path… | Give this agent its own id: in OneAgentStudio, select no step and change Agent id under Properties. Copy and Duplicate give a copy its own id.<br>Give one of the two agents another id (the Agent id in its details), then import again. | control-room |
| `CR.DUPLICATE_REFERENCE` | The queue already has an item with reference '…' | Use a new reference, or update the item that has it.<br>Give each item its own reference, or add it with if_exists skip or update. | control-room |
| `CR.FILE_TYPE_BLOCKED` | Programs and scripts (.exe, .dll, .msi, .bat, .ps1 and similar) may not be uploaded | An administrator can allow them in Settings | control-room |
| `CR.FORBIDDEN` | You do not have permission to …<br>The access token lacks the scope …<br>Unknown webhook or wrong secret | Ask One CommandCenter administrator for the right role.<br>Make a token with the scope the message names, or ask an administrator to.<br>Check the webhook's address and secret in Manage > Triggers; copy them again if the secret was replaced. | control-room, modules/ai-services, modules/coe, modules/document-automation, modules/governance, modules/integration, modules/low-code, modules/one-chat |
| `CR.GIT` | The branch … is not in Git yet<br>In the SaaS service the Git remote is an https:// or SSH address<br>The Git server refused the push: …… (…)… | Sync to Git first, so the branch holds the agents.<br>Use the address your Git server shows for cloning.<br>Check the token's write access to the branch. | modules/integration |
| `CR.HTTPS_REQUIRED` | Runners connect to One CommandCenter only over HTTPS | Point the runner at the https:// address of One CommandCenter. | control-room |
| `CR.INTERNAL` | Cannot make the Teams package: …<br>… | Try again; if it keeps happening, an administrator can look in the server log. | modules/low-code |
| `CR.INVALID_BOT` | … validation problem(s): …message… | Fix the problems in OneAgentStudio (Check now lists them) before checking in.<br>Open the agent in the source One CommandCenter, fix the problems Check now lists, and export it again. | control-room |
| `CR.IN_USE` | … still owns …, …<br>The agent is used by …, …<br>Workflows name this agent by path: …, … | Hand those to someone else first, or disable the user instead.<br>Delete or change those first.<br>Change those workflows after moving. | control-room |
| `CR.IO` | Cannot store the file: …<br>The stored content of … is missing: …<br>Cannot save the template folders: … | Check that the server's data folder has space and that the service account may write there.<br>Upload the file again.<br>Check that the server's data folder has space and that the service account may read and write there. | control-room, modules/ai-services |
| `CR.IP_BLOCKED` | This address may not use One CommandCenter | Ask an administrator to add your network address to the allowed list (Administration > Policies > IP allow list). | control-room |
| `CR.LICENSE_CITIZEN` | A Citizen Developer seat does not include … (step …id…) | Ask a developer to check this agent in, or build it without that toolkit. | control-room |
| `CR.LICENSE_EXPIRED` | …<br>A licensed installation runs on Microsoft SQL Server; this one uses the built-in database<br>…evaluation_ended…The ONEOFFICEAUTOMATION evaluation has ended…The ONEOFFICEAUTOMATION license has expired | An administrator installs a license issued for this install id in Administration > Licenses<br>…<br>An administrator installs a license in Administration > Licenses | control-room |
| `CR.LICENSE_FEATURE` | … is not part of this license | Ask ONEOFFICEAUTOMATION for a license that includes …. | control-room |
| `CR.LICENSE_LIMIT` | All … … seats are in use<br>The evaluation allows …developer… developers, and both seats are in use<br>All …developer… Developer seats of the subscription are in use | Take a seat back from someone, or install a license with more seats<br>Install a license in Administration > Licenses<br>Take a seat back in Administration > Licenses, or ask ONEOFFICEAUTOMATION for more seats | control-room |
| `CR.LICENSE_REQUIRED` | … has no Developer or Citizen Developer license<br>… | An administrator assigns one in Administration > Licenses<br>An administrator adds or renews the license in Administration > Licenses. | control-room |
| `CR.LINK_EXPIRED` | This approval link has expired | Open the task in One CommandCenter (Workflows > Tasks). | control-room |
| `CR.LINK_INVALID` | This approval link is not valid | Open the task in One CommandCenter (Workflows > Tasks). | control-room |
| `CR.LOCKED` | Checked out by …locked_by… since …locked_at…<br>Only …locked_by… or an administrator can undo this check-out<br>Checked out by …locked_by… | Ask them to check it in, or an administrator to undo the check-out.<br>Ask the person who checked it out to check it in, or ask an administrator to undo the check-out.<br>Ask them to check it in first. | control-room |
| `CR.LOCKED_OUT` | Too many failed sign-ins; try again after …<br>Too many failed sign-ins; try again after …locked_until… | Wait …lockout_minutes… minutes, or ask an administrator to unlock your account. | control-room |
| `CR.LOGIN_FAILED` | Wrong user name or password<br>Unknown, expired or revoked API key<br>The current password is wrong | Check the user name and password; after several tries the account is locked for a while.<br>Make a new API key in Administration > Settings > API access and use it in place of the old one.<br>Type your current password again; Forgot password on the sign-in page resets it if you no longer know it. | control-room |
| `CR.MAIL_OWNER_GONE` | The person who set up this e-mail is no longer active | Set it up again as someone active. | modules/insights |
| `CR.MFA_EXPIRED` | The sign-in took too long<br>Too many wrong codes<br>This sign-in was finished already | Sign in again with your password. | control-room |
| `CR.MFA_SETUP_REQUIRED` | Set up two-step sign-in before continuing | Your administrator requires it for your account. | control-room |
| `CR.MFA_WRONG_CODE` | That code is not right | Enter the 6-digit code your authenticator app shows now, or a recovery code.<br>Enter the 6-digit code the authenticator app shows for ONEOFFICEAUTOMATION. | control-room |
| `CR.NOTICE_NOT_ACCEPTED` | Accept the notice on the sign-in page to sign in | Tick the notice on the sign-in page, then sign in. | control-room |
| `CR.NOTIFY_FAILED` | The notification service answered HTTP …<br>Cannot reach …: …<br>Interrupted | Check the channel's address and token in Administration > Settings > Approvals; the service may also be down.<br>Check that the server can reach the notification service (network, proxy, firewall).<br>Try again; if it keeps happening, an administrator can look in the server log. | control-room |
| `CR.NOT_AN_AGENT` | … is not an agent | Start workflows from Workflow; forms run inside workflows. | control-room |
| `CR.NOT_ASKED` | Nothing was asked of this runner | Press Check in My devices first, then let the runner answer.<br>Ask the runner for its logs or screen first (Manage > Runners). | control-room |
| `CR.NOT_CHECKED_OUT` | …locked_by…Check the agent out before checking in a new version…Checked out by …locked_by… | Use Agent check-out first, so two people never overwrite each other's work. | control-room |
| `CR.NOT_FOUND` | … not found<br>There is no user called …<br>Agent … has no version labelled '…' | Reload the page and choose it again; it may have been renamed, moved or deleted.<br>Check the user name in Administration > Users.<br>Check the version labels on the agent's Versions tab, or use production or latest. | control-room, conversation, modules/ai-services, modules/document-automation, modules/low-code |
| `CR.NOT_SERVERLESS` | The agent uses …, …, which serverless runs cannot use | Run it on a runner, or keep to web services and data toolkits (REST, SOAP, the cloud apps, Excel, PDF, JSON and the like). | control-room |
| `CR.NO_DEFAULT_RUNNER` | … has no default runner | Set one in Users (Default runner), or choose a runner.<br>Set one in Users, or choose a runner. | control-room |
| `CR.NO_PRODUCTION` | Agent … has no live production version | Set a live production version in the agent repository. | control-room |
| `CR.NO_RUNNER` | No runner of the pool is online | Start a runner of the pool, or pick another pool. | control-room |
| `CR.NO_RUNNER_PACKAGE` | This One CommandCenter has no setup package to make a runner installer from | Put ooa-setup-<version>.zip in the data folder's downloads folder, or give people the setup package and the key. | control-room |
| `CR.PASSWORD_CHANGE_REQUIRED` | Change your password before continuing | Change your password first (the console asks for it after sign-in). | control-room |
| `CR.POLICY_VIOLATION` | … code analysis error(s): …message… | Fix them, or ask an administrator about the code analysis policy '…name…'. | modules/governance |
| `CR.PORT_IN_USE` | Port … is already in use; another One CommandCenter may still be running | Stop it, or start with --port …. | control-room |
| `CR.PROMOTE_FAILED` | Cannot reach …: … | Check the address and that the other One CommandCenter is running. | control-room |
| `CR.REGISTRATION_CLOSED` | New runners cannot register now | An administrator opens registrations in Settings > Runner Machines. | control-room |
| `CR.RUNNER_OFFLINE` | The runner …name… is offline, so the run was not started | Start the runner, or choose another runner or a pool. | control-room |
| `CR.SCALE_FAILED` | Signing for AWS failed: …<br>Azure gave no access token<br>… answered HTTP …: … | Check the pool's cloud settings and credentials in Manage > Runners > Pools; the message has the cloud's answer.<br>Try again; if it keeps happening, an administrator can look in the server log. | modules/runner-management |
| `CR.SCREEN_OFF` | Screen pictures of runners are off | An administrator can allow them in Settings (Runners). | control-room |
| `CR.SIEM_FAILED` | The SIEM at … did not accept the entry: … | Check the address, the token and the sign-in type | control-room |
| `CR.STORAGE` | Cannot keep the jar: … | Check that the server's data folder has space and that the service account may write there. | control-room |
| `CR.SYSLOG_FAILED` | The syslog collector at … could not be reached: … | Check the host, port, protocol and firewall | control-room |
| `CR.TARGET_NOT_ALLOWED` | Agents may be promoted only to the One CommandCenters listed in Settings | Allowed: …, … | control-room |
| `CR.TLS_CONFIG` | Cannot read …: …<br>The certificate file … named in … does not exist<br>The environment variable … is not set | Correct the HTTPS settings (ooa-tls.properties: keystore, its password and alias) and restart the service.<br>Run: ooa tls configure --pfx FILE<br>Set the environment variable for the service's account, then restart the service. | control-room |
| `CR.TOOLKIT_DISABLED` | The toolkit '…' is switched off in this One CommandCenter (step …id…)<br>The toolkit '…' is switched off in this One CommandCenter (its code in step …id… may call it) | Ask an administrator to switch it on under Toolkits, or remove the step.<br>Ask an administrator to switch it on under Toolkits, or take it out of the step's Toolkits it may call. | control-room |
| `CR.TOO_LARGE` | A shared value is at most 100000 characters<br>The request is larger than the Largest upload setting allows<br>The upload is larger than the Largest upload setting allows | Keep shared values under 100,000 characters; put larger data in a file in the repository instead.<br>Raise Settings > Largest upload, or send a smaller file<br>Raise Settings > Largest upload, or send fewer files at once | control-room, modules/ai-services, modules/document-automation |
| `CR.TRIGGER_DISABLED` | This webhook is disabled | Enable the trigger in Manage > Triggers. | control-room |
| `CR.TRIGGER_SIGN_IN` | Microsoft sign-in failed: … | Sign in to Microsoft again in the trigger's connection, or check that the app may still read the mailbox. | control-room |
| `CR.UNAUTHENTICATED` | Please sign in<br>MCP sign-in with OAuth is not set up<br>The access token was refused: … | Sign in again; your session may have ended.<br>An administrator sets the MCP audience in Settings > AI assistants.<br>Get a new access token (Administration > Settings > API access) and use it in place of the old one. | control-room, modules/document-automation, modules/integration, modules/low-code |
| `CR.USER_DISABLED` | This account is disabled | Contact your administrator.<br>Contact your One CommandCenter administrator. | control-room |
| `CR.VAULT_ERROR` | Encryption failed | Check the vault key source in Administration > Settings > Security; the key must be the same one the vault was made with. | control-room |
| `CR.VAULT_KEY` | The vault master key must be 32 bytes<br>Cannot read or create the vault key: …<br>The vault master key must be 32 bytes (Base64) | Check the vault key source in Administration > Settings > Security; the key must be the same one the vault was made with.<br>Check that the service account may read and write the vault key file in the data folder.<br>Start One CommandCenter in a command window (not as a service), or use another key source | control-room |
| `CR.VAULT_TAMPERED` | A stored credential could not be decrypted (wrong master key or modified data) | Check OOA_MASTER_KEY is the key the vault was created with. | control-room |
| `CR.WEAK_PASSWORD` | …<br>A new password can be changed again after … day……s….<br>Choose a different password from the current one. | Choose a password that meets the rule the message names (Administration > Policies shows the password rules).<br>Ask an administrator to reset it if you must change it now.<br>Choose a new password that differs from the current one. | control-room |

## CREDMAN

| Code | What it means | What to do | Where |
|---|---|---|---|
| `CREDMAN.BAD_ENCODING` | Unknown encoding '…' | Use auto, utf16 or utf8.<br>Use utf16 or utf8. | packages-windows |
| `CREDMAN.BAD_FILTER` | The filter may only have * at the end | For example MyApp/*. | packages-windows |
| `CREDMAN.BAD_PERSIST` | Unknown persist value '…' | Use session, local_machine or enterprise. | packages-windows |
| `CREDMAN.BAD_TARGET` | The target name is too long | Shorten the target name. | packages-windows |
| `CREDMAN.FAILED` | … failed for '…' (Windows error …) | Check that Windows Credential Manager may be used by the runner's account; the message has Windows' error number. | packages-windows |
| `CREDMAN.INVALID` | Windows rejected the credential for '…' (error …) | Check the target name and user name. | packages-windows |
| `CREDMAN.NOT_FOUND` | No generic credential named '…' in Credential Manager | Check the target name, or list targets to see what is stored for this account. | packages-windows |
| `CREDMAN.NO_PROFILE` | Credential Manager is not available for this logon session | Run the agent under a user account with a loaded profile. | packages-windows |
| `CREDMAN.NO_SECRET` | The secret must be a Credential value | Map password to a Credential variable or vault entry. | packages-windows |
| `CREDMAN.NO_TARGET` | Give the credential's target name | For example MyApp/service-account. | packages-windows |
| `CREDMAN.TOO_LONG` | The secret is longer than Credential Manager allows (… bytes) | Store a shorter secret, or keep it in the vault in One CommandCenter instead. | packages-windows |

## CRYPTO

| Code | What it means | What to do | Where |
|---|---|---|---|
| `CRYPTO.BAD_ALGORITHM` | Unknown HMAC algorithm '…' | Use SHA-256, SHA-384, SHA-512 or SHA-1. | packages |
| `CRYPTO.BAD_FORMAT` | Unknown output format '…' | Use hex, hex_upper or base64. | packages |
| `CRYPTO.BAD_KEY` | The key is not valid Base64 | Turn off key_is_base64 or check the key. | packages |
| `CRYPTO.BAD_KEY_SIZE` | Key size must be 2048, 3072 or 4096 bits, not … | Use 3072 or 4096. | packages |
| `CRYPTO.BAD_OPTION` | Unknown if_keys_exist '…' | Use error, rotate or overwrite. | packages |
| `CRYPTO.KEY_EXISTS` | Keys already exist in … | Set if_keys_exist to rotate (keeps a backup) or overwrite, or choose another folder. | packages |
| `CRYPTO.KEY_FORMAT` | Use an unencrypted PKCS#8 private key (BEGIN PRIVATE KEY) | Convert with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem; store the file in a protected folder. | packages |
| `CRYPTO.NOT_ENCRYPTED` | The data was not encrypted by this toolkit<br>Not valid Base64 text | Use data produced by Encrypt text or Encrypt file.<br>Give the text the Encrypt step made (Base64), unchanged. | packages |
| `CRYPTO.TOO_LONG` | Text is too long for RSA | Encrypt the text with AES and the AES passphrase with RSA. | packages |
| `CRYPTO.WRONG_KEY` | Decryption failed: wrong passphrase or the data was changed<br>Decryption failed: wrong key or damaged data | Check the passphrase in the vault.<br>Use the same key (or password) the data was encrypted with, and check that the data was not changed. | packages |

## CSV

| Code | What it means | What to do | Where |
|---|---|---|---|
| `CSV.BAD_DELIMITER` | Delimiter must be one character | Use , ; \| tab or regional. | packages |
| `CSV.BAD_OPTION` | skip_lines cannot be negative<br>Unknown trim option '…' | Use 0 or more.<br>Use none, leading, trailing or both. | packages |
| `CSV.UNCLOSED_QUOTE` | A quoted field is not closed | Check for a missing closing quote. | packages |

## CURRENCY

| Code | What it means | What to do | Where |
|---|---|---|---|
| `CURRENCY.BAD_CODE` | '…' is not a three-letter currency code | Use ISO 4217 codes such as EUR, USD or INR. | packages |
| `CURRENCY.BAD_DATE` | The date … is in the future<br>Cannot read … as a date | Give today's date or an earlier one.<br>Use a date such as 2026-03-31. | packages |
| `CURRENCY.BAD_DECIMALS` | decimals must be between 0 and 12 | Set decimals between 0 and 12. | packages |
| `CURRENCY.BAD_PATH` | Cannot read the path '…' | Use a form like data.rates or $.results[0].rates. | packages |
| `CURRENCY.BAD_RATE` | Rate for … is not a number<br>Rate for … must be greater than 0 | Give each rate as a number, for example 1.08.<br>Give each rate as a number greater than 0. | packages |
| `CURRENCY.BAD_RESPONSE` | The ECB answer contained no exchange rates<br>No rates object at '…$…' in the answer<br>The rates object has no numeric rates | Check the url returns the eurofxref XML.<br>Set rates_path to the field holding {"CODE": rate, ...}.<br>Check rates_path. | packages |
| `CURRENCY.BAD_SOURCE` | Unknown rate source '…' | Use ecb, json or fixed. | packages |
| `CURRENCY.DATE_NOT_SUPPORTED` | A past date works only with the ecb source | Use source ecb, or leave date empty. | packages |
| `CURRENCY.NO_AMOUNT` | Give an amount to convert | Fill in the amount to convert. | packages |
| `CURRENCY.NO_BASE` | The answer does not say which currency the rates are against<br>The fixed source needs base_currency | Set base_currency.<br>Name the currency your rates are quoted against. | packages |
| `CURRENCY.NO_RATES` | The fixed source needs a rates Dictionary | … | packages |
| `CURRENCY.NO_RATE_FOR_DATE` | The ECB has no rates on or shortly before … (its file starts …) | ECB reference rates start on 1999-01-04; choose a later date. | packages |
| `CURRENCY.NO_URL` | The json source needs a url | Give the address of the rates service. | packages |
| `CURRENCY.UNKNOWN_CURRENCY` | No rate for … in the … rates | Use List currencies to see the available codes. | packages |

## DATA

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DATA.BAD_FILTER` | Unknown comparison '…' | Use =, !=, >, >=, <, <=, contains, starts_with, empty or not_empty.<br>Use one of the comparisons the step offers (equals, not_equals, contains, greater_than and so on). | modules/low-code, packages |
| `DATA.BAD_NAME` | The name '…' is not allowed | Use letters, digits, spaces, - and _ in the name. | packages |
| `DATA.BAD_OP` | Unknown operation … | Choose one of the operations the step offers. | packages |
| `DATA.BAD_PATH` | Give the file's path in the bucket (up to 400 characters)<br>The path '…' is not allowed<br>The path leaves the bucket | Give a path inside the bucket, without .. or a leading /.<br>Use letters, digits, spaces and _ . ( ) @ + -, with / between folders. | modules/low-code, packages |
| `DATA.BAD_VALUE` | … is a number, not '…'<br>… is yes or no, not '…'<br>… is a date (yyyy-MM-dd), not '…' | Give a value of the type the message names. | modules/low-code |
| `DATA.DUPLICATE` | Another record already has …name… … | Record …id… | modules/low-code |
| `DATA.EXISTS` | The bucket …name… already has …<br>The bucket already has …path… | Turn on Replace, or choose another path.<br>Choose another path, or delete the file that is there first. | modules/low-code, packages |
| `DATA.FULL` | The entity …name… has … records, the most it can hold | Delete records you no longer need, or use another entity. | modules/low-code |
| `DATA.IO` | Cannot save the file: …<br>Cannot read the file: …<br>Cannot delete the file: … | Check that the server's data folder has space and that the service account may read and write there.<br>Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it. | modules/low-code, packages |
| `DATA.MISSING` | Give the … | Fill in what the message names. | packages |
| `DATA.NOT_FOUND` | No record … in …name…<br>The bucket …name… has no file …<br>No record … | Check the record's id or the file's path; it may have been deleted.<br>Check the record's id; it may have been deleted.<br>Check the file's path in the bucket. | modules/low-code, packages |
| `DATA.NO_FIELD` | The entity …name… has no field '…'<br>Cannot sort by '…': no such field | Fields: …name…<br>Use a field the entity has, or add it in Build > Apps > Data. | modules/low-code |
| `DATA.REQUIRED` | The field …name… is required | Fill in the field the message names. | modules/low-code |
| `DATA.TOO_LARGE` | A record is up to 100,000 characters<br>Files in the bucket …name… are up to …max_mb… MB<br>Files of up to 200 MB go to a bucket | Store less in one record or file, or put large files in a share.<br>Store files larger than 200 MB in a share the runner reads, not in a bucket. | modules/low-code, packages |

## DATABRICKS

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DATABRICKS.AMBIGUOUS_NAME` | … …s are named '…' | Use the …'s id. | toolkits/cloud-data |
| `DATABRICKS.BAD_ID` | Invalid … id '…'<br>Give the job<br>Give the cluster | A … id is a number, shown in the address of its page.<br>Copy the id from the …'s details page.<br>Its name or id (see List jobs). | toolkits/cloud-data |
| `DATABRICKS.BAD_PATH` | A remote path is required<br>Remote path must not contain .. | Example: /Volumes/main/finance/inbox/report.csv or dbfs:/FileStore/report.csv.<br>Write the full path. | toolkits/cloud-data |
| `DATABRICKS.BAD_RESPONSE` | Unexpected result link from Databricks<br>Databricks did not return an upload handle for … | Report this with the run id.<br>Check workspace_url and that the token may write to DBFS. | toolkits/cloud-data |
| `DATABRICKS.CLUSTER_FAILED` | Cluster … did not start; its state is … | Check the cluster's event log in Databricks. | toolkits/cloud-data |
| `DATABRICKS.FILE_EXISTS` | A file already exists at … | Choose another save_as, delete the file first, or turn on overwrite. | toolkits/cloud-data |
| `DATABRICKS.NOT_FOUND` | No … named '…' | Check the name, or use the …'s id. | toolkits/cloud-data |
| `DATABRICKS.NO_SQL` | The SQL statement is empty | Write the SQL to run. | toolkits/cloud-data |
| `DATABRICKS.RUN_FAILED` | Job run … ended with …result_state…: …state_message… | Open the run page for details: …run_page_url… | toolkits/cloud-data |
| `DATABRICKS.STATEMENT_CANCELED` | The SQL statement was … | Run it again. | toolkits/cloud-data |
| `DATABRICKS.STATEMENT_FAILED` | The SQL statement failed: … | Check the SQL, table names and your permissions on the warehouse. | toolkits/cloud-data |
| `DATABRICKS.STILL_RUNNING` | The statement is still running | Wait, then try again, or check it with Get statement status. | toolkits/cloud-data |
| `DATABRICKS.WAIT_TIMEOUT` | Job run … is still …life_cycle_state… after … s<br>Cluster … is still … after … s | Raise timeout_seconds, or check the run later with Get job run: …run_page_url…<br>Raise timeout_seconds, or check the cluster in Databricks. | toolkits/cloud-data |

## DATATABLE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DATATABLE.NOT_A_NUMBER` | Row … of column '…' is '…', not a number | Fix the value, or set if_not_number to skip to leave such cells out. | packages |

## DATETIME

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DATETIME.BAD_AMOUNT` | Add to date: … must be a whole number, got …<br>Add working days: days must be a whole number, got …<br>At most … working days can be added at once | Round the amount first, or use a smaller unit (e.g. days).<br>Round the number first.<br>Add the working days in smaller steps. | packages |
| `DATETIME.BAD_HOLIDAY` | Holiday '…' is not a date | Write holidays as yyyy-MM-dd, e.g. 2026-12-25. | packages |
| `DATETIME.BAD_PART` | Unknown date part '…' | Use year, month, day, hour, minute, second, day_of_week, week_of_year, quarter ... | packages |
| `DATETIME.BAD_PATTERN` | Unknown standard format '…'<br>Invalid date pattern '…': …<br>Give a pattern or a standard format | Use one of …, ….<br>Use letters like yyyy, MM, dd, HH, mm, ss.<br>Fill in 'pattern', e.g. dd/MM/yyyy. | packages |
| `DATETIME.BAD_UNIT` | Unknown unit '…'<br>Unknown period '…' | Use one of …, ….<br>Use day, week, month or year. | packages |
| `DATETIME.BAD_WEEKEND` | The weekend cannot be every day of the week<br>'…' is not a day of the week | Leave at least one working day, e.g. weekend SAT,SUN.<br>Write the weekend as days such as SAT,SUN or FRI,SAT, or NONE when every day is a working day. | packages |
| `DATETIME.BAD_ZONE` | Unknown time zone '…' | Use an IANA name such as Europe/London or Asia/Kolkata. | packages |
| `DATETIME.CALENDAR_NOT_FOUND` | There is no holiday calendar '…' in One CommandCenter | Check the name under Manage > Schedules > Holiday calendars (names are exact), or give the holidays and weekend on the step. | packages |
| `DATETIME.MISSING` | Input '…' has no date<br>Give a date or a year<br>Give the number of working days | Give a date such as 2026-10-18 or 2026-10-18T14:30:00.<br>Fill in 'value' or 'year'.<br>Fill in 'days', e.g. 5 or -2. | packages |
| `DATETIME.NO_COMMAND_CENTER` | The holiday calendar '…' is kept in One CommandCenter, and this run is not connected to it<br>This One CommandCenter is too old to give holiday calendars to agents | Run the agent from One CommandCenter, or leave the calendar empty and give the holidays and weekend on the step.<br>Update One CommandCenter, or give the holidays and weekend on the step. | packages |
| `DATETIME.PARSE_FAILED` | Cannot read '…' as …<br>Cannot read '…' with pattern '…' | Check the text matches the chosen standard format.<br>Check the pattern matches the text exactly. | packages |

## DB

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DB.BAD_DELIMITER` | The delimiter must be one character, got '…' | Use , ; \| or tab. | packages |
| `DB.BAD_DRIVER` | Invalid driver class '…' | Give the full class name, e.g. org.postgresql.Driver. | packages |
| `DB.BAD_FILE` | A quoted field that starts on line … is not closed | Check for a missing closing quote. | packages |
| `DB.BAD_INPUT` | Give a jdbc_url or a database_type<br>… needs a database file<br>odbc needs a data source | Choose the database type and fill in its server or file, or type the full JDBC URL.<br>Fill in file with the path of the database file.<br>Fill in database with a DSN name (see Windows' ODBC Data Sources) or an ODBC connection string. | packages |
| `DB.BAD_NAME` | Invalid … name '…' | Use letters, digits and underscores, optionally schema.name. | packages |
| `DB.BAD_PARAMETER` | Parameter … has direction '…direction…'<br>Unknown parameter type '…' | Use in, out or inout.<br>Use one of …, …. | packages |
| `DB.COLUMN_MISMATCH` | columns lists … names but the Data Table has … columns<br>Line … has … fields but … columns are inserted | Give one database column per Data Table column.<br>Give one database column per field in columns, or check the delimiter. | packages |
| `DB.CONNECT_FAILED` | Cannot connect: … | Check the URL, network access and credentials. | packages |
| `DB.EXPORT_FAILED` | Cannot write …: … | Check the folder exists and the file is not open. | packages |
| `DB.FILE_EXISTS` | File already exists: … | Set overwrite to true or choose another name. | packages |
| `DB.NO_COLUMNS` | The Data Table has no columns<br>No database columns | Give a Data Table with at least one column.<br>…The file has no header line at start_row; fill in columns.…Fill in columns with the database column of each field. | packages |
| `DB.NO_DRIVER` | JDBC driver … is not installed on the runner<br>No JDBC driver found for …:… | Ask an administrator to install the database driver on the runner.<br>The runner includes drivers for SQL Server, PostgreSQL, MySQL/MariaDB, Oracle, SQLite, H2 and Access, and reaches any ODBC data source on Windows (database_type odbc); for another database ask an administrator to put its driver JAR in the runner's lib folder. | packages |
| `DB.NO_RESULT` | The procedure … returned no rows to export | Choose a procedure that ends with a SELECT, or use Run stored procedure. | packages |
| `DB.READ_ONLY` | The session was opened read-only, so …this statement…procedure…stored procedures (which may change data)… cannot run…: only SELECT, WITH, VALUES, SHOW, DESCRIBE and EXPLAIN are allowed…… | Connect again with read_only off to change data. | packages |
| `DB.SQL_ERROR` | … (SQLState …)…… | Check the SQL and that parameters match the ? placeholders. | packages |
| `DB.TABLE_NOT_FOUND` | Table '…' was not found | Use List tables to see the exact names. | packages |

## DEBUG

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DEBUG.BAD_COMMAND` | Unknown debug command '…' | Use continue, stepInto, stepOver or stepOut. | cli |
| `DEBUG.NOT_STOPPED` | The run is not stopped at a step<br>Variables can be changed only while the run is stopped at a step | Wait until the run stops at a breakpoint or step, then try again.<br>Pause the run at a step first, then change the variable. | cli |
| `DEBUG.READ_ONLY` | Credentials cannot be changed while debugging | Stop debugging to change credentials. | runner |

## DELAY

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DELAY.BAD_RANGE` | random_max (…) is smaller than seconds (…seconds…) | Give a random_max at least as large as seconds. | runner |
| `DELAY.BAD_UNIT` | Unknown delay unit '…' | Use seconds, milliseconds or minutes. | runner |
| `DELAY.BAD_VALUE` | A delay cannot be negative | Use 0 or more. | runner |

## DESKTOP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DESKTOP.BAD_AREA` | Width and height must be greater than 0 | Check the region size. | packages |
| `DESKTOP.BAD_BUTTON` | Button must be left, right or middle, not '…' | Use left, right or middle. | packages |
| `DESKTOP.BAD_DURATION` | duration_ms must be 0 or more, not … | Use 0 to jump, or e.g. 500 to glide. | packages |
| `DESKTOP.BAD_FILE_TYPE` | Invalid file type '…' | List extensions such as pdf, xlsx or *.csv. | packages |
| `DESKTOP.BAD_KEY` | Unknown key '…' in '…'<br>No key given<br>Unknown key '…' | Use names like ENTER, TAB, ESC, F5, CTRL+S, ALT+F4.<br>Fill in the key, e.g. F9.<br>Use a name like F9, ENTER, ESC, SPACE or a letter. | packages |
| `DESKTOP.BAD_OPTION` | Unknown … '…'<br>The window size needs both width and height<br>The window size must be at least 50 x 50 pixels | Use one of: …, ….<br>Fill in both, or leave both empty to keep the window's size.<br>Use the size Pick on screen filled in. | packages |
| `DESKTOP.BAD_POINT` | The starting point needs both from_x and from_y | Fill in both, or leave both empty to start where the pointer is. | packages |
| `DESKTOP.CANCELLED` | The user cancelled the password prompt, or it timed out | Type the password when the prompt opens, or raise the step's time limit. | packages |
| `DESKTOP.DIALOG_FAILED` | … | Make sure the runner has an unlocked desktop session where the dialog can open. | packages |
| `DESKTOP.FAILED` | Cannot set …: …<br>Waiting for input failed: …<br>Cannot start the input block: … | Make sure the runner has an unlocked desktop session, then run the step again; the message has Windows' answer. | packages |
| `DESKTOP.INPUT_TIMEOUT` | Nothing was pressed or clicked within … s | Raise timeout_seconds, or turn off fail_on_timeout. | packages |
| `DESKTOP.KEY_STATE_UNCHANGED` | Windows did not turn … on… off… | The desktop may be locked, or another program holds the keyboard; try again with the desktop unlocked. | packages |
| `DESKTOP.NEEDS_ADMIN` | Windows did not allow blocking the mouse and keyboard | Run the agent as administrator, or leave this step out. | packages |
| `DESKTOP.NOTHING_TO_TYPE` | Neither text nor a credential was given | Fill in the text, or pick a credential from the vault. | packages |
| `DESKTOP.NOT_A_CREDENTIAL` | The credential input needs a vault credential, not plain text | Pick a credential attribute from the vault, or use the text input. | packages |
| `DESKTOP.NO_DISPLAY` | This runner has no screen | Run the agent on a runner with a signed-in desktop. | packages |
| `DESKTOP.NO_POINTER` | The mouse position cannot be read | The desktop may be locked; unlock it and try again. | packages |
| `DESKTOP.NO_SESSION` | No desktop session to open files in<br>This action needs a signed-in desktop session<br>Cannot control the desktop: … | Run this agent on a runner with a signed-in desktop (attended, or unattended with auto-login).<br>Run the agent attended, or unattended on a runner with auto-login enabled.<br>Check the session is unlocked. | packages |
| `DESKTOP.NO_TEXT` | The callout needs a text | Fill in the text. | packages |
| `DESKTOP.NO_WINDOW` | Coordinates relative to a window need the window input<br>A window size needs the window input | Fill in window, or use relative_to screen.<br>Fill in window, or leave the window size empty. | packages |
| `DESKTOP.UNTYPABLE` | Character … of the credential cannot be typed as a keystroke<br>Cannot type '…' as a keystroke<br>…Character … of the credential cannot be typed on this keyboard…Cannot type '…' on this keyboard | Credentials are typed key by key; use characters found on a US keyboard.<br>Use method paste for this text.<br>…Credentials are typed key by key; use characters found on a US keyboard.…Use method paste for this text. | packages |
| `DESKTOP.WINDOW_NOT_FOUND` | Cannot give window '…' the size … x …<br>No visible window matches '…' | Check the window is open (Window > List windows); some windows have a fixed size: leave the size empty for those.<br>Use Window > List windows to see the exact titles. | packages |
| `DESKTOP.WINDOW_UNAVAILABLE` | Choosing a window needs a Windows runner with the Window toolkit installed<br>Cannot read the position of window '…' | Leave window empty to use the window in front.<br>Use relative_to screen. | packages |

## DESKTOP_UI

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DESKTOP_UI.BAD_ACTION` | Unknown object action '…' | Use one of …, …. | packages-windows |
| `DESKTOP_UI.BAD_INPUT` | Give the window title or its program<br>Give the control's name, role or path | Fill in the window's title or its program.<br>Give a control's name, role or path; Capture in the step fills them in. | packages-windows |
| `DESKTOP_UI.BAD_OBJECT` | … | Capture the object again with Pick object, or fix the object dictionary. | packages-windows |
| `DESKTOP_UI.BAD_OPTION` | levels must be 1 to 50 | Go up 1 to 50 levels. | packages-windows |
| `DESKTOP_UI.BRIDGE_FAILED` | The UI Automation helper did not start…<br>Cannot start the UI Automation helper: … | Check that Windows PowerShell 5.1 and .NET Framework 4 are available on this machine.<br>Check that powershell.exe is on the PATH (Windows PowerShell 5.1). | packages-windows |
| `DESKTOP_UI.BRIDGE_STOPPED` | The UI Automation helper has stopped…<br>The UI Automation helper stopped while working… | Run the step again. | packages-windows |
| `DESKTOP_UI.BRIDGE_TIMEOUT` | The application did not answer UI Automation within … s | The application may be busy or not responding; wait for it or raise the timeout. | packages-windows |
| `DESKTOP_UI.NOT_IN_BACKGROUND` | A … click needs the mouse, which Run in background does not use<br>Focus brings the window to the front, which Run in background does not do<br>This … cannot be used without the mouse and keyboard: … | Use a left click, or turn off Run in background.<br>Turn off Run in background.<br>Turn off Run in background for this step (the window will then come to the front). | packages-windows |
| `DESKTOP_UI.NO_ITEM` | Give the item text or item_index to select | Fill in the item's text, or item_index. | packages-windows |
| `DESKTOP_UI.NO_PROPERTY` | Give the property to read | For example Name, Value or IsEnabled. | packages-windows |
| `DESKTOP_UI.OBJECT_NOT_FOUND` | The desktop object … was not found within … s<br>No open window matches …'…'…<br>No control ……named '…' ……with role '…' …in the window | Check the application window is open, or capture the object again and review which properties are used.<br>Open the application first, or check the title.<br>Use Find legacy objects to see the names and roles the application gives its controls. | packages-windows |
| `DESKTOP_UI.UNKNOWN_PROPERTY` | The object has no property '…' | Available: …, …. | packages-windows |
| `DESKTOP_UI.WAIT_TIMEOUT` | The desktop object …wait_appear… did not appear… did not disappear… within … s<br>The desktop object … did not appear… did not disappear… within … s | Raise timeout_seconds, or use Wait for object with fail_on_timeout false to branch instead.<br>Raise timeout_seconds, or set fail_on_timeout to false to branch instead. | packages-windows |

## DICT

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DICT.KEY_NOT_FOUND` | Key '…' is not in the dictionary | Keys: …, ….<br>Check the key; keys are case-sensitive. | packages |

## DLL

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DLL.BAD_ARGUMENT` | Argument … is a …; give a number, text, true/false or {"type": ..., "value": ...} | Types: int, long, double, bool, string (ANSI) or wstring (Unicode). | packages-windows |
| `DLL.BAD_CONVENTION` | Unknown calling convention '…' | Use c or stdcall. | packages-windows |
| `DLL.BAD_NAME` | Invalid function name '…' | Use the function's exported name (letters, digits and _). | packages-windows |
| `DLL.BAD_TYPE` | Unknown argument type '…' | Use int, long, double, bool, string (ANSI) or wstring (Unicode). | packages-windows |
| `DLL.NOT_FOUND` | Cannot find …!…: …<br>Cannot load …: … | Check the DLL name/path, its bitness (64-bit) and the exported function name.<br>Check the DLL name/path and its bitness (64-bit). | packages-windows |
| `DLL.NO_LIBRARY` | Give the library or a session from Open DLL<br>Give the library to open | Fill in the library's path, or the session from an Open DLL step.<br>Fill in the library's path. | packages-windows |

## DOCS

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DOCS.EMAIL` | DocFlow refused … (HTTP …) | Check that the Document Automation service is running and set up (Administration > Settings > Document Automation); the message has its answer. | modules/document-automation |
| `DOCS.FILE_TYPE` | Document Automation reads PDF, PNG, JPG and TIFF files, not …<br>Document Automation reads PDF, PNG, JPG and TIFF files, not .… | Check that the Document Automation service is running and set up (Administration > Settings > Document Automation); the message has its answer.<br>Convert the file first. | modules/document-automation, packages |
| `DOCS.NOT_AVAILABLE` | Document Automation is reachable only when the agent runs from the Command Center | Check the agent in and run it from the Command Center, where Document Automation is set up. | packages |
| `DOCS.NOT_SET_UP` | Document Automation is not set up yet: no address for its DocFlow service has been entered | An administrator starts DocFlow and enters its address (http://127.0.0.1:8085 when it runs on this server) and token in Administration > Settings > Document Automation. | modules/document-automation |
| `DOCS.NO_DOCUMENT` | Give the ids of the documents to mark<br>Give the document id | Use document_ids from List finished documents.<br>Use the document_id that Send document to process gave. | packages |
| `DOCS.NO_PROCESS` | Choose the process to send the document to<br>Choose the process | Use the process's name as shown in Document Automation. | packages |
| `DOCS.REFUSED` | Document Automation refused the document: …HTTP …<br>Document Automation did not list the documents: HTTP …<br>Document Automation did not give the document: HTTP … | Check that the Document Automation service is running and set up (Administration > Settings > Document Automation); the message has its answer. | modules/document-automation |
| `DOCS.TIMEOUT` | No answer from the Command Center about the document | Check that the Document Automation service is running and set up (Administration > Settings > Document Automation); the message has its answer. | agent |
| `DOCS.TOKEN` | The Document Automation service refused the Command Center's token | Put the same token in DocFlow (DOCAUTO_API_TOKEN) and in the vault credential named by Settings > Document Automation. | modules/document-automation |
| `DOCS.UNAVAILABLE` | The Document Automation service at … is not answering<br>Interrupted | Check that DocFlow is running (on Windows: the OneOffice DocFlow service, or windows\start.bat), and the address in Administration > Settings > Document Automation.<br>Try again; if it keeps happening, an administrator can look in the server log. | modules/document-automation |
| `DOCS.WAIT_TIMEOUT` | Document … is still …status… after … s | …Raise timeout_seconds, or pick the document up later with List finished documents.…Raise timeout_seconds. | packages |

## DOCUSIGN

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DOCUSIGN.BAD_ACCOUNT` | An account id is required | DocuSign account id (a GUID or number shown under Apps and Keys). | toolkits/enterprise |
| `DOCUSIGN.BAD_DOCUMENT` | Invalid document id '…' | Use a number such as 1, or certificate, combined or archive. | toolkits/enterprise |
| `DOCUSIGN.BAD_ID` | Invalid … id '…' | Ids look like 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. | toolkits/enterprise |
| `DOCUSIGN.BAD_RECIPIENT` | … entries must be dictionaries with name and email<br>… entry … needs a name and an email<br>Each role must be a dictionary with role, name and email | Example: [{"name": "Ann Lee", "email": "ann@acme.com"}].<br>Example: {"name": "Ann Lee", "email": "ann@acme.com"}.<br>Give each role as a dictionary with role, name and email, for example {"role": "Signer", "name": "Ana", "email": "ana@example.com"}. | toolkits/enterprise |
| `DOCUSIGN.NOTHING_TO_UPDATE` | Give a new name or a new email<br>No fields were given | Fill new_name or new_email.<br>Fill fields with at least one name and value. | toolkits/enterprise |
| `DOCUSIGN.NOT_FOUND` | … | Check the id and that it belongs to this account. | toolkits/enterprise |
| `DOCUSIGN.NO_BASE_URL` | DocuSign did not say which address account … uses | Check the account id and token, or fill in base_url (e.g. https://demo.docusign.net/restapi). | toolkits/enterprise |
| `DOCUSIGN.NO_DOCUMENTS` | Add at least one document | List one or more local file paths in documents. | toolkits/enterprise |
| `DOCUSIGN.NO_REASON` | A reason is required to void an envelope | Fill reason. | toolkits/enterprise |
| `DOCUSIGN.NO_SIGNERS` | Add at least one signer<br>Fill at least one template role | Example: [{"name": "Ann Lee", "email": "ann@acme.com"}].<br>Example: [{"role": "Client", "name": "Ann Lee", "email": "ann@acme.com"}]. | toolkits/enterprise |

## DOTNET

| Code | What it means | What to do | Where |
|---|---|---|---|
| `DOTNET.MISSING` | Fill in … | Fill in the field the message names. | packages |
| `DOTNET.NO_CODE` | Type the C# code<br>Type the VB.NET code | Fill in code. | packages |

## EC2

| Code | What it means | What to do | Where |
|---|---|---|---|
| `EC2.BAD_COUNT` | Count must be 1-100, not … | Set Count between 1 and 100. | toolkits/cloud-data |
| `EC2.BAD_INSTANCE_ID` | '…' is not an instance id | Instance ids look like i-0abc1234def567890. | toolkits/cloud-data |
| `EC2.BAD_OWNER` | '…' is not a snapshot owner | Use self, amazon or a 12-digit AWS account id. | toolkits/cloud-data |
| `EC2.BAD_REGION` | '…' is not an AWS region | Use a region code such as us-east-1, eu-west-1 or ap-south-1. | toolkits/cloud-data |
| `EC2.BAD_RESPONSE` | The answer to … is not an EC2 response (a <…> document, often a proxy or sign-in page) | Check the region or endpoint, and the proxy between the runner and AWS. | toolkits/cloud-data |
| `EC2.BAD_SIZE` | …A size is required for an empty volume…The size must be 1-65536 GiB, not … | Give size_gib, or a snapshot to restore. | toolkits/cloud-data |
| `EC2.BAD_SNAPSHOT_ID` | '…' is not a snapshot id | Snapshot ids look like snap-0abc1234def567890. | toolkits/cloud-data |
| `EC2.BAD_STATE` | Unknown state '…' | Use one of …, …. | toolkits/cloud-data |
| `EC2.BAD_TYPE` | An instance type is required | For example t3.micro. | toolkits/cloud-data |
| `EC2.BAD_VOLUME_ID` | '…' is not a volume id | Volume ids look like vol-0abc1234def567890. | toolkits/cloud-data |
| `EC2.BAD_VOLUME_TYPE` | Unknown volume type '…' | Use one of …, …. | toolkits/cloud-data |
| `EC2.BAD_ZONE` | '…' is not an availability zone | Use a zone name such as eu-west-1a. | toolkits/cloud-data |
| `EC2.INSTANCE_NOT_FOUND` | No instance is named '…' | Check the Name tag, or use the instance id. | toolkits/cloud-data |
| `EC2.NO_ACCESS_KEY` | The access key id from the vault is required | Map access_key_id to a Credential. | toolkits/cloud-data |
| `EC2.NO_DEVICE` | A device name is required | For example /dev/sdf or xvdf. | toolkits/cloud-data |
| `EC2.NO_INSTANCES` | No instance ids given | Give at least one instance id, e.g. from Describe instances. | toolkits/cloud-data |
| `EC2.NO_SECRET_KEY` | The secret access key from the vault is required | Map secret_access_key to a Credential. | toolkits/cloud-data |
| `EC2.NO_TAGS` | No tags given | Give at least one tag name and value. | toolkits/cloud-data |
| `EC2.TIMEOUT` | No answer from EC2 within 60 s | Try again later. | toolkits/cloud-data |
| `EC2.UNREACHABLE` | Cannot reach …: … | Check the region, proxy and network access. | toolkits/cloud-data |
| `EC2.WAIT_TIMEOUT` | Not all instances were … after … s: … | Raise timeout_seconds, or check the instances in the AWS console. | toolkits/cloud-data |

## EMAIL

| Code | What it means | What to do | Where |
|---|---|---|---|
| `EMAIL.AUTH_FAILED` | Mail sign-in failed: … | Check the user and password or token; Microsoft 365 and Gmail need OAuth or an app password. | packages-email |
| `EMAIL.BAD_ADDRESS` | The server refused the address(es) …: …<br>Invalid email address: …<br>Invalid email address in …: … | Check the recipient addresses.<br>Check the sender and recipient addresses.<br>Check the addresses. | packages-email |
| `EMAIL.BAD_INPUT` | Give the user that signs in to …<br>Unknown protocol '…'<br>unread_only and read_only cannot both be true | Fill in user.<br>Use imap or pop3.<br>Choose one of them. | packages-email |
| `EMAIL.ERROR` | … | Check the mail server's address, port and the sign-in in the connection; the message has the server's answer. | packages-email |
| `EMAIL.MSG_UNAVAILABLE` | Saving .msg files needs the Office module | Install the Office package, or save the email as .eml. | packages |
| `EMAIL.NOT_FOUND` | No email with id … in … | The email may have been moved or deleted. | packages-email |
| `EMAIL.NOT_SUPPORTED` | … needs an IMAP mailbox; POP3 has no folders or read status on the server | Connect with protocol imap. | packages-email |
| `EMAIL.NO_CREDENTIAL` | Give a password or an OAuth access token from the vault | Fill in password or access_token. | packages-email |
| `EMAIL.NO_FOLDER` | Mail folder '…' not found<br>The server did not create mail folder '…' | Use Get folders to see the exact names.<br>Create it in the mailbox first, or set create_folder to true.<br>Check the folder name and your rights. | packages-email |
| `EMAIL.NO_RECIPIENT` | Give at least one address in …<br>The original email has no sender to reply to | Fill in ….<br>Reply only to messages that have a sender; use Send email for a new message instead. | packages-email |
| `EMAIL.NO_SENDER` | There is no sender address for this email | Fill in from (or user, which is also used as the sender). | packages-email |
| `EMAIL.NO_SMTP` | No outgoing (SMTP) server for this step | Fill in smtp_host and user, or give Connect an smtp_host so the mailbox session can send. | packages-email |
| `EMAIL.READ_FAILED` | … | Check the mail server's address, port and the sign-in in the connection, and that the mailbox exists. | packages-email |
| `EMAIL.UNREACHABLE` | Cannot reach the mail server: … (…;\s*timeout -?\d+……)… | Check host, port, security and network access. | packages-email |

## ENCODING

| Code | What it means | What to do | Where |
|---|---|---|---|
| `ENCODING.INVALID` | Not valid Base64: … | Check the input is Base64 text. | packages |

## EVENTS

| Code | What it means | What to do | Where |
|---|---|---|---|
| `EVENTS.ADDRESS_BLOCKED` | The event receiver at …(no address)… is not a public internet address | Events go to public addresses only. To send them inside your own network, switch on Settings > E-mail, proxy and SIEM > Allow receivers in private networks. | control-room |

## EXCEL

| Code | What it means | What to do | Where |
|---|---|---|---|
| `EXCEL.AUTOFIT_FAILED` | Cannot measure the text to fit the columns: … | On a Linux server install a font package (for example fontconfig and dejavu fonts), or set widths with set_column_width. | packages-office |
| `EXCEL.BAD_CLEAR` | Clear must be contents, formats or all, not '…' | Pick one of the three. | packages-office |
| `EXCEL.BAD_COLOR` | '…' is not a colour | Use a hex colour such as #FFC000, red,green,blue numbers such as 255,192,0, or a name: …, …. | packages-office |
| `EXCEL.BAD_COLUMN` | No column given<br>'…' is not a column<br>Column number … is out of range | Give a column letter such as C or a number such as 3.<br>Columns are numbered 1 to 16384.<br>Pick columns inside the range. | packages-office |
| `EXCEL.BAD_COUNT` | Count must be at least 1 | Set count to 1 or more. | packages-office |
| `EXCEL.BAD_DIRECTION` | Direction must be down or right, not '…'<br>Direction must be down, up, right or left, not '…' | Use down or right.<br>Use down, up, right or left. | packages-office |
| `EXCEL.BAD_FILTER` | The between operator needs value and value_to | Set both bounds. | packages-office |
| `EXCEL.BAD_FORMAT` | Unsupported format '…' | Use xlsx, xls or csv. | packages-office |
| `EXCEL.BAD_FORMULA` | Invalid formula '…': …<br>Replacing in … gives an invalid formula '…' | Check the formula syntax.<br>Check the replacement text. | packages-office |
| `EXCEL.BAD_FUNCTION` | '…' is not a summary function | Use sum, count, average, max or min, e.g. Amount:average. | packages-office |
| `EXCEL.BAD_HEADER` | Column … of the source range has no header<br>Two source columns are both called '…' | Every source column needs a name in the header row.<br>Give every source column its own name. | packages-office |
| `EXCEL.BAD_LINK` | The new link is empty | Give the path of the new source workbook. | packages-office |
| `EXCEL.BAD_MOVE` | Unknown move '…'<br>The move goes past the edge of the sheet | Use none, left, right, up, down, row_start, row_end, column_start or column_end.<br>Start from another cell. | packages-office |
| `EXCEL.BAD_NAME` | '…' cannot be a table name | Start with a letter; use letters, digits, _ and .; it must not look like a cell address. | packages-office |
| `EXCEL.BAD_OPERATOR` | Unknown operator '…' | Use equals, not_equals, contains, starts_with, ends_with, gt, gte, lt, lte, empty or not_empty. | packages-office |
| `EXCEL.BAD_PASTE` | Paste must be all, values, formulas or formats, not '…' | Pick one of the four. | packages-office |
| `EXCEL.BAD_PATH` | Give the workbook's path in the drive after onedrive:, for example onedrive:Reports/2026/sales.xlsx | Write the path after onedrive:, for example onedrive:Reports/2026/sales.xlsx. | packages-office |
| `EXCEL.BAD_POSITION` | Position must be between 1 and …<br>Table '…' has no column … | Leave position empty to add at the end.<br>Use a position between 1 and …. | packages-office |
| `EXCEL.BAD_RANGE` | '…' is not a range<br>End row … is before start row …<br>The copy would go past the edge of the sheet | Use an address such as A1:D20.<br>Give an end row at or after the start row.<br>Pick a destination nearer the top-left. | packages-office |
| `EXCEL.BAD_ROW` | Row number … is out of range | Rows are numbered from 1. | packages-office |
| `EXCEL.BAD_SHEET` | A sheet name cannot be empty<br>Sheet name '…' has … characters; Excel allows at most 31<br>Sheet name '…' contains one of : \ / ? * [ ] | Give the sheet a name.<br>Shorten the sheet name to 31 characters or fewer.<br>Remove : \ / ? * [ ] from the sheet name. | packages-office |
| `EXCEL.BAD_SHEET_NAME` | … | Sheet names are at most 31 characters and cannot contain : \ / ? * [ ]. | packages-office |
| `EXCEL.BAD_SHIFT` | Shift must be up or left, not '…' | Use up or left. | packages-office |
| `EXCEL.BAD_SIZE` | Width … is out of range<br>Height … is out of range<br>Width and height must be at least 1 | Use a width from 0 to 255 characters.<br>Use a height from 0 to 409 points.<br>Give the size in columns and rows. | packages-office |
| `EXCEL.BAD_STYLE` | '…' is not an Excel table style | Use TableStyleLight1–21, TableStyleMedium1–28 or TableStyleDark1–11. | packages-office |
| `EXCEL.COLUMN_EXISTS` | Table '…' already has a column '…' | Choose another column name. | packages-office |
| `EXCEL.EMPTY` | The source sheet has no data<br>The sheet has no data | Write the data first or give a range. | packages-office |
| `EXCEL.EMPTY_FIND` | Text to find is empty | Give the text to replace. | packages-office |
| `EXCEL.EXISTS` | A file already exists at …<br>A pivot table called '…' already exists<br>A table called '…' already exists | Set overwrite to true or use open to edit it.<br>Choose another name.<br>Set overwrite to true or choose another path. | packages-office |
| `EXCEL.LAST_COLUMN` | A table must keep at least one column | Delete the whole table range instead. | packages-office |
| `EXCEL.LAST_SHEET` | A workbook must keep at least one sheet | Create another sheet first. | packages-office |
| `EXCEL.LAST_VISIBLE_SHEET` | Cannot hide the only visible sheet | Unhide or create another sheet first. | packages-office |
| `EXCEL.NOTHING_TO_LOCK` | No password was given<br>Neither the structure nor the windows were chosen | Give a password to open the file, a structure password, or both.<br>Set lock_structure or lock_windows to true. | packages-office |
| `EXCEL.NOTHING_TO_UNLOCK` | Nothing to unprotect was chosen | Give the structure password, set remove_open_password, or both. | packages-office |
| `EXCEL.NOT_FOUND` | Workbook not found: … | Set create to true or fix the path.<br>Fix the path. | packages-office |
| `EXCEL.NOT_SUPPORTED` | A password to open needs an .xlsx workbook | Save the workbook as .xlsx first. | packages-office |
| `EXCEL.NO_EMPTY_CELL` | No empty cell before the edge of the sheet | Start from another cell or search in the other direction. | packages-office |
| `EXCEL.NO_LINK` | The workbook has no link to '…' | Use get_links to see the linked workbooks. | packages-office |
| `EXCEL.NO_SELECTION` | No range given and the sheet has no active cell | Set the range, or run select_range or go_to_cell first. | packages-office |
| `EXCEL.NO_SHEET` | Sheet '…' not found<br>There is no sheet number … (the workbook has …)<br>Give a sheet name or index | Sheets: …, ….<br>Use an index from 1 to ….<br>Set the sheet name or its 1-based index. | packages-office |
| `EXCEL.NO_TABLE` | Excel table '…' not found | …The workbook has no Excel tables.…Tables: …, …. | packages-office |
| `EXCEL.NO_TOKEN` | A onedrive: path needs access_token | Pick the Microsoft Graph token from the vault. | packages-office |
| `EXCEL.NO_VALUES` | No value fields given | Name at least one field to summarise, e.g. ["Amount"]. | packages-office |
| `EXCEL.OPEN_FAILED` | Cannot open …: …<br>… | Close the file in Excel and check it is a valid workbook.<br>Check that the workbook exists, is a valid .xlsx or .xls file, and is not open in Excel with a lock. | packages-office |
| `EXCEL.OVERLAP` | The range overlaps the table … | Pick a range outside existing tables. | packages-office |
| `EXCEL.PASSWORD_REQUIRED` | The workbook … is password-protected<br>An empty password would leave the sheet unprotected | Set the password input from the credential vault.<br>Give a password, ideally a credential from the vault. | packages-office |
| `EXCEL.READ_ONLY` | The workbook was opened read-only | Use save_as with another path, or open it without read_only. | packages-office |
| `EXCEL.SAVE_FAILED` | Cannot save …: … | Close the file if it is open in Excel. | packages-office |
| `EXCEL.SHEET_EXISTS` | Sheet '…' already exists | Set replace to true or choose another name.<br>Choose another name. | packages-office |
| `EXCEL.WRONG_PASSWORD` | The password for … is wrong<br>The workbook password is wrong<br>The sheet password is wrong | Check the password stored in the credential vault. | packages-office |
| `EXCEL.XLSX_ONLY` | … is only supported for .xlsx workbooks | Save the workbook as .xlsx first (save_as with format xlsx). | packages-office |

## EXCEL_DESKTOP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `EXCEL_DESKTOP.BAD_FORMAT` | Unsupported target format .… | Use .xlsx, .xlsm, .xlsb, .xls, .csv, .txt, .ods, .xltx or .xltm. | packages |
| `EXCEL_DESKTOP.BAD_MACRO` | Invalid macro name '…' | Write the macro's name as Excel shows it (Module1.MyMacro), with letters, digits, . and _ only. | packages |
| `EXCEL_DESKTOP.BAD_PAGES` | from_page and to_page must be 1 or more, with to_page not before from_page | Fix the page numbers. | packages |
| `EXCEL_DESKTOP.BAD_QUALITY` | Quality must be standard or minimum | Use standard or minimum. | packages |
| `EXCEL_DESKTOP.EXISTS` | A file already exists at … | Set overwrite to true or choose another path. | packages |
| `EXCEL_DESKTOP.FAILED` | Excel reported an error: … | Check the workbook, macro name and that macros are allowed. | packages |
| `EXCEL_DESKTOP.NOT_INSTALLED` | Microsoft Excel is not installed on this runner | Install Excel, or use Excel (no Office needed). | packages |
| `EXCEL_DESKTOP.PDF_UNAVAILABLE` | Excel cannot export to PDF on this runner and the Microsoft Print to PDF printer is missing. Office 2007 needs Microsoft's Save as PDF or XPS add-in; Office 2010 and later export directly. | Install the add-in, a newer Office, or the Microsoft Print to PDF printer (Windows features). | packages |
| `EXCEL_DESKTOP.TOO_MANY_ARGS` | At most 10 macro arguments | Pass at most 10 arguments; put more in cells the macro reads. | packages |
| `EXCEL_DESKTOP.WRONG_PASSWORD` | Excel could not open the workbook with the given password | Check the password stored in the credential vault. | packages |

## EXCEL_OPEN

| Code | What it means | What to do | Where |
|---|---|---|---|
| `EXCEL_OPEN.BAD_RANGE` | '…' is not a range<br>'…' is not a cell | Use an address such as A1:D20.<br>Use a cell such as B2. | packages |
| `EXCEL_OPEN.NO_VALUES` | Nothing to write | Give a value, a list of rows or a Data Table. | packages |

## EXCHANGE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `EXCHANGE.ACCESS_DENIED` | Exchange refused access for … (HTTP 403)<br>… | Check the user may use EWS, and its rights on the mailbox.<br>Give the user rights on the mailbox (full access, Send As, or the ApplicationImpersonation role). | toolkits/microsoft |
| `EXCHANGE.AUTH_FAILED` | Exchange refused the sign-in of … (HTTP 401) | Check the user (DOMAIN\user or user@domain), the password in the vault and sign_in (ntlm or basic, as the server's EWS site allows). | toolkits/microsoft |
| `EXCHANGE.BAD_ADDRESS` | Give at least one recipient in to<br>Without the sender there is nobody left to reply to<br>… | Fill in to.<br>Use Reply to email, or give cc.<br>Check the recipient addresses. | toolkits/microsoft |
| `EXCHANGE.BAD_INPUT` | Unknown sign_in '…'<br>impersonate needs the mailbox to act as<br>unread_only and read_only cannot both be true | Use ntlm or basic.<br>Fill in mailbox, or clear impersonate.<br>Choose one of them. | toolkits/microsoft |
| `EXCHANGE.BAD_RESPONSE` | Exchange did not give the email's content<br>Exchange did not return the item's id<br>The answer from … is not an EWS answer (HTTP …; often a proxy or sign-in page) | Forward it without as_attachment.<br>The service answered in an unexpected way; try again, and if it keeps happening check the service's status page.<br>Check the EWS address ends with /EWS/Exchange.asmx and that no proxy is in the way. | toolkits/microsoft |
| `EXCHANGE.BAD_URL` | Invalid EWS address '…'<br>There is no EWS service at … (HTTP 404)<br>Exchange at … redirects elsewhere (HTTP …) | Use the server's EWS address, e.g. https://mail.example.com/EWS/Exchange.asmx.<br>Use the server's EWS address, usually https://<mail server>/EWS/Exchange.asmx.<br>Use the final EWS address (often https:// instead of http://). | toolkits/microsoft |
| `EXCHANGE.HTTP_ERROR` | Exchange answered HTTP … to ……: … | Try again later, or ask the Exchange administrator to check the server. | toolkits/microsoft |
| `EXCHANGE.NOT_FOUND` | No email with id …<br>… | The email may have been moved (it then has a new id) or deleted.<br>The email may have been moved (it then has a new id) or deleted; take the id from Find emails. | toolkits/microsoft |
| `EXCHANGE.NO_FOLDER` | Mail folder '…' not found (no '…')<br>… | Use Get folders to see the exact names, or set create_folder to true.<br>Use Get folders to see the exact names. | toolkits/microsoft |
| `EXCHANGE.NO_MAILBOX` | … | Check the mailbox address on Connect. | toolkits/microsoft |
| `EXCHANGE.NO_SECRET` | Input 'password' must come from the vault (a Credential), so nothing was sent | Give a vault reference {"$credential": "locker/credential/attribute"} or a Credential variable. | toolkits/microsoft |
| `EXCHANGE.NO_USER` | Give the user that signs in to Exchange | Fill in user as DOMAIN\user or user@domain. | toolkits/microsoft |
| `EXCHANGE.SERVER_BUSY` | … | Exchange is throttling or busy; try again later. | toolkits/microsoft |
| `EXCHANGE.SERVER_ERROR` | … | Check the inputs; the response code tells the Exchange administrator what went wrong. | toolkits/microsoft |
| `EXCHANGE.TIMEOUT` | No answer from Exchange at … within … s (…)<br>… | Raise timeout_seconds on Connect, or check the server.<br>Try again, or narrow the search. | toolkits/microsoft |
| `EXCHANGE.TLS_SETUP_FAILED` | Cannot set up TLS: … | Check the runner's Java security settings and the server's certificate; ask IT whether TLS inspection is in the way. | toolkits/microsoft |
| `EXCHANGE.TOO_LARGE` | … | Send smaller attachments, or ask for a larger mailbox or message limit. | toolkits/microsoft |
| `EXCHANGE.UNREACHABLE` | Cannot reach Exchange at …: … | Check the EWS address, the network and the certificate (insecure_tls is for test systems only). | toolkits/microsoft |

## EXPR

| Code | What it means | What to do | Where |
|---|---|---|---|
| `EXPR.DIV_ZERO` | Division by zero | Check the divisor before dividing. | runner |
| `EXPR.INVALID` | Unexpected character '…' at position … in: …<br>… in: …<br>Unknown function '…' | Check quotes and operators in the expression.<br>Correct the expression the message names; Help > Expressions lists the functions and their arguments.<br>Available: …, …. | runner |

## FILE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `FILE.BAD_BASE64` | The text is not Base64: … | Pass the Base64 text exactly as the web service returned it. | packages |
| `FILE.BAD_ENCODING` | Unknown text encoding '…'<br>Unknown encoding '…' | Use utf-8, utf-8-bom, ansi, unicode, utf-16 or windows-1251.<br>Use utf-8, utf-16le, windows-1252 or ansi. | packages |
| `FILE.BAD_NAME` | The extension or prefix holds a character a file name cannot have | Leave out \ / : * ? " < > \|. | packages |
| `FILE.BAD_OPERATOR` | Unknown operator '…' | Use eq, ne, gt, ge, lt or le. | packages |
| `FILE.BAD_OPTION` | Unknown trim '…'<br>Unknown sort '…'<br>Give the number of days | Use none, leading, trailing or both.<br>Use name, modified or size.<br>Fill in 'days'. | packages |
| `FILE.BAD_TARGET` | The target … is a file | With a pattern, the target must be a folder. | packages |
| `FILE.EXISTS` | File already exists: …<br>Target exists: …<br>Archive already exists: … | Set overwrite to true or choose another name.<br>Choose another name or turn off fail_if_exists.<br>Turn on overwrite or choose another path. | packages, packages-transfer, packages-windows, toolkits/microsoft |
| `FILE.IO_ERROR` | …<br>Cannot read …: …<br>Cannot write …: … | Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.<br>Check the path and permissions.<br>Check the folder exists and can be written. | packages, packages-browser, packages-email, packages-pdf, toolkits/ai, toolkits/enterprise, toolkits/google, toolkits/microsoft |
| `FILE.NOT_CREATED` | The PDF … was not written within … s | Check the printer queue and that the target folder is writable. | packages |
| `FILE.NOT_FOUND` | File not found: …<br>Python module not found: …<br>Messages file not found: … | Check the path.<br>Check the path; the file may have been moved or renamed.<br>Check the key path. | packages, packages-email, packages-office, packages-pdf, packages-transfer, toolkits/ai, toolkits/enterprise, toolkits/google, toolkits/microsoft |
| `FILE.NO_FILES` | Give at least one file to print | Fill in the files list, or a folder with matching files. | packages |
| `FILE.OUTSIDE_WORKSPACE` | This run may only use files in its own workspace, not … | Use a path relative to the workspace. | sdk |
| `FILE.READ_FAILED` | Cannot read …: … | Check that the file exists, is not open in another program, and that the runner's account may read it. | toolkits/enterprise |
| `FILE.TOO_LARGE` | … is … MB; Base64 text is limited to 50 MB of file | Send large files as files (for example with a REST upload) rather than as text. | packages |
| `FILE.WAIT_TIMEOUT` | No finished file matching … appeared in time | Raise timeout_seconds, or check the folder and name pattern. | packages |
| `FILE.WRITE_FAILED` | Cannot write …: … | Check the folder exists and is writable. | packages, toolkits/devops, toolkits/enterprise |

## FLOW

| Code | What it means | What to do | Where |
|---|---|---|---|
| `FLOW.BAD_BREAK` | break/continue used outside a loop…… with label '…'…<br>Exit loop or Continue loop in subflow '…' is not inside a loop of the subflow…… with label '…'… | Put Break and Continue inside a loop (and use the label of a loop around them).<br>A subflow can only leave or continue its own loops. | runner |
| `FLOW.BAD_CONDITION` | 'between' needs right = [low, high]<br>Unknown condition operator '…' | Example: {"op": "between", "left": "$n", "right": [1, 10]}.<br>Use one of …, …. | runner |
| `FLOW.BAD_EXIT_SUBFLOW` | Exit subflow used outside a subflow | Use Stop agent to end the agent's main steps. | runner |
| `FLOW.BAD_GOTO` | Go to label '…': there is no such label in the step list or in a list around it<br>Go to label '…' in subflow '…': the label is not in the subflow<br>Go to label '…' would leave the state machine | Put a Label step with this name in the same list as the Go to, or in a list around it.<br>Go to can only jump to a label in the same subflow, in the same step list or a list around it.<br>Go to can only jump to a label in the same step list of a state, or a list inside it. | runner |
| `FLOW.INVALID` | Flow has … problem(s); first: …message…<br>Assign several variables needs a list of assignments<br>Each assignment needs a variable name | …suggestedFix…<br>Add records with variable and value.<br>Give every row a declared variable. | runner |
| `FLOW.SCHEMA_MISSING` | flow.v1.json is not on the classpath | Run gradlew :adapters-jackson:syncFlowSchema | adapters-jackson |
| `FLOW.UNKNOWN_ACTION` | Unknown core action '…'<br>…@… has no action '…' | Update the runner; the agent uses a command this runner does not know.<br>Install the toolkit version the agent was built with, or update the step to the installed version. | runner |
| `FLOW.UNKNOWN_CONDITION` | Unknown condition '…' | Update the runner; the agent uses a condition this runner does not know. | runner |
| `FLOW.UNKNOWN_ITERATOR` | Unknown iterator '…' | Update the runner; the agent uses a loop kind this runner does not know. | runner |

## FOLDER

| Code | What it means | What to do | Where |
|---|---|---|---|
| `FOLDER.BAD_NAME` | Invalid folder name '…' | Give only the new name, without slashes or the characters < > : " \| ? *. | packages |
| `FOLDER.BAD_OPTION` | Sizes cannot be negative<br>larger_than_kb … is not below smaller_than_kb …, so no folder can match<br>modified_after … is not before modified_before …, so no folder can match | Use 0 or more KB.<br>Give a size range whose lower limit is below its upper limit, or fill in only one of them.<br>Give a date range whose start is before its end, or fill in only one of them. | packages |
| `FOLDER.BAD_TARGET` | Cannot move … into itself | Choose a destination outside the folder. | packages |
| `FOLDER.EXISTS` | Folder already exists: …<br>A file or folder named '…' already exists in …<br>'…' already exists | Choose another name or turn off fail_if_exists.<br>Choose another name.<br>Turn on overwrite to merge into it, or choose another destination. | packages |
| `FOLDER.IO_ERROR` | Cannot read the size of …: …<br>Cannot rename …: …<br>Cannot move …: … | Close any program using the folder and try again. | packages |
| `FOLDER.NOT_EMPTY` | Folder is not empty: … | Set recursive to true to delete its contents. | packages |
| `FOLDER.NOT_FOUND` | Folder not found: … | Check the folder path. | packages |
| `FOLDER.OPEN_FAILED` | Cannot open …: … | Check a file manager is installed on the runner. | packages |
| `FOLDER.PROTECTED` | Refusing to delete …<br>Refusing to rename …<br>Refusing to move … | Point to a specific sub-folder. | packages |

## FORMS

| Code | What it means | What to do | Where |
|---|---|---|---|
| `FORMS.BAD_COLOR` | Not a colour: '…' | Use #rrggbb, e.g. #d97706, or a colour name such as red. | packages |
| `FORMS.BAD_FIELD` | Each field must be a Dictionary with at least id and type<br>Field ids must be unique and use letters, digits and _ ('…')<br>A dynamic area cannot hold another area | Give each field as a dictionary with at least id and type.<br>Give every field its own id of letters, digits and _.<br>Put the inner area's fields directly in the outer area. | packages |
| `FORMS.BAD_FORM` | Not a form from the form designer: … (…) | Open it in OneAgentStudio's form designer and save it again. | packages |
| `FORMS.BAD_KIND` | Unknown highlight kind '…' | Use color, warning or error. | packages |
| `FORMS.BAD_MODE` | Unknown mode '…' | Use replace or append. | packages |
| `FORMS.ELEMENT_NOT_FOUND` | The form has no field or button '…'<br>The form has no field '…' to fill in | Use an id from the form designer (select the field to see its id).<br>Use field ids from the form designer. | packages |
| `FORMS.NOT_AN_AREA` | '…' is not a dynamic area | Add a Dynamic area in the form designer. | packages |
| `FORMS.NOT_A_FIELD` | '…' is not a field with a value | Choose an input field, not a button, heading or area. | packages |
| `FORMS.NOT_A_LIST` | '…' is not a drop-down or choice field | Choose a drop-down or choice field of the form. | packages |
| `FORMS.NOT_OPEN` | The form '…' is not open | Display it first (Display form) with this name, and do not close it before this step.<br>Check the name given when it was displayed. | packages |
| `FORMS.NO_BROWSER` | Cannot open a window for the form: … | Install Microsoft Edge or Google Chrome on the runner. | packages |
| `FORMS.NO_DESKTOP` | Forms need a runner with a desktop and a signed-in person | Run this agent attended, on a runner where someone is signed in. | packages |
| `FORMS.SERVER_FAILED` | Cannot show forms on this computer: … | Make sure the runner has an unlocked desktop session where the form can open. | packages |
| `FORMS.TIMEOUT` | Nothing happened on the form within … s | Raise the time-out, or handle timed_out. | packages |

## FUZZY

| Code | What it means | What to do | Where |
|---|---|---|---|
| `FUZZY.BAD_ALGORITHM` | Unknown algorithm '…' | Use one of …, …. | packages |
| `FUZZY.BAD_THRESHOLD` | Threshold must be between 0 and 100, got … | Use for example 85 to accept close matches only. | packages |
| `FUZZY.NO_TEXT` | Give the text to look for | Fill in 'text' or 'text_file'. | packages |

## GITHUB

| Code | What it means | What to do | Where |
|---|---|---|---|
| `GITHUB.BAD_NAME` | Invalid … '…' | Use the plain name as shown on GitHub, e.g. acme-corp or billing-service. | toolkits/devops |
| `GITHUB.BAD_OWNER_TYPE` | Unknown owner type '…' | Use organization or user. | toolkits/devops |
| `GITHUB.NOTHING_TO_UPDATE` | No changes were given | Fill in at least one of title, body, state, labels or assignees. | toolkits/devops |
| `GITHUB.NOT_A_FILE` | '…' is not a file | Give the path of a file, not a folder. | toolkits/devops |
| `GITHUB.NOT_MERGEABLE` | … | The pull request cannot be merged now: check conflicts, required reviews and checks, or whether the source branch changed. | toolkits/devops |
| `GITHUB.NO_CONTENT` | Nothing to commit | Fill in text or local_file. | toolkits/devops |
| `GITHUB.RATE_LIMITED` | … | GitHub's request limit was reached; retry after it resets. | toolkits/devops |

## GLOBALS

| Code | What it means | What to do | Where |
|---|---|---|---|
| `GLOBALS.BAD_NAME` | '…' is not a shared value name | Names use letters, digits and _ and do not start with a digit. | packages |
| `GLOBALS.DENIED` | …, the user this run runs as, may not change shared values | Give that user a role with Manage shared values, or run the agent as a user who has it. | control-room |
| `GLOBALS.INVALID` | global-values.json must contain a JSON object<br>global-values.json is not valid JSON: … | Example: {"reports_folder": "D:/Reports"}<br>Fix the file in the workspace. | packages, runner |
| `GLOBALS.NOT_FOUND` | Shared value '…' is not defined in One CommandCenter<br>Shared value '…' is not defined | Agents change existing shared values only; create it in One CommandCenter under Shared values first.<br>Add it to global-values.json in the workspace, or give a default.<br>Add it to global-values.json in the workspace first; agents change existing shared values only. | control-room, packages |
| `GLOBALS.NO_VALUE` | Give the new value of '…' | Fill in value. | packages |
| `GLOBALS.READ_ONLY` | This run can read shared values but not change them | Run the agent from One CommandCenter on a runner of this release, or change the value in One CommandCenter. | packages |
| `GLOBALS.SECRET` | A credential cannot be stored as a shared value: shared values are not secret | Keep it in the credential vault (Save credential, ooa.vault). | packages |
| `GLOBALS.TOO_LARGE` | A shared value is at most 100000 characters | Keep shared values under 100,000 characters; put larger data in a file in the repository instead. | control-room |
| `GLOBALS.WRONG_TYPE` | …<br>Shared value '…' cannot be read as …<br>The new value of '…' cannot be read as … | Give a value of the shared value's type (…).<br>Check the value or choose type Any. | control-room, packages |

## GOOGLE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `GOOGLE.AMBIGUOUS` | … spreadsheets are named '…'<br>… items are named '…' in … | Give the spreadsheet id or link instead of the name.<br>Give the id instead of the path. | toolkits/google |
| `GOOGLE.BAD_ADDRESS` | Invalid address | Use an e-mail address such as name@example.com; separate several with commas. | toolkits/google |
| `GOOGLE.BAD_INPUT` | … | … | toolkits/google |
| `GOOGLE.BAD_RESPONSE` | Google Docs did not return the new document's id | The service answered in an unexpected way; try again, and if it keeps happening check the service's status page. | toolkits/google |
| `GOOGLE.BAD_SUBJECT` | The subject cannot contain line breaks | Write the subject on one line. | toolkits/google |
| `GOOGLE.NOTHING_TO_FORMAT` | No formatting was given | Fill in at least one format input such as bold or background_color. | toolkits/google |
| `GOOGLE.NOT_FOUND` | No spreadsheet named '…' was found in Drive<br>No …folder…file or folder… named '…' in … | Check the name, or give the spreadsheet id or link.<br>Check the path; it starts at My Drive, e.g. Reports/2026. | toolkits/google |
| `GOOGLE.NO_CONTENT` | Gmail returned no raw content for message … | The service answered in an unexpected way; try again, and if it keeps happening check the service's status page. | toolkits/google |
| `GOOGLE.NO_LABEL` | Gmail label '…' not found | Check the label name; system labels are INBOX, SPAM, TRASH, UNREAD, STARRED, IMPORTANT. | toolkits/google |
| `GOOGLE.NO_RECIPIENT` | Give at least one recipient<br>The original message has no sender to reply to | Fill in to.<br>Reply only to messages that have a sender; use Send for a new message instead. | toolkits/google |
| `GOOGLE.NO_SHEET` | The spreadsheet has no sheets<br>Sheet '…' not found; the sheets are …title…<br>There is no sheet at position …; the spreadsheet has … | Add a sheet to the spreadsheet, or check that the id is the right spreadsheet.<br>Check the sheet name or use List sheets.<br>Check the position or use List sheets. | toolkits/google |
| `GOOGLE.UPLOAD_FAILED` | Google did not open an upload session | Try again. | toolkits/google |

## GOOGLE_AI

| Code | What it means | What to do | Where |
|---|---|---|---|
| `GOOGLE_AI.BAD_CREDENTIAL_TYPE` | Unknown credential_type '…' | Use api_key or access_token. | packages |
| `GOOGLE_AI.BAD_ENDPOINT` | '…' is not an https address | Leave endpoint empty for Google's own addresses. | packages |
| `GOOGLE_AI.BAD_LANGUAGE` | '…' is not a language code | Use a code such as en, de or ja, or leave it empty. | packages |
| `GOOGLE_AI.BAD_LOCATION` | '…' is not a location | Use us or eu. | packages |
| `GOOGLE_AI.BAD_PROCESSOR` | '…' is not a processor id<br>'…' is not a processor version id | Copy the id from the processor's page in the Google Cloud console.<br>Leave it empty for the default version. | packages |
| `GOOGLE_AI.BAD_PROJECT` | '…' is not a project id | Use the project id, e.g. acme-ai-prod, or its number. | packages |
| `GOOGLE_AI.BAD_URL` | Invalid address: … | Check the endpoint given to Connect. | packages |
| `GOOGLE_AI.IMAGE_ERROR` | Cloud Vision could not read the picture: …message… | Check the file is a supported, undamaged picture. | packages |
| `GOOGLE_AI.NO_CREDENTIAL` | An API key or access token from the vault is required | Map credential to a Credential. | packages |
| `GOOGLE_AI.NO_PROJECT` | No project given to Connect | Give Connect the project id that owns the processor. | packages |
| `GOOGLE_AI.TOKEN_REQUIRED` | Document AI does not take API keys | Connect with credential_type access_token and an OAuth token of a service account that may use the processor. | packages |
| `GOOGLE_AI.UNKNOWN_FILE_TYPE` | Cannot tell the file type of … | Give mime_type, e.g. application/pdf or image/png. | packages |

## GOTO

| Code | What it means | What to do | Where |
|---|---|---|---|
| `GOTO.LIMIT` | The run made more than … Go to jumps (last to label '…') | Go to may be jumping round endlessly: check the condition that ends it, or use a Loop. | runner |

## HTTP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `HTTP.BAD_CERTIFICATE` | Cannot open client certificate …: … | Check the file is PKCS#12 (.p12/.pfx) and the password is right. | packages |
| `HTTP.BAD_PROXY` | Invalid proxy '…'<br>Invalid proxy port in '…' | Use host:port, e.g. proxy.example.com:8080.<br>Use a port between 1 and 65535. | packages |
| `HTTP.CERTIFICATE_NOT_FOUND` | Client certificate not found: … | Give a .p12 or .pfx file. | packages |
| `HTTP.TLS_SETUP_FAILED` | Cannot set up TLS: … | Check the client certificate and its password. | packages |

## IDP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `IDP.BAD_ANSWER` | The model did not answer with the fields | Retry, or try another model. | packages |
| `IDP.BAD_EXAMPLES` | Cannot read the example file …: … | Check that the file exists, is not open in another program, and that the runner's account may read it. | packages |
| `IDP.BAD_RULE` | The rule for … is not a valid regular expression: …<br>The rule for … needs regex or after | Correct the rule's regular expression; the message names where it breaks.<br>Give the rule either a regex or an after text. | packages |
| `IDP.BAD_TYPE` | Each document type needs a name | Give every document type a name. | packages |
| `IDP.NO_FIELDS` | Give the validated values<br>Give the fields to extract | Give the validated values.<br>Name the fields to extract. | packages |
| `IDP.NO_TYPE` | Give the document type | Fill in the document type. | packages |
| `IDP.NO_TYPES` | Give the document types | Each: {"name": "invoice", "keywords": ["invoice", "VAT"], "description": "a supplier invoice"} | packages |

## IMAGE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `IMAGE.BAD_COMPRESSION` | Unknown TIFF compression '…' | Use one of …, …. | packages |
| `IMAGE.BAD_INTERVAL` | repeat_delay_ms must be between 0 and 60000, not …<br>interval_ms must be between 50 and 60000, not … | Use e.g. 500. | packages |
| `IMAGE.BAD_OCCURRENCE` | Occurrence must be 1 or more | Use 1 for the first match. | packages |
| `IMAGE.BAD_OPTION` | tolerance and max_difference_percent must be 0 to 100 | Give percentages from 0 to 100. | packages |
| `IMAGE.BAD_POSITION` | Position must be center or top_left | Use center or top_left. | packages |
| `IMAGE.BAD_QUALITY` | JPEG quality must be between 1 and 100, not … | Use 90 for good quality, or lower for smaller files. | packages |
| `IMAGE.BAD_REGION` | A search region needs x, y, width and height, with width and height above 0<br>The search region lies outside the image (…x…)<br>The search region lies outside the screen | Set all four, or none to search everywhere.<br>Check the coordinates. | packages |
| `IMAGE.BAD_REPEAT` | repeat_times must be between 1 and 1000, not … | Use 1 to look once, or e.g. 10. | packages |
| `IMAGE.BAD_TIMEOUT` | timeout_seconds must be between 0 and 3600, not … | Use 0 to look once, or e.g. 10 to keep looking. | packages |
| `IMAGE.NOT_FOUND` | Image did not appear within … s (best similarity …similarity…%)<br>Image not found on screen (best similarity …similarity…%) | Check the screen resolution and scaling match the captured picture, or raise the tolerance.<br>Check the window is visible and not covered, or raise the tolerance. | packages |
| `IMAGE.NOT_STEADY` | The screen region kept changing for … s | Raise timeout_seconds or threshold_percent, or choose a smaller region. | packages |
| `IMAGE.NO_CHANGE` | The screen region did not change within … s | Raise timeout_seconds, or lower threshold_percent. | packages |
| `IMAGE.SIZE_MISMATCH` | The pictures differ in size: … x … and … x … | Capture both at the same size, or set size_mismatch to scale. | packages |
| `IMAGE.STILL_VISIBLE` | Image was still on the screen after … s | Raise the timeout or lower the tolerance. | packages |
| `IMAGE.UNSUPPORTED` | Not a readable image: … | Use PNG, JPG, BMP or GIF. | packages |

## INSIGHTS

| Code | What it means | What to do | Where |
|---|---|---|---|
| `INSIGHTS.BAD_CATEGORY` | Invalid category '…' | Use letters, digits, - and _. | packages |
| `INSIGHTS.BAD_TRANSACTION` | Invalid transaction name '…' | Use up to 100 characters without < or >. | packages |
| `INSIGHTS.NOT_OPEN` | Transaction '…' was not opened | Add Open transaction with the same name before this step. | packages |
| `INSIGHTS.RESERVED_NAME` | transaction, outcome, started and duration_seconds are recorded automatically | Rename that value. | packages |
| `INSIGHTS.SECRET` | Credentials cannot be recorded as insights | Record a value that is not a credential, or leave the field out. | packages |

## INTERRUPT

| Code | What it means | What to do | Where |
|---|---|---|---|
| `INTERRUPT.BAD_RULE` | close_window answers window rules only<br>A rule needs a name<br>Rule '…': give either a window title pattern or an element selector | Use close_window only on window rules.<br>Give the rule a name.<br>Use when_window or when_element, not both. | packages |
| `INTERRUPT.NO_REPOSITORY` | This run cannot start child agents | Run the agent from OneAgentStudio or One CommandCenter. | packages |
| `INTERRUPT.NO_SESSION` | No browser session '…' is open to click … | Use x,y screen coordinates for desktop windows, or open the browser session first. | packages |

## JAVA

| Code | What it means | What to do | Where |
|---|---|---|---|
| `JAVA.NOT_INSTALLED` | No Java found on this runner | Install Java 11 or later, or give its program in java. | packages |
| `JAVA.SETUP` | Cannot find the runner's own classes for the Java call: … | Reinstall the runner; its program files are incomplete. | packages |

## JAVA_APPS

| Code | What it means | What to do | Where |
|---|---|---|---|
| `JAVA_APPS.ATTACH_FAILED` | The Java application did not answer the agent<br>Cannot connect to the Java application …: … | Turn on the Java Access Bridge on the runner (jabswitch -enable) and start the application again.<br>The application must run as the same user as the runner, on Java 8 or later, and allow agents (no -XX:-EnableDynamicAgentLoading). | packages-windows |
| `JAVA_APPS.BAD_INPUT` | Give the control's name, role or path | Give a control's name, role or path; Capture in the step fills them in. | packages-windows |
| `JAVA_APPS.FAILED` | The Java application refused: …error… | Check that the Java application is open and its Access Bridge is on; the message has its answer. | packages-windows |
| `JAVA_APPS.NOT_INSTALLED` | The Java applications agent is missing from this runner | Reinstall the runner. | packages-windows |
| `JAVA_APPS.NOT_REACHABLE` | The Java application … no longer answers: … | Check it is still open. | packages-windows |
| `JAVA_APPS.OBJECT_NOT_FOUND` | No control ……named '…' ……with role '…' …in a Java window…… titled '…'… | Use Find Java objects to see the controls, or Get Java applications to see which programs are reachable. | packages-windows |

## JIRA

| Code | What it means | What to do | Where |
|---|---|---|---|
| `JIRA.BAD_COMMENT_ID` | Invalid comment id '…' | Comment ids are numbers; read them with Get comments. | toolkits/enterprise |
| `JIRA.BAD_DATE` | Invalid due date '…' | Use yyyy-MM-dd. | toolkits/enterprise |
| `JIRA.BAD_FIELD` | Invalid field name '…' | Use the field's id as Jira shows it (for example summary or customfield_10010). | toolkits/enterprise |
| `JIRA.BAD_ID` | Invalid category id '…' | Give the category's number id, as Jira shows it in the category's address. | toolkits/enterprise |
| `JIRA.BAD_KEY` | Invalid issue key '…' | Example: OPS-123. | toolkits/enterprise |
| `JIRA.BAD_LABEL` | Labels cannot contain spaces: '…' | Write each label as one word; use - or _ instead of spaces. | toolkits/enterprise |
| `JIRA.BAD_PROJECT_KEY` | Invalid project key '…' | Use upper-case letters and digits, e.g. OPS. | toolkits/enterprise |
| `JIRA.BAD_VISIBILITY` | Give a role or a group, not both | Fill in either Visible to role or Visible to group, not both. | toolkits/enterprise |
| `JIRA.NO_FIELDS` | No fields to change | Give at least one field, e.g. {"summary": "New title"}.<br>Give at least one field, e.g. {"name": "Operations"}. | toolkits/enterprise |
| `JIRA.NO_LEAD` | A project lead is required | Give lead_account_id (Cloud) or lead_user_name (Data Center). | toolkits/enterprise |
| `JIRA.NO_TRANSITION` | Transition '…' is not available for … | Available now: …. | toolkits/enterprise |
| `JIRA.USER_NOT_FOUND` | No Jira user with email … | Check the address, or give the account id. | toolkits/enterprise |

## JOBS

| Code | What it means | What to do | Where |
|---|---|---|---|
| `JOBS.BAD_CALL` | Unknown request '…' | Use one of the requests the agent toolkit offers; update the runner if it is older than One CommandCenter. | control-room |
| `JOBS.NOT_AVAILABLE` | This step works only when the agent runs from One CommandCenter | Check the agent in and run it from One CommandCenter; in Studio it has no other runs or alert rules to use. | packages |
| `JOBS.NOT_FOUND` | There is no run '…' that … may see | Check the run id in Agent Activity; you see only runs you started or may manage. | control-room |
| `JOBS.NOT_RUNNING` | Only a running agent can ask One CommandCenter | Use this only from a step of a running agent. | control-room |
| `JOBS.NO_ID` | Give the run's id | Take it from List jobs, from a Run agent step's run id, or from the run's page. | packages |
| `JOBS.NO_USER` | The run's user '…' is not an active user of One CommandCenter | Run the agent as an active user. | control-room |
| `JOBS.SELF` | An agent cannot stop its own run this way | Use the Stop agent step. | control-room, packages |
| `JOBS.TIMEOUT` | No answer from One CommandCenter | Check that One CommandCenter is running and not overloaded; the step can be run again. | agent |

## JSON

| Code | What it means | What to do | Where |
|---|---|---|---|
| `JSON.BAD_INDENT` | Indent must be between 0 and 16 | Use 2 or 4. | packages |
| `JSON.BAD_PATH` | 'paths' must be a Dictionary or a List<br>Entry '…' has no path<br>Entry '…' has an unknown key '…' | Example: {"id": "order.id"}.<br>Write it as {"path": "order.total", "type": "Number"}.<br>Use only path and type. | packages |
| `JSON.BAD_SCHEMA` | Referenced schema … is larger than 5 MB<br>Cannot load a schema from '…'<br>Referenced schema … is not valid JSON: … | Keep referenced schemas under 5 MB, or copy the part you need into the main schema.<br>Use a file next to the schema or an http(s) URL.<br>Check the referenced file. | packages |
| `JSON.BAD_TYPE` | Unknown type '…' for '…' | Use String, Number, DateTime or Boolean. | packages |
| `JSON.CONVERT_FAILED` | '…': cannot convert … to … | …Number…Check the value is numeric, e.g. 1234.50, or leave the type out.…DateTime…Use ISO dates such as 2026-10-18 or 2026-10-18T14:30:00, or leave the type out.…Boolean…Use true/false, yes/no or 1/0, or leave the type out.…Leave the type out. | packages |
| `JSON.INVALID` | Invalid JSON at line …, column …: …<br>…<br>…: … | Check commas, quotes and brackets.<br>Check the JSON syntax. | adapters-jackson, packages |
| `JSON.NODE_EXISTS` | Key '…' already exists at '…' | Use update_node to change it, or set if_exists. | packages |
| `JSON.NOT_AN_ARRAY` | The node at '…' is …, not a list<br>Expected a list of objects, got … | Point the path at a JSON array. | packages |
| `JSON.NOT_A_DOCUMENT` | Expected a Dictionary, List or JSON text, got … | Parse the JSON first. | packages |
| `JSON.NOT_OBJECTS` | Item … is …, not an object | Every array item must be a JSON object. | packages |
| `JSON.PATH_NOT_FOUND` | Path '…' does not exist<br>'…' in path '…' is not a list index<br>Index … in path '…' is outside the list (size …) | Check the path; keys are case-sensitive.<br>Use a number such as [0] for list items.<br>Indexes start at 0. | packages |
| `JSON.REF_NOT_FOUND` | Referenced schema file not found: … | Check the $ref file name; it is read relative to the schema file's folder. | packages |
| `JSON.REF_UNREACHABLE` | Cannot load referenced schema …: HTTP …<br>Cannot load referenced schema …: … | Check the $ref URL.<br>Check the network and the $ref URL. | packages |
| `JSON.WRITE_FAILED` | … | Check the value; it holds something that cannot be written as JSON. | adapters-jackson |

## KAFKA

| Code | What it means | What to do | Where |
|---|---|---|---|
| `KAFKA.BAD_GROUP` | Invalid consumer group '…' | Use letters, digits, dot, underscore and dash. | toolkits/cloud-data |
| `KAFKA.BAD_OFFSET` | An offset of 0 or more is required | Use the offset column from Read messages. | toolkits/cloud-data |
| `KAFKA.BAD_PARTITION` | A partition number of 0 or more is required | Use the partition column from Read messages. | toolkits/cloud-data |
| `KAFKA.BAD_TOPIC` | Invalid topic name '…'<br>Give at least one topic | Topic names use letters, digits, dot, underscore and dash only.<br>Example: ["orders"]. | toolkits/cloud-data |
| `KAFKA.NO_CLUSTER` | The REST Proxy reports no Kafka cluster | Check the address, or give cluster_id. | toolkits/cloud-data |
| `KAFKA.NO_TOKEN` | Sign-in details are required | Map api_key and api_secret, or access_token, to vault values. | toolkits/cloud-data |
| `KAFKA.PRODUCE_FAILED` | Kafka did not accept the message: …message… | Check the topic exists and the account may write to it. | toolkits/cloud-data |
| `KAFKA.TOPIC_EXISTS` | Topic '…' already exists | Use another name, or turn off fail_if_exists. | toolkits/cloud-data |

## KNOWLEDGE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `KNOWLEDGE.BAD_INDEX` | … is not a knowledge index<br>Cannot read the index …: … | Give an index file made by Build a knowledge index, or a knowledge base name.<br>Build it again. | toolkits/ai |
| `KNOWLEDGE.BAD_INPUT` | Each item of texts is a dictionary with source and text<br>overlap must be smaller than chunk_size | Give texts as a list of dictionaries, each with source and text, for example [{"source": "faq", "text": "..."}].<br>Make overlap smaller than chunk_size (for example chunk_size 1000 and overlap 100). | toolkits/ai |
| `KNOWLEDGE.BAD_NAME` | The knowledge base name '…' is not allowed | Use letters, digits, spaces, - and _ only in a knowledge base name. | toolkits/ai |
| `KNOWLEDGE.BAD_URL` | Not a web address: … | Give the full address, starting with https://. | toolkits/ai |
| `KNOWLEDGE.EMPTY` | The knowledge base …name… has no index yet<br>There was no text to index | Run its indexing agent (Build index with this knowledge base).<br>…Give files or texts.…No file could be read: … | modules/ai-services, toolkits/ai |
| `KNOWLEDGE.FETCH` | HTTP … | Check the address, and that the site lets the runner read it (it may need a sign-in or block automated readers). | toolkits/ai |
| `KNOWLEDGE.NO_INDEX` | No knowledge index at …<br>Give an index file or a knowledge base<br>Say where the index goes: an index file or a knowledge base | Make it with Build index first.<br>Fill in either Index file or Knowledge base.<br>Fill in either Index file or Knowledge base, to say where the index is saved. | toolkits/ai |
| `KNOWLEDGE.TOO_LARGE` | The documents make … passages; an index holds at most … | Split them into several indexes, or raise chunk_size. | toolkits/ai |
| `KNOWLEDGE.UNREADABLE` | Cannot read …<br>Cannot read …: … | Check that the file exists and that the runner's account may read it.<br>Check that the file is not damaged or open in another program, and that the runner's account may read it.<br>Check the PDF is not password protected. | toolkits/ai |
| `KNOWLEDGE.UNSUPPORTED` | Cannot read … for the index<br>The file is not a Word document<br>This runner cannot read … | Use text, Markdown, HTML, CSV, JSON, XML, .docx or PDF files, or pass the text with texts (for example from the PDF or OCR toolkit).<br>Give a .docx file, or convert the document to Word or PDF first.<br>Pass the text with texts instead. | toolkits/ai |

## LDAP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `LDAP.FAILED` | The directory answered with an error: … | Check the directory settings in Administration > Settings > Sign-in (address, bind user, search base) and that the server can reach the directory. | control-room |
| `LDAP.NAME_TAKEN` | An account named … exists already and is not from the directory | Ask an administrator to rename or remove it. | control-room |
| `LDAP.NOT_SET_UP` | Sign-in with Active Directory or LDAP is not set up<br>The directory's base, service account and its password are needed | An administrator sets it in Settings > Single sign-on.<br>Fill them in Settings > Single sign-on. | control-room |
| `LDAP.NO_ROLE` | Your directory account is in no group that gives access to One CommandCenter | Ask an administrator to add you to a group in the groups-to-roles list. | control-room |
| `LDAP.SERVICE_ACCOUNT` | The directory refused the service account … | Check ldap_bind_dn and its password in the vault. | control-room |
| `LDAP.UNREACHABLE` | The directory could not be reached…… (…)… | Check the addresses, the network and the certificate. | control-room |

## LICENSE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `LICENSE.BAD_INPUT` | Unknown feature …; features: …<br>The expiry date is in the past | Correct the value the message names and make the license again. | vendor-tools |
| `LICENSE.BAD_KEY` | Not an Ed25519 private key<br>Not an Ed25519 public key | Use the vendor's Ed25519 private key file.<br>Use the vendor's Ed25519 public key. | control-room |
| `LICENSE.DB_REQUIRED` | A licensed installation runs on Microsoft SQL Server; this one still uses the built-in evaluation database | … | control-room |
| `LICENSE.EXPIRED_FILE` | This license expired on … | Ask for a current license | control-room |
| `LICENSE.INVALID` | …<br>Paste or upload the license file | Install the license file exactly as ONEOFFICEAUTOMATION sent it<br>Paste the license file's text, or upload the .lic file you received. | control-room |
| `LICENSE.KEY_FAILED` | Key generation failed: … | Check that the license tool may write the keys folder. | control-room |
| `LICENSE.OTHER_INSTALL` | … | Send this install id (Administration > Licenses) to ONEOFFICEAUTOMATION for a license issued for it | control-room |
| `LICENSE.SIGN_FAILED` | Signing failed: … | Check the vendor key file in the license keys folder; it must be the Ed25519 private key. | control-room |
| `LICENSE.TOO_FEW_SEATS` | This license has … … seats but … are assigned | Take seats back in Administration > Licenses first | control-room |

## LIST

| Code | What it means | What to do | Where |
|---|---|---|---|
| `LIST.INDEX` | Index … is outside the list (size …) | Indexes start at 0. | packages |
| `LIST.NOTHING_TO_REMOVE` | Give a position or a value to remove | Fill in either Position or Value. | packages |
| `LIST.NO_FIELD` | No field given | Fill in the field name, e.g. email. | packages |

## LOCK

| Code | What it means | What to do | Where |
|---|---|---|---|
| `LOCK.BAD_LEASE` | The lease is a number of seconds, not …<br>The lease is between … seconds and 7 days (… seconds)<br>The lease is between 5 seconds and 7 days (604800 seconds) | Give the lease as a number of seconds.<br>Give a lease within the limits the message names.<br>Give a lease between 5 seconds and 7 days. | control-room, packages |
| `LOCK.BAD_NAME` | Give the lock a name<br>A lock's name has at most … characters<br>A lock's name has at most 200 characters | Use the name of the system it guards, e.g. SAP production login.<br>Shorten the lock's name.<br>Shorten the lock's name to 200 characters or fewer. | control-room, packages |
| `LOCK.NO_ANSWER` | No answer from One CommandCenter about the lock | Check that One CommandCenter is running and not overloaded; the step can be run again. | agent |
| `LOCK.RUN_NOT_RUNNING` | Locks are only for running agents; run … is …status… | Take locks only from a step of a running agent. | control-room |
| `LOCK.TIMEOUT` | The lock … is held by another run…… (…)… and was not given back within … seconds…… | Wait longer, or see who holds it in One CommandCenter (Manage > Environment locks). | packages |

## LOG

| Code | What it means | What to do | Where |
|---|---|---|---|
| `LOG.BAD_ENCODING` | Unknown encoding '…' | Use utf-8, utf-16le or ansi. | packages |
| `LOG.BAD_FIELD` | '…' cannot be a log field name | Start with a letter; use letters, digits, _ . and -, at most 64 characters. | packages |
| `LOG.BAD_LEVEL` | Unknown level '…' | Use DEBUG, INFO, WARN or ERROR. | packages |
| `LOG.BAD_TIMESTAMP_FORMAT` | Invalid timestamp format '…' | Use a pattern such as yyyy-MM-dd HH:mm:ss. | packages |
| `LOG.BAD_TYPE` | Unknown variable type '…' | Use all, String, Number, Boolean, DateTime, List, Dictionary, Table, Record, File, Window, UIElement, Credential, Any, Time, TimeSpan, Image or Binary. | packages |
| `LOG.IO_ERROR` | Cannot write log …: … | Check the folder exists and is writable. | packages |
| `LOG.NOTHING_TO_LOG` | No variables to log | Fill in variables, or choose a type in all_of_type. | packages |
| `LOG.NO_FIELDS` | No log fields given | Give at least one name and value. | packages |
| `LOG.NO_VARIABLES` | This run cannot list its variables | List the variables in the variables input instead. | packages |
| `LOG.TOO_MANY_FIELDS` | A run can have at most 50 log fields | Remove fields that are no longer needed. | packages |

## LONGRUN

| Code | What it means | What to do | Where |
|---|---|---|---|
| `LONGRUN.BAD_TIME` | '…' is not a date and time | Write it as 2026-11-09T08:00 or 2026-11-09T08:00:00+05:30. | packages |
| `LONGRUN.CANNOT_SUSPEND` | The run cannot be suspended …: it could not be resumed at the right place | … | runner |
| `LONGRUN.NOT_AVAILABLE` | Serverless Agent APIs cannot be suspended<br>An agent can only be suspended when it runs from One CommandCenter | Run the agent on a runner (not as a serverless Agent API) when it needs to wait.<br>Check the agent in and run it from One CommandCenter; in Studio or on the command line there is nothing to resume it. | control-room, packages |
| `LONGRUN.NOT_REGISTERED` | The run ended as suspended, but One CommandCenter has no wait for it | Run the agent again; if it keeps happening, report it with the run id. | control-room |
| `LONGRUN.NO_ID` | Give the … | … | packages |
| `LONGRUN.NO_PUBLIC_URL` | One CommandCenter does not know its own address, so it cannot make a callback URL | An administrator sets it in Administration > Settings (Public address). | control-room |
| `LONGRUN.NO_TIME` | Say how long to wait, or until when | Fill in either Wait for (a length of time) or Until (a date and time). | packages |
| `LONGRUN.NO_USER` | The run's user … is no longer an active user | Ask an administrator to enable the run's user, or start the run again as another user. | control-room |
| `LONGRUN.RESUME_FAILED` | The run could not be resumed: … | … | control-room |
| `LONGRUN.SELF` | A run cannot wait for itself | Wait for another run's id. | packages |
| `LONGRUN.SESSION_OPEN` | These sessions are open and cannot be carried to the resumed run: …, … | Close them (Close browser, Close workbook, Close file...) before the wait, and open them again after it. | runner |
| `LONGRUN.TIMEOUT` | Waited for … until the timeout | Catch LONGRUN.TIMEOUT in a Try around this step, or set On timeout to continue and check Timed out. | packages |
| `LONGRUN.TOO_LARGE` | The agent's values take … characters; at most … can be kept while it waits | Keep big tables and texts in a file or a work item, and only their name in a variable. | control-room |
| `LONGRUN.TOO_MANY` | A run makes at most … callback URLs | Make fewer callback addresses in one run. | control-room |

## LOOP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `LOOP.BAD_RANGE` | '…' must be a whole number, got …<br>from_index and to_index cannot be negative<br>step must be 1 or more for this iterator, got … | Use 0, 1, 2 ...<br>Indexes start at 0.<br>Use 1 to visit every item, 2 for every second item. | runner |
| `LOOP.LIMIT` | Loop exceeded … iterations | A reversed loop reads every item first; raise max_iterations or turn reverse off.<br>Check the loop condition or raise max_iterations. | runner |
| `LOOP.NO_COLUMN` | The current row has no column '…' | Columns: …. | runner |

## M365

| Code | What it means | What to do | Where |
|---|---|---|---|
| `M365.ATTACHMENT_TOO_LARGE` | … is larger than 3 MB | Upload it to OneDrive and send a link instead.<br>Upload it to OneDrive and share a link instead. | toolkits/microsoft |
| `M365.BAD_INPUT` | @… mentions work in channel posts only<br>The message mentions @[…], which is not in mentions<br>…Give team_id or team_name, not both…A team is required | Mention people by email address in chats.<br>Add … to mentions.<br>Set team_id or team_name. | toolkits/microsoft |
| `M365.BAD_ITEM` | No item given | Give a path or id:{item-id}; the drive root cannot be deleted. | toolkits/microsoft |
| `M365.BAD_OPTION` | Unknown … '…'<br>Unknown weekday '…' in recurrence_days_of_week<br>Unknown recurrence_week_of_month '…' | Use …, ….<br>Use full English day names such as monday or friday.<br>Use first, second, third, fourth or last. | toolkits/microsoft |
| `M365.BAD_RANGE` | Invalid range '…'<br>Invalid start cell '…'<br>Invalid cell '…' | Example: A1:D20.<br>Example: A1.<br>Example: B2. | toolkits/microsoft |
| `M365.BAD_RESPONSE` | The next page is on another server (…)<br>Planner did not return the task's version (ETag) | Check base_url.<br>The service answered in an unexpected way; try again, and if it keeps happening check the service's status page. | toolkits/microsoft |
| `M365.BAD_STATUS` | Unknown status '…' | Use read, unread, flagged, unflagged or complete. | toolkits/microsoft |
| `M365.BAD_TIMES` | The end must be after the start<br>The event's start and end could not be read<br>The event's start could not be read | Set the end later than the start.<br>Give start and end.<br>Give start. | toolkits/microsoft |
| `M365.CONFLICT` | The task kept changing while it was being updated | Try again. | toolkits/microsoft |
| `M365.COPY_FAILED` | The copy failed: …error…message… | Check the destination and that the name is free. | toolkits/microsoft |
| `M365.EXISTS` | A file named … already exists there | Set if_exists to replace or rename. | toolkits/microsoft |
| `M365.NOTHING_TO_DO` | No formatting was given<br>No changes were given<br>Nothing was ticked to clear | Set at least one formatting input, such as fill_color, bold or font_name.<br>Set at least one of subject, start, end, body or location.<br>Tick at least one detail to clear. | toolkits/microsoft |
| `M365.NOT_FOUND` | No calendar named '…'<br>No worksheet at position …<br>No … named '…' | …Check the name and the account's access.…Available: …, ….<br>The workbook has … worksheet(s); the first is 1. | toolkits/microsoft |
| `M365.NO_FOLDER` | Mail folder '…' not found | Check the path, e.g. Inbox/Invoices; names are not case-sensitive. | toolkits/microsoft |
| `M365.NO_OLDER_VERSION` | The file has no earlier version | Version history starts after the file is changed. | toolkits/microsoft |
| `M365.NO_RECIPIENTS` | No recipients to forward to<br>Without the sender there is nobody left to reply to<br>Nobody to share with | Give at least one address.<br>Turn off exclude_sender.<br>Give at least one email address. | toolkits/microsoft |
| `M365.NO_VALUES` | The range returned no cells<br>There are no values to write<br>The Data Table is empty | Check the sheet and range.<br>Give a list of rows, each a list of cell values.<br>Check that the table has columns and rows. | toolkits/microsoft |
| `M365.UPLOAD_EXPIRED` | The upload session expired after … of … bytes<br>The upload session expired | Run the step again to start a new upload. | toolkits/microsoft |
| `M365.UPLOAD_FAILED` | The service did not open an upload session<br>The service accepted every byte but did not create the file | Try again later. | toolkits/microsoft |
| `M365.USER_NOT_FOUND` | No user '…' to mention | Use the person's email address (user principal name) or user id. | toolkits/microsoft |

## MAIL

| Code | What it means | What to do | Where |
|---|---|---|---|
| `MAIL.BAD_ADDRESS` | Not an e-mail address: … | Correct the address, for example name@example.com. | control-room |
| `MAIL.FAILED` | Could not reach the mail server …:…: … | Check the server, port and security in Administration > Settings > E-mail | control-room |
| `MAIL.NOT_SET_UP` | E-mail is not set up | Fill in the outgoing mail server and sender in Administration > Settings > E-mail | control-room |
| `MAIL.NO_RECIPIENT` | No one to send the e-mail to<br>Give an address to send the test to<br>None of the people or roles has an e-mail address | Add at least one recipient.<br>Or add your own e-mail address to your user<br>Add an e-mail address to the people (Administration > Users), or send to addresses directly. | control-room, modules/insights |
| `MAIL.NO_TLS` | The mail server does not offer STARTTLS | Choose SSL, or none only on a trusted network | control-room |
| `MAIL.REFUSED` | The mail server answered: … | Check the sender, the account and the recipients | control-room |
| `MAIL.TOO_MANY` | At most 50 recipients | Send to at most 50 people at a time, or use a distribution list. | control-room |

## MCP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `MCP.BAD_ANSWER` | The MCP server gave no answer to …<br>The MCP server's answer is not JSON | Check that the address is an MCP server and that it is running.<br>Check that the address is an MCP server (not a web page) and that it is running. | packages |
| `MCP.BAD_URL` | The MCP server address starts with https:// (or http:// on this computer's network) | Give the MCP server's address starting with https://. | packages |
| `MCP.DENIED` | The MCP server refused the sign-in (HTTP …) | Check the token. | packages |
| `MCP.ERROR` | The MCP server refused …: …message… | Check the tool's inputs against what the MCP server expects; the message has the server's answer. | packages |
| `MCP.HTTP` | The MCP server answered HTTP … | Check the MCP server's address and its sign-in token; the server may also be down. | packages |
| `MCP.NO_RUNNER` | No runner is online to run … | Start a runner (Manage > Runners), then try again. | modules/integration |
| `MCP.NO_TOOL` | Give the tool's name | Fill in the tool's name; List tools shows the names the server offers. | packages |
| `MCP.TOOL_ERROR` | The tool … reported an error: …text… | Check the tool's inputs against what the MCP server expects; the message has the server's answer. | packages |
| `MCP.UNKNOWN_TOOL` | No exposed agent is called … | Check the tool's name; Build > Agent APIs lists the agents offered over MCP. | modules/integration |
| `MCP.UNREACHABLE` | Cannot reach the MCP server …: … | Check the address and that the server is running. | packages |

## MINING

| Code | What it means | What to do | Where |
|---|---|---|---|
| `MINING.BAD_TIME` | Row …: cannot read the time '…' | Use ISO times (2026-11-06T09:30:00Z) or yyyy-MM-dd HH:mm[:ss]. | toolkits/ai |
| `MINING.EMPTY` | The event log is empty | Check that the event log has rows below its header line. | toolkits/ai |
| `MINING.NO_COLUMN` | The event log has no column '…' | Columns: … | toolkits/ai |
| `MINING.NO_LOG` | Give the event log as a CSV file or a table | Fill in either Event log file (CSV) or Event log table. | toolkits/ai |
| `MINING.NO_MODEL` | Give the allowed flows | Give the allowed flows as a list of pairs, for example [["Receive", "Approve"], ["Approve", "Pay"]]. | toolkits/ai |
| `MINING.TOO_LARGE` | At most 2,000,000 events at a time | Filter the log to fewer events (for example one month or one process) and mine each part. | toolkits/ai |

## MOVE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `MOVE.DAMAGED` | The move file is damaged or incomplete: … | Copy the file again (check its size), or export it again; nothing was imported. | control-room |
| `MOVE.EXPORT` | Reading … failed: … | Check that the database is running; then export again. | control-room |
| `MOVE.FORMAT` | Unknown move file format …format…<br>This is not a ONEOFFICEAUTOMATION move file | Import it into a region of the release that made it.<br>Choose the file ooa tenant export wrote (.ooamove). | control-room |
| `MOVE.PASSPHRASE` | Set … to the move file's passphrase<br>The passphrase must be at least … characters<br>The passphrase does not open this move file | Put the passphrase in the environment variable --password-env names (not on the command line, where others could see it).<br>Use a long passphrase and keep it apart from the file; the import needs it.<br>Use the passphrase given to ooa tenant export (or the file was changed; copy it again). | cli, control-room |
| `MOVE.SECRET` | A secret in … (…#user:.*……) does not decrypt with this server's vault key | Check that OOA_MASTER_KEY or master.key is this One CommandCenter's key (ooa check); remove or set again the value the message names, then export again. | control-room |
| `MOVE.VERSION` | The database is at schema version …; this release needs …<br>The file was made by release …product_version… (schema …); this region runs … (schema …)<br>The file holds data of module …, which this region does not have | Run the export with the release that runs this One CommandCenter, after ooa db migrate.<br>…Upgrade the on-premises One CommandCenter to the region's release, then export again.…Upgrade the region to the release that made the file, then import again.<br>Install the module in the region, or import into a region that has it. | control-room |

## NETWORK

| Code | What it means | What to do | Where |
|---|---|---|---|
| `NETWORK.UNKNOWN_HOST` | Unknown host '…' | Check the host name and DNS. | packages |

## NUMBER

| Code | What it means | What to do | Where |
|---|---|---|---|
| `NUMBER.BAD_DECIMALS` | Decimals must be between 0 and 15, got …<br>Decimals cannot be negative (…) | Use 0 for whole numbers.<br>Use 0 or more. | packages |
| `NUMBER.BAD_PATTERN` | Invalid number pattern '…': … | Use 0, #, comma and dot, e.g. #,##0.00. | packages |
| `NUMBER.BAD_RANGE` | max (…) is smaller than min (…)<br>No number with … decimals lies between min and max | Swap min and max.<br>Widen the range or allow more decimals. | packages |

## OBJECT

| Code | What it means | What to do | Where |
|---|---|---|---|
| `OBJECT.BAD_REPOSITORY` | An object repository is a file ending in .objects.json: …<br>The object repository must be inside the workspace: …<br>… is not an object repository | Choose an object repository file (.objects.json) inside the workspace; Capture makes one. | runner |
| `OBJECT.NOT_IN_REPOSITORY` | The object repository … has no element …<br>The element … in … has no captured object | Open the repository and check the application, screen and element names, or choose the element again.<br>Capture the element again in the repository. | runner |
| `OBJECT.REPOSITORY_NOT_FOUND` | Object repository not found: … | Check the path. From One CommandCenter, check the repository file in with the agent. | runner |

## OCR

| Code | What it means | What to do | Where |
|---|---|---|---|
| `OCR.ACCESS_DENIED` | Adding a Windows language needs administrator rights | Run the step on a runner with administrator rights, or ask IT to add the OCR language. | packages |
| `OCR.BAD_ENGINE` | The cloud engine is azure or google | Set the cloud engine to azure or google. | packages |
| `OCR.BAD_LANGUAGE` | Invalid language '…'<br>Invalid language tag '…' | Examples: eng, deu, eng+fra.<br>Use a tag such as de-DE or fr-FR. | packages |
| `OCR.BAD_MODE` | Image mode must be none, gray or black_white | Use none, gray or black_white. | packages |
| `OCR.BAD_REGION` | A region needs x, y, width and height, with width and height above 0<br>The region lies outside the image (…x…)<br>The area starts outside the window (…x…) | Set all four, or none for the whole image.<br>Check the coordinates.<br>Check x and y against the window's size. | packages |
| `OCR.BAD_SCALE` | Scale must be between 1 and 5 | Use 2 or 3 for small text. | packages |
| `OCR.BAD_URL` | '…' is not a URL<br>Only http and https image addresses are supported | Use an http or https address.<br>Download the file first for other locations. | packages |
| `OCR.DOWNLOAD_FAILED` | The server answered … for …<br>Cannot download …: … | Check the address is reachable without signing in.<br>Check the address and the network. | packages |
| `OCR.FAILED` | Tesseract failed: …<br>Windows OCR failed: … | Check the image is readable. | packages |
| `OCR.LANGUAGE_FAILED` | Could not add the OCR language …: … | Check the runner can reach Windows Update. | packages |
| `OCR.LANGUAGE_MISSING` | Tesseract language data '…' is not installed<br>The Windows OCR engine has no language for '…'<br>Windows has no OCR language '…' installed…… (installed: …)… | Install the language pack for Tesseract.<br>Use a language Windows knows (…, …) or install Tesseract.<br>Add the language in Windows Settings > Time & language > Language (with its OCR feature), or install Tesseract with that language. | packages |
| `OCR.LANGUAGE_UNAVAILABLE` | Windows has no OCR language pack for … | Check the tag; Get OCR engines lists the installed ones. | packages |
| `OCR.NOT_INSTALLED` | No OCR engine on this runner: Tesseract is not installed…… (the Windows OCR engine needs Windows 10 or later)…<br>The Windows OCR engine is only on Windows<br>No OCR engine: Tesseract is not installed and Windows OCR is not available on this Windows version | Install Tesseract (on Windows the UB Mannheim installer, on Linux the tesseract-ocr package) or set OOA_TESSERACT to its program.<br>Install Tesseract, or unset OOA_OCR_ENGINE.<br>Install Tesseract (the UB Mannheim installer) or set OOA_TESSERACT to tesseract.exe. | packages |
| `OCR.SCREEN` | Cannot keep the screen picture: … | Check that the runner's temporary folder has space and may be written. | packages |
| `OCR.TEXT_NOT_FOUND` | '…' was not found on the screen… … times…… | Check the window is visible; try a shorter phrase. | packages |
| `OCR.UNSUPPORTED_IMAGE` | Not a readable image: …<br>The address did not return a readable image | Use PNG, JPG, BMP or GIF.<br>Use the direct address of a PNG, JPG, BMP or GIF image. | packages |

## OFFICE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `OFFICE.ENCRYPTED` | … is password protected | Open .docx and .pptx files with their password in Open document; old .doc and .ppt files must be saved without one. | packages-office |
| `OFFICE.UNREADABLE` | Cannot read …: … | Check the file is not damaged and has the right extension. | packages-office |
| `OFFICE.UNSUPPORTED` | Cannot read the text of … | Use .doc, .docx, .rtf, .ppt or .pptx. | packages-office |

## OFFICE_ATTACH

| Code | What it means | What to do | Where |
|---|---|---|---|
| `OFFICE_ATTACH.NO_FILE` | Give the open file's name or full path | For example Sales.xlsx or C:\Reports\Sales.xlsx. | packages |

## OKTA

| Code | What it means | What to do | Where |
|---|---|---|---|
| `OKTA.BAD_GROUP` | A group id or name is required | Use the group's id or its exact name. | toolkits/enterprise |
| `OKTA.BAD_INPUT` | Give either name_starts_with or search, not both | Clear one of the two inputs. | toolkits/enterprise |
| `OKTA.BAD_USER` | A user id or login is required | The person's Okta user id, or their sign-in name (login), e.g. ann.lee@acme.com. | toolkits/enterprise |
| `OKTA.GROUP_NOT_FOUND` | No Okta group is named '…' | Check the spelling, or use the group's id. | toolkits/enterprise |
| `OKTA.NOTHING_TO_UPDATE` | No profile values were given | Fill profile_fields with at least one value. | toolkits/enterprise |
| `OKTA.NO_TOKEN` | An Okta API token or access token from the vault is required | Map api_token (or access_token) to a Credential variable. | toolkits/enterprise |

## OUTLOOK

| Code | What it means | What to do | Where |
|---|---|---|---|
| `OUTLOOK.BAD_ACCOUNT` | No account … in this Outlook profile | Leave From account empty to send from the default account. | packages |
| `OUTLOOK.FAILED` | Outlook reported an error: … | Check that Outlook is installed, set up for the runner's account and not showing a dialog.<br>Check that Outlook has a mail profile for the runner's user and is not showing a dialog. | packages |
| `OUTLOOK.FOLDER_NOT_FOUND` | No folder … in the mailbox | Use Get folders to see the folder paths, e.g. Inbox/Invoices. | packages |
| `OUTLOOK.MAILBOX_NOT_FOUND` | The mailbox … is not open in this Outlook profile | Add it to the runner user's Outlook, or check its name. | packages |
| `OUTLOOK.NOT_FOUND` | No email with that id | The email may have been moved or deleted; ids change when an email is moved. | packages |
| `OUTLOOK.NOT_INSTALLED` | Microsoft Outlook is not installed on this runner | Install Outlook with a mail profile for the runner's user, or use Email or Microsoft 365. | packages |
| `OUTLOOK.NO_RECIPIENT` | Give at least one recipient | Add at least one recipient. | packages |

## PDF

| Code | What it means | What to do | Where |
|---|---|---|---|
| `PDF.BAD_AREA` | Width and height must be greater than 0 | Measure the area in points from the top-left corner of the page. | packages-pdf |
| `PDF.BAD_BOOKMARK_LEVEL` | bookmark_level must be 1 or more, not … | Use 1 for top-level bookmarks, 2 to include their sub-bookmarks. | packages-pdf |
| `PDF.BAD_COLOR` | Colour must be color, gray or black_white | Use color, gray or black_white. | packages-pdf |
| `PDF.BAD_FORMAT` | Unsupported image format '…' | Use png, jpg, bmp, gif or tiff. | packages-pdf |
| `PDF.BAD_KEY_LENGTH` | Key length must be 128 or 256, not … | Use 256, or 128 for older readers. | packages-pdf |
| `PDF.BAD_MODE` | Unknown split mode '…' | Use pages_per_file, ranges, selected_pages, blank_page or bookmarks. | packages-pdf |
| `PDF.BAD_OPTION` | min_columns must be at least 2 | A table needs two columns or more. | packages-pdf |
| `PDF.BAD_PAGES` | '…' is not a page or page range<br>Pages '…' are outside 1-…<br>No pages selected | Use page numbers and ranges such as 1,3,5-7.<br>The document has … pages.<br>The document has … page……s…. | packages-pdf |
| `PDF.BAD_VALUE` | Check box '…' cannot be set to '…'<br>Field '…' cannot be set to '…'…: …… | Use true or false….…, or its on-value ….…<br>…Use Read form fields to see what the field accepts.…Allowed values: …. | packages-pdf |
| `PDF.DECRYPT_FAILED` | … | Check the password. | packages-pdf |
| `PDF.ENCRYPT_FAILED` | … | Check that the PDF is not already encrypted and that the output folder may be written. | packages-pdf |
| `PDF.EXISTS` | A file already exists at … | Set overwrite to true or choose another name. | packages-pdf |
| `PDF.FORM_FAILED` | … | Check the field names (List form fields shows them) and the values' types. | packages-pdf |
| `PDF.IMAGES_FAILED` | Cannot extract images: … | Check the PDF opens in a viewer. | packages-pdf |
| `PDF.INVALID` | Cannot read PDF …: …<br>…<br>… has no XPS pages | Check it is a valid PDF file.<br>Check that the file is a PDF that is not damaged or password-protected.<br>Check it is an .xps or .oxps file. | packages-pdf |
| `PDF.MERGE_FAILED` | Cannot list …: …<br>… | Check that the folder exists and that the runner's account may read it.<br>Check that every file to merge is a valid PDF. | packages-pdf |
| `PDF.NEED_FILES` | Give at least two PDFs to merge | Fill in files, or a folder with two or more PDFs. | packages-pdf |
| `PDF.NO_BOOKMARKS` | The PDF has no bookmarks pointing to pages | Use another split mode, or add bookmarks to the PDF. | packages-pdf |
| `PDF.NO_FIELD` | Fields not found: … | Use Read form fields without field names to see the exact names.<br>Use Read form fields to see the exact field names. | packages-pdf |
| `PDF.NO_FORM` | This PDF has no form fields | Use a PDF with form fields, or write the text onto the page instead. | packages-pdf |
| `PDF.NO_PAGE` | There is no page … (the file has …) | Use a page from 1 to …. | packages-pdf |
| `PDF.NO_PAGES` | Every page of the PDF is blank | Check the file, or use another split mode. | packages-pdf |
| `PDF.PASSWORD_REQUIRED` | The PDF is password-protected or the password is wrong | Provide the password from the vault. | packages-pdf |
| `PDF.RENDER_FAILED` | … | Check that the file is a PDF that is not damaged or password-protected. | packages-pdf |
| `PDF.SPLIT_FAILED` | … | Check that the file is a PDF that is not damaged or password-protected. | packages-pdf |
| `PDF.TEXT_FAILED` | … | Check that the file is a PDF that is not damaged or password-protected. | packages-pdf |

## PGP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `PGP.BAD_ALGORITHM` | Unknown algorithm '…' | Use ed25519, rsa3072 or rsa4096. | packages |
| `PGP.BAD_CIPHER` | Unknown cipher '…' | Use AES256, AES192, AES128, CAMELLIA256, TWOFISH or leave it empty for the default. | packages |
| `PGP.BAD_COMMENT` | The comment may not contain < > ( ) or control characters | Remove < > ( ) and control characters from the comment. | packages |
| `PGP.BAD_COMPRESSION` | Unknown compression '…' | Use none, zip, zlib or bzip2, or leave it empty for the default. | packages |
| `PGP.BAD_EMAIL` | Invalid email address '…' | Give an address such as finance-agent@example.com. | packages |
| `PGP.BAD_EXPIRY` | Invalid expiry '…' | Use e.g. 1y, 6m, 90d, a date like 2028-12-31, or never. | packages |
| `PGP.BAD_KEY` | Invalid key '…' | Give the key's id or file as the step expects. | packages |
| `PGP.BAD_NAME` | Invalid key owner name '…' | Give a plain name, e.g. Finance Agent. | packages |
| `PGP.BAD_PASSPHRASE` | Wrong passphrase | Check the passphrase in the vault. | packages |
| `PGP.BAD_PASSPHRASE_INPUT` | The passphrase must come from the credential vault | Pick a credential for passphrase. | packages |
| `PGP.BAD_RECIPIENT` | Invalid recipient '…' | Give the recipient's e-mail address or key id as the public key shows it. | packages |
| `PGP.DECRYPT_FAILED` | Decryption failed: the file holds no data<br>Decryption failed: the file was changed after it was encrypted<br>Decryption failed: … | Check the file is OpenPGP-encrypted data.<br>Ask the sender for the file again.<br>Check the file is OpenPGP-encrypted and the private key matches. | packages |
| `PGP.EMPTY_PASSPHRASE` | The passphrase is empty | Store a non-empty passphrase in the vault. | packages |
| `PGP.ENCRYPT_FAILED` | No public key for '…' in the key folder<br>Encryption failed: … | Import the recipient's public key first.<br>Check the key and that the target folder is writable.<br>…Check GnuPG is installed and the target folder is writable.…Import the recipient's public key first. | packages |
| `PGP.EXPORT_FAILED` | Exporting the public key failed: …<br>Exporting the private key failed: … | Check that the key exists in the key folder and that GnuPG is installed on the runner.<br>Check the passphrase. | packages |
| `PGP.IMPORT_FAILED` | No key was read from …: …<br>The key could not be added: …<br>No key was imported from … | Check the file is an OpenPGP key. | packages |
| `PGP.KEYGEN_FAILED` | Key generation failed: …<br>Adding the encryption subkey failed: … | Try again; check the key folder is writable.<br>Check GnuPG 2.1 or later is installed and the key folder is writable.<br>Check the passphrase and GnuPG version. | packages |
| `PGP.KEYRING_FAILED` | Cannot read the keys in …: … | Check the key folder holds keys written by this toolkit. | packages |
| `PGP.NOT_INSTALLED` | Only the gpg that comes with Git for Windows was found; it cannot work with Windows paths<br>GnuPG is not installed on this runner | Install Gpg4win, or set OOA_GPG to the full path of a Windows gpg.exe.<br>Install Gpg4win (Windows) or GnuPG, or set OOA_GPG. | packages |
| `PGP.NO_RECIPIENT` | Give at least one recipient or a passphrase | Fill in recipients, public_key_file or passphrase. | packages |
| `PGP.NO_TARGET` | Give the file to encrypt<br>Cannot work out the decrypted file name: '…' does not end in .pgp, .gpg or .asc | Fill in 'path'.<br>Fill in 'target'. | packages |
| `PGP.SIGN_FAILED` | Signing failed: …the key folder has no signing key…no signing key for '…'…<br>Signing failed: … | Create or import a key with its private part first.<br>Check the key and passphrase. | packages |

## PKG

| Code | What it means | What to do | Where |
|---|---|---|---|
| `PKG.BAD_JAR` | The file is not a toolkit jar: …<br>Cannot read …: …<br>The class … is not in the jar | Build the toolkit with the OOA SDK and sign it with ooa toolkit sign.<br>Check that the file is the toolkit's jar as its publisher made it.<br>Pack the toolkit classes into the jar. | sdk |
| `PKG.BAD_KEY` | Not an Ed25519 public key<br>… is not a publisher key file | Paste the public key file ooa toolkit keygen wrote (one Base64 line).<br>Use the publisher's key file as ooa toolkit key made it. | sdk |
| `PKG.BAD_SIGNATURE` | Signature check failed for …@… | Install the toolkit again from its publisher. | sdk |
| `PKG.ERROR` | The panel of '…' …<br>Input '…': visibleWhen part '…' is not field=value<br>… is not annotated with @OoaPackage | List every input once: "a, [Label: b], c \| d".<br>Fix the toolkit's code as the message says (this is for toolkit developers), then build it again.<br>Use lower_snake_case. | sdk |
| `PKG.KEY_EXISTS` | … already exists | Keep the key you have; a new key means admins must trust it again. | sdk |
| `PKG.NOT_FOUND` | …@… is not installed<br>Package '…' is not installed | Install that toolkit version on the runner (Manage > Toolkits), or update the agent to the installed one.<br>Install … in Resources > Packages. | sdk |
| `PKG.PUBLISHER_NOT_ALLOWED` | Publisher '…' is not on this tenant's allow-list | Ask a tenant admin to approve the publisher. | sdk |
| `PKG.TAMPERED` | The stored jar was changed<br>Package …@… was modified after signing | Upload the toolkit jar again.<br>Install the toolkit again from its publisher. | control-room, sdk |
| `PKG.UNSIGNED` | Package …@… is unsigned<br>The jar has no META-INF/ooa-toolkit.json | Build the package with the OOA SDK so it is signed.<br>Sign it first: ooa toolkit sign --jar <file> --class <toolkit class> --key <private key file>. | sdk |
| `PKG.UNTRUSTED` | The publisher '…' is not trusted here<br>The jar is not signed by a trusted publisher<br>No trusted key for publisher '…' | Add the publisher's public key in Toolkits > Publishers first.<br>Sign the jar with a publisher certificate that is trusted in Administration > Settings > Toolkits.<br>Trust the publisher's key in Administration > Settings > Toolkits, or use a toolkit signed by a trusted publisher. | control-room, sdk |
| `PKG.VERSION_MISMATCH` | No installed version of '…' satisfies '…' | Installed: … | sdk |

## POWERPOINT_DESKTOP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `POWERPOINT_DESKTOP.BAD_MACRO` | Invalid macro name '…' | Use a name such as Module1.BuildDeck. | packages |
| `POWERPOINT_DESKTOP.BAD_OPTION` | paste_as must be default, picture or text | Pick one of the three. | packages |
| `POWERPOINT_DESKTOP.BAD_SLIDE` | …OOA_BAD_SLIDE: ……<br>Slide numbers start at 1 | Use a slide number from 1 to the number of slides.<br>Use a slide number from 1. | packages |
| `POWERPOINT_DESKTOP.EXISTS` | A file already exists at … | Set overwrite to true or choose another path. | packages |
| `POWERPOINT_DESKTOP.FAILED` | PowerPoint reported an error: … | Check the presentation and the file paths. | packages |
| `POWERPOINT_DESKTOP.MACRO_ERROR` | The macro stopped with an error: … | Fix the macro, or handle the error inside it. | packages |
| `POWERPOINT_DESKTOP.NOT_INSTALLED` | Microsoft PowerPoint is not installed on this runner | Install PowerPoint, or use PowerPoint (no Office needed). | packages |
| `POWERPOINT_DESKTOP.NO_MACRO` | The macro was not found in the presentation, or macros are disabled | Check the macro name, e.g. Module1.BuildDeck, and that the file is a .pptm. | packages |
| `POWERPOINT_DESKTOP.TOO_MANY_ARGS` | At most 5 macro arguments | Pass more values as one text, e.g. separated by \|. | packages |

## PPT

| Code | What it means | What to do | Where |
|---|---|---|---|
| `PPT.BAD_ALIGNMENT` | Alignment must be left, center, right or justify | Use left, center, right or justify. | packages-office |
| `PPT.BAD_COLOR` | '…' is not a colour | Use a hex colour such as #C00000. | packages-office |
| `PPT.BAD_COUNT` | Count must be at least 1 | Set count to 1 or more. | packages-office |
| `PPT.BAD_FORMAT` | Unsupported format '…' | Use pptx, ppt, odp or pdf. | packages-office |
| `PPT.BAD_IMAGE` | Unsupported image type: … | Use a PNG, JPEG, GIF, BMP, TIFF, EMF or WMF file. | packages-office |
| `PPT.BAD_LAYOUT` | Unknown layout '…' | Use title, title_and_content, title_only, section_header, blank, two_content, comparison, content_with_caption or picture_with_caption. | packages-office |
| `PPT.BAD_PLACEHOLDER` | Unknown placeholder '…' | Use none, title or body. | packages-office |
| `PPT.BAD_WIDTHS` | column_widths has … values for … columns | Give one width per column. | packages-office |
| `PPT.EMPTY_FIND` | Text to find is empty<br>Text to format is empty | Give the text to replace.<br>Give the text to format. | packages-office |
| `PPT.EMPTY_TABLE` | The table has no columns | Pass a Data Table with at least one column. | packages-office |
| `PPT.EXISTS` | A file already exists at … | Set overwrite to true or use open to edit it.<br>Set overwrite to true or choose another path. | packages-office |
| `PPT.NOT_FOUND` | Presentation not found: …<br>Template not found: … | Set create to true or fix the path.<br>Fix the template path. | packages-office |
| `PPT.NO_PICTURE` | There is no picture number … (the presentation has …)<br>Give alt_text or index to choose the picture | Use a number from 1 to ….<br>Set alt_text or index. | packages-office |
| `PPT.NO_PLACEHOLDER` | The slide has no …title…body… placeholder | Use a layout with that placeholder, or insert a text box instead. | packages-office |
| `PPT.NO_SLIDE` | There is no slide … (the presentation has …) | …Add a slide first.…Use a slide number from 1 to …. | packages-office |
| `PPT.OPEN_FAILED` | Cannot open …: …<br>Cannot open template …: … | Check the file opens in PowerPoint.<br>Close it in PowerPoint and check it is a .pptx file.<br>Use a .pptx template. | packages-office |
| `PPT.PASSWORD_NOT_SUPPORTED` | Passwords can be kept in .pptx files only, not .…<br>Password-protected .… files cannot be opened | Save as .pptx, or clear the password.<br>Remove the password in PowerPoint, or save the file as .pptx. | packages-office |
| `PPT.READ_ONLY` | The presentation was opened read-only | Use save_as with another path, or open it without read_only. | packages-office |
| `PPT.SAVE_FAILED` | Cannot save .… here<br>Cannot save …: … | Save as .pptx.<br>Close the file if it is open in PowerPoint.<br>Check the folder is writable. | packages-office |

## PRINTER

| Code | What it means | What to do | Where |
|---|---|---|---|
| `PRINTER.BAD_NAME` | Invalid printer name '…' | Use Get printers to see the exact names. | packages |
| `PRINTER.CONFIRM_REQUIRED` | The printer was not removed because confirm is false | Set confirm to true to allow this agent to remove the printer. | packages |
| `PRINTER.FAILED` | …<br>Printing failed: … | Check a default application is set for this file type.<br>Some printers accept only certain formats; try printing via the default application. | packages |
| `PRINTER.NOT_FOUND` | Printer '…' not found | Use Get printers to see the exact names. | packages |
| `PRINTER.NO_DEFAULT_APP` | Cannot print with the default application on this runner | Give a printer name, or run on a desktop session. | packages |
| `PRINTER.WINDOWS_ONLY` | … is supported on Windows only | Run this agent on a Windows runner. | packages |

## PROCESS

| Code | What it means | What to do | Where |
|---|---|---|---|
| `PROCESS.FAILED` | … exited with code …: … | Check the arguments, or set fail_on_error to false to handle the exit code yourself. | packages |
| `PROCESS.NOT_FOUND` | Cannot start '…': …<br>… is only the Microsoft Store shortcut, not an installed Python | Check the program is installed or give its full path.<br>Check the program is installed and on the PATH, or give its full path.<br>Install Python from python.org (or turn off the Microsoft Store shortcut in App execution aliases), then try again. | packages |
| `PROCESS.TIMEOUT` | '…' did not finish within … s | Raise timeout_seconds or check the program is not waiting for input. | packages |

## QUEUE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `QUEUE.BAD_INPUT` | if_exists is error, skip or update, not '…'<br>kind is business or system, not '…'<br>A reference has at most 200 characters | Set if_exists to error, skip or update.<br>Set kind to business or system.<br>Shorten the reference to 200 characters or fewer. | packages |
| `QUEUE.BAD_NAME` | Invalid queue name '…'<br>Give the name of the One CommandCenter queue | Use letters, digits, - and _.<br>Fill in the queue's name as Manage > Work queues shows it. | packages |
| `QUEUE.BAD_STATUS` | Unknown status '…'<br>on_hold_until needs status on_hold | Use unchanged, reprocess or on_hold.<br>Set status to on_hold when you give on_hold_until.<br>Use one of the statuses the step offers. | packages |
| `QUEUE.BAD_TAG` | Tag '…' is not allowed<br>An item has at most … tags | Tags have at most 50 characters and no commas.<br>Give an item at most the number of tags the message names. | packages |
| `QUEUE.BAD_TIME` | on_hold_until is in the past | Give a future time (UTC). | packages |
| `QUEUE.DUPLICATE_REFERENCE` | The queue already has an item with reference '…' | Give each item its own reference, or set if_exists to skip or update. | packages |
| `QUEUE.IO_ERROR` | Queue '…' could not be read or saved: … | Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it. | packages |
| `QUEUE.ITEM_NOT_FOUND` | Work item '…' not found | Use the item_id from Get next item. | control-room, packages |
| `QUEUE.NEEDS_FORCE` | Items that are … are only deleted with force on | Turn force on, or delete finished items. | packages |
| `QUEUE.NOT_IN_PROGRESS` | Work item '…' is not in progress, or another run is still working on it<br>Work item '…' is not in progress | Only items in progress whose run has ended can be unlocked.<br>Only items in progress can be unlocked. | control-room, packages |
| `QUEUE.NO_CENTRAL` | One CommandCenter work queues can only be used by runs started from One CommandCenter | Turn central off to use the queue file in the workspace, or start the agent from One CommandCenter. | packages |
| `QUEUE.TIMEOUT` | No answer from One CommandCenter about the work queue | Check that One CommandCenter is running and not overloaded; the step can be run again. | agent |

## RELEASE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `RELEASE.BAD_MANIFEST` | Not a ONEOFFICEAUTOMATION release manifest | Use the manifest from the ONEOFFICEAUTOMATION release page. | cli |
| `RELEASE.BAD_SIGNATURE` | The manifest's signature is not valid: it was changed or not made by ONEOFFICEAUTOMATION | Download the release again from the ONEOFFICEAUTOMATION release page. | cli |
| `RELEASE.EMPTY` | No release files (.zip, .msi, .json, .txt) in … | Put the release files (.zip, .msi, .json, .txt) in the folder. | cli |
| `RELEASE.PAGE_FETCH` | GitHub answered … for … | …Check --repo; a private repository needs --token-env with a token that can read it.… | cli |
| `RELEASE.SIGN_FAILED` | Signing failed: … | Check the vendor signing key file. | cli |

## REPO

| Code | What it means | What to do | Where |
|---|---|---|---|
| `REPO.IO` | … | Check that the workspace folder may be written. | cli |
| `REPO.NOT_FOUND` | Agent '…' is not published in One CommandCenter (no live production version)<br>Automation '…' not found in the workspace | Check it in and set a live production version.<br>Save the child agent anywhere in the workspace; its agent id must match.<br>Check the agent in and set a version live for production on its Versions tab. | agent, cli, control-room |

## REST

| Code | What it means | What to do | Where |
|---|---|---|---|
| `REST.AUTH_UNSUPPORTED` | Signing in as the current Windows user works only on Windows runners | Use windows_auth user_password with basic_user and basic_password, or run the agent on a Windows runner. | packages |
| `REST.BAD_AUTH` | Unknown windows_auth '…'<br>windows_auth cannot be combined with bearer_token, a proxy, a client certificate, insecure_tls or accept_cookies<br>windows_auth cannot be combined with bearer_token, a proxy, a client certificate or insecure_tls | Use current_user or user_password.<br>Use Windows sign-in alone (the runner's proxy settings apply). | packages |
| `REST.BAD_BODY` | More than one body was given | Give only one of json, form, multipart, body or body_file. | packages |
| `REST.BAD_HEADER` | content_type may not contain line breaks | Write the content type on one line, for example application/json. | packages |
| `REST.BAD_URL` | Invalid URL: … | Check the address, e.g. https://intranet.example.com/api.<br>Check the address, e.g. https://api.example.com/orders. | packages |
| `REST.FILE_ERROR` | Cannot read …: …<br>Cannot write into …: …<br>Cannot save …: … | Check the file is not locked.<br>Check the folder exists and is writable.<br>Close the file if it is open and check the folder is writable. | packages |
| `REST.FILE_EXISTS` | File already exists: … | Set overwrite to true or choose another path. | packages |
| `REST.FILE_NOT_FOUND` | File not found: …<br>File not found for part '…': … | Check the body_file path.<br>Check the file path.<br>Check the path. | packages |
| `REST.HTTP_ERROR` | … … returned HTTP … | …Check the credentials and permissions.… | packages |
| `REST.NO_SECRET` | Input 'basic_password' must come from the vault (a Credential), so the request was not sent<br>Input '…' must come from the vault (a Credential), so the request was not sent | Give a vault reference {"$credential": "locker/credential/attribute"} or a Credential variable. | packages |
| `REST.NO_USER` | windows_auth user_password needs basic_user (DOMAIN\user) and basic_password | Fill basic_user and basic_password, or use current_user to sign in as the runner's Windows account. | packages |
| `REST.OAUTH_FAILED` | No answer from the token endpoint … within … s<br>Cannot reach the token endpoint …: …<br>The token endpoint … refused the sign-in (HTTP …) | Check the token URL.<br>Check the token URL, proxy settings and network access.<br>…Check the client id and that the refresh token is still valid (sign in again to get a new one).…Check the client id, client secret, scope and token URL. | packages |
| `REST.OAUTH_SETTINGS` | OAuth 2.0 sign-in needs oauth_token_url (an http or https address), so the request was not sent<br>OAuth 2.0 sign-in needs oauth_client_id, so the request was not sent<br>OAuth 2.0 client credentials sign-in needs oauth_client_secret, so the request was not sent | Give the provider's token endpoint, e.g. https://login.example.com/oauth2/token.<br>Give the client (application) id.<br>Give the client secret from the vault, or a refresh token in oauth_refresh_token. | packages |
| `REST.TIMEOUT` | No response from … within … s | Raise timeout_seconds or check the service. | packages |
| `REST.UNREACHABLE` | Cannot reach …: …<br>Cannot reach … as the current Windows user: …^error …… | Check the URL, the user name (DOMAIN\user) and network access.<br>Check the URL and that the runner's Windows account may use the site.<br>Check the URL, proxy settings and network access. | packages |

## RULES

| Code | What it means | What to do | Where |
|---|---|---|---|
| `RULES.BAD_CONDITION` | Row …, …: cannot read the condition '…' | Use a value, <, <=, >, >=, !=, 10..20, a list such as EU, US, or not ... | packages |
| `RULES.BAD_TABLE` | A decision table needs in: and out: columns | Header example: rule, in:amount, in:region, out:approver | packages |
| `RULES.EMPTY` | The decision table is empty | Add rows to the decision table. | packages |
| `RULES.MISSING_INPUT` | The decision table tests …, but the inputs have no … | Add the input the message names, or remove its column from the table. | packages |
| `RULES.NOT_A_NUMBER` | Row …, … compares numbers, but the input is …empty…'…'… | Give a number for the input the message names. | packages |
| `RULES.NOT_UNIQUE` | Several rules match: …, … | Make the rules exclusive, or use hit policy first. | packages |
| `RULES.NO_MATCH` | No rule of the decision table matches … | Add a rule that matches these inputs, or a last row that matches anything. | packages |
| `RULES.NO_TABLE` | Give the decision table as a CSV file or a table | Fill in file or table. | packages |

## RUN

| Code | What it means | What to do | Where |
|---|---|---|---|
| `RUN.CANCELLED` | Run thread interrupted<br>Interrupted<br>The wait was stopped | The run was stopped before this step finished. Run the agent again when it should go on.<br>Start the import again.<br>Nothing to fix: someone stopped the run in One CommandCenter. | cli, control-room, packages, packages-browser, packages-windows, runner, sdk, toolkits/ai, toolkits/cloud-data, toolkits/microsoft |
| `RUN.INPUT_INVALID` | Input '…' must be one of … | Give one of the values the message lists. | runner |
| `RUN.INPUT_MISSING` | Required input '…' has no value | …description…description…Provide a …type… value for '…'. | runner |
| `RUN.INPUT_UNKNOWN` | Unknown input(s): …, … | Only variables with direction 'input' can be supplied. | runner |
| `RUN.INTERNAL` | Internal runner error: … | Run the agent again; if it keeps happening, report it with the run log. | runner |
| `RUN.INTERRUPTED` | One CommandCenter stopped during the run | Run the agent again. | control-room |
| `RUN.NEEDS_PERSON` | … waits for an answer, but this run is unattended and nobody is at the runner<br>Forms wait for a person to fill them in, but this run is unattended and nobody is at the runner | Give it a timeout so it closes by itself, or run the agent attended.<br>Run this agent attended. | packages |
| `RUN.START_NOT_FOUND` | There is no step '…' in agent … | Choose a step of the agent being run. | runner |
| `RUN.TIMEOUT` | Run exceeded its maximum duration<br>Run exceeded its maximum duration while a step was running; the step was stopped | Raise the run time limit or split the bot. | runner |
| `RUN.TOOLKIT_LOAD` | … | Install the toolkit on the runner (Manage > Toolkits) or update it; the message names it. | agent |
| `RUN.TOOLKIT_MISSING` | The runner …name… does not have the toolkit(s) …, …<br>The jar of … cannot be read on One CommandCenter: … | Update the runner (Runners > Update), or send the run to a runner that has them.<br>Upload the toolkit again in Manage > Toolkits. | control-room |
| `RUN.WRONG_KIND` | Agent definitions run in the agent runtime, not the Runner | Use `ooa agent run`. | runner |

## RUN_CONTEXT

| Code | What it means | What to do | Where |
|---|---|---|---|
| `RUN_CONTEXT.UNKNOWN` | Unknown run value '…' | Use one of …, …. | packages |

## SAAS

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SAAS.ADDRESS_BLOCKED` | … at …(no address)… is not on the public internet | The SaaS One CommandCenter connects only to public addresses. For systems in your own network, use a runner there. | control-room |
| `SAAS.API` | The region answered … without JSON<br>The region refused: … (HTTP …)<br>The region refused the import: … (HTTP …) | Check the region's address; it may be behind a proxy that answers with a web page.<br>Correct what the message names and run the command again.<br>Check the region's address; it may be behind a proxy that answers instead. | cli |
| `SAAS.BAD_NAME` | Give the tenant a name | Give the tenant a name. | control-room |
| `SAAS.BAD_SEATS` | No seat type '…'<br>Seats of '…' are a whole number from 0 | Use …, …<br>Give seats as a whole number from 0. | control-room |
| `SAAS.BAD_SLUG` | '…' cannot be a tenant's address name | Use lower-case letters, digits and - (3 to 40 characters), not a reserved name.<br>Use 3 to 40 lower-case letters, digits and hyphens, starting with a letter (not www, api, admin, ...). | control-room |
| `SAAS.CONFIG` | No directory settings at …<br>region.….token_sha256 is 64 hex digits (ooa region token)<br>No regions in … | Write it as docs/infrastructure/09-saas-and-multi-region-operations.md shows.<br>Make the token's hash with ooa region token and paste all 64 hex digits.<br>Add the regions to the file (ooa region add). | cli, control-room |
| `SAAS.DELETE_FAILED` | Cannot remove …: … | Check that the service account may delete the tenant's folder, and that no file in it is open. | control-room |
| `SAAS.DIRECTORY` | The directory at … cannot be reached: …<br>Interrupted<br>The directory answered …: … | Check that the region can reach the directory's address (network, firewall), then try again.<br>Try again; if it keeps happening, an administrator can look in the server log. | control-room |
| `SAAS.FOLDER` | Cannot create …: … | Check that the service account may create folders in the data folder. | control-room |
| `SAAS.MOVE_BUSY` | Tenant … is … | Wait until it is active or suspended, then import again. | control-room |
| `SAAS.NOT_AVAILABLE` | … is not available in the SaaS service<br>Choosing the Document Automation service is not available in the SaaS service | Use a runner in your own network for it. | control-room, modules/document-automation |
| `SAAS.NOT_SUSPENDED` | Suspend … first; a tenant is deleted only when suspended | Suspend the tenant first, then delete it. | control-room |
| `SAAS.NO_TENANT` | No tenant '…' in region … | Check the tenant's address name and region. | control-room |
| `SAAS.SLUG_TAKEN` | The address name '…' is taken<br>The address name '…' is taken (in this or another region) | Choose another address name. | control-room |
| `SAAS.TENANT_NOT_EMPTY` | Tenant … already has data | Import into a new tenant name, or delete this tenant (suspend, then delete) and import again. | control-room |
| `SAAS.TOKEN` | Set …token-env… to the operator token | Set the environment variable to the region's operator token (ooa region token makes one). | cli |
| `SAAS.UNKNOWN_HOST` | …: the name … is not known | Check the host name for typing errors, and that the server's DNS can find it. | control-room |

## SALESFORCE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SALESFORCE.AMBIGUOUS_EXTERNAL_ID` | More than one … record has … = … | Use a field whose values are unique, or read the record by its Salesforce id. | toolkits/enterprise |
| `SALESFORCE.BAD_BATCH` | The batch size must be between 200 and 2000 | Set the batch size between 200 and 2000. | toolkits/enterprise |
| `SALESFORCE.BAD_FIELD` | Invalid field name '…' | Example: Name, Email or Account.Industry. | toolkits/enterprise |
| `SALESFORCE.BAD_GRANT` | Unknown grant type '…' | Use client_credentials or password. | toolkits/enterprise |
| `SALESFORCE.BAD_ID` | Invalid record id '…'<br>Invalid file id '…' | Salesforce ids are 15 or 18 characters.<br>A file (ContentDocument) id starts with 069 and has 15 or 18 characters. | toolkits/enterprise |
| `SALESFORCE.BAD_OBJECT` | Invalid object name '…' | Example: Account, Contact, Case or My_Object__c. | toolkits/enterprise |
| `SALESFORCE.BAD_ORDER` | Sort by one field, then ASC or DESC | Example: CreatedDate DESC. | toolkits/enterprise |
| `SALESFORCE.BAD_PARAMETER` | An empty list cannot go into SOQL | Give at least one value, or leave the condition out. | toolkits/enterprise |
| `SALESFORCE.BAD_VISIBILITY` | Unknown visibility '…' | Use AllUsers, InternalUsers or SharedUsers. | toolkits/enterprise |
| `SALESFORCE.NO_CLIENT` | The client id (consumer key) is required | Fill in the connected app's consumer key in the connection. | toolkits/enterprise |
| `SALESFORCE.NO_EXTERNAL_ID` | The external id value is empty<br>Upsert needs the external id field | Put the external id value in id.<br>Example: ERP_Number__c.<br>Map external_id_value to the key from the source system. | toolkits/enterprise |
| `SALESFORCE.NO_ID_COLUMN` | Update needs an Id column | Add the record ids in a column named Id. | toolkits/enterprise |
| `SALESFORCE.NO_RECORD` | No record id given | Fill in record_id, or content_document_id to upload a new version of a file. | toolkits/enterprise |
| `SALESFORCE.NO_USER` | The password grant needs a user name and password | Fill in User name and Password in the connection, or choose another way to sign in. | toolkits/enterprise |
| `SALESFORCE.VISIBILITY_WITH_VERSION` | Visibility cannot be set when uploading a new version | Leave visibility empty: a new version keeps the sharing of the existing file. | toolkits/enterprise |

## SAP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SAP.ACTION_FAILED` | … | Check SAP GUI is open on the expected screen and the element id is correct. | toolkits/enterprise |
| `SAP.ALREADY_LOGGED_ON` | … | Choose what to do with the other session in the logon step's multiple logon option. | toolkits/enterprise |
| `SAP.BAD_BUTTON` | Invalid toolbar button id '…' | Use the button id as SAP's scripting recorder shows it, for example &MB_EXPORT. | toolkits/enterprise |
| `SAP.BAD_COLUMN` | Give the column of the cell | Use a 0-based position, the column title or the technical field name. | toolkits/enterprise |
| `SAP.BAD_ENCODING` | Unknown encoding '…' | Use an encoding name such as UTF-8, UTF-16LE or windows-1252. | toolkits/enterprise |
| `SAP.BAD_ID` | Invalid SAP element id '…' | Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. | toolkits/enterprise |
| `SAP.BAD_INDEX` | With match_by index, value must be a 0-based number<br>With match_by index, node_key must be a 0-based number | Give a number from 0 (the first item), or set match_by to text.<br>Give a number from 0 (the first node), or set match_by to text. | toolkits/enterprise |
| `SAP.BAD_KEY` | Invalid virtual key … | Use a virtual key number from 0 to 99 (for example 0 is Enter, 3 is F3 Back, 11 is Ctrl+S Save). | toolkits/enterprise |
| `SAP.BAD_LANGUAGE` | Invalid logon language '…' | Use a two-letter code such as EN or DE. | toolkits/enterprise |
| `SAP.BAD_LOGON_PATH` | The SAP Logon path must point to an .exe file<br>SAP Logon was not found at … | Example: saplogon.exe in the SAP GUI folder.<br>Check the SAP GUI installation folder. | toolkits/enterprise |
| `SAP.BAD_MENU` | Unknown menu_by '…'<br>With menu_by position, menu_item must be 0-based positions such as 3 or 2 > 1, not '…'<br>The menu path is empty | Use code, text or position.<br>Count the entries from 0 at the top.<br>Example: System > User Profile > Own Data. | toolkits/enterprise |
| `SAP.BAD_OPTION` | Unknown multiple_logon value '…' | Use one of: continue, close_others, or fail. | toolkits/enterprise |
| `SAP.BAD_PROPERTY` | Unknown property '…' | Use text, tooltip, name, type, changeable, max_length or icon_name. | toolkits/enterprise |
| `SAP.BAD_ROW` | Rows start at 0<br>Give the row of the cell, or find_text to find the cell by its text | Give a row number from 0 (the first row).<br>Fill in Row, or Find text to find the cell by its text. | toolkits/enterprise |
| `SAP.BAD_SYSTEM` | The system is empty | Fill in the system as it is named in SAP Logon. | toolkits/enterprise |
| `SAP.BAD_TCODE` | Invalid transaction code '…' | Use a transaction code such as VA01 or /nSE16 (letters, digits, / and _ only). | toolkits/enterprise |
| `SAP.BAD_WINDOW` | Invalid window number … | Use a window number from 0 to 9; the main window is 0. | toolkits/enterprise |
| `SAP.CONNECT_FAILED` | … | Check the system name exactly as it appears in SAP Logon, and that the server is reachable. | toolkits/enterprise |
| `SAP.FAILED` | Unexpected SAP script output: … | Check that SAP GUI scripting is allowed on the server and on the runner (RZ11 sapgui/user_scripting = TRUE, and Options > Scripting in SAP GUI). | toolkits/enterprise |
| `SAP.LOGON_FAILED` | … | Check the client, user, password and language. | toolkits/enterprise |
| `SAP.NOT_RUNNING` | … | Start SAP GUI and enable scripting (SAP GUI options > Accessibility & Scripting; on the server: RZ11 sapgui/user_scripting = TRUE). | toolkits/enterprise |
| `SAP.NO_CONNECTION` | … | Open the system first (Connect / Log on), or check the connection number (0 = first open system). | toolkits/enterprise |
| `SAP.NO_SESSION` | … | Check the session number (0 = first window of the connection). | toolkits/enterprise |
| `SAP.OBJECT_NOT_FOUND` | … | Check the element id (record it again or read it with the SAP GUI scripting tracker) and that the right screen is open. | toolkits/enterprise |
| `SAP.START_FAILED` | Cannot start SAP Logon: …<br>SAP Logon did not start within … s | Check that SAP GUI is installed on the runner (saplogon.exe) and that the runner's account may start it.<br>Check that SAP Logon can start on the runner, or raise the time limit if the computer is slow. | toolkits/enterprise |
| `SAP.STATUS_ERROR` | … | SAP showed an error message in the status bar; read it with Get status bar or fix the input. | toolkits/enterprise |
| `SAP.TEXT_NOT_FOUND` | No cell of … holds '…'…… in column … | Check the text, or leave column empty to search every column. | toolkits/enterprise |
| `SAP.TRANSACTION_FAILED` | … | Check the transaction code and that the user may run it. | toolkits/enterprise |

## SAP_BAPI

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SAP_BAPI.ABAP_EXCEPTION` | The function raised ABAP exception …: … | Check the input values for this function. | toolkits/enterprise |
| `SAP_BAPI.BAD_PARAMETER` | Cannot set ….…: …^SAP JCo \w+: …… | Check the parameter and field names and types with Get function details. | toolkits/enterprise |
| `SAP_BAPI.BAD_PROPERTY` | Property '…' is not a JCo property | JCo properties start with jco., e.g. jco.client.trace. | toolkits/enterprise |
| `SAP_BAPI.BAD_SERVER` | Give host and system_number, or message_server and system_id<br>A message server needs system_id | Fill the application server or the message server fields.<br>Fill system_id (three characters, e.g. PRD). | toolkits/enterprise |
| `SAP_BAPI.BAD_TABLE` | Each table row must be a dictionary of field values, got …<br>A table parameter needs a Table or a list of dictionaries | Give a Table, or a list of dictionaries such as [{"MATERIAL": "100-100"}].<br>Give a Table, or a list of dictionaries. | toolkits/enterprise |
| `SAP_BAPI.COMMIT_FAILED` | The commit failed: …message… | See the messages. | toolkits/enterprise |
| `SAP_BAPI.ERROR` | SAP JCo …: … | See the SAP message; check the function and its parameters. | toolkits/enterprise |
| `SAP_BAPI.FAILED` | … returned an error: …message… | See the messages; the call was rolled back. | toolkits/enterprise |
| `SAP_BAPI.JCO_MISSING` | SAP Java Connector (JCo 3) is not installed on this runner<br>SAP JCo class … is missing: … | Download SAP JCo 3.1 from the SAP Support Portal (your SAP licence) and put sapjco3.jar and its native library (sapjco3.dll on Windows) in a folder named by OOA_SAPJCO_HOME, or in lib\sapjco next to the runner; then restart the runner.<br>Install SAP JCo 3.1 (sapjco3.jar). | toolkits/enterprise |
| `SAP_BAPI.JCO_NATIVE_MISSING` | SAP JCo could not load its native library: … | Put sapjco3.dll (64-bit, matching the runner's Java) next to sapjco3.jar, then restart the runner. | toolkits/enterprise |
| `SAP_BAPI.LOGON_FAILED` | SAP logon failed: … | Check client, user, password and language. | toolkits/enterprise |
| `SAP_BAPI.NO_FUNCTION` | SAP has no remote-enabled function … | Check the name in transaction SE37 and that it is remote-enabled (BAPIs are). | toolkits/enterprise |
| `SAP_BAPI.NO_SECRET` | Input 'password' must come from the vault (a Credential) | Give a vault reference {"$credential": "locker/credential/attribute"} or a Credential variable. | toolkits/enterprise |
| `SAP_BAPI.NO_SESSION` | No SAP BAPI connection '…sap…' | Run Connect first, with the same session name. | toolkits/enterprise |
| `SAP_BAPI.NO_USER` | Give user and password, or snc_partner_name for single sign-on | Fill user and password from the vault. | toolkits/enterprise |
| `SAP_BAPI.PROVIDER_IN_USE` | Another destination provider is already registered with SAP JCo in this runner | Run SAP BAPI agents on a runner without other JCo-based add-ons. | toolkits/enterprise |
| `SAP_BAPI.UNKNOWN_PARAMETER` | … has no table parameters<br>… has no … parameters | Remove tables, or check the function.<br>Remove them, or check the function. | toolkits/enterprise |
| `SAP_BAPI.UNREACHABLE` | Cannot reach the SAP system: … | Check the host, system number, router string and network access. | toolkits/enterprise |

## SCRIPT

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SCRIPT.BAD_ARGUMENT` | At most 64 arguments can be passed to a VBScript function<br>VBScript function arguments cannot contain line breaks | Pass at most 64 arguments; put more in a file or a list the function reads.<br>Pass multi-line text through a file instead. | packages |
| `SCRIPT.BAD_FUNCTION` | Invalid function name '…' | Give a plain Python name such as calculate_total.<br>Give a plain JavaScript name such as calculateTotal.<br>Give a plain VBScript name such as FormatInvoice. | packages |
| `SCRIPT.BAD_INTERPRETER` | Invalid interpreter '…' | Give the program name or full path, e.g. C:/Python312/python.exe. | packages |
| `SCRIPT.BAD_RESULT` | The function result could not be read as JSON | Return numbers, text, lists or dictionaries. | packages |
| `SCRIPT.FAILED` | Script exited with code …: …<br>… did not return a result (exit code …exit_code…): …error_output…<br>VBScript did not return a result (exit code …exit_code…): …error_output… | Read the error output, fix the script, or set fail_on_error to false.<br>Check the file runs on its own.<br>Check the file runs on its own with cscript. | packages |
| `SCRIPT.INTERPRETER_NOT_FOUND` | Python is not installed on this runner (tried …, …)<br>…Python is not installed on this runner…Microsoft Store… (python only opens the Microsoft Store)……None of … is installed on this runner | Install Python 3 from python.org (tick Add to PATH) or give its program in interpreter.<br>…Install Python 3 from python.org (tick Add to PATH) or give its program in interpreter, e.g. C:/Python312/python.exe.…Install the interpreter on the runner or add it to the PATH. | packages |
| `SCRIPT.NO_SCRIPT` | Give the script text or a script file | Fill in 'script' or 'file'. | packages |
| `SCRIPT.NO_SOURCE` | Give the … file or type the script | Fill in file or script. | packages |
| `SCRIPT.VBSCRIPT_ERROR` | … | Fix the VBScript function or its arguments. | packages |

## SERVICE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SERVICE.UNAVAILABLE` | No agent repository available for child agents<br>Platform service '…' is not available to this run | Run the agent from One CommandCenter or Studio, which give child agents a repository.<br>Check the run was started with the required platform services. | runner, sdk |

## SERVICENOW

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SERVICENOW.BAD_CONTENT_TYPE` | Invalid content type '…' | Use a content type such as application/pdf or image/png. | toolkits/enterprise |
| `SERVICENOW.BAD_FIELD` | Invalid field name '…'<br>Invalid filter field '…'<br>Invalid order_by field '…' | Use the field's column name as ServiceNow shows it (for example short_description).<br>Use the field's column name, e.g. assignment_group.<br>Use a column name as ServiceNow shows it (for example sys_created_on); add DESC to sort the other way. | toolkits/enterprise |
| `SERVICENOW.BAD_FILTER` | The value for … contains ^ | Use the query input for values with ^. | toolkits/enterprise |
| `SERVICENOW.BAD_GRANT` | Unknown grant type '…' | Use one of the sign-in kinds the connection offers: password, client_credentials or refresh_token. | toolkits/enterprise |
| `SERVICENOW.BAD_OPTION` | display_value must be true, false or all | Set display_value to true, false or all. | toolkits/enterprise |
| `SERVICENOW.BAD_SYS_ID` | Invalid sys_id '…' | A sys_id is 32 hexadecimal characters. | toolkits/enterprise |
| `SERVICENOW.BAD_TABLE` | Invalid table '…' | Example: incident, sc_request, change_request. | toolkits/enterprise |
| `SERVICENOW.BAD_TIME` | Invalid time '…' | Use yyyy-MM-dd HH:mm:ss in UTC, e.g. 2026-10-19 08:00:00. | toolkits/enterprise |
| `SERVICENOW.FILE_EXISTS` | … already exists | Turn on overwrite or pick another name. | toolkits/enterprise |
| `SERVICENOW.NO_CLIENT` | The client id is required | Fill in the OAuth client id in the connection (System OAuth > Application Registry in ServiceNow). | toolkits/enterprise |
| `SERVICENOW.NO_USER` | The password grant needs a user and password | Fill in User and Password in the connection, or choose another way to sign in. | toolkits/enterprise |

## SERVICES

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SERVICES.ACCESS_DENIED` | Access denied to … '…'… | …Use an account that is an administrator on … (user and password inputs).…Run the agent with administrator rights. | packages |
| `SERVICES.BAD_COMPUTER` | Invalid computer name '…' | Use a computer name, a fully qualified name or an IP address, without spaces. | packages |
| `SERVICES.BAD_INPUT` | user applies only to another computer | Fill in computer, or clear user and password. | packages |
| `SERVICES.BAD_NAME` | Invalid service name '…' | Use the service name shown by sc query, e.g. Spooler. | packages |
| `SERVICES.CANNOT_PAUSE` | '…' does not support pause and resume | Use Stop service and Start service instead. | packages |
| `SERVICES.DISABLED` | '…' is disabled… | Set its startup type to Manual or Automatic first. | packages |
| `SERVICES.FAILED` | sc … … failed: … | Run the runner with an account that may manage Windows services, and check the service's name. | packages |
| `SERVICES.LOGON_FAILED` | Cannot sign in to … as … (error …) | Check the user name (DOMAIN\user) and the password in the vault. | packages |
| `SERVICES.NOT_FOUND` | Service '…' does not exist… | Check the service name (not the display name). | packages |
| `SERVICES.NOT_RESPONDING` | '…' did not respond to the request in time… | Check the service's own logs. | packages |
| `SERVICES.NOT_RUNNING` | '…'… is stopped, so it cannot be …paused…resumed… | Use Start service instead. | packages |
| `SERVICES.NO_SECRET` | A password is needed with user …<br>The password must come from the vault | Choose the password from the vault.<br>Choose a credential from the vault instead of typing the password. | packages |
| `SERVICES.TIMEOUT` | '…'… did not reach … within … s | Raise the timeout, or check the service's own log for why it does not start or stop. | packages |
| `SERVICES.UNREACHABLE` | Cannot connect to … (error …)<br>Cannot reach the Service Control Manager on …this computer… (error …) | Check the computer name, that it is switched on and that File and Printer Sharing is allowed through its firewall.<br>Check the computer name, that it is on the network and that the Remote Service Management firewall rule is enabled. | packages |

## SESSION

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SESSION.NOT_FOUND` | Session '…' is not open<br>Several are open (…, …); this step does not say which | Add an 'open' step that creates session '…' before this step.<br>Choose the session on this step. | sdk |
| `SESSION.WRONG_TYPE` | Session '…' is a …, expected … | Use the session from the matching Open step (a browser session for browser steps, and so on). | sdk |

## SLACK

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SLACK.BAD_BLOCKS` | blocks must be a list of Block Kit blocks<br>blocks is not valid JSON: … | Build the layout in Slack's Block Kit Builder and paste its blocks array.<br>Correct the blocks JSON; Slack's Block Kit Builder shows valid blocks you can copy. | toolkits/enterprise |
| `SLACK.BAD_CHANNEL` | A channel is required | Give a channel id such as C0123456789 or a name such as #ops-alerts. | toolkits/enterprise |
| `SLACK.BAD_RESPONSE` | Slack's answer to … has no …<br>The reply to … is not a Slack answer (no ok or error field) | Check base_url (https://slack.com/api), the token's scopes (users:read.email, im:write) and any proxy between the runner and Slack.<br>Check base_url (https://slack.com/api) and any proxy between the runner and Slack. | toolkits/enterprise |
| `SLACK.BAD_TIME` | '…' is not a date, a date and time, or a message timestamp id | Examples: 2026-11-01, 2026-11-01T09:00 or 1700000000.000100. | toolkits/enterprise |
| `SLACK.BAD_USER` | Invalid user '…'<br>No user given | Give a member id such as U0123456789 or an email address.<br>Give a member id or an email address. | toolkits/enterprise |
| `SLACK.CHANNEL_NOT_FOUND` | No channel named '…' | Check the name; for a private channel, invite the agent first. | toolkits/enterprise |
| `SLACK.NO_FILES` | Give at least one file to upload | Fill in at least one file to upload. | toolkits/enterprise |
| `SLACK.WORKSPACE_NOT_FOUND` | No workspace named '…' | The agent's app is installed in: …, …. | toolkits/enterprise |

## SNMP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SNMP.BAD_ENGINE_ID` | The engine ID must be 5 to 32 bytes of hex, such as 800000000403414243 | Leave it empty to use this computer's own. | packages |
| `SNMP.BAD_OID` | Invalid OID '…'<br>OID part … is out of range in '…'<br>Give at least one OID | Use numbers separated by dots, e.g. 1.3.6.1.2.1.1.1.0.<br>Each part must be between 0 and 4294967295.<br>OIDs start with 0, 1 or 2, e.g. 1.3.6.1... | packages |
| `SNMP.BAD_OPTION` | GETBULK needs SNMP v2c or v3<br>Unknown walk_type '…'<br>max_repetitions must be between 1 and 100, not … | Use walk_type getnext with v1, or version v2c.<br>Use getnext or getbulk.<br>Use e.g. 10. | packages |
| `SNMP.BAD_RESPONSE` | Cannot read the SNMP message: … | Check the device speaks SNMP v2c on this port. | packages |
| `SNMP.BAD_SECURITY` | Unknown sign-in protocol '…'<br>Unknown privacy protocol '…'<br>SNMP v3 needs the security user name | Use none, md5, sha, sha256 or sha512.<br>Use none, des or aes128.<br>Fill in the SNMP v3 user name. | packages |
| `SNMP.BAD_TRAP_TYPE` | Unknown generic trap type '…' | Use one of …. | packages |
| `SNMP.BAD_TYPE` | Unsupported value type …<br>Unknown value type '…'<br>counter64 values need SNMP v2c | Use one of the value types the step offers.<br>Use integer, string, oid, ip_address, counter32, gauge32, timeticks or counter64.<br>Use version v2c. | packages |
| `SNMP.BAD_VALUE` | Invalid IPv4 address '…'<br>Value … is out of range for … | Use the form 192.168.1.10.<br>Give an IPv4 address such as 192.168.1.10.<br>Give a value within the range of the type the message names. | packages |
| `SNMP.BAD_VERSION` | Unsupported SNMP version '…' | Use v1, v2c or v3. | packages |
| `SNMP.DECRYPTION` | The encrypted answer has a bad length<br>Cannot decrypt the SNMP message: …<br>The device could not decrypt the request | Check the privacy protocol and password against the device's settings.<br>Check the privacy protocol and password. | packages |
| `SNMP.ERROR` | The SNMP agent answered … | …Use a community with write access, and check the object is writable.…Check the OID, value and type.<br>Check the OID, value and type, and that the user may read or write it. | packages |
| `SNMP.NOT_IN_TIME_WINDOW` | The device's clock and this runner's disagree | Check the device's SNMP engine. | packages |
| `SNMP.REPORT` | The device answered with report … | Check the SNMP v3 settings. | packages |
| `SNMP.TIMEOUT` | No SNMP answer from …:… after … attempt(s)<br>No SNMP v3 answer from …:… after … attempt(s) | Check the host, the port (usually 161) and the community; devices silently ignore a wrong community.<br>Check the host, the port (usually 161) and that the device has SNMP v3 on. | packages |
| `SNMP.UNKNOWN_HOST` | Unknown host '…' | Check the host name and DNS. | packages |
| `SNMP.UNKNOWN_USER` | The device does not know this SNMP user | Check the security user name. | packages |
| `SNMP.UNREACHABLE` | Cannot send to …:…: …<br>Cannot send the trap to …:…: … | Check the network and firewall. | packages |
| `SNMP.UNSUPPORTED_SECURITY` | The device does not allow this security level for the user | Check which sign-in and privacy the user has on the device. | packages |
| `SNMP.WRONG_DIGEST` | The SNMP message's signature does not match<br>The device refused the sign-in | Check the sign-in protocol and password. | packages |

## SNOWFLAKE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SNOWFLAKE.BAD_FORMAT` | Unknown format '…' | Use csv, tsv or json_lines. | toolkits/cloud-data |
| `SNOWFLAKE.BAD_HANDLE` | Invalid statement handle '…' | Use the statement_handle returned by Run SQL statement. | toolkits/cloud-data |
| `SNOWFLAKE.BAD_NAME` | A name is empty<br>A table name is required | Give a database, schema, table or column name.<br>Example: SALES.PUBLIC.ORDERS or ORDERS. | toolkits/cloud-data |
| `SNOWFLAKE.FILE_EXISTS` | A file already exists at … | Set overwrite to true, or choose another file. | toolkits/cloud-data |
| `SNOWFLAKE.NO_COLUMNS` | The data table has no columns | Build the table with column names that match the target table. | toolkits/cloud-data |
| `SNOWFLAKE.NO_SQL` | The SQL statement is empty | Write the SQL to run. | toolkits/cloud-data |
| `SNOWFLAKE.STATEMENT_FAILED` | … | Check the SQL, the object names and the role's privileges. | toolkits/cloud-data |
| `SNOWFLAKE.STILL_RUNNING` | Snowflake did not finish listing in time<br>The statement is still running<br>The statement did not finish within … seconds | Try again in a moment.<br>Wait, then try again, or check it with Get statement status.<br>Raise wait_seconds, or use Get statement status with the handle …. | toolkits/cloud-data |
| `SNOWFLAKE.WRITE_FAILED` | Cannot write …: … | Check the folder exists and can be written. | toolkits/cloud-data |

## SOAP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SOAP.BAD_RESPONSE` | The answer is not a SOAP envelope (a <…> document, often a proxy or sign-in page): … | Check the endpoint URL and the proxy between the runner and the service. | packages |
| `SOAP.BAD_URL` | Invalid URL: … | Give the address starting with https:// or http://. | packages |
| `SOAP.BAD_WSDL` | Give the WSDL address or file<br>This is not a WSDL 1.1 document<br>The WSDL has no SOAP operations | Fill in either WSDL address or WSDL file.<br>Give the service's ?wsdl address or file.<br>Check it describes a SOAP service with a service and port. | packages |
| `SOAP.BAD_XPATH` | Invalid XPath '…': … | Use e.g. //*[local-name()='Price']. | packages |
| `SOAP.FAULT` | SOAP fault: …unknown… | Check the request against the service's WSDL. | packages |
| `SOAP.FILE_ERROR` | Cannot save the response to …: … | Check the folder is writable. | packages |
| `SOAP.HTTP_ERROR` | Service returned HTTP ……: …<br>Service returned HTTP …: …<br>Service returned HTTP … | Check the service's address and the sign-in in the connection; the message has the service's answer.<br>…Give basic_user and basic_password.… | packages |
| `SOAP.UNREACHABLE` | Cannot reach …: … | Check the URL and network access.<br>Check the address and network access. | packages |

## SOUND

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SOUND.BAD_VOLUME` | volume_percent must be 0 to 100, not … | Set volume_percent between 0 and 100. | packages |
| `SOUND.CANNOT_PLAY` | Cannot play the file: …<br>The media player stopped with code …: …<br>The media player gave no result | Check the file plays in Windows Media Player; some formats (e.g. MKV, FLAC on older Windows) need a codec.<br>Check the file plays on this runner.<br>Check the file plays in Windows Media Player. | packages |
| `SOUND.NO_DEVICE` | No audio runner available: … | Run on a runner with sound output. | packages |
| `SOUND.NO_PLAYER` | Cannot start the media player: …<br>No media player found on this runner | Check the runner can play media.<br>Install ffplay (FFmpeg), mpv or VLC, or run the bot on a Windows runner. | packages |
| `SOUND.UNSUPPORTED` | Unsupported media type '.…'<br>Unsupported sound format | Use …, ….<br>Use a WAV file. | packages |

## SSO

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SSO.BAD_STATE` | The sign-in link expired or was already used | Start the sign-in again. | control-room |
| `SSO.BAD_TOKEN` | The identity provider sent no valid ID token<br>Unsupported ID token algorithm …<br>The ID token signature is not valid | Check the identity provider's settings in Administration > Settings > Sign-in (issuer, client id and signing keys), then sign in again.<br>Set the identity provider to sign ID tokens with RS256.<br>Sign in again; if it keeps happening, check that the server's clock is right. | control-room |
| `SSO.CONFIG` | The identity provider certificate in Settings cannot be read | Paste the identity provider's signing certificate (PEM). | control-room |
| `SSO.DENIED` | The identity provider did not sign you in: …error…error_description… (…error_description…)……<br>The identity provider did not sign you in…… (…Value….*:……)… | Check the sign-in settings in Administration > Settings > Sign-in, or ask your identity provider's administrator why you were refused.<br>Ask your identity provider's administrator whether you may use One CommandCenter. | control-room |
| `SSO.DISABLED` | SAML sign-in is not set up<br>Single sign-on is not set up | An administrator sets it up in Administration > Settings.<br>An administrator sets up SAML in Administration > Settings > Sign-in. | control-room |
| `SSO.INVALID` | … | …Try signing in again; if it keeps failing, an administrator checks the SAML settings.… | control-room |
| `SSO.NO_ACCOUNT` | There is no One CommandCenter account for … | Ask an administrator to create your account. | control-room |
| `SSO.PROVIDER` | The identity provider answered HTTP … for …<br>The identity provider could not be reached: …<br>Interrupted | Check that the server can reach the identity provider's address (network, proxy, firewall), then sign in again.<br>Try again; if it keeps happening, an administrator can look in the server log. | control-room |
| `SSO.TOKEN` | The identity provider refused the sign-in (HTTP …) | Check the client id and secret in Administration > Settings > Sign-in. | control-room |

## STATE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `STATE.LIMIT` | The state machine took more than … transitions (last out of state '…') | The states may be going round endlessly: check the transition conditions, or raise max_transitions. | runner |
| `STATE.NOT_FOUND` | The state machine has no states<br>There is no state named '…'… to start in… (a transition of state '…' leads there)… | Add the states and their transitions.<br>States: …, …. | runner |
| `STATE.NO_TRANSITION` | No transition out of state '…' is true | Add a transition without a condition as the last one (it is taken when no other is), or mark the state final. | runner |

## STEP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `STEP.TIMEOUT` | Action did not finish within … s | Raise timeoutSeconds on the step or check the target application. | runner |

## STRING

| Code | What it means | What to do | Where |
|---|---|---|---|
| `STRING.BAD_CHARACTER` | Pad with one character, not '…' | Give a single character such as 0 or a space. | packages |
| `STRING.BAD_COUNT` | max_parts cannot be negative | Use 0 for all parts. | packages |
| `STRING.BAD_DECIMALS` | Decimals must be between 0 and 50, got … | Use a small positive number. | packages |
| `STRING.BAD_GROUP` | The expression has … capture group(s), group … was asked for | Use 0 for the whole match or a group number that exists. | packages |
| `STRING.BAD_LENGTH` | max_length cannot be negative<br>Length cannot be negative (…)<br>Length … is out of range | Use 0 or more.<br>Use a length from 0 to 100000.<br>Choose a smaller positive length. | packages |
| `STRING.BAD_MODE` | Unknown compare mode '…' | Use exact, ignore_case or natural. | packages |
| `STRING.BAD_OCCURRENCE` | Occurrences start at 1<br>Occurrences start at 1, got … | Use 1 for the first match. | packages |
| `STRING.BAD_OPTION` | Unknown until option '…' | Use marker, end_of_line or end_of_text. | packages |
| `STRING.BAD_POSITION` | Start position … is outside the text (length …)<br>End position … is before start position … | Use a position from 0 to the text length.<br>Use an end at or after the start. | packages |
| `STRING.BAD_REGEX` | Invalid regular expression: … | Escape special characters such as ( ) [ ] . * | packages |
| `STRING.BAD_SEPARATORS` | Decimal and grouping separators are both '…' | Use different separators. | packages |
| `STRING.EMPTY_FIND` | Nothing to count | Fill in the text to count. | packages |
| `STRING.NOT_A_NUMBER` | '…' is not a number for locale … (decimal '…', grouping '…') | Check the locale or set decimal_separator and grouping_separator. | packages |
| `STRING.NO_CHARACTER_SET` | No character set selected | Turn on letters, digits or symbols. | packages |

## STUDIO

| Code | What it means | What to do | Where |
|---|---|---|---|
| `STUDIO.ALREADY_COPIED` | You already have a copy of … in your workspace | Open it from the Private tab; check it out to change it. | cli |
| `STUDIO.BAD_CLIPBOARD` | Send {steps: [...]} | Copy steps from the editor and paste them again. | cli |
| `STUDIO.BAD_INPUT` | Give the captured object to keep in the repository<br>Connection names use letters, digits, spaces, _ . and - (up to 80)<br>Choose the toolkit the connection is for | Capture the object first, then keep it.<br>Example: ServiceNow production.<br>Example: ooa.servicenow. | cli, vendor-tools |
| `STUDIO.BAD_NAME` | Give the copy a name<br>'…' cannot be used as a name<br>Application, screen and element names are 1 to 120 characters, without / | Give the copy a name.<br>Do not end a name with a dot or a space, or use a name Windows keeps for itself (CON, PRN, AUX, NUL, COM1, LPT1 ...).<br>Use 1 to 120 characters without / in the name. | cli |
| `STUDIO.BAD_URL` | Enter One CommandCenter address, e.g. http://localhost:8720 | Enter One CommandCenter's address, for example http://localhost:8720 or https://ooa.example.com. | cli |
| `STUDIO.CLONE_READ_ONLY` | … is a clone, which is read-only | Check it out on the Public tab to change it, or use Copy to Private for a copy of your own. | cli |
| `STUDIO.CR_BAD_RESPONSE` | Unexpected answer from … (HTTP …) | Check One CommandCenter address. | cli |
| `STUDIO.CR_FILE` | Cannot send … to One CommandCenter: … | Check you may add files to that folder. | cli |
| `STUDIO.CR_MFA_CODE` | This account uses two-step sign-in | Enter the code from your authenticator app in the Code box, then connect again. | cli |
| `STUDIO.CR_NOT_CONNECTED` | Not connected to One CommandCenter | Connect from the Explorer's One CommandCenter section. | cli |
| `STUDIO.CR_PASSWORD_CHANGE` | Your One CommandCenter password must be changed first | Sign in to One CommandCenter in the browser (…), choose a new password, then connect again.<br>Sign in to One CommandCenter in the browser and change your password, then connect again. | cli |
| `STUDIO.CR_UNREACHABLE` | Cannot reach One CommandCenter at ……: …<br>Cannot reach One CommandCenter at … | Check the address and that One CommandCenter is running.<br>Check One CommandCenter's address and that this computer can reach it (network, VPN, proxy). | cli |
| `STUDIO.EXISTS` | "… / … / …" is already in the repository | Choose another name, or replace it. | cli |
| `STUDIO.INTERNAL` | … | Try again; if it keeps happening, report it with Studio's log. | cli |
| `STUDIO.INTERRUPTED` | Interrupted | Try again; if it keeps happening, look in the runner's or Studio's log for this code. | cli |
| `STUDIO.IO` | Cannot create the file for recorded objects: … | Check that the workspace folder may be written. | cli |
| `STUDIO.KEY` | The private key in … does not match the key built into the product | Use the key made with ooa license keygen for this product | vendor-tools |
| `STUDIO.NAME_TAKEN` | Your workspace already has your own …, which is not a copy of this agent<br>Your workspace already has …<br>The repository already has an agent called … | Rename or move your own item, then check out again.<br>Rename or move your own item, then clone again.<br>Choose another name or folder. | cli |
| `STUDIO.NOT_A_BOT` | Not an agent file: … | Agents end with .flow.json.<br>Open a .flow.json agent file. | cli |
| `STUDIO.NOT_A_FOLDER` | There is no folder … | Choose a place on the left. | cli |
| `STUDIO.NOT_FOUND` | Choose the agent to copy<br>Agent not found: …<br>The agent uses the object repository …, which is not in the workspace | Choose the agent to copy.<br>Reload the workspace (F5); it may have been renamed, moved or deleted.<br>Put the repository file back, or choose the elements again. | cli |
| `STUDIO.NOT_LINKED` | … is not a copy of an agent in the repository | Only checked-out agents can go back to their checked-out version. | cli |
| `STUDIO.NO_ACCESS` | This folder cannot be opened: … | Choose another folder. | cli |
| `STUDIO.NO_BROWSER_MODULE` | The Browser module is not installed | Add packages-browser to ooa.optionalModules in gradle.properties. | cli |
| `STUDIO.NO_DESKTOP_MODULE` | The Windows desktop module is not installed<br>The Window toolkit is not installed<br>The Desktop objects toolkit is not installed | Add packages-windows to ooa.optionalModules in gradle.properties.<br>Add packages-windows to ooa.optionalModules in gradle.properties and run Studio on Windows. | cli |
| `STUDIO.PORT_IN_USE` | Port … is already in use; another OneAgentStudio may still be running | Stop the other OneAgentStudio (Ctrl+C in its window), or start this one with --port …. | cli |
| `STUDIO.TOO_LARGE` | Uploads are limited to … MB per file<br>The folder is larger than 200 MB<br>The file is larger than 200 MB | Upload a smaller file, or put large files in a share the runner reads.<br>Download smaller folders, or copy it in File Explorer.<br>Copy it in File Explorer instead. | cli, vendor-tools |
| `STUDIO.TOO_MANY_RUNS` | Four runs are already in progress | Wait for a run to finish or stop one. | cli |
| `STUDIO.UNKNOWN` | Unknown file operation … | Update the Studio page (reload it); it is older than the server. | cli |
| `STUDIO.UNSUPPORTED` | Open in File Explorer works on Windows only | Open the workspace folder yourself: … | cli |

## SUBFLOW

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SUBFLOW.DEPTH` | Subflows are nested more than … deep (subflow '…') | A subflow that runs itself needs a condition that stops it; check the Run subflow steps. | runner |
| `SUBFLOW.NOT_FOUND` | There is no subflow named '…'<br>Run subflow has no subflow chosen | …Add the subflow to the agent, or remove the Run subflow step.…Subflows: …, ….<br>Choose the subflow to run. | runner |

## SWITCH

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SWITCH.BAD_MATCH` | Unknown match '…' | Use one of …, …. | runner |
| `SWITCH.BAD_REGEX` | Case … has an invalid pattern: … | Fix the regular expression. | runner |

## SYSTEM

| Code | What it means | What to do | Where |
|---|---|---|---|
| `SYSTEM.BAD_DRIVE` | '…' is not a drive letter | Give one letter, e.g. D. | packages |
| `SYSTEM.BAD_SCOPE` | Unknown scope '…' | Use process or user. | packages |
| `SYSTEM.BAD_VARIABLE_NAME` | Invalid environment variable name '…' | Use letters, digits and underscores, e.g. REPORT_FOLDER. | packages |
| `SYSTEM.CONFIRM_REQUIRED` | … was not performed because confirm is false<br>The screen resolution was not changed because confirm is false<br>The recycle bin was not emptied because confirm is false | Set confirm to true to allow this agent to … the runner.<br>Set confirm to true to allow this agent to change the screen resolution.<br>Set confirm to true to allow this agent to delete the recycle bin's contents for good. | packages |
| `SYSTEM.FAILED` | … failed: … | Check that the runner's account may do this on Windows; the message has Windows' answer. | packages |
| `SYSTEM.NO_DESKTOP` | There is no screen here | Run the agent on a runner with a signed-in desktop. | toolkits/ai |
| `SYSTEM.NO_DISPLAY` | This runner has no screen | Run the agent on a runner with a signed-in desktop. | packages |
| `SYSTEM.NO_TITLE` | The notification needs a title | Fill in the title. | packages |
| `SYSTEM.POWER_FAILED` | … failed: … | The agent may need administrator rights. | packages |
| `SYSTEM.RESOLUTION_FAILED` | Windows did not change the resolution (…) | A restart may be needed (result 1), or the runner has no physical screen. | packages |
| `SYSTEM.RESOLUTION_UNSUPPORTED` | The screen does not offer … x …… at … Hz… | Use Get screen resolution, or Windows display settings, to see the sizes the screen offers. | packages |
| `SYSTEM.SECRET_VARIABLE` | Reading '…' is blocked because it looks like a secret<br>Setting '…' is blocked because it looks like a secret | Store secrets in the credential vault and use a Credential variable.<br>Pass secrets to actions as Credential variables instead of environment variables. | packages |
| `SYSTEM.SET_VARIABLE_FAILED` | Cannot set …: … | Check that the runner's account may change this environment variable (machine variables need an administrator). | packages |
| `SYSTEM.WINDOWS_ONLY` | Device credentials are checked on Windows runners<br>Automatic sign-in is for Windows runners<br>Server sessions are for Windows Server runners | Check them on a Windows runner.<br>Use a Windows runner.<br>Run this on a Windows runner. | agent, packages, packages-windows, toolkits/enterprise |

## TABLE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `TABLE.BAD_COLUMN_NAME` | Column name is empty<br>The new column name is empty | Give the new column a name.<br>Give the column a name. | packages |
| `TABLE.BAD_CONDITION` | Condition … is not a column, a comparison and a value<br>Condition … has an unknown comparison '…' | Give each a column, an operator and a value.<br>Use one of …, …. | packages |
| `TABLE.BAD_DELIMITER` | Delimiter must be one character, got '…' | Use , ; \| or a tab.<br>Use , ; \| or tab. | packages |
| `TABLE.BAD_FORMAT` | Unknown file format '…' | Use csv or json. | packages |
| `TABLE.BAD_JOIN_TYPE` | Unknown join type '…' | Use inner, left, right or full. | packages |
| `TABLE.BAD_OPERATOR` | Unknown operator '…' | Use eq, ne, gt, ge, lt or le. | packages |
| `TABLE.BAD_POSITION` | Column position … is outside 0..… | Use a position from 0 to …. | packages |
| `TABLE.BAD_REGEX` | Invalid regular expression: … | Escape special characters such as ( ) [ ] . * | packages |
| `TABLE.BAD_ROW` | The row must be a Dictionary of column values or a List of values | For example {"Name": "Ann"} or ["Ann", 42]. | packages |
| `TABLE.BAD_SUMMARY` | No summaries given<br>Unknown summary '…' for …<br>Summary '…' for … needs a column | Give at least one, e.g. {"Total": "sum:Amount"}.<br>Use count, sum, avg, min, max, first, last or count_distinct.<br>Write it as …:ColumnName. | packages |
| `TABLE.BAD_TYPE` | Unknown column type '…' | Use String, Number, DateTime or Boolean. | packages |
| `TABLE.COLUMNS_DIFFER` | Tables have different columns: … vs … | Use columns = union, or make the columns match. | packages |
| `TABLE.COLUMN_EXISTS` | Column '…' already exists | Choose a different name. | packages |
| `TABLE.CONVERT_FAILED` | Row …, column '…': cannot convert … to … | …Number…Clean the value so it is numeric, e.g. 1234.50.…DateTime…Use ISO dates such as 2026-10-18 or 2026-10-18T14:30:00.…Boolean…Use true/false, yes/no or 1/0.…Check the value. | packages |
| `TABLE.EMPTY_FIND` | Nothing to replace | Fill in the text to find. | packages |
| `TABLE.NOTHING_TO_DELETE` | Give a row number, or a column and value | Fill in 'row' or 'column' and 'value'. | packages |
| `TABLE.NOT_A_TABLE` | Item … in 'tables' is not a table | Pass only tables. | packages |
| `TABLE.NO_COLUMN` | Column '…' not found<br>Give a column name or a column index<br>Column index … is outside the table (… columns) | Columns: …, ….<br>Fill in 'column' or 'column_index'.<br>Column indexes start at 0. | packages |
| `TABLE.NO_FOLDER` | The folder … does not exist | Create the folder first, or turn on create_folders. | packages |
| `TABLE.NO_KEYS` | No key columns given<br>key_columns and other_key_columns have different lengths | List the columns to match, e.g. ["id"].<br>Give one second-table column for each key column. | packages |
| `TABLE.NO_LIST` | No list given | Pass the list whose items fill the new column. | packages |
| `TABLE.NO_ROW` | Row … does not exist (table has … rows)<br>Row … does not exist<br>Row position … is outside the table (… rows) | Row numbers start at 0.<br>Give a row number that the table has (the first row is 1).<br>Use a position from 0 to …. | packages |
| `TABLE.NO_TABLES` | No tables to merge | Pass a list of at least one table. | packages |
| `TABLE.TOO_MANY_VALUES` | … values for … columns | Give at most one value per column: …, …. | packages |

## TEMPLATES

| Code | What it means | What to do | Where |
|---|---|---|---|
| `TEMPLATES.BAD_FEED` | This is not a ONEOFFICEAUTOMATION template catalogue | Use a catalogue made by ONEOFFICEAUTOMATION, or ask its publisher for a correct one. | control-room |
| `TEMPLATES.BAD_SIGNATURE` | The template catalogue's signature is not valid: it was changed or not made by ONEOFFICEAUTOMATION | Use a catalogue made by ONEOFFICEAUTOMATION, or ask its publisher for a correct one. | control-room |
| `TEMPLATES.BAD_TEMPLATE` | A template needs an id of lower-case letters, digits, - and _<br>Template … has no name<br>Template … holds no agent bundle | Give the template an id of lower-case letters, digits, - and _.<br>Give the template a name.<br>Add the agent bundle to the template. | control-room |
| `TEMPLATES.BAD_URL` | The template catalogue address starts with https:// | Give the catalogue's address starting with https://. | control-room |
| `TEMPLATES.EMPTY` | No template folders (with template.json) in … | Put each template in its own folder with a template.json. | cli |
| `TEMPLATES.NO_REGION` | Sharing templates is part of the SaaS service | On premises, export the agent (Export / promote) and send the file. | control-room |
| `TEMPLATES.SAVE` | Cannot save the catalogue: … | Check that the server's data folder has space and that the service account may write there. | control-room |
| `TEMPLATES.SIGN_FAILED` | Signing failed: … | Check the vendor's template signing key. | control-room |
| `TEMPLATES.UNAVAILABLE` | The template catalogue cannot be read: … | Check the address in Settings (template catalogue), or upload the catalogue file. | control-room |

## TERMINAL

| Code | What it means | What to do | Where |
|---|---|---|---|
| `TERMINAL.AUTH_FAILED` | Sign-in to … failed | Check the user, password or key file. | packages |
| `TERMINAL.BAD_CHARACTER` | The character '…' does not exist in the host code page | Change the text, or choose the host's code page in Connect. | packages |
| `TERMINAL.BAD_CODE_PAGE` | Invalid code page '…'<br>Code page '…' is not available | Example: cp037.<br>Use a single-byte EBCDIC code page such as cp037, cp273, cp277, cp280, cp285, cp297 or cp500. | packages |
| `TERMINAL.BAD_CONDITION` | Unknown condition '…' | Use input_field, unlock, screen_change, screen_blank, cursor_at, cursor_leaves or seconds. | packages |
| `TERMINAL.BAD_DELAY` | The delay must be between 0 and 600000 ms | Set the delay between 0 and 600,000 ms. | packages |
| `TERMINAL.BAD_EMULATION` | Unknown emulation '…' | Use vt100, vt220, xterm or ansi. | packages |
| `TERMINAL.BAD_HOST` | Invalid host '…' | Give a host name or IP address (letters, digits, . - and :). | packages |
| `TERMINAL.BAD_KEY` | Unknown key '…'<br>Cannot read the escape sequence '…' | …Use Enter, Tab, arrows, Home, End, PageUp, PageDown, BackSpace, Delete, Escape, Ctrl+C, Alt+X, PF1-PF20, SEQ:\e[24~ for any escape sequence, or a name set with Set key mapping.…Use Enter, Tab, Clear, PF1-PF24 or PA1-PA3, or a name set with Set key mapping.<br>Write Escape as \e or ^[, bytes as \x1b, control keys as ^C, e.g. SEQ:\e[24~.<br>… Or a name set with Set key mapping. | packages |
| `TERMINAL.BAD_KEY_FILE` | The key file could not be read: … | Use an OpenSSH or PEM private key, and give key_passphrase when the key is protected. | packages |
| `TERMINAL.BAD_LU` | Invalid LU name '…' | Give the LU name as the host administrator gave it (letters and digits). | packages |
| `TERMINAL.BAD_MODEL` | Model '…' is not a 5250 display | Use 3179-2 (24 x 80) or 3477-FC (27 x 132). | packages |
| `TERMINAL.BAD_PATTERN` | Invalid prompt pattern: … | Use a regular expression such as [$#>]\s*$. | packages |
| `TERMINAL.BAD_PORT` | Invalid port … | Use 23 for telnet, 992 for TLS or 22 for SSH. | packages |
| `TERMINAL.BAD_POSITION` | Row and column start at 1<br>Row, column and length start at 1<br>Give both row and column, starting at 1 | Give row and column numbers from 1 (the top-left corner is row 1, column 1).<br>Give row, column and length from 1.<br>Fill in both row and column, from 1. | packages |
| `TERMINAL.BAD_REPEAT` | Repeat must be between 1 and 200 | Set Repeat between 1 and 200. | packages |
| `TERMINAL.BAD_SCREEN_SIZE` | Screen size … x … is out of range | Use 5-255 rows and 20-255 columns; 24 x 80 suits most hosts. | packages |
| `TERMINAL.BAD_TIMEOUT` | The timeout must be between 1 and 3600 seconds | Set the timeout between 1 and 3600 seconds. | packages |
| `TERMINAL.COMMAND_FAILED` | …String\(.*\)…String(****)… failed: … …<br>The cursor (row …, column …) is not in an input field<br>… needs the cursor in an input field | Check the connection and that the cursor is in an input field.<br>Allow more time, or wait for specific text instead.<br>Wait for specific text instead, or allow more time. | packages |
| `TERMINAL.CONNECT_FAILED` | Unknown host …<br>Could not connect to …:…: …<br>Could not open a terminal on …: … | Check the host name.<br>Check the host, port (23, or 992 with TLS) and network.<br>Check the host, port (22 for SSH) and network. | packages |
| `TERMINAL.ENDED` | The terminal engine stopped unexpectedly<br>The host closed the connection<br>The host closed the connection…… (…)… | Check the host address and network.<br>Connect again; the host may have signed the session out. | packages |
| `TERMINAL.FIELD_NOT_FOUND` | No field follows the label '…'<br>No field at row …, column …<br>There is no field number … (the screen has …) | Check the label text exactly as shown on the screen.<br>Use Get all fields to see the field positions.<br>Use Get all fields to see the field numbers. | packages |
| `TERMINAL.HOST_KEY_MISMATCH` | Host fingerprint … does not match the expected one | This may be a different machine; confirm the fingerprint with the host's owner. | packages |
| `TERMINAL.INTERRUPTED` | Stopped while waiting for the host | The run was stopped before this step finished. Run the agent again when it should go on. | packages |
| `TERMINAL.IO_ERROR` | … | Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it. | packages |
| `TERMINAL.KEYBOARD_LOCKED` | The host is not waiting for a key yet<br>The keyboard is locked while the host works | Wait for the screen (Wait for screen, or wait_for_host) before pressing the next key.<br>Wait for the screen before typing. | packages |
| `TERMINAL.KEY_FILE_NOT_FOUND` | Key file not found: … | Check the key_file path. | packages |
| `TERMINAL.KEY_NOT_ON_5250` | … is not a 5250 key<br>The key '…' does not exist on 5250 displays | … | packages |
| `TERMINAL.KEY_NOT_ON_VT` | The key '…' exists only on 3270 terminals | On VT hosts use Enter, Tab, arrows, Home, End, PageUp, PageDown, BackSpace, Delete, Escape, Ctrl+C, Alt+X, PF1-PF20 or SEQ:\e[24~ for any escape sequence. | packages |
| `TERMINAL.NOT_INSTALLED` | The x3270 terminal engine is not installed | Install wc3270 (Windows) or s3270, or set OOA_S3270 to its path. | packages |
| `TERMINAL.NOT_ON_VT` | … is a 3270 command; VT hosts have no such key | Use keys a VT host understands: Enter, Tab, BackTab, arrows, Home, End, PageUp, PageDown, BackSpace, Delete, Insert, Escape, Ctrl+C, PF1-PF20. | packages |
| `TERMINAL.NO_CURSOR` | The engine did not report a cursor position: … … | Check the session is connected. | packages |
| `TERMINAL.NO_FIELDS` | The screen has no fields | Wait for a formatted screen before reading fields. | packages |
| `TERMINAL.NO_POSITION` | Give the row and column for …<br>Give a row and column, or a label | Rows and columns start at 1. | packages |
| `TERMINAL.NO_PROMPT` | No prompt matching … within … s; the cursor line shows '…' | Check the command finished, or change the prompt pattern. | packages |
| `TERMINAL.NO_SIGN_IN` | Give a password or a key file to sign in to … | Fill in password (a Credential) or key_file. | packages |
| `TERMINAL.NO_USER` | SSH hosts need a user name | Fill in user. | packages |
| `TERMINAL.PROTECTED_FIELD` | The field at row …, column … cannot be typed into | Pick an input field; Get all fields shows which are protected. | packages |
| `TERMINAL.TEXT_NOT_FOUND` | '…' did not …disappear…appear… within … s | Check the previous step reached the expected screen. | packages |
| `TERMINAL.TLS_FAILED` | The TLS connection to …:… failed: … | Check the port (992 is usual for TLS) and that the host's certificate is trusted by this machine. | packages |
| `TERMINAL.TOO_LONG` | The value has … characters but the field holds … | Shorten the value. | packages |
| `TERMINAL.UNSUPPORTED_TYPE` | Terminal type '…' is not supported | Use tn3270e or tn3270 for IBM 3270 hosts, tn5250 for IBM i hosts, or vt-ssh for VT100/ANSI hosts over SSH. | packages |
| `TERMINAL.WAIT_TIMEOUT` | The condition … was not met within … s | Check the previous step reached the expected screen. | packages |

## TRANSFER

| Code | What it means | What to do | Where |
|---|---|---|---|
| `TRANSFER.ACCESS_DENIED` | …: permission denied | Check that the server account in the connection may read or write this folder. | packages-transfer |
| `TRANSFER.AUTH_FAILED` | Sign-in failed: …<br>FTP sign-in failed | Check the user, password or private key.<br>Check the user and password. | packages-transfer |
| `TRANSFER.BAD_COMMAND` | Give the FTP command to send<br>The FTP command must be a single line<br>'…' is not an FTP command name | For example FEAT, SYST or SITE CHMOD 644 file.txt.<br>Send one command per step.<br>FTP commands are 3 or 4 letters, such as FEAT, SYST, SITE or MDTM. | packages-transfer |
| `TRANSFER.BAD_FILTER` | Both a number of days and dates were given<br>modified_before (…) is not after modified_after (…) | Filter by the last days or by dates, not both.<br>Give a later end date. | packages-transfer |
| `TRANSFER.BAD_MODE` | Unknown transfer_mode '…' | Use binary or ascii. | packages-transfer |
| `TRANSFER.BAD_OPTION` | implicit_tls applies to ftps only<br>retry_attempts must be from 0 to 20, not …<br>retry_delay_seconds must be from 0 to 600, not … | Set protocol to ftps or turn off implicit_tls.<br>Use 0 to try once, or a small number such as 3.<br>Use a delay such as 5 seconds. | packages-transfer |
| `TRANSFER.CANCELLED` | Stopped while waiting to connect again | The run was stopped before this step finished. Run the agent again when it should go on. | packages-transfer |
| `TRANSFER.EXISTS` | Remote file already exists: … | Set overwrite to true or choose another path. | packages-transfer |
| `TRANSFER.FAILED` | …: … | Check the connection and paths.<br>Check the server's address, port and the sign-in in the connection; the message has the server's answer. | packages-transfer |
| `TRANSFER.HOST_KEY_MISMATCH` | Server fingerprint … does not match the expected one | Possible man-in-the-middle attack; confirm the fingerprint with the server owner. | packages-transfer |
| `TRANSFER.NOT_EMPTY` | Remote folder is not empty: … | Set recursive to true to delete its contents. | packages-transfer |
| `TRANSFER.NOT_FOUND` | …: no such file or folder | Check the remote path. | packages-transfer |
| `TRANSFER.NOT_SUPPORTED` | Session '…' is not an FTP or FTPS session; SFTP has no raw commands | Use the other File transfer actions on SFTP sessions. | packages-transfer |
| `TRANSFER.NO_PATTERN` | Give the wildcard of the files to delete | Use * to delete every file in the folder. | packages-transfer |
| `TRANSFER.PROTECTED` | Refusing to delete '…' | Point to a specific sub-folder. | packages-transfer |
| `TRANSFER.TOO_DEEP` | Folder nesting deeper than … levels under … | Copy a folder nearer to the files, or flatten the folder structure. | packages-transfer |
| `TRANSFER.UNREACHABLE` | …: … | Check the host, port and protocol (SFTP is usually port 22, FTP 21, FTPS 990), and that a firewall allows the connection. | packages-transfer |
| `TRANSFER.UNSAFE_NAME` | The server listed an unsafe file name '…' | The folder was not copied; check the server.<br>Ask the server's administrator to rename the file; names with .. or / are refused to keep the runner's folders safe. | packages-transfer |

## TRIGGER

| Code | What it means | What to do | Where |
|---|---|---|---|
| `TRIGGER.BAD_BOT` | Trigger '…': agent not found: … | Choose an agent from the workspace. | cli |
| `TRIGGER.BAD_CLICK` | … | Give the window title or the process. | packages |
| `TRIGGER.BAD_FOLDER` | Trigger '…': folder not found: … | Create the folder first. | cli |
| `TRIGGER.BAD_ID` | Trigger ids must be unique: letters, digits, - and _ | Give every trigger its own id of letters, digits, - and _. | cli |
| `TRIGGER.BAD_INPUT` | … | Check the trigger settings. | packages |
| `TRIGGER.BAD_INTERVAL` | The interval must be greater than 0 seconds<br>interval_seconds must be greater than 0 | Use for example 1.<br>Use for example 60. | packages |
| `TRIGGER.BAD_KEY` | … | Write the key like F9 or CTRL+ENTER. | packages |
| `TRIGGER.BAD_LIMIT` | Limits cannot be negative<br>settle_seconds cannot be negative | Use 0 for no limit.<br>Set settle_seconds to 0 or more. | packages |
| `TRIGGER.BAD_PATTERN` | Invalid file pattern '…' | Use a glob such as *.csv. | packages |
| `TRIGGER.BAD_SCHEDULE` | Trigger '…': give either every_minutes or daily_at (HH:mm)<br>every_minutes must be at least 1<br>daily_at must look like 08:30 | Fill in either every_minutes or daily_at.<br>Set every_minutes to 1 or more.<br>Write the time as hours and minutes, for example 08:30. | cli |
| `TRIGGER.BAD_TYPE` | Trigger '…': type must be file, schedule or webhook | Choose file, schedule or webhook. | cli |
| `TRIGGER.DISABLED` | Webhook '…' is disabled | Enable the webhook in Details > Triggers. | cli |
| `TRIGGER.FORBIDDEN` | Wrong webhook secret | Use the webhook's current secret from Details > Triggers. | cli |
| `TRIGGER.HOOK_FAILED` | Cannot watch the keyboard or mouse: … | Run the agent on a Windows runner with a signed-in desktop. | packages |
| `TRIGGER.INVALID` | Send a list of triggers<br>Each trigger must be an object | Reload the page and save the triggers again. | cli |
| `TRIGGER.IO_ERROR` | Cannot read folder …: … | Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it. | packages |
| `TRIGGER.NOT_FOUND` | Trigger '…' not found<br>Webhook '…' not found | Reload Details > Triggers; it may have been deleted.<br>Check the webhook's address in Details > Triggers. | cli |
| `TRIGGER.NO_FOLDER` | Folder not found: …<br>The watched folder was removed: … | Create the folder or check the path.<br>Recreate the folder and start the agent again. | packages |
| `TRIGGER.NO_PROCESS` | Give the program's executable name | For example notepad.exe.<br>Fill in the program's executable name, for example excel.exe. | packages |
| `TRIGGER.WEAK_SECRET` | Trigger '…': webhook secret must be at least 16 characters | Use a webhook secret of at least 16 characters. | cli |
| `TRIGGER.WINDOWS_ONLY` | … (this computer runs …os.name…) | Run this agent on a Windows runner with a signed-in desktop. | packages |

## UI

| Code | What it means | What to do | Where |
|---|---|---|---|
| `UI.ELEMENT_NOT_FOUND` | No element found within … ms; tried ……; errors: … | Check the page or window shows the element, capture it again, or raise the timeout. | packages |
| `UI.NO_LOCATOR` | No locator was given for the element | Give a selector, or capture the element again. | packages |

## VAR

| Code | What it means | What to do | Where |
|---|---|---|---|
| `VAR.EMPTY` | A value used as a number is empty | A variable used here has no value yet (an input that was not given is empty): give it a value before this step, or set a default in the variable. | runner |
| `VAR.INVALID` | Cannot convert … value … to Number | Check the value is numeric, e.g. 42 or 19.99. | runner |
| `VAR.READ_ONLY` | '…' is a built-in variable and cannot be changed | Assign to one of your own variables instead. | runner |
| `VAR.SECURE_LEAK` | A Credential value cannot be used in …<br>Credential cannot be assigned to String variable '…' | Pass the Credential variable directly to an action input that accepts credentials.<br>Keep secrets in Credential variables. | runner |
| `VAR.SECURE_REQUIRED` | Input '…' needs a Credential, not plain text<br>Input '…' needs a Credential, got …<br>Variable '…' needs a Credential reference, not a plain value | Give a vault reference {"$credential": "locker/credential/attribute"} or a Credential variable.<br>Pass a vault reference like {"$credential": "locker/name/attribute"}. | runner |
| `VAR.TOO_LARGE` | File … is …: too large to hold in a … variable | Keep large files as files (a File variable) and pass their path. | runner |
| `VAR.TYPE_MISMATCH` | not a date<br>not a Boolean<br>'…' expects a file path, got … (…) | Give the value as a date, for example 2026-11-13.<br>Give the value as true or false.<br>Give a file path. An Image can be given directly to the picture fields of the Image commands. | packages, runner |
| `VAR.UNDECLARED` | Variable '$…' is not declared<br>Variable '…' is not declared | Declare '…' in the agent's variables.<br>Add the variable in the Variables panel, or correct its name. | runner |

## VAULT

| Code | What it means | What to do | Where |
|---|---|---|---|
| `VAULT.BAD_DATE` | '…' is not a date | Write the expiry date as yyyy-MM-dd, e.g. 2027-03-31. | packages |
| `VAULT.BAD_REF` | Bad credential reference '…'<br>'…' is not a credential reference<br>Input '…' has a malformed vault reference | Use vault folder/credential/attribute, with letters, digits, . _ -<br>Use vault folder/credential or vault folder/credential/attribute.<br>Write it as vault folder/credential/attribute, e.g. finance/sap-login/password. | control-room, packages, runner |
| `VAULT.DENIED` | You may not use vault folder '…'<br>Credentials are only available to running agents<br>'…requested_by…' may not use vault folder '…' | Ask the vault folder owner to add you as a consumer.<br>Read credentials from a step of a running agent, not from outside a run.<br>Ask the vault folder owner to add the user or role as a consumer. | control-room, modules/document-automation, runner |
| `VAULT.EMPTY` | The new value is empty | Give the credential's new value. | control-room, packages |
| `VAULT.EXTERNAL` | Vault folder '…' reads its values from a company vault<br>Unknown external vault provider …<br>Reading the external vault failed: … | Change the value in the company vault itself.<br>Use one of the providers the link offers: HashiCorp Vault, Azure Key Vault, AWS Secrets Manager, CyberArk, Delinea, BeyondTrust or ARCON.<br>Check the external vault's address and that the server can reach it; the message has the vault's answer. | control-room |
| `VAULT.EXTERNAL_DENIED` | The external vault refused access to … (HTTP …)<br>No token is stored for this HashiCorp Vault link<br>No client secret is stored for this Azure Key Vault link | Check the sign-in secret and the vault's access policy for One CommandCenter.<br>Store the missing secret for this link in Manage > Credentials > External vaults.<br>Check the Azure app's tenant, client id and secret in the link. | control-room |
| `VAULT.EXTERNAL_UNREACHABLE` | The external vault did not answer: …<br>Interrupted while reading the external vault | Check the vault URL and network access from One CommandCenter.<br>Try again; if it keeps happening, an administrator can look in the server log. | control-room |
| `VAULT.LOCAL_READ_ONLY` | Credentials can only be saved by an agent running from One CommandCenter | In Studio credentials are read from environment variables; run the agent from One CommandCenter to save one. | packages |
| `VAULT.NOT_FOUND` | Credential '…' not found<br>Vault folder … not found<br>You have not stored your own value for '…' | For local testing set the environment variable … before starting OneAgentStudio.<br>Check the credential's name and folder in Manage > Credentials, and that the agent's user may use it.<br>Store your own value for this credential in Manage > Credentials (My values). | cli, control-room, runner |
| `VAULT.NO_SECRET` | Input 'value' must be a Credential, so nothing was saved | Give a Credential variable or a vault reference {"$credential": "folder/credential/attribute"}; typed text is refused. | packages |
| `VAULT.PASSWORD_ONLY` | The secret … may only be typed into password fields<br>The credential … may only be typed into password fields, and … is not one | Nothing to change: the agent may type this secret only into a password field.<br>Point the action at the password field, or ask the credential owner to clear Password fields only. | packages-browser, sdk |
| `VAULT.REFERENCE_NOT_ALLOWED` | Input '…' is not a Credential field, so the vault reference in it would be sent as plain text | Use the command's Credential field for the secret (for example password, bearer_token or access_token). | runner |
| `VAULT.TIMEOUT` | No answer from One CommandCenter for credential … | Check that One CommandCenter is running and not overloaded; the step can be run again. | agent |
| `VAULT.UNAVAILABLE` | No vault configured for the credentials of connection '…'<br>No vault configured for the sign-in of connection '…'<br>No vault configured for credential inputs | Run the agent from One CommandCenter (which holds the vault), or set up the Private workspace's vault in Settings.<br>Run the agent where a vault is set up (One CommandCenter, or OOA_SECRET_... variables for local runs). | runner |
| `VAULT.WRITE_OFF` | Agents may not save credentials in this One CommandCenter | An administrator can switch on Settings > Agents and repository > Agents may save credentials. | control-room |

## VISION

| Code | What it means | What to do | Where |
|---|---|---|---|
| `VISION.BAD_ANSWER` | The model did not answer with a position | Try a model that reads pictures, or describe the control differently. | toolkits/ai |
| `VISION.NOT_FOUND` | Could not find on the screen: … (confidence …confidence…) | Describe it more exactly (its text, its place), make sure it is visible, or lower min_confidence. | toolkits/ai |
| `VISION.NO_DESCRIPTION` | Describe the control to find | Say in a few words which control to find, for example: the Save button at the bottom right. | toolkits/ai |
| `VISION.SCREEN` | Cannot take the screenshot: … | Make sure the runner has an unlocked desktop session (not a locked screen or a closed remote session). | toolkits/ai |

## WAIT

| Code | What it means | What to do | Where |
|---|---|---|---|
| `WAIT.TIMEOUT` | Condition not met within … s | Raise the wait's timeout, or check why the condition is not met (the screen or data it waits for). | runner |

## WEBAGENT

| Code | What it means | What to do | Where |
|---|---|---|---|
| `WEBAGENT.BAD_OPTION` | max_steps must be between 1 and 60<br>save_trajectory_to must be a .json file: … | Set max_steps between 1 and 60.<br>Example: logs/web-agent-run.json | packages-browser |
| `WEBAGENT.BAD_PROXY` | A proxy user name or password was given without a proxy server<br>Not a proxy address: …<br>A proxy password was given without a user name | Fill in proxy_server, e.g. http://proxy.example.com:3128.<br>Write it as host:port or http://host:port, e.g. http://proxy.example.com:3128.<br>Fill in proxy_username too. | packages-browser |
| `WEBAGENT.BAD_SECRET` | proxy_password must be a Credential<br>Secret names use letters, digits, - and _: …<br>The secret '…' must be a Credential | Map it to a Credential variable from the vault.<br>Name secrets with letters, digits, - and _.<br>Map it to a Credential variable. | packages-browser |
| `WEBAGENT.BAD_STEP` | No control number given | Run the step again; the model's answer named no control. | packages-browser |
| `WEBAGENT.BAD_URL` | start_url must be an http or https address: … | Example: https://portal.example.com/ | packages-browser |
| `WEBAGENT.BROWSER_NOT_FOUND` | There is no browser program at … | Give the full path of the browser's .exe file. | packages-browser |
| `WEBAGENT.NOT_ALLOWED` | … is not one of the allowed sites | Add it to allowed_sites or start elsewhere. | packages-browser |
| `WEBAGENT.NOT_SUPPORTED` | The web goal agent needs a browser it starts itself | Use Start web agent session, or Open browser with engine playwright. | packages-browser |
| `WEBAGENT.NO_CONTROL` | There is no control … on the page now | Run the step again; the page changed while the agent was working. | packages-browser |
| `WEBAGENT.NO_GOAL` | Describe the goal | Describe the goal in a sentence, for example: download last month's invoice. | packages-browser |
| `WEBAGENT.NO_SECRET` | There is no secret named … | Add the secret the message names to the step's secrets. | packages-browser |
| `WEBAGENT.OPEN_FAILED` | Cannot open …: … | Check the address and the network. | packages-browser |
| `WEBAGENT.WRITE_FAILED` | Cannot write …: …<br>Cannot write the trajectory file …: … | Check the folder exists and is writable. | packages-browser |

## WF

| Code | What it means | What to do | Where |
|---|---|---|---|
| `WF.BAD_STAGE` | Unknown stage type … | Update One CommandCenter; the workflow uses a stage it does not know. | modules/orchestration |
| `WF.EMPTY` | The workflow has no stages | Add at least one stage to the workflow. | modules/orchestration |
| `WF.STARTER_DISABLED` | The person who started the workflow is disabled | Ask an administrator to enable the person who started it, or start the workflow again. | modules/orchestration |

## WINDOW

| Code | What it means | What to do | Where |
|---|---|---|---|
| `WINDOW.BAD_KEY` | Unknown key … | Use {ENTER}, {TAB}, {BACKSPACE}, {DELETE}, {ESC}, arrows, {HOME}, {END}, {PGUP}, {PGDN}, {F1}-{F12}, optionally with a count such as {TAB 3}; { {} types {. | packages-windows |
| `WINDOW.BAD_OPTION` | method must be type, replace or append | Set method to type, replace or append. | packages-windows |
| `WINDOW.BAD_PATTERN` | …<br>Invalid regular expression: … | Check the regular expression after re:, e.g. re:^Invoice \d+ - Excel$.<br>Check the pattern, e.g. ^Invoice .* - Excel$. | packages-windows |
| `WINDOW.BAD_SIZE` | Width and height must be greater than 0 | Check the size. | packages-windows |
| `WINDOW.BAD_TITLE` | The new title must be at most 512 characters without line breaks | Shorten the title. | packages-windows |
| `WINDOW.CAPTURE_FAILED` | … | Make sure the window is visible and not minimised. | packages-windows |
| `WINDOW.NOT_ACTIVATED` | Windows did not let '…' come to the front | Another program is holding the focus (for example a full-screen or elevated window); close it, or run the agent with the desktop unlocked. | packages-windows |
| `WINDOW.NOT_FOUND` | No visible window title matches '…'<br>…Window '…' did not appear within … s…No visible window title matches '…'<br>No visible window of … within … s…… | Use List windows to see the exact titles.<br>Use List windows to see the exact titles, or record alternatives for the window.<br>Raise timeout_seconds so the program can open its window. | packages-windows |
| `WINDOW.NOT_HIDDEN` | Windows did not let '…' be hidden | The window may belong to a program running as administrator; run the agent with the same rights. | packages-windows |
| `WINDOW.NOT_RESPONDING` | The window did not take the typed input within 5 s | Check the program is not busy or showing a dialog. | packages-windows |
| `WINDOW.NOT_SUPPORTED` | Cannot set text on this system | Run this step on a Windows runner. | packages-windows |
| `WINDOW.NO_CONTROL` | Window '…' has …no… control……s… of class ……; index … is out of range… | Check the class name (e.g. Edit, RichEdit20W) with a window spy, and use control_index 0 for the first. | packages-windows |
| `WINDOW.NO_TARGET` | Give the window title or a process id | Fill in title, or process_id from Start program. | packages-windows |
| `WINDOW.PATTERN_TOO_BROAD` | Give at least two characters of the window title<br>The pattern matches an empty title, so it would match every window | A blank or one-letter pattern would match almost every window.<br>Make the pattern more specific. | packages-windows |
| `WINDOW.PICK_TIMEOUT` | No Ctrl+Shift+C within … s | Try again, and press Ctrl+Shift+C over the place. | packages-windows |
| `WINDOW.SET_TITLE_FAILED` | Cannot change the title of '…' | The window may belong to a program running with higher rights than the agent. | packages-windows |
| `WINDOW.STILL_OPEN` | Window '…' was still open after … s | Raise timeout_seconds, or close the window first (Close window). | packages-windows |

## WORD

| Code | What it means | What to do | Where |
|---|---|---|---|
| `WORD.BAD_ALIGNMENT` | Alignment must be left, center or right | Use left, center or right. | packages-office |
| `WORD.BAD_COLOR` | '…' is not a colour | Use a hex colour such as #C00000. | packages-office |
| `WORD.BAD_FORMAT` | Unsupported format '…' | Use docx, doc, rtf, odt, pdf or txt. | packages-office |
| `WORD.BAD_IMAGE` | Cannot add the picture …...…: …<br>Cannot insert …: …<br>Cannot add …: … | Use PNG, JPEG, GIF or BMP pictures.<br>Check the image file is not damaged. | packages-office |
| `WORD.BAD_OPTION` | page_breaks must be auto, layout or breaks, not '…' | Set page_breaks to auto, layout or breaks. | packages-office |
| `WORD.BAD_POSITION` | Unknown position '…' | Use end, start, after_text, before_text or bookmark. | packages-office |
| `WORD.BAD_RANGE` | Paragraph range … to … is not valid<br>The … span …… to …… is not valid | Use 1-based numbers with from_paragraph not above to_paragraph.<br>Use 1-based numbers with from_… not above to_…. | packages-office |
| `WORD.BAD_STYLE` | The document has no paragraph style '…' | …The document defines no paragraph styles; use …Styles in this document: …, …; or use …Heading1-6, Title, Subtitle or Quote, which are added when missing. | packages-office |
| `WORD.EMPTY_FIND` | Text to find is empty<br>Text to format is empty | Fill in the text to find.<br>Give the text to format. | packages-office |
| `WORD.EMPTY_TABLE` | The table has no columns<br>The table has no rows and include_header is false | Give the table at least one column.<br>Pass rows or include the header. | packages-office |
| `WORD.EXISTS` | A file already exists at … | Set overwrite to true or use open to edit it.<br>Set overwrite to true or choose another path. | packages-office |
| `WORD.INSERT_FAILED` | Cannot insert a table at that position<br>Cannot insert a paragraph at that position<br>Cannot insert at that position | Leave after_text and bookmark empty to add it at the end.<br>Use position end instead. | packages-office |
| `WORD.NOT_FOUND` | Picture not found: …<br>Document not found: …<br>Template not found: … | Use a local file or a data: URI in the img src.<br>Set create to true or fix the path.<br>Fix the template path. | packages-office |
| `WORD.NO_ANCHOR` | Position '…' needs an anchor | Set anchor to the marker text or bookmark name. | packages-office |
| `WORD.NO_BOOKMARK` | Bookmark '…' not found | Check the bookmark name in Word (Insert > Bookmark). | packages-office |
| `WORD.NO_LAYOUT` | The file holds no page layout from Word | Open and save it in Word once, or use page_breaks auto or breaks. | packages-office |
| `WORD.NO_PAGE` | Page must be 1 or more, not …<br>There is no page … (the document has …)<br>There is no page … (the document has … in Word's saved layout)… counted from its page and section breaks)… | Use a 1-based page number.<br>Use a page from 1 to …. | packages-office |
| `WORD.NO_PICTURE` | There is no picture number … (the document has …)<br>Give alt_text or index to choose the picture | Use a number from 1 to ….<br>Set alt_text or index. | packages-office |
| `WORD.NO_TABLE` | There is no table number … (the document has …)<br>No table has the title '…' | …The document has no tables.…Use a number from 1 to ….<br>Check the table's alternative-text title in Word (Table Properties > Alt Text) or its caption. | packages-office |
| `WORD.OPEN_FAILED` | Cannot open …: …<br>Cannot open template …: … | Close it in Word and check it is a .docx file.<br>Use a .docx template.<br>Check the file opens in Word. | packages-office |
| `WORD.PASSWORD_NOT_SUPPORTED` | Password-protected .… files cannot be opened<br>Passwords can be kept in .docx files only, not .… | Remove the password in Word, or save the file as .docx.<br>Save as .docx, or clear the password. | packages-office |
| `WORD.READ_ONLY` | The document was opened read-only | Use save_as with another path, or open it without read_only. | packages-office |
| `WORD.SAVE_FAILED` | Cannot save .… here<br>Cannot save …: … | Save as .docx.<br>Close the file if it is open in Word.<br>Check the folder is writable. | packages-office |
| `WORD.TEXT_NOT_FOUND` | Text '…' was not found in the document<br>Text '…' is not inside a single paragraph's runs | Check the marker text; it must appear exactly.<br>Use new_paragraph true. | packages-office |

## WORD_OPEN

| Code | What it means | What to do | Where |
|---|---|---|---|
| `WORD_OPEN.NO_TEXT` | Nothing to find<br>Nothing to add | Fill in the text to find.<br>Fill in the text. | packages |
| `WORD_OPEN.TOO_LONG` | Word replaces at most 255 characters at a time | Split the text, or use Word (no Office needed) for long texts. | packages |

## WORKDAY

| Code | What it means | What to do | Where |
|---|---|---|---|
| `WORKDAY.BAD_FORMAT` | Unknown format '…' | Use json, csv, simple_xml or workday_xml. | toolkits/enterprise |
| `WORKDAY.BAD_ID` | Invalid integration system id '…'<br>A WID is 32 hexadecimal characters<br>Invalid launch parameter name '…' | Use the integration system's id as Workday shows it (letters, digits, _ and -).<br>Use id_type integration_system_id for a named id.<br>Use the launch parameter's name as the integration defines it. | toolkits/enterprise |
| `WORKDAY.BAD_ID_TYPE` | Invalid id type '…' | Use WID, or a Workday ID type name such as the ones shown in the event's reference ids. | toolkits/enterprise |
| `WORKDAY.BAD_PATH` | The path must start with /ccx/<br>The report path must start with /ccx/service/customreport2/ and have no query | Example: /ccx/service/customreport2/acme/owner/Report?format=json<br>Example: /ccx/service/customreport2/acme/isu_reports/Active_Workers; put prompts in parameters. | toolkits/enterprise |
| `WORKDAY.BAD_RESPONSE` | Workday sent a report that is not valid XML<br>Workday sent a reply that is not valid XML<br>Workday did not return an integration event id | Check that the report is a web service report (RaaS) with XML output, and that the user may run it.<br>The service answered in an unexpected way; try again, and if it keeps happening check the service's status page.<br>Check the integration can be launched by this user. | toolkits/enterprise |
| `WORKDAY.BAD_TENANT` | Invalid tenant name '…' | Use the tenant name as it appears in your Workday address (letters, digits, _ and -). | toolkits/enterprise |
| `WORKDAY.BAD_VERSION` | Invalid web service version '…' | Example: v42.0. | toolkits/enterprise |
| `WORKDAY.NOT_FOUND` | Integration event … was not found | Check the event id and the user's access to it. | toolkits/enterprise |
| `WORKDAY.NO_CLIENT` | The client id is required | Fill in the API client id in the connection (Register API Client in Workday). | toolkits/enterprise |
| `WORKDAY.NO_FILE_FIELD` | Say which EIB field takes the file | Use the data source field name shown on the EIB's Launch / Schedule page. | toolkits/enterprise |
| `WORKDAY.NO_TOKEN` | Sign-in details are required | Give access_token, or user and password. | toolkits/enterprise |
| `WORKDAY.SOAP_FAULT` | Workday refused the request: … | Check the integration id, the user's security group and the web service version. | toolkits/enterprise |

## WORKSPACE

| Code | What it means | What to do | Where |
|---|---|---|---|
| `WORKSPACE.BAD_CONTENT` | Nothing to save | Add content before saving. | cli |
| `WORKSPACE.BAD_JSON` | Not valid JSON: …<br>The file must contain a JSON object | Fix the JSON, or use the designer tab.<br>Save the content as a JSON object ({ ... }). | cli |
| `WORKSPACE.BAD_NAME` | Invalid name '…' | Use letters, digits, spaces and - _ ( ) &; up to 80 characters. | cli |
| `WORKSPACE.BAD_TYPE` | Unknown item type '…' | Use form, process, document, objects, skill or script-python/powershell/vbscript/javascript. | cli |
| `WORKSPACE.EXISTS` | '…' already exists here<br>An agent named '…' already exists here<br>'…' already exists in the destination | Choose another name.<br>Rename one of them first.<br>Rename or delete some of them. | cli |
| `WORKSPACE.HIDDEN` | Hidden paths cannot be used: … | Use a path without a leading dot. | cli |
| `WORKSPACE.INTO_ITSELF` | A folder cannot be moved into itself<br>A folder cannot be copied into itself | Choose a folder outside the one being moved.<br>Choose a folder outside the one being copied. | cli |
| `WORKSPACE.LINK` | Links cannot be copied: … | Copy the file the link points to instead. | cli |
| `WORKSPACE.NOT_EDITABLE` | OneAgentStudio cannot open this kind of file: …<br>OneAgentStudio cannot save this kind of file: … | Open it with another program.<br>Edit this file in the program it belongs to. | cli |
| `WORKSPACE.NOT_FOUND` | Folder not found: …<br>Not found: …<br>Folder not found for … | Choose a folder that exists; reload the workspace if it was renamed or moved.<br>Reload the workspace (F5); it may have been renamed, moved or deleted. | cli |
| `WORKSPACE.OUTSIDE` | Path is outside the workspace: … | Choose a file or folder inside the workspace instead. | cli |
| `WORKSPACE.ROOT` | The workspace itself cannot be renamed<br>The workspace itself cannot be moved<br>The workspace itself cannot be copied | Choose a file or folder inside the workspace instead. | cli |
| `WORKSPACE.TOO_LARGE` | The file is larger than 2 MB: …<br>The content is larger than 2 MB | Open files larger than 2 MB in another program.<br>Save less than 2 MB in one file. | cli |

## XML

| Code | What it means | What to do | Where |
|---|---|---|---|
| `XML.BAD_NAME` | Invalid attribute name: …<br>Invalid element or attribute name: … | Use an XML name: letters, digits, - _ and ., starting with a letter. | packages |
| `XML.BAD_NAMESPACE` | Invalid namespace prefix '…'<br>The namespace prefix '…' has no URI | Use a prefix of letters, digits, _ . or -, not starting with xml.<br>Give each prefix its namespace URI. | packages |
| `XML.BAD_OPTION` | Unknown return_value '…'<br>Unknown position '…'<br>reference_child needs position before or after | Use text, xml or xpath.<br>Use end, start, before or after.<br>Set position to before or after, or leave reference_child empty. | packages |
| `XML.BAD_POSITION` | Cannot insert next to the root element | Use position end or start, or point the XPath at a child node. | packages |
| `XML.BAD_RESULT_TYPE` | Unknown result type '…' | Use string, number or boolean. | packages |
| `XML.BAD_SCHEMA` | Cannot use schema …: … | Check the XSD file. | packages |
| `XML.BAD_XPATH` | Invalid XPath '…': … | Example: /invoice/lines/line[1]/amount<br>Example: count(//line) or string(/invoice/@id) | packages |
| `XML.EXTERNAL_ENTITY_NOT_ALLOWED` | The XML declares an entity that reads a file or URL (SYSTEM/PUBLIC), which is refused for security<br>The XML refers to an external entity, which is refused for security | Remove the external <!ENTITY ...> declaration; internal entities and DOCTYPE lines are allowed.<br>Remove the external entity or DTD reference. | packages |
| `XML.INVALID` | Invalid XML: … | Check the document is well-formed XML. | packages |
| `XML.NOT_A_NUMBER` | The expression '…' did not produce a number | Check the nodes contain numbers, or use result_type string. | packages |
| `XML.NO_REFERENCE` | The node … has no child element '…' | Check reference_child, or leave it empty to insert next to the matched node. | packages |
| `XML.NO_SOURCE` | Give either XML text or a file path<br>This action changes a file; give its path<br>The XML text is empty | Fill in 'xml' or 'path'.<br>Fill in 'path'.<br>Fill in 'xml'. | packages |
| `XML.NO_VALUE` | Give the new value | Fill in 'value', or 'attributes' to set several attributes. | packages |
| `XML.TOO_MANY_ENTITIES` | The XML expands entities beyond the safe limit (an entity-expansion attack or a very unusual document) | Remove the nested entity declarations. | packages |
| `XML.UNKNOWN_PREFIX` | XPath prefix '…:' is not declared in the document or in namespaces | Add it to namespaces as {"…": "namespace URI"}….…; the document declares ….…<br>Add it to namespaces as {"…": "namespace URI"}. | packages |
| `XML.WRITE_FAILED` | … | Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it. | packages |

## ZENDESK

| Code | What it means | What to do | Where |
|---|---|---|---|
| `ZENDESK.AMBIGUOUS_USER` | More than one agent is named '…' | Use the agent's email or user id. | toolkits/enterprise |
| `ZENDESK.BAD_ID` | Invalid … id '…' | Use the numeric id, e.g. 35436. | toolkits/enterprise |
| `ZENDESK.BAD_INPUT` | Both requester_id and requester_email were given<br>requester_email is not an email address: …<br>… must be one of …, …, not '…' | Give the requester by id or by email, not both.<br>Give an address such as pat@customer.com.<br>Pick one of the listed values. | toolkits/enterprise |
| `ZENDESK.BAD_URL` | The Zendesk address is empty<br>Invalid Zendesk address '…' | Your Zendesk address, e.g. https://acme.zendesk.com, or just the subdomain acme. | toolkits/enterprise |
| `ZENDESK.FIELD_NOT_FOUND` | There is no ticket field named '…' | Use the field's title as agents see it, or its id (see List ticket fields). | toolkits/enterprise |
| `ZENDESK.GROUP_NOT_FOUND` | No Zendesk group named '…' | Use the group's name or id (see List groups). | toolkits/enterprise |
| `ZENDESK.NOTHING_TO_UPDATE` | No changes were given<br>Nothing to change | Fill in at least one field to change.<br>Fill in at least one field. | toolkits/enterprise |
| `ZENDESK.NOT_FOUND` | Attachment … has no file | Check the id with List comments. | toolkits/enterprise |
| `ZENDESK.NO_EMAIL` | An agent email is needed with an API token | Fill in email, or use oauth_token instead. | toolkits/enterprise |
| `ZENDESK.USER_NOT_FOUND` | No Zendesk user '…' | Use the agent's email, full name or user id. | toolkits/enterprise |

## ZIP

| Code | What it means | What to do | Where |
|---|---|---|---|
| `ZIP.BAD_COMPRESSION` | Unknown compression '…' | Use normal, fast, fastest or store. | packages |
| `ZIP.BAD_OPTION` | only_newer works only when adding to an existing archive<br>Files cannot be added to an existing archive with a password<br>The archive … is password-protected; files cannot be added to it | Turn on add_to_existing, or turn off only_newer.<br>Leave the password out, or write a new archive.<br>Write a new archive instead. | packages |
| `ZIP.BAD_PASSWORD` | The password is empty | Pick a credential with a value, or leave the password out. | packages |
| `ZIP.CORRUPT` | Cannot read the existing archive …: …<br>… is not a zip archive<br>… is damaged | Check it is a valid .zip file, or turn off add_to_existing to replace it.<br>Check the file.<br>Check the file, or get a new copy. | packages |
| `ZIP.PASSWORD_REQUIRED` | The archive is password-protected (…) | Give the password from the vault. | packages |
| `ZIP.TOO_LARGE` | More than 65,535 files for one encrypted archive<br>… is larger than 512 MB<br>… is too large | Split the files over several archives.<br>Encrypt very large files one by one, or zip them without a password.<br>Use a smaller file. | packages |
| `ZIP.UNSAFE_ENTRY` | Archive entry '…' points outside the destination | The archive may be malicious; do not extract it. | packages |
| `ZIP.UNSUPPORTED` | Unknown AES strength in …<br>… uses compression method …<br>… is a ZIP64 archive | Extract it with 7-Zip. | packages |
| `ZIP.WRONG_PASSWORD` | The password does not open … | Check the password stored in the vault. | packages |
