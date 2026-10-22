package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * OAuth token requests for the Salesforce, ServiceNow and Workday Authenticate actions: a form-encoded POST to the
 * service's token endpoint. Secret fields travel only in the request body (never in the URL or the log), and the
 * token comes back as a Credential so later steps can pass it on without it ever being shown.
 */
final class EnterpriseAuth {
    private EnterpriseAuth() {}

    /** Posts the form and returns the JSON answer; fields may hold Strings or Credentials (SecureString). */
    static Map<String, Object> token(ActionContext ctx, String prefix, String url, Map<String, Object> fields, String basicUser, Object basicSecret) {
        StringJoiner body = new StringJoiner("&");
        for (var f : fields.entrySet()) {
            Object v = f.getValue();
            if (v == null) continue;
            String text = v instanceof SecureString s ? s.reveal() : Values.text(v);
            if (text.isEmpty()) continue;
            body.add(HttpJson.enc(f.getKey()) + "=" + HttpJson.enc(text));
        }
        HttpJson.Req req = HttpJson.req(prefix, "POST", url).bytes(body.toString().getBytes(StandardCharsets.UTF_8), "application/x-www-form-urlencoded");
        if (basicUser != null && !basicUser.isBlank()) req.basic(basicUser, basicSecret);
        Map<String, Object> r = req.timeout(60).send(ctx).map();
        if (!(r.get("access_token") instanceof String t) || t.isBlank())
            throw new ActionException(prefix + ".AUTH_FAILED", "The token endpoint did not return an access token", "Check the client id, secret and user details.");
        return r;
    }

    /** The access token as a Credential, labelled with where it came from. */
    static SecureString credential(Map<String, Object> answer, String ref) { return new SecureString(String.valueOf(answer.get("access_token")), ref); }

    static String secret(Object v, String prefix, String what) {
        if (v instanceof SecureString s) return s.reveal();
        throw new ActionException(prefix + ".NO_SECRET", "The " + what + " must come from the vault", "Map it to a Credential variable.");
    }
}
