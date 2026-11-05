package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;

/**
 * The credential vault of One CommandCenter, beyond reading a value with {"$credential": ...} (gap G30): save a new
 * value of a credential attribute, for example after a password reset, with its new expiry date, and check how long a
 * credential has left. One CommandCenter decides as the user the run runs as: saving needs the administrator's switch
 * (Settings, Agents may save credentials) and permission to change the credential; every save is in the Audit Trail.
 * Values only come from the vault or another Credential, never as text typed into the agent, and are never logged.
 */
@OoaPackage(id = "ooa.vault", name = "Credential vault", version = "1.0.0",
        description = "Save a credential's new value in One CommandCenter's vault, for example after a password reset, and check when a credential expires.")
public final class VaultPackage {
    static final String REF_FIX = "Write it as vault folder/credential/attribute, e.g. finance/sap-login/password.";

    @SuppressWarnings("unchecked")
    static Function<Map<String, Object>, Map<String, Object>> writer(ActionContext ctx) {
        try { return ctx.service("vault_write", Function.class); }
        catch (OoaException e) { return null; }
    }

    static String[] parts(String ref, int min) {
        String r = ref == null ? "" : ref.strip();
        String[] p = r.split("/");
        if (p.length < min || p.length > 3 || Arrays.stream(p).anyMatch(x -> !x.matches("[A-Za-z0-9_.-]{1,80}")))
            throw new ActionException("VAULT.BAD_REF", "'" + ref + "' is not a credential reference", REF_FIX);
        return p;
    }

    /** The new expiry date: a date (yyyy-MM-dd), or today plus a number of days; null when neither is given. */
    static String expiry(String date, Integer days) {
        if (date != null && !date.isBlank()) {
            String d = date.strip();
            try { return LocalDate.parse(d.length() > 10 ? d.substring(0, 10) : d).toString(); }
            catch (DateTimeParseException e) { throw new ActionException("VAULT.BAD_DATE", "'" + date + "' is not a date", "Write the expiry date as yyyy-MM-dd, e.g. 2027-03-31."); }
        }
        if (days != null && days > 0) return LocalDate.now().plusDays(days).toString();
        return null;
    }

    @Action(id = "save_credential", name = "Save credential value", category = "Credential vault",
            description = "Store a new value of a credential attribute in One CommandCenter's vault, for example the new password after a reset, and optionally its new expiry date. Needs Settings > Agents may save credentials and permission to change the credential for the user the run runs as.",
            panel = "credential, value | [Set the expiry date: expires, expires_in_days]")
    @Output(name = "expires", type = OoaType.STRING, description = "The credential's expiry date (yyyy-MM-dd), or empty when it has none.")
    @Output(name = "days_left", type = OoaType.NUMBER, description = "Days until it expires; empty when it has no expiry date.")
    public Map<String, Object> saveCredential(ActionContext ctx,
            @Input(name = "credential", type = OoaType.STRING, description = "Where to store it: vault folder/credential/attribute, e.g. finance/sap-login/password.") String credential,
            @Input(name = "value", type = OoaType.CREDENTIAL, description = "The new value: a Credential variable or a vault reference, never typed text.") Object value,
            @Input(name = "expires", type = OoaType.STRING, required = false, description = "The credential's new expiry date, yyyy-MM-dd. Empty: unchanged.") String expires,
            @Input(name = "expires_in_days", type = OoaType.NUMBER, required = false, min = 1, max = 3650,
                    description = "Or: the credential expires this many days from today (used when expires is empty).") Integer expiresInDays) {
        String[] p = parts(credential, 3);
        if (!(value instanceof SecureString s))
            throw new ActionException("VAULT.NO_SECRET", "Input 'value' must be a Credential, so nothing was saved",
                    "Give a Credential variable or a vault reference {\"$credential\": \"folder/credential/attribute\"}; typed text is refused.");
        String exp = expiry(expires, expiresInDays);
        Function<Map<String, Object>, Map<String, Object>> w = writer(ctx);
        if (w == null) throw new ActionException("VAULT.LOCAL_READ_ONLY", "Credentials can only be saved by an agent running from One CommandCenter",
                "In Studio credentials are read from environment variables; run the agent from One CommandCenter to save one.");
        String secret = s.reveal();
        if (secret.isEmpty()) throw new ActionException("VAULT.EMPTY", "The new value is empty", "Give the credential's new value.");
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("op", "save");
        req.put("ref", String.join("/", p));
        req.put("value", secret);
        if (exp != null) req.put("expires", exp);
        Map<String, Object> r = w.apply(req);
        ctx.info("Saved a new value of " + String.join("/", p) + (exp != null ? ", expires " + exp : ""));
        return Out.of("expires", r.get("expires") == null ? "" : r.get("expires"), "days_left", r.get("days_left"));
    }

    @Action(id = "credential_expiry", name = "Check credential expiry", category = "Credential vault", idempotent = true,
            description = "Read when a credential expires, how many days are left and whether it has expired. The user the run runs as must be able to use the vault folder.",
            panel = "credential, warn_days")
    @Output(name = "expires", type = OoaType.STRING, description = "The expiry date (yyyy-MM-dd), or empty when it has none.")
    @Output(name = "days_left", type = OoaType.NUMBER, description = "Days until it expires (negative once past); empty when it has no expiry date.")
    @Output(name = "expired", type = OoaType.BOOLEAN, description = "True when the expiry date has passed.")
    @Output(name = "expiring_soon", type = OoaType.BOOLEAN, description = "True when it expires within warn_days (and has not expired yet).")
    @Output(name = "has_expiry", type = OoaType.BOOLEAN, description = "True when the credential has an expiry date.")
    public Map<String, Object> credentialExpiry(ActionContext ctx,
            @Input(name = "credential", type = OoaType.STRING, description = "vault folder/credential, or folder/credential/attribute (the expiry belongs to the whole credential).") String credential,
            @Input(name = "warn_days", type = OoaType.NUMBER, required = false, defaultValue = "14", min = 0, max = 3650,
                    description = "Report expiring_soon when this many days or fewer are left.") Integer warnDays) {
        String[] p = parts(credential, 2);
        Function<Map<String, Object>, Map<String, Object>> w = writer(ctx);
        Map<String, Object> r;
        if (w == null) {
            ctx.warn("No expiry dates outside One CommandCenter: " + String.join("/", p) + " is reported as having none.");
            r = Map.of();
        } else {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("op", "expiry");
            req.put("ref", String.join("/", p));
            r = w.apply(req);
        }
        Object days = r.get("days_left");
        boolean has = r.get("expires") != null;
        long left = days instanceof Number n ? n.longValue() : 0;
        int warn = warnDays == null ? 14 : warnDays;
        return Out.of("expires", has ? r.get("expires") : "", "days_left", has ? days : null,
                "expired", has && left < 0, "expiring_soon", has && left >= 0 && left <= warn, "has_expiry", has);
    }

    @Condition(id = "is_expired", name = "Credential has expired", description = "True when the credential's expiry date has passed.")
    public boolean isExpired(ActionContext ctx,
            @Input(name = "credential", type = OoaType.STRING, description = "vault folder/credential, or folder/credential/attribute.") String credential) {
        return Boolean.TRUE.equals(credentialExpiry(ctx, credential, 14).get("expired"));
    }
}
