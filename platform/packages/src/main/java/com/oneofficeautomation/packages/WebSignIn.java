package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * The sign-in of a web step, the same for Send request, Upload file, Download file and SOAP (R2, command review batch
 * 8): none, user name and password, a bearer token, OAuth 2.0 (client credentials or a refresh token, renewed before
 * it runs out), OAuth 1.0a, or Windows (NTLM with a user, or the runner's own account). One option at a time; the
 * checks are those Send request always made, with the step's own error prefix (REST or SOAP).
 */
final class WebSignIn {
    final String basicUser;
    final Object basicPassword;
    final Object bearer;
    final String windowsAuth;
    final RestSignIn.OAuth2 o2;
    final RestSignIn.OAuth1 o1;

    private WebSignIn(String basicUser, Object basicPassword, Object bearer, String windowsAuth, RestSignIn.OAuth2 o2, RestSignIn.OAuth1 o1) {
        this.basicUser = basicUser; this.basicPassword = basicPassword; this.bearer = bearer; this.windowsAuth = windowsAuth; this.o2 = o2; this.o1 = o1;
    }

    static boolean given(Object v) { return v != null && !(v instanceof String s && s.isBlank()); }

    /** The sign-in the step's fields describe; refuses two options at once, as Send request did. */
    static WebSignIn of(String p, String basicUser, Object basicPassword, Object bearer, String windowsAuth,
                        String oauthTokenUrl, String oauthClientId, Object oauthClientSecret, Object oauthRefreshToken, String oauthScope, String oauthAudience,
                        String oauthResource, String oauthClientAuth,
                        String oauth1ConsumerKey, Object oauth1ConsumerSecret, Object oauth1Token, Object oauth1TokenSecret, String oauth1Method,
                        String ntlmUser, String ntlmDomain, Object ntlmPassword) {
        IntegratedAuthHttp.check(windowsAuth);
        boolean oauth2 = given(oauthTokenUrl) || given(oauthClientId) || given(oauthClientSecret) || given(oauthRefreshToken);
        boolean oauth1 = given(oauth1ConsumerKey) || given(oauth1ConsumerSecret) || given(oauth1Token) || given(oauth1TokenSecret);
        boolean ntlm = given(ntlmUser) || given(ntlmPassword);
        boolean older = given(basicUser) || given(basicPassword) || given(bearer) || given(windowsAuth);
        if ((oauth2 ? 1 : 0) + (oauth1 ? 1 : 0) + (ntlm ? 1 : 0) + (older ? 1 : 0) > 1 && (oauth2 || oauth1 || ntlm))
            throw new ActionException(p + ".BAD_AUTH", "More than one sign-in was given (" + String.join(", ",
                    java.util.stream.Stream.of(older ? "user name and password, bearer token or windows_auth" : null, oauth2 ? "OAuth 2.0" : null, oauth1 ? "OAuth 1.0a" : null, ntlm ? "NTLM" : null)
                            .filter(Objects::nonNull).toList()) + "), so the request was not sent", "Fill the fields of one sign-in option only.");
        RestSignIn.OAuth2 o2 = oauth2 ? RestPackage.oauth2Settings(oauthTokenUrl, oauthClientId, oauthClientSecret, oauthRefreshToken, oauthScope, oauthAudience, oauthResource, oauthClientAuth) : null;
        RestSignIn.OAuth1 o1 = oauth1 ? RestPackage.oauth1Settings(oauth1ConsumerKey, oauth1ConsumerSecret, oauth1Token, oauth1TokenSecret, oauth1Method) : null;
        if (ntlm) {
            if (!given(ntlmUser)) throw new ActionException(p + ".NO_USER", "NTLM sign-in needs ntlm_user and ntlm_password", "Fill ntlm_user (and ntlm_domain) and ntlm_password.");
            if (!(RestPackage.vaultSecret(ntlmPassword, "ntlm_password") instanceof SecureString))
                throw new ActionException(p + ".NO_SECRET", "NTLM sign-in needs ntlm_password from the vault, so the request was not sent",
                        "Give a vault reference {\"$credential\": \"locker/credential/attribute\"} or a Credential variable.");
            String u = ntlmUser.strip();
            basicUser = !given(ntlmDomain) || u.contains("\\") || u.contains("@") ? u : ntlmDomain.strip() + "\\" + u;
            basicPassword = ntlmPassword;
            windowsAuth = "user_password";
        }
        return new WebSignIn(basicUser, basicPassword, bearer, windowsAuth, o2, o1);
    }

    boolean windows() { return windowsAuth != null && !windowsAuth.isBlank(); }

    /** The user name, password and bearer token as headers (not for Windows sign-in, which signs in itself). */
    void apply(HttpRequest.Builder b) {
        if (windows()) return;
        if (basicUser != null && RestPackage.vaultSecret(basicPassword, "basic_password") instanceof SecureString pw)
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((basicUser + ":" + pw.reveal()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (RestPackage.vaultSecret(bearer, "bearer_token") instanceof SecureString t) b.header("Authorization", "Bearer " + t.reveal());
    }

    /**
     * Sends the request: with OAuth 2.0 a token is added (kept for the run, renewed before it runs out, and fetched
     * again once on a 401); with OAuth 1.0a the request is signed; otherwise it is sent as built.
     */
    <T> HttpResponse<T> send(ActionContext ctx, HttpClient client, HttpRequest.Builder b, HttpResponse.BodyHandler<T> handler, String method, String fullUrl,
                             List<String[]> formFields, String url, int timeout) {
        if (o2 == null && o1 == null) return RestPackage.sendHonouringRateLimit(ctx, client, b.build(), handler, url, timeout);
        for (int attempt = 0; ; attempt++) {
            HttpRequest.Builder rb = b.copy();
            rb.setHeader("Authorization", o2 != null ? RestSignIn.authorization(ctx, client, o2, attempt > 0, timeout)
                    : RestSignIn.oauth1Header(o1, method, fullUrl, formFields == null ? List.of() : formFields));
            HttpResponse<T> resp = RestPackage.sendHonouringRateLimit(ctx, client, rb.build(), handler, url, timeout);
            if (resp.statusCode() != 401 || o2 == null || attempt > 0) return resp;
            ctx.info("HTTP 401: getting a new OAuth token and sending once more");
        }
    }

    /**
     * The address a step calls: its URL as given, or, when it is a path (/invoices/42), the path after base_url, which a
     * saved connection usually supplies.
     */
    static String address(String p, String baseUrl, String url) {
        String u = url == null ? "" : url.strip();
        if (u.matches("(?i)^https?://.*")) return u;
        if (!given(baseUrl))
            throw new ActionException(p + ".BAD_URL", "'" + u + "' is not a full address and there is no base_url", "Give an address starting with https://, or choose a connection that has a base address.");
        String base = baseUrl.strip();
        if (!base.matches("(?i)^https?://.*")) throw new ActionException(p + ".BAD_URL", "base_url must start with http:// or https://, not '" + base + "'");
        if (u.isEmpty()) return base;
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + (u.startsWith("/") || u.startsWith("?") ? u : "/" + u);
    }
}
