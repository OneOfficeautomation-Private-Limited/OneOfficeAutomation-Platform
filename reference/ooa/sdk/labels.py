"""Plain labels for toolkit fields without one of their own (T3), the same rules as the Java SDK's FieldLabels:
a label is made from the field's name word by word, with abbreviations and codes written out (delay_ms is
"Delay (milliseconds)", max_rows "Maximum rows", api_token "API token", folder_id "Folder ID")."""

NAMES = {
    "object": "Captured object", "jdbc_url": "Database address (JDBC URL)", "sql": "SQL statement",
    "imap_host": "Incoming mail server (IMAP)", "smtp_host": "Outgoing mail server (SMTP)", "base_url": "Endpoint address",
    "mask_pii": "Hide personal data", "insecure_tls": "Allow untrusted certificates", "wait_closed_seconds": "Wait until closed (seconds)",
    "type_delay_ms": "Pause between keys (milliseconds)", "timeout_ms": "Wait up to (milliseconds)", "html": "Body is HTML",
    "include_original": "Include the original message", "aws_access_key_id": "AWS access key ID", "aws_region": "AWS region",
    "url": "Address (URL)", "if_not_found": "If not found", "keep_open": "Keep the browser open after the run",
    "press_enter": "Press Enter afterwards", "detect_types": "Detect numbers and dates", "double_click": "Double-click",
    "header": "First row is the header", "base_dn": "Search base (distinguished name)", "ou": "Organizational unit (OU)",
    "ou_description": "Organizational unit description", "sam_account_name": "Pre-Windows 2000 name (sAMAccountName)",
    "update_sam_account_name": "Also change the pre-Windows 2000 name", "sys_id": "Record sys_id", "sha": "Current version (SHA)",
    "expected_head_sha": "Only if the branch is still at (commit SHA)", "regex": "Find is a regular expression",
    "use_regex": "Title is a regular expression", "json": "JSON", "xml": "XML", "auth": "Sign-in settings", "dpi": "Resolution (DPI)",
    "hold_ms": "Hold keys down for (milliseconds)", "stable_ms": "Unchanged for (milliseconds)", "id_type": "Kind of ID",
    "event_id": "Integration event (WID)", "url_safe": "URL-safe alphabet", "implicit_tls": "Implicit TLS", "tls": "Encrypted connection (TLS)",
    "random_max": "Random, up to (same unit)", "max": "Largest value", "min": "Smallest value", "client_user_id": "Client user ID",
    "label_id": "Sensitivity label ID", "site_id": "Tenant (site) ID", "in_folder_id": "Only in folder (ID)",
}

WORDS = {
    "id": "ID", "ids": "IDs", "url": "address (URL)", "uri": "address (URI)", "dn": "(distinguished name)", "ou": "organizational unit",
    "max": "maximum", "min": "minimum", "regex": "regular expression", "api": "API", "json": "JSON", "xml": "XML", "csv": "CSV",
    "html": "HTML", "sql": "SQL", "tls": "TLS", "ssl": "SSL", "smtp": "SMTP", "imap": "IMAP", "pop3": "POP3", "http": "HTTP",
    "https": "HTTPS", "ftp": "FTP", "sftp": "SFTP", "ssh": "SSH", "sha": "SHA", "mime": "MIME", "dpi": "DPI", "pdf": "PDF",
    "ocr": "OCR", "ui": "UI", "ip": "IP", "dns": "DNS", "jql": "JQL", "soql": "SOQL", "uuid": "UUID", "guid": "GUID", "jdbc": "JDBC",
    "oauth": "OAuth", "xpath": "XPath", "cc": "Cc", "bcc": "Bcc", "utc": "UTC", "crm": "CRM", "sap": "SAP", "ldap": "LDAP", "kpi": "KPI",
    "sla": "SLA", "ai": "AI", "llm": "LLM", "qr": "QR", "wid": "WID", "aws": "AWS", "gcp": "GCP", "vm": "VM", "os": "OS",
    "pii": "personal data", "ssn": "SSN", "iban": "IBAN", "vat": "VAT", "smb": "SMB", "unc": "UNC", "rpa": "RPA", "auth": "sign-in",
    "mfa": "MFA", "otp": "one-time code", "totp": "TOTP", "hmac": "HMAC", "aes": "AES", "rsa": "RSA", "pgp": "PGP", "utf8": "UTF-8",
    "eol": "line ending", "org": "organization",
}

UNITS = {"ms": "milliseconds", "seconds": "seconds", "minutes": "minutes", "hours": "hours", "days": "days", "bytes": "bytes",
         "kb": "KB", "mb": "MB", "px": "pixels", "pct": "%"}


def label_of(name: str) -> str:
    """The label for a field without one of its own."""
    if name in NAMES:
        return NAMES[name]
    words = [w for w in name.split("_") if w]
    if not words:
        return name
    unit = UNITS[words.pop()] if len(words) > 1 and words[-1] in UNITS else None
    label = " ".join(WORDS.get(w, w) for w in words)
    if label.startswith("("):
        label = "Name " + label
    label = label[0].upper() + label[1:]
    return f"{label} ({unit})" if unit else label
