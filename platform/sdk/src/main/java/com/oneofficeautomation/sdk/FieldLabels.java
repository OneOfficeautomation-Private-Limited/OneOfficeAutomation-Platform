package com.oneofficeautomation.sdk;

import java.util.*;

/**
 * Plain labels for toolkit fields that have none of their own (T3). A label is made from the field's name word by word,
 * with abbreviations and codes written out: delay_ms is "Delay (milliseconds)", base_dn "Search base (distinguished
 * name)", max_rows "Maximum rows", api_token "API token", sys_id "Record sys_id". A few names whose words alone read
 * badly have a label of their own. A label given with @Input always wins.
 */
public final class FieldLabels {
    private FieldLabels() { }

    /** Whole names with a label of their own. */
    static final Map<String, String> NAMES = Map.ofEntries(
        Map.entry("object", "Captured object"), Map.entry("jdbc_url", "Database address (JDBC URL)"), Map.entry("sql", "SQL statement"),
        Map.entry("imap_host", "Incoming mail server (IMAP)"), Map.entry("smtp_host", "Outgoing mail server (SMTP)"), Map.entry("base_url", "Endpoint address"),
        Map.entry("mask_pii", "Hide personal data"), Map.entry("insecure_tls", "Allow untrusted certificates"), Map.entry("wait_closed_seconds", "Wait until closed (seconds)"),
        Map.entry("type_delay_ms", "Pause between keys (milliseconds)"), Map.entry("timeout_ms", "Wait up to (milliseconds)"), Map.entry("html", "Body is HTML"),
        Map.entry("include_original", "Include the original message"), Map.entry("aws_access_key_id", "AWS access key ID"), Map.entry("aws_region", "AWS region"),
        Map.entry("url", "Address (URL)"), Map.entry("if_not_found", "If not found"), Map.entry("keep_open", "Keep the browser open after the run"),
        Map.entry("press_enter", "Press Enter afterwards"), Map.entry("detect_types", "Detect numbers and dates"), Map.entry("double_click", "Double-click"),
        Map.entry("header", "First row is the header"),
        Map.entry("base_dn", "Search base (distinguished name)"), Map.entry("ou", "Organizational unit (OU)"), Map.entry("ou_description", "Organizational unit description"),
        Map.entry("sam_account_name", "Pre-Windows 2000 name (sAMAccountName)"), Map.entry("update_sam_account_name", "Also change the pre-Windows 2000 name"),
        Map.entry("sys_id", "Record sys_id"), Map.entry("sha", "Current version (SHA)"), Map.entry("expected_head_sha", "Only if the branch is still at (commit SHA)"),
        Map.entry("regex", "Find is a regular expression"), Map.entry("use_regex", "Title is a regular expression"), Map.entry("json", "JSON"), Map.entry("xml", "XML"),
        Map.entry("auth", "Sign-in settings"), Map.entry("dpi", "Resolution (DPI)"), Map.entry("hold_ms", "Hold keys down for (milliseconds)"),
        Map.entry("stable_ms", "Unchanged for (milliseconds)"), Map.entry("id_type", "Kind of ID"), Map.entry("event_id", "Integration event (WID)"),
        Map.entry("url_safe", "URL-safe alphabet"), Map.entry("implicit_tls", "Implicit TLS"), Map.entry("tls", "Encrypted connection (TLS)"),
        Map.entry("random_max", "Random, up to (same unit)"), Map.entry("max", "Largest value"), Map.entry("min", "Smallest value"),
        Map.entry("client_user_id", "Client user ID"), Map.entry("label_id", "Sensitivity label ID"), Map.entry("site_id", "Tenant (site) ID"), Map.entry("in_folder_id", "Only in folder (ID)"));

    /** Words written out wherever they appear in a name. */
    static final Map<String, String> WORDS = Map.ofEntries(
        Map.entry("id", "ID"), Map.entry("ids", "IDs"), Map.entry("url", "address (URL)"), Map.entry("uri", "address (URI)"), Map.entry("dn", "(distinguished name)"),
        Map.entry("ou", "organizational unit"), Map.entry("max", "maximum"), Map.entry("min", "minimum"), Map.entry("regex", "regular expression"),
        Map.entry("api", "API"), Map.entry("json", "JSON"), Map.entry("xml", "XML"), Map.entry("csv", "CSV"), Map.entry("html", "HTML"), Map.entry("sql", "SQL"),
        Map.entry("tls", "TLS"), Map.entry("ssl", "SSL"), Map.entry("smtp", "SMTP"), Map.entry("imap", "IMAP"), Map.entry("pop3", "POP3"), Map.entry("http", "HTTP"),
        Map.entry("https", "HTTPS"), Map.entry("ftp", "FTP"), Map.entry("sftp", "SFTP"), Map.entry("ssh", "SSH"), Map.entry("sha", "SHA"), Map.entry("mime", "MIME"),
        Map.entry("dpi", "DPI"), Map.entry("pdf", "PDF"), Map.entry("ocr", "OCR"), Map.entry("ui", "UI"), Map.entry("ip", "IP"), Map.entry("dns", "DNS"),
        Map.entry("jql", "JQL"), Map.entry("soql", "SOQL"), Map.entry("uuid", "UUID"), Map.entry("guid", "GUID"), Map.entry("jdbc", "JDBC"), Map.entry("oauth", "OAuth"),
        Map.entry("xpath", "XPath"), Map.entry("cc", "Cc"), Map.entry("bcc", "Bcc"), Map.entry("utc", "UTC"), Map.entry("crm", "CRM"), Map.entry("sap", "SAP"),
        Map.entry("ldap", "LDAP"), Map.entry("kpi", "KPI"), Map.entry("sla", "SLA"), Map.entry("ai", "AI"), Map.entry("llm", "LLM"), Map.entry("qr", "QR"),
        Map.entry("wid", "WID"), Map.entry("aws", "AWS"), Map.entry("gcp", "GCP"), Map.entry("vm", "VM"), Map.entry("os", "OS"), Map.entry("pii", "personal data"),
        Map.entry("ssn", "SSN"), Map.entry("iban", "IBAN"), Map.entry("vat", "VAT"), Map.entry("smb", "SMB"), Map.entry("unc", "UNC"), Map.entry("rpa", "RPA"),
        Map.entry("auth", "sign-in"), Map.entry("mfa", "MFA"), Map.entry("otp", "one-time code"), Map.entry("totp", "TOTP"), Map.entry("hmac", "HMAC"),
        Map.entry("aes", "AES"), Map.entry("rsa", "RSA"), Map.entry("pgp", "PGP"), Map.entry("utf8", "UTF-8"), Map.entry("eol", "line ending"), Map.entry("org", "organization"));

    /** Units at the end of a name: written in brackets after the rest. */
    static final Map<String, String> UNITS = Map.ofEntries(Map.entry("ms", "milliseconds"), Map.entry("seconds", "seconds"), Map.entry("minutes", "minutes"),
        Map.entry("hours", "hours"), Map.entry("days", "days"), Map.entry("bytes", "bytes"), Map.entry("kb", "KB"), Map.entry("mb", "MB"), Map.entry("px", "pixels"),
        Map.entry("pct", "%"));

    /** The label for a field without one of its own. */
    public static String of(String name) {
        String own = NAMES.get(name);
        if (own != null) return own;
        List<String> words = new ArrayList<>(Arrays.asList(name.split("_")));
        words.removeIf(String::isEmpty);
        if (words.isEmpty()) return name;
        String unit = null;
        if (words.size() > 1 && UNITS.containsKey(words.get(words.size() - 1))) unit = UNITS.get(words.remove(words.size() - 1));
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(WORDS.getOrDefault(w, w));
        }
        String label = sb.toString();
        if (label.startsWith("(")) label = "Name " + label;   // a name that is only "dn"
        label = Character.toUpperCase(label.charAt(0)) + label.substring(1);
        return unit == null ? label : label + " (" + unit + ")";
    }
}
