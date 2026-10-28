# DocuSign

Toolkit `ooa.docusign`, version 1.0.0. Send documents for e-signature, track and manage envelopes and recipients, use templates and download signed documents.

[All toolkits](README.md)

## Contents

- **Recipients**: Add recipients, List recipients, Update recipient
- **Envelopes**: Create envelope from documents, Create envelope from template, Get envelope status, List envelopes, Resend envelope, Send draft envelope, Void envelope
- **Embedded signing**: Get embedded signing link
- **Documents**: Download combined PDF, Download document, Download signed ZIP, List envelope documents
- **Custom fields**: Get envelope custom fields, Get filled-in form data, Set envelope custom fields
- **Templates**: Get template, List templates

## Recipients

### Add recipients

`ooa.docusign/add_recipients` (action)

Add signers or copy recipients to an envelope that is still a draft or in progress, optionally in a signing order.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |
| Recipients | `recipients` | List | Yes |  | Main | Recipients: each {"name", "email", "type": signer or copy, "routing_order": 1, 2 ...}, e.g. [{"name": "Ann Lee", "email": "ann@acme.test"}]. |
| Resend | `resend` | Yes/No | No | Yes | More options | Send the request to the new recipients when the envelope is already sent. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of recipients added. |

*Time limit 60 s; needs: network.*

### List recipients

`ooa.docusign/list_recipients` (action)

Return everyone on an envelope with their progress.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: recipient_id, type, name, email, routing_order, status, delivered, signed, declined_reason. |
| `count` | Number | Number of recipients. |

*Time limit 60 s; safe to repeat; needs: network.*

### Update recipient

`ooa.docusign/update_recipient` (action)

Correct a recipient's name or email address, for example after a typo, and optionally send the request again.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |
| Recipient ID | `recipient_id` | Text | Yes |  | Main | Recipient id from List recipients, e.g. 1. |
| New name | `new_name` | Text | No |  | Main | Corrected full name. Leave empty to keep it. |
| New email | `new_email` | Text | No |  | Main | Corrected email address. Leave empty to keep it. |
| Recipient type | `recipient_type` | Text | No | `signer` | More options | Whether this person signs or only receives a copy. One of: `signer`, `copy` |
| Resend | `resend` | Yes/No | No | Yes | More options | Send the signing request again to the corrected address. |

*Time limit 60 s; needs: network.*

## Envelopes

### Create envelope from documents

`ooa.docusign/create_envelope` (action)

Prepare an envelope from local files and signers, then send it now or keep it as a draft.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Email subject | `email_subject` | Text | Yes |  | Main | Subject of the signing request email. |
| Signers | `signers` | List | Yes |  | Main | People who sign, in a list like [{"name": "Ann Lee", "email": "ann@acme.com"}]. Add routing_order to sign one after another. |
| Documents | `documents` | List | Yes |  | Main | Local files to sign, e.g. ["contracts/nda.pdf"]. PDF, Word and most office formats work. |
| Send now | `send_now` | Yes/No | No | Yes | Main | Send right away. When off, the envelope is saved as a draft to send later. |
| Email message | `email_message` | Text | No |  | More options | Message shown in the signing request email. |
| Copy to | `copy_to` | List | No |  | More options | People who receive a copy when signing is finished, same format as signers. |
| Sign here text | `sign_here_text` | Text | No |  | More options | Text in the document where the signature box goes. Leave empty to let signers place it themselves. Example: `/sig/` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `envelope_id` | Text | Id of the new envelope. |
| `status` | Text | sent or created (draft). |

*Time limit 600 s; needs: network, filesystem.*

### Create envelope from template

`ooa.docusign/create_envelope_from_template` (action)

Start an envelope from a saved template by filling each template role with a person.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Email subject | `email_subject` | Text | No |  | Main | Subject of the email. Leave empty to use the template's subject. |
| Template ID | `template_id` | Text | Yes |  | Main | Template to use (see List templates). |
| Roles | `roles` | List | Yes |  | Main | Who fills each role, e.g. [{"role": "Client", "name": "Ann Lee", "email": "ann@acme.com"}]. |
| Send now | `send_now` | Yes/No | No | Yes | Main | Send right away. When off, the envelope is saved as a draft. |
| Field values | `field_values` | Dictionary | No |  | More options | Pre-filled text fields by their label, e.g. {"ContractNumber": "C-1042"}. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `envelope_id` | Text | Id of the new envelope. |
| `status` | Text | sent or created (draft). |

*Time limit 60 s; needs: network.*

### Get envelope status

`ooa.docusign/get_envelope_status` (action)

Check where an envelope is: draft, sent, delivered, completed, declined or voided.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `status` | Text | created, sent, delivered, completed, declined or voided. |
| `envelope` | Dictionary | envelope_id, status, subject, sent, delivered, completed, voided_reason, status_changed. |

*Time limit 60 s; safe to repeat; needs: network.*

### List envelopes

`ooa.docusign/list_envelopes` (action)

Return envelopes changed since a date, optionally only those with a given status or matching some text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Changed since | `changed_since` | DateTime | No |  | Main | Only envelopes changed on or after this date. Leave empty for the last 30 days. |
| Changed before | `changed_before` | DateTime | No |  | Main | Only envelopes changed before this date. |
| Status | `status` | Text | No |  | Main | Only these statuses, separated by commas, e.g. sent,delivered. Example: `completed` |
| Search text | `search_text` | Text | No |  | More options | Only envelopes whose subject, sender or recipients contain this text. |
| Maximum results | `max_results` | Number | No | `1000` | More options | Most envelopes to return. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: envelope_id, status, subject, sent, delivered, completed, voided_reason, status_changed. |
| `count` | Number | Number of envelopes returned. |

*Time limit 600 s; safe to repeat; needs: network.*

### Resend envelope

`ooa.docusign/resend_envelope` (action)

Send the signing reminder email again to recipients who have not finished.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |

*Time limit 60 s; needs: network.*

### Send draft envelope

`ooa.docusign/send_envelope` (action)

Send an envelope that was saved as a draft.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |

*Time limit 60 s; needs: network.*

### Void envelope

`ooa.docusign/void_envelope` (action)

Cancel an envelope that is still out for signature. Recipients are told why.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |
| Reason | `reason` | Text | Yes |  | Main | Why the envelope is cancelled; recipients see this. |

*Time limit 60 s; needs: network.*

## Embedded signing

### Get embedded signing link

`ooa.docusign/create_signing_url` (action)

Get a short-lived link that opens the signing session for a recipient inside your own app or portal.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |
| Name | `name` | Text | Yes |  | Main | Signer's name exactly as on the envelope. |
| Email | `email` | Text | Yes |  | Main | Signer's email exactly as on the envelope. |
| Client user ID | `client_user_id` | Text | Yes |  | Main | The client_user_id given to this signer when the envelope was created. |
| Return address (URL) | `return_url` | Text | Yes |  | Main | Page the signer goes to when they finish or leave. |
| Authentication method | `authentication_method` | Text | No | `none` | More options | How your app checked who the signer is, e.g. none, email or password. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `url` | Text | Signing link; it works once and expires within minutes. |

*Time limit 60 s; needs: network.*

## Documents

### Download combined PDF

`ooa.docusign/download_combined_pdf` (action)

Save all documents of an envelope as one PDF, optionally with the signing certificate at the end.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |
| Save as | `save_as` | File path | Yes |  | Main | Where to save the PDF, e.g. signed/all.pdf. Missing folders are created. |
| Include certificate | `include_certificate` | Yes/No | No | Yes | More options | Add the signing certificate as the last pages. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Full path of the saved PDF. |

*Time limit 600 s; needs: network, filesystem.*

### Download document

`ooa.docusign/download_document` (action)

Save one document from an envelope, the signing certificate, or all documents as a zip archive.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |
| Document ID | `document_id` | Text | Yes |  | Main | Document number from List envelope documents, or certificate for the signing certificate, or archive for a zip of everything. Example: `1` |
| Save as | `save_as` | File path | Yes |  | Main | Where to save it, e.g. signed/contract.pdf. Missing folders are created. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Full path of the saved file. |

*Time limit 600 s; needs: network, filesystem.*

### Download signed ZIP

`ooa.docusign/download_zip` (action)

Save all documents of an envelope as separate PDFs in one ZIP file, with the signing certificate.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |
| Save as | `save_as` | File path | Yes |  | Main | Where to save the ZIP, e.g. signed/contract.zip. Missing folders are created. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `path` | File path | Full path of the saved ZIP. |

*Time limit 600 s; needs: network, filesystem.*

### List envelope documents

`ooa.docusign/list_documents` (action)

Return the documents inside an envelope.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: document_id, name, type, pages, order. |

*Time limit 60 s; safe to repeat; needs: network.*

## Custom fields

### Get envelope custom fields

`ooa.docusign/get_custom_fields` (action)

Read the custom fields stored on an envelope, such as a contract or order number.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `fields` | Dictionary | Field name to value. |

*Time limit 60 s; safe to repeat; needs: network.*

### Get filled-in form data

`ooa.docusign/get_form_data` (action)

Read the values recipients typed into the envelope's fields, for example after it is completed.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | Dictionary | Field label to entered value. |

*Time limit 60 s; safe to repeat; needs: network.*

### Set envelope custom fields

`ooa.docusign/set_custom_fields` (action)

Add or change custom fields on an envelope. Fields that already exist are updated, new ones are added.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Envelope ID | `envelope_id` | Text | Yes |  | Main | Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59. |
| Fields | `fields` | Dictionary | Yes |  | Main | Field name to value, e.g. {"OrderNumber": "SO-5531"}. |
| Show to recipients | `show_to_recipients` | Yes/No | No | No | More options | Let recipients see these fields. |

*Time limit 60 s; needs: network.*

## Templates

### Get template

`ooa.docusign/get_template` (action)

Read a template's name, roles and documents, so you know which roles to fill.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Template ID | `template_id` | Text | Yes |  | Main | Template id from List templates. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `template` | Dictionary | template_id, name, description, email_subject, documents. |
| `roles` | List | Names of the roles to fill, e.g. ["Client", "Manager"]. |

*Time limit 60 s; safe to repeat; needs: network.*

### List templates

`ooa.docusign/list_templates` (action)

Return the templates you can use, optionally only those matching some text.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Access token | `access_token` | Credential (vault) | Yes |  | Main | DocuSign OAuth access token from the vault. |
| Account ID | `account_id` | Text | Yes |  | Main | DocuSign account id (a GUID or number shown under Apps and Keys). |
| Endpoint address | `base_url` | Text | Yes |  | Main | Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production. |
| Search text | `search_text` | Text | No |  | Main | Only templates whose name or description contains this text. |
| Maximum results | `max_results` | Number | No | `500` | More options | Most templates to return. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: template_id, name, description, folder, last_modified. |
| `count` | Number | Number of templates returned. |

*Time limit 300 s; safe to repeat; needs: network.*
