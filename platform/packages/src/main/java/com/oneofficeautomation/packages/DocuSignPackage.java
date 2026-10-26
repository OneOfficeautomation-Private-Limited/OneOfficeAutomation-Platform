package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** DocuSign eSignature REST API (v2.1): envelopes, recipients, documents, templates, embedded signing and custom fields. */
@OoaPackage(id = "ooa.docusign", name = "DocuSign", version = "1.0.0",
        description = "Send documents for e-signature, track and manage envelopes and recipients, use templates and download signed documents.")
public final class DocuSignPackage {
    static final String P = "DOCUSIGN";
    private static final Pattern GUID = Pattern.compile("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$");
    private static final Pattern DOC_ID = Pattern.compile("^(\\d{1,9}|combined|archive|certificate|portfolio)$");
    private static final String D_URL = "Your account's REST address, e.g. https://demo.docusign.net/restapi for testing or https://na4.docusign.net/restapi in production.";
    private static final String D_ACCOUNT = "DocuSign account id (a GUID or number shown under Apps and Keys).";
    private static final String D_TOKEN = "DocuSign OAuth access token from the vault.";
    private static final String D_ENV = "Envelope id, e.g. 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59.";
    private static final List<String> RECIPIENT_TYPES = List.of("signers", "carbonCopies", "certifiedDeliveries", "agents", "editors", "intermediaries", "inPersonSigners", "witnesses");

    private static String api(String base, String account) {
        if (account == null || account.isBlank()) throw new ActionException(P + ".BAD_ACCOUNT", "An account id is required", D_ACCOUNT);
        return base.replaceAll("/+$", "") + "/v2.1/accounts/" + HttpJson.enc(account.trim());
    }

    private static String guid(String what, String v) {
        if (v == null || !GUID.matcher(v.trim()).matches()) throw new ActionException(P + ".BAD_ID", "Invalid " + what + " id '" + v + "'", "Ids look like 4b2f1a8c-1d3e-4f5a-9b7c-0e1d2c3b4a59.");
        return v.trim();
    }

    /** Sends a request; DocuSign reports a missing envelope or template as HTTP 400, which is turned into NOT_FOUND here. */
    private static HttpJson.Response send(ActionContext ctx, HttpJson.Req r) {
        try { return r.send(ctx); }
        catch (ActionException e) {
            String m = String.valueOf(e.getMessage());
            if (e.code().equals(P + ".REQUEST_FAILED") && (m.contains("DOES_NOT_EXIST") || m.contains("INVALID_ENVELOPE_ID") || m.contains("TEMPLATE_ID_INVALID")))
                throw new ActionException(P + ".NOT_FOUND", m, "Check the id and that it belongs to this account.", false, e.details());
            throw e;
        }
    }

    private static HttpJson.Req req(String method, String url, Object token) { return HttpJson.req(P, method, url).bearer(token); }

    /** DocuSign sends counts as text, e.g. "totalSetSize": "42". */
    private static int intOf(Object v) {
        if (v == null) return 0;
        try { return Values.toNumber(v).intValue(); } catch (RuntimeException e) { return 0; }
    }

    private static String str(Map<?, ?> m, String k) { Object v = m.get(k); return v == null ? null : Values.text(v); }

    private static Map<String, Object> envelopeSummary(Map<?, ?> e) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("envelope_id", e.get("envelopeId"));
        o.put("status", e.get("status"));
        o.put("subject", e.get("emailSubject"));
        o.put("sent", e.get("sentDateTime"));
        o.put("delivered", e.get("deliveredDateTime"));
        o.put("completed", e.get("completedDateTime"));
        o.put("voided_reason", e.get("voidedReason"));
        o.put("status_changed", e.get("statusChangedDateTime"));
        return o;
    }

    /** Turns {"name","email",...} dictionaries (any key spelling) into signer entries. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> people(List<Object> list, String what, int startId, String anchor) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (list == null) return out;
        int n = startId;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> raw)) throw new ActionException(P + ".BAD_RECIPIENT", what + " entries must be dictionaries with name and email", "Example: [{\"name\": \"Ann Lee\", \"email\": \"ann@acme.com\"}].");
            Map<String, Object> m = new LinkedHashMap<>();
            for (var e : ((Map<String, Object>) raw).entrySet()) m.put(e.getKey().toLowerCase(Locale.ROOT).replace("_", ""), e.getValue());
            if (m.get("name") == null || m.get("email") == null)
                throw new ActionException(P + ".BAD_RECIPIENT", what + " entry " + (n - startId + 1) + " needs a name and an email", "Example: {\"name\": \"Ann Lee\", \"email\": \"ann@acme.com\"}.");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("recipientId", String.valueOf(n));
            r.put("name", Values.text(m.get("name")));
            r.put("email", Values.text(m.get("email")));
            r.put("routingOrder", m.get("routingorder") == null ? "1" : Values.text(m.get("routingorder")));
            if (m.get("clientuserid") != null) r.put("clientUserId", Values.text(m.get("clientuserid")));
            if (anchor != null && !anchor.isBlank())
                r.put("tabs", Map.of("signHereTabs", List.of(Map.of("anchorString", anchor, "anchorUnits", "pixels", "anchorXOffset", "0", "anchorYOffset", "0"))));
            out.add(r);
            n++;
        }
        return out;
    }

    // ----- envelopes -----

    @Action(id = "create_envelope", name = "Create envelope from documents", category = "Envelopes", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Prepare an envelope from local files and signers, then send it now or keep it as a draft.",
            panel = "access_token, account_id, base_url, email_subject, signers, documents, send_now | email_message, copy_to, sign_here_text")
    @Output(name = "envelope_id", type = OoaType.STRING, description = "Id of the new envelope.")
    @Output(name = "status", type = OoaType.STRING, description = "sent or created (draft).")
    public Map<String, Object> createEnvelope(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "documents", type = OoaType.LIST, description = "Local files to sign, e.g. [\"contracts/nda.pdf\"]. PDF, Word and most office formats work.") List<Object> docs,
            @Input(name = "signers", type = OoaType.LIST, description = "People who sign, in a list like [{\"name\": \"Ann Lee\", \"email\": \"ann@acme.com\"}]. Add routing_order to sign one after another.") List<Object> signers,
            @Input(name = "email_subject", type = OoaType.STRING, description = "Subject of the signing request email.") String subject,
            @Input(name = "email_message", type = OoaType.STRING, required = false, description = "Message shown in the signing request email.") String message,
            @Input(name = "copy_to", type = OoaType.LIST, required = false, description = "People who receive a copy when signing is finished, same format as signers.") List<Object> cc,
            @Input(name = "sign_here_text", type = OoaType.STRING, required = false, example = "/sig/", description = "Text in the document where the signature box goes. Leave empty to let signers place it themselves.") String anchor,
            @Input(name = "send_now", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Send right away. When off, the envelope is saved as a draft to send later.") boolean sendNow,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        if (docs == null || docs.isEmpty()) throw new ActionException(P + ".NO_DOCUMENTS", "Add at least one document", "List one or more local file paths in documents.");
        if (signers == null || signers.isEmpty()) throw new ActionException(P + ".NO_SIGNERS", "Add at least one signer", "Example: [{\"name\": \"Ann Lee\", \"email\": \"ann@acme.com\"}].");
        List<Map<String, Object>> documents = new ArrayList<>();
        int i = 1;
        for (Object d : docs) {
            Path p = ctx.resolvePath(Values.text(d));
            String fn = p.getFileName().toString();
            int dot = fn.lastIndexOf('.');
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("documentId", String.valueOf(i++));
            doc.put("name", fn);
            doc.put("fileExtension", dot > 0 ? fn.substring(dot + 1) : "pdf");
            doc.put("documentBase64", Base64.getEncoder().encodeToString(HttpJson.readFile(p)));
            documents.add(doc);
        }
        List<Map<String, Object>> s = people(signers, "signers", 1, anchor);
        Map<String, Object> recipients = new LinkedHashMap<>();
        recipients.put("signers", s);
        if (cc != null && !cc.isEmpty()) recipients.put("carbonCopies", people(cc, "copy_to", s.size() + 1, null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("emailSubject", subject);
        if (message != null) body.put("emailBlurb", message);
        body.put("documents", documents);
        body.put("recipients", recipients);
        body.put("status", sendNow ? "sent" : "created");
        Map<String, Object> r = send(ctx, req("POST", api(base, account) + "/envelopes", token).json(body).timeout(600)).map();
        return Out.of("envelope_id", r.get("envelopeId"), "status", r.get("status"));
    }

    @Action(id = "create_envelope_from_template", name = "Create envelope from template", category = "Envelopes", capabilities = "network",
            description = "Start an envelope from a saved template by filling each template role with a person.",
            panel = "access_token, account_id, base_url, email_subject, template_id, roles, send_now | field_values")
    @Output(name = "envelope_id", type = OoaType.STRING, description = "Id of the new envelope.")
    @Output(name = "status", type = OoaType.STRING, description = "sent or created (draft).")
    public Map<String, Object> fromTemplate(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "template_id", type = OoaType.STRING, description = "Template to use (see List templates).") String template,
            @Input(name = "roles", type = OoaType.LIST, description = "Who fills each role, e.g. [{\"role\": \"Client\", \"name\": \"Ann Lee\", \"email\": \"ann@acme.com\"}].") List<Object> roles,
            @Input(name = "field_values", type = OoaType.DICTIONARY, required = false, description = "Pre-filled text fields by their label, e.g. {\"ContractNumber\": \"C-1042\"}.") Map<String, Object> fields,
            @Input(name = "email_subject", type = OoaType.STRING, required = false, description = "Subject of the email. Leave empty to use the template's subject.") String subject,
            @Input(name = "send_now", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Send right away. When off, the envelope is saved as a draft.") boolean sendNow,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        if (roles == null || roles.isEmpty()) throw new ActionException(P + ".NO_SIGNERS", "Fill at least one template role", "Example: [{\"role\": \"Client\", \"name\": \"Ann Lee\", \"email\": \"ann@acme.com\"}].");
        List<Map<String, Object>> tr = new ArrayList<>();
        for (Object o : roles) {
            if (!(o instanceof Map<?, ?> m)) throw new ActionException(P + ".BAD_RECIPIENT", "Each role must be a dictionary with role, name and email");
            Map<String, Object> x = new LinkedHashMap<>();
            for (var e : m.entrySet()) x.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT).replace("_", ""), e.getValue());
            Object roleName = x.get("role") != null ? x.get("role") : x.get("rolename");
            if (roleName == null || x.get("name") == null || x.get("email") == null)
                throw new ActionException(P + ".BAD_RECIPIENT", "Each role needs role, name and email", "Example: {\"role\": \"Client\", \"name\": \"Ann Lee\", \"email\": \"ann@acme.com\"}.");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("roleName", Values.text(roleName));
            r.put("name", Values.text(x.get("name")));
            r.put("email", Values.text(x.get("email")));
            if (x.get("clientuserid") != null) r.put("clientUserId", Values.text(x.get("clientuserid")));
            if (fields != null && !fields.isEmpty()) {
                List<Map<String, Object>> tabs = new ArrayList<>();
                fields.forEach((k, v) -> tabs.add(Map.of("tabLabel", k, "value", Values.text(v))));
                r.put("tabs", Map.of("textTabs", tabs));
            }
            tr.add(r);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateId", guid("template", template));
        body.put("templateRoles", tr);
        if (subject != null && !subject.isBlank()) body.put("emailSubject", subject);
        body.put("status", sendNow ? "sent" : "created");
        Map<String, Object> r = send(ctx, req("POST", api(base, account) + "/envelopes", token).json(body)).map();
        return Out.of("envelope_id", r.get("envelopeId"), "status", r.get("status"));
    }

    @Action(id = "send_envelope", name = "Send draft envelope", category = "Envelopes", capabilities = "network", description = "Send an envelope that was saved as a draft.",
            panel = "access_token, account_id, base_url, envelope_id")
    public Map<String, Object> sendEnvelope(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        send(ctx, req("PUT", api(base, account) + "/envelopes/" + guid("envelope", env), token).json(Map.of("status", "sent")));
        return Map.of();
    }

    @Action(id = "get_envelope_status", name = "Get envelope status", category = "Envelopes", idempotent = true, capabilities = "network",
            description = "Check where an envelope is: draft, sent, delivered, completed, declined or voided.",
            panel = "access_token, account_id, base_url, envelope_id")
    @Output(name = "status", type = OoaType.STRING, description = "created, sent, delivered, completed, declined or voided.")
    @Output(name = "envelope", type = OoaType.DICTIONARY, description = "envelope_id, status, subject, sent, delivered, completed, voided_reason, status_changed.")
    public Map<String, Object> status(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        Map<String, Object> r = send(ctx, req("GET", api(base, account) + "/envelopes/" + guid("envelope", env), token)).map();
        return Out.of("status", r.get("status"), "envelope", envelopeSummary(r));
    }

    @Action(id = "list_envelopes", name = "List envelopes", category = "Envelopes", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Return envelopes changed since a date, optionally only those with a given status or matching some text.",
            panel = "access_token, account_id, base_url, changed_since, changed_before, status | search_text, max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: envelope_id, status, subject, sent, delivered, completed, voided_reason, status_changed.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of envelopes returned.")
    public Map<String, Object> listEnvelopes(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "changed_since", type = OoaType.DATETIME, required = false, description = "Only envelopes changed on or after this date. Leave empty for the last 30 days.") Object from,
            @Input(name = "changed_before", type = OoaType.DATETIME, required = false, description = "Only envelopes changed before this date.") Object to,
            @Input(name = "status", type = OoaType.STRING, required = false, example = "completed", description = "Only these statuses, separated by commas, e.g. sent,delivered.") String status,
            @Input(name = "search_text", type = OoaType.STRING, required = false, description = "Only envelopes whose subject, sender or recipients contain this text.") String text,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "1000", description = "Most envelopes to return.") int max,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("from_date", from == null || Values.text(from).isBlank() ? LocalDate.now().minusDays(30).toString() : Values.toDateTime(from).toString());
        if (to != null && !Values.text(to).isBlank()) q.put("to_date", Values.toDateTime(to).toString());
        if (status != null && !status.isBlank()) q.put("status", status.replace(" ", ""));
        if (text != null && !text.isBlank()) q.put("search_text", text);
        List<Object> rows = new ArrayList<>();
        int start = 0;
        while (rows.size() < max) {
            q.put("count", Math.min(100, max - rows.size()));
            q.put("start_position", start);
            Map<String, Object> r = send(ctx, req("GET", api(base, account) + "/envelopes?" + HttpJson.query(q), token).timeout(120)).map();
            List<?> page = (List<?>) r.getOrDefault("envelopes", List.of());
            for (Object o : page) { rows.add(new ArrayList<>(envelopeSummary((Map<?, ?>) o).values())); if (rows.size() >= max) break; }
            int total = intOf(r.get("totalSetSize"));
            start += page.size();
            if (page.isEmpty() || start >= total) break;
        }
        return Out.of("table", Values.table(List.of("envelope_id", "status", "subject", "sent", "delivered", "completed", "voided_reason", "status_changed"), rows),
                "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "void_envelope", name = "Void envelope", category = "Envelopes", capabilities = "network",
            description = "Cancel an envelope that is still out for signature. Recipients are told why.",
            panel = "access_token, account_id, base_url, envelope_id, reason")
    public Map<String, Object> voidEnvelope(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "reason", type = OoaType.STRING, description = "Why the envelope is cancelled; recipients see this.") String reason,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        if (reason == null || reason.isBlank()) throw new ActionException(P + ".NO_REASON", "A reason is required to void an envelope", "Fill reason.");
        send(ctx, req("PUT", api(base, account) + "/envelopes/" + guid("envelope", env), token).json(Map.of("status", "voided", "voidedReason", reason)));
        return Map.of();
    }

    @Action(id = "resend_envelope", name = "Resend envelope", category = "Envelopes", capabilities = "network",
            description = "Send the signing reminder email again to recipients who have not finished.",
            panel = "access_token, account_id, base_url, envelope_id")
    public Map<String, Object> resend(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        send(ctx, req("PUT", api(base, account) + "/envelopes/" + guid("envelope", env) + "?resend_envelope=true", token).json(Map.of()));
        return Map.of();
    }

    // ----- recipients -----

    @Action(id = "list_recipients", name = "List recipients", category = "Recipients", idempotent = true, capabilities = "network",
            description = "Return everyone on an envelope with their progress.",
            panel = "access_token, account_id, base_url, envelope_id")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: recipient_id, type, name, email, routing_order, status, delivered, signed, declined_reason.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of recipients.")
    public Map<String, Object> listRecipients(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        Map<String, Object> r = send(ctx, req("GET", api(base, account) + "/envelopes/" + guid("envelope", env) + "/recipients", token)).map();
        List<Object> rows = new ArrayList<>();
        for (String type : RECIPIENT_TYPES) {
            if (!(r.get(type) instanceof List<?> l)) continue;
            for (Object o : l) {
                Map<?, ?> x = (Map<?, ?>) o;
                rows.add(new ArrayList<>(Arrays.asList(str(x, "recipientId"), type, str(x, "name"), str(x, "email"), str(x, "routingOrder"), str(x, "status"),
                        str(x, "deliveredDateTime"), str(x, "signedDateTime"), str(x, "declinedReason"))));
            }
        }
        return Out.of("table", Values.table(List.of("recipient_id", "type", "name", "email", "routing_order", "status", "delivered", "signed", "declined_reason"), rows),
                "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "update_recipient", name = "Update recipient", category = "Recipients", capabilities = "network",
            description = "Correct a recipient's name or email address, for example after a typo, and optionally send the request again.",
            panel = "access_token, account_id, base_url, envelope_id, recipient_id, new_name, new_email | recipient_type, resend")
    public Map<String, Object> updateRecipient(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "recipient_id", type = OoaType.STRING, description = "Recipient id from List recipients, e.g. 1.") String rid,
            @Input(name = "new_name", type = OoaType.STRING, required = false, description = "Corrected full name. Leave empty to keep it.") String name,
            @Input(name = "new_email", type = OoaType.STRING, required = false, description = "Corrected email address. Leave empty to keep it.") String email,
            @Input(name = "recipient_type", type = OoaType.STRING, required = false, defaultValue = "\"signer\"", enumValues = {"signer", "copy"}, description = "Whether this person signs or only receives a copy.") String type,
            @Input(name = "resend", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Send the signing request again to the corrected address.") boolean resend,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        if ((name == null || name.isBlank()) && (email == null || email.isBlank()))
            throw new ActionException(P + ".NOTHING_TO_UPDATE", "Give a new name or a new email", "Fill new_name or new_email.");
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("recipientId", rid);
        if (name != null && !name.isBlank()) rec.put("name", name);
        if (email != null && !email.isBlank()) rec.put("email", email);
        String key = "copy".equals(type) ? "carbonCopies" : "signers";
        send(ctx, req("PUT", api(base, account) + "/envelopes/" + guid("envelope", env) + "/recipients?resend_envelope=" + resend, token).json(Map.of(key, List.of(rec))));
        return Map.of();
    }

    // ----- documents -----

    @Action(id = "list_documents", name = "List envelope documents", category = "Documents", idempotent = true, capabilities = "network",
            description = "Return the documents inside an envelope.",
            panel = "access_token, account_id, base_url, envelope_id")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: document_id, name, type, pages, order.")
    public Map<String, Object> listDocuments(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        Map<String, Object> r = send(ctx, req("GET", api(base, account) + "/envelopes/" + guid("envelope", env) + "/documents", token)).map();
        List<Object> rows = new ArrayList<>();
        for (Object o : (List<?>) r.getOrDefault("envelopeDocuments", List.of())) {
            Map<?, ?> d = (Map<?, ?>) o;
            Object pages = d.get("pages") instanceof List<?> pl ? BigDecimal.valueOf(pl.size()) : d.get("pages");
            rows.add(new ArrayList<>(Arrays.asList(str(d, "documentId"), str(d, "name"), str(d, "type"), pages, str(d, "order"))));
        }
        return Out.of("table", Values.table(List.of("document_id", "name", "type", "pages", "order"), rows));
    }

    @Action(id = "download_document", name = "Download document", category = "Documents", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Save one document from an envelope, the signing certificate, or all documents as a zip archive.",
            panel = "access_token, account_id, base_url, envelope_id, document_id, save_as")
    @Output(name = "path", type = OoaType.FILE, description = "Full path of the saved file.")
    public Map<String, Object> downloadDocument(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "document_id", type = OoaType.STRING, example = "1", description = "Document number from List envelope documents, or certificate for the signing certificate, or archive for a zip of everything.") String doc,
            @Input(name = "save_as", type = OoaType.FILE, description = "Where to save it, e.g. signed/contract.pdf. Missing folders are created.") String saveAs,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        if (doc == null || !DOC_ID.matcher(doc.trim()).matches())
            throw new ActionException(P + ".BAD_DOCUMENT", "Invalid document id '" + doc + "'", "Use a number such as 1, or certificate, combined or archive.");
        return save(ctx, api(base, account) + "/envelopes/" + guid("envelope", env) + "/documents/" + doc.trim(), saveAs, token);
    }

    @Action(id = "download_combined_pdf", name = "Download combined PDF", category = "Documents", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Save all documents of an envelope as one PDF, optionally with the signing certificate at the end.",
            panel = "access_token, account_id, base_url, envelope_id, save_as | include_certificate")
    @Output(name = "path", type = OoaType.FILE, description = "Full path of the saved PDF.")
    public Map<String, Object> downloadCombined(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "save_as", type = OoaType.FILE, description = "Where to save the PDF, e.g. signed/all.pdf. Missing folders are created.") String saveAs,
            @Input(name = "include_certificate", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Add the signing certificate as the last pages.") boolean cert,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        return save(ctx, api(base, account) + "/envelopes/" + guid("envelope", env) + "/documents/combined?certificate=" + cert, saveAs, token);
    }

    @Action(id = "download_zip", name = "Download signed ZIP", category = "Documents", timeoutSeconds = 600, capabilities = {"network", "filesystem"},
            description = "Save all documents of an envelope as separate PDFs in one ZIP file, with the signing certificate.",
            panel = "access_token, account_id, base_url, envelope_id, save_as")
    @Output(name = "path", type = OoaType.FILE, description = "Full path of the saved ZIP.")
    public Map<String, Object> downloadZip(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "save_as", type = OoaType.FILE, description = "Where to save the ZIP, e.g. signed/contract.zip. Missing folders are created.") String saveAs,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        return save(ctx, api(base, account) + "/envelopes/" + guid("envelope", env) + "/documents/archive", saveAs, token);
    }

    @Action(id = "add_recipients", name = "Add recipients", category = "Recipients", capabilities = "network",
            description = "Add signers or copy recipients to an envelope that is still a draft or in progress, optionally in a signing order.",
            panel = "access_token, account_id, base_url, envelope_id, recipients | resend")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of recipients added.")
    public Map<String, Object> addRecipients(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "recipients", type = OoaType.LIST,
                    description = "Recipients: each {\"name\", \"email\", \"type\": signer or copy, \"routing_order\": 1, 2 ...}, e.g. [{\"name\": \"Ann Lee\", \"email\": \"ann@acme.test\"}].") List<Object> recipients,
            @Input(name = "resend", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Send the request to the new recipients when the envelope is already sent.") boolean resend,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        if (recipients == null || recipients.isEmpty()) throw new ActionException(P + ".BAD_RECIPIENT", "No recipients given", "Give one or more {name, email}.");
        List<Object> signers = new ArrayList<>(), copies = new ArrayList<>();
        int n = 100;   // ids above the usual 1, 2, 3 ... so they do not clash with existing recipients
        for (Object o : recipients) {
            if (!(o instanceof Map<?, ?> m) || m.get("email") == null || m.get("name") == null)
                throw new ActionException(P + ".BAD_RECIPIENT", "Each recipient needs a name and an email", "Example: {\"name\": \"Ann Lee\", \"email\": \"ann@acme.test\"}.");
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("recipientId", String.valueOf(n++));
            r.put("name", String.valueOf(m.get("name")));
            r.put("email", String.valueOf(m.get("email")));
            if (m.get("routing_order") != null) r.put("routingOrder", String.valueOf(m.get("routing_order")));
            ("copy".equals(String.valueOf(m.get("type"))) ? copies : signers).add(r);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        if (!signers.isEmpty()) body.put("signers", signers);
        if (!copies.isEmpty()) body.put("carbonCopies", copies);
        send(ctx, req("POST", api(base, account) + "/envelopes/" + guid("envelope", env) + "/recipients?resend_envelope=" + resend, token).json(body));
        return Out.of("count", java.math.BigDecimal.valueOf(signers.size() + copies.size()));
    }

    private static Map<String, Object> save(ActionContext ctx, String url, String saveAs, Object token) {
        byte[] data = send(ctx, req("GET", url, token).header("Accept", "application/pdf, application/zip, */*").binaryResponse().timeout(600)).bytes();
        Path out = ctx.resolvePath(saveAs);
        HttpJson.writeFile(out, data);
        return Out.of("path", out.toString());
    }

    // ----- templates -----

    @Action(id = "list_templates", name = "List templates", category = "Templates", idempotent = true, timeoutSeconds = 300, capabilities = "network",
            description = "Return the templates you can use, optionally only those matching some text.",
            panel = "access_token, account_id, base_url, search_text | max_results")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: template_id, name, description, folder, last_modified.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of templates returned.")
    public Map<String, Object> listTemplates(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "search_text", type = OoaType.STRING, required = false, description = "Only templates whose name or description contains this text.") String text,
            @Input(name = "max_results", type = OoaType.NUMBER, required = false, defaultValue = "500", description = "Most templates to return.") int max,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        List<Object> rows = new ArrayList<>();
        int start = 0;
        while (rows.size() < max) {
            Map<String, Object> q = new LinkedHashMap<>();
            if (text != null && !text.isBlank()) q.put("search_text", text);
            q.put("count", Math.min(100, max - rows.size()));
            q.put("start_position", start);
            Map<String, Object> r = send(ctx, req("GET", api(base, account) + "/templates?" + HttpJson.query(q), token)).map();
            List<?> page = (List<?>) r.getOrDefault("envelopeTemplates", List.of());
            for (Object o : page) {
                Map<?, ?> t = (Map<?, ?>) o;
                rows.add(new ArrayList<>(Arrays.asList(str(t, "templateId"), str(t, "name"), str(t, "description"), str(t, "folderName"), str(t, "lastModified"))));
                if (rows.size() >= max) break;
            }
            int total = intOf(r.get("totalSetSize"));
            start += page.size();
            if (page.isEmpty() || start >= total) break;
        }
        return Out.of("table", Values.table(List.of("template_id", "name", "description", "folder", "last_modified"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get_template", name = "Get template", category = "Templates", idempotent = true, capabilities = "network",
            description = "Read a template's name, roles and documents, so you know which roles to fill.",
            panel = "access_token, account_id, base_url, template_id")
    @Output(name = "template", type = OoaType.DICTIONARY, description = "template_id, name, description, email_subject, documents.")
    @Output(name = "roles", type = OoaType.LIST, description = "Names of the roles to fill, e.g. [\"Client\", \"Manager\"].")
    public Map<String, Object> getTemplate(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "template_id", type = OoaType.STRING, description = "Template id from List templates.") String template,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        Map<String, Object> r = send(ctx, req("GET", api(base, account) + "/templates/" + guid("template", template), token)).map();
        List<Object> roles = new ArrayList<>();
        if (r.get("recipients") instanceof Map<?, ?> rec)
            for (String type : RECIPIENT_TYPES) if (rec.get(type) instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m && m.get("roleName") != null) roles.add(Values.text(m.get("roleName")));
        List<Object> docs = new ArrayList<>();
        if (r.get("documents") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) docs.add(str(m, "name"));
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("template_id", r.get("templateId"));
        t.put("name", r.get("name"));
        t.put("description", r.get("description"));
        t.put("email_subject", r.get("emailSubject"));
        t.put("documents", docs);
        return Out.of("template", t, "roles", roles);
    }

    // ----- embedded signing -----

    @Action(id = "create_signing_url", name = "Get embedded signing link", category = "Embedded signing", capabilities = "network",
            description = "Get a short-lived link that opens the signing session for a recipient inside your own app or portal.",
            panel = "access_token, account_id, base_url, envelope_id, name, email, client_user_id, return_url | authentication_method")
    @Output(name = "url", type = OoaType.STRING, description = "Signing link; it works once and expires within minutes.")
    public Map<String, Object> signingUrl(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "name", type = OoaType.STRING, description = "Signer's name exactly as on the envelope.") String name,
            @Input(name = "email", type = OoaType.STRING, description = "Signer's email exactly as on the envelope.") String email,
            @Input(name = "client_user_id", type = OoaType.STRING, description = "The client_user_id given to this signer when the envelope was created.") String clientUserId,
            @Input(name = "return_url", type = OoaType.STRING, pattern = "^https?://", description = "Page the signer goes to when they finish or leave.") String returnUrl,
            @Input(name = "authentication_method", type = OoaType.STRING, required = false, defaultValue = "\"none\"", description = "How your app checked who the signer is, e.g. none, email or password.") String auth,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userName", name);
        body.put("email", email);
        body.put("clientUserId", clientUserId);
        body.put("returnUrl", returnUrl);
        body.put("authenticationMethod", auth);
        Map<String, Object> r = send(ctx, req("POST", api(base, account) + "/envelopes/" + guid("envelope", env) + "/views/recipient", token).json(body)).map();
        return Out.of("url", r.get("url"));
    }

    // ----- custom fields and form data -----

    @Action(id = "get_custom_fields", name = "Get envelope custom fields", category = "Custom fields", idempotent = true, capabilities = "network",
            description = "Read the custom fields stored on an envelope, such as a contract or order number.",
            panel = "access_token, account_id, base_url, envelope_id")
    @Output(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value.")
    public Map<String, Object> getCustomFields(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<?, ?> f : customFields(ctx, base, account, env, token)) out.put(str(f, "name"), f.get("value"));
        return Out.of("fields", out);
    }

    private static List<Map<?, ?>> customFields(ActionContext ctx, String base, String account, String env, Object token) {
        Map<String, Object> r = send(ctx, req("GET", api(base, account) + "/envelopes/" + guid("envelope", env) + "/custom_fields", token)).map();
        List<Map<?, ?>> out = new ArrayList<>();
        for (String k : List.of("textCustomFields", "listCustomFields")) if (r.get(k) instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) out.add(m);
        return out;
    }

    @Action(id = "set_custom_fields", name = "Set envelope custom fields", category = "Custom fields", capabilities = "network",
            description = "Add or change custom fields on an envelope. Fields that already exist are updated, new ones are added.",
            panel = "access_token, account_id, base_url, envelope_id, fields | show_to_recipients")
    public Map<String, Object> setCustomFields(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "fields", type = OoaType.DICTIONARY, description = "Field name to value, e.g. {\"OrderNumber\": \"SO-5531\"}.") Map<String, Object> fields,
            @Input(name = "show_to_recipients", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Let recipients see these fields.") boolean show,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        if (fields == null || fields.isEmpty()) throw new ActionException(P + ".NOTHING_TO_UPDATE", "No fields were given", "Fill fields with at least one name and value.");
        Map<String, Object> existing = new HashMap<>();
        for (Map<?, ?> f : customFields(ctx, base, account, env, token)) existing.put(str(f, "name"), f.get("fieldId"));
        List<Map<String, Object>> add = new ArrayList<>(), change = new ArrayList<>();
        fields.forEach((k, v) -> {
            Map<String, Object> f = new LinkedHashMap<>();
            if (existing.containsKey(k)) f.put("fieldId", Values.text(existing.get(k)));
            f.put("name", k);
            f.put("value", Values.text(v));
            f.put("show", String.valueOf(show));
            (existing.containsKey(k) ? change : add).add(f);
        });
        String url = api(base, account) + "/envelopes/" + guid("envelope", env) + "/custom_fields";
        if (!change.isEmpty()) send(ctx, req("PUT", url, token).json(Map.of("textCustomFields", change)));
        if (!add.isEmpty()) send(ctx, req("POST", url, token).json(Map.of("textCustomFields", add)));
        return Map.of();
    }

    @Action(id = "get_form_data", name = "Get filled-in form data", category = "Custom fields", idempotent = true, capabilities = "network",
            description = "Read the values recipients typed into the envelope's fields, for example after it is completed.",
            panel = "access_token, account_id, base_url, envelope_id")
    @Output(name = "values", type = OoaType.DICTIONARY, description = "Field label to entered value.")
    public Map<String, Object> formData(ActionContext ctx,
            @Input(name = "base_url", type = OoaType.STRING, pattern = "^https?://", description = D_URL) String base,
            @Input(name = "account_id", type = OoaType.STRING, description = D_ACCOUNT) String account,
            @Input(name = "envelope_id", type = OoaType.STRING, description = D_ENV) String env,
            @Input(name = "access_token", type = OoaType.CREDENTIAL, description = D_TOKEN) Object token) {
        Map<String, Object> r = send(ctx, req("GET", api(base, account) + "/envelopes/" + guid("envelope", env) + "/form_data", token)).map();
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.get("formData") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> m) out.put(str(m, "name"), m.get("value"));
        return Out.of("values", out);
    }
}
