package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Where the vault master key comes from when it is kept outside the Command Center (S7), named by OOA_MASTER_KEY_SOURCE:
 * <ul>
 * <li>{@code azure-keyvault:https://NAME.vault.azure.net/secrets/SECRET} — a Key Vault secret, read with the machine's
 *     managed identity (virtual machine, App Service, Container Apps) or AZURE_TENANT_ID, AZURE_CLIENT_ID and
 *     AZURE_CLIENT_SECRET;</li>
 * <li>{@code aws-secrets:REGION/SECRET-ID} — a Secrets Manager secret, read with AWS_ACCESS_KEY_ID and
 *     AWS_SECRET_ACCESS_KEY (and AWS_SESSION_TOKEN), the container's task role or the EC2 instance role;</li>
 * <li>{@code gcp-secret:projects/P/secrets/S/versions/latest} — a Secret Manager secret, read with the machine's
 *     service account;</li>
 * <li>{@code prompt} — nobody stores it: an operator types the key when the Command Center starts (manual unlock).</li>
 * </ul>
 * The secret holds the key as Base64 (32 random bytes), as OOA_MASTER_KEY would. The key only ever lives in memory.
 */
public final class KeySources {
    private KeySources() { }

    /** Addresses of the metadata and cloud services; tests point them at a local server. */
    static String azureLogin = "https://login.microsoftonline.com", azureImds = "http://169.254.169.254", gcpMetadata = "http://metadata.google.internal",
        gcpSecrets = "https://secretmanager.googleapis.com", awsImds = "http://169.254.169.254", awsEcs = "http://169.254.170.2", awsEndpoint = null;
    /** The environment (tests replace it). */
    static java.util.function.Function<String, String> env = System::getenv;
    /** The operator's typed key for "prompt" (tests replace it). */
    static java.util.function.Supplier<char[]> prompt = () -> {
        java.io.Console c = System.console();
        if (c == null) throw new OoaException("CR.VAULT_KEY", "OOA_MASTER_KEY_SOURCE=prompt needs a console to type the key in",
            "Start the Command Center in a command window (not as a service), or use another key source");
        return c.readPassword("Vault master key (Base64), typed to unlock the Command Center: ");
    };

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** A short description of the source, without secrets, for logs and Settings. */
    public static String describe(String source) {
        String s = source.trim();
        if (s.equals("prompt")) return "typed in at start (manual unlock)";
        int c = s.indexOf(':');
        return (c < 0 ? s : switch (s.substring(0, c)) {
            case "azure-keyvault" -> "Azure Key Vault";
            case "aws-secrets" -> "AWS Secrets Manager";
            case "gcp-secret" -> "Google Secret Manager";
            default -> s.substring(0, c);
        }) + (c < 0 ? "" : " " + s.substring(c + 1));
    }

    /** The 32-byte key from the source. */
    static byte[] fetch(String source) {
        String s = source.trim();
        String text;
        try {
            if (s.equals("prompt")) text = new String(prompt.get());
            else if (s.startsWith("azure-keyvault:")) text = azure(s.substring(15));
            else if (s.startsWith("aws-secrets:")) text = aws(s.substring(12));
            else if (s.startsWith("gcp-secret:")) text = gcp(s.substring(11));
            else throw new OoaException("CR.VAULT_KEY", "Unknown OOA_MASTER_KEY_SOURCE: " + describe(s),
                "Use azure-keyvault:https://NAME.vault.azure.net/secrets/SECRET, aws-secrets:REGION/SECRET-ID, gcp-secret:projects/P/secrets/S/versions/latest or prompt");
        } catch (OoaException e) { throw e; }
        catch (Exception e) { throw new OoaException("CR.VAULT_KEY", "Could not read the vault key from " + describe(s) + ": " + e.getMessage(), "Check the machine's identity has read access to the secret"); }
        byte[] raw;
        try { raw = Base64.getDecoder().decode(text.trim()); } catch (IllegalArgumentException e) { raw = new byte[0]; }
        if (raw.length != 32) throw new OoaException("CR.VAULT_KEY", "The key from " + describe(s) + " is not 32 bytes in Base64", "Store the key as made by: openssl rand -base64 32");
        return raw;
    }

    // ---------- Azure Key Vault ----------

    @SuppressWarnings("unchecked")
    static String azure(String secretUrl) throws Exception {
        URI u = URI.create(secretUrl);
        if (!"https".equals(u.getScheme()) && !secretUrl.startsWith("http://127.0.0.1")) throw new OoaException("CR.VAULT_KEY", "The Key Vault secret address starts with https://");
        String token;
        String tenant = env.apply("AZURE_TENANT_ID"), client = env.apply("AZURE_CLIENT_ID"), secret = env.apply("AZURE_CLIENT_SECRET");
        if (tenant != null && client != null && secret != null) {
            String form = "grant_type=client_credentials&client_id=" + enc(client) + "&client_secret=" + enc(secret) + "&scope=" + enc("https://vault.azure.net/.default");
            token = (String) json(HTTP.send(HttpRequest.newBuilder(URI.create(azureLogin + "/" + enc(tenant) + "/oauth2/v2.0/token")).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString())).get("access_token");
        } else if (env.apply("IDENTITY_ENDPOINT") != null && env.apply("IDENTITY_HEADER") != null) {
            // App Service and Container Apps
            token = (String) json(HTTP.send(HttpRequest.newBuilder(URI.create(env.apply("IDENTITY_ENDPOINT") + "?api-version=2019-08-01&resource=" + enc("https://vault.azure.net")))
                .timeout(Duration.ofSeconds(20)).header("X-IDENTITY-HEADER", env.apply("IDENTITY_HEADER")).GET().build(), HttpResponse.BodyHandlers.ofString())).get("access_token");
        } else {
            // a virtual machine's managed identity
            token = (String) json(HTTP.send(HttpRequest.newBuilder(URI.create(azureImds + "/metadata/identity/oauth2/token?api-version=2018-02-01&resource=" + enc("https://vault.azure.net")
                + (client != null ? "&client_id=" + enc(client) : ""))).timeout(Duration.ofSeconds(20)).header("Metadata", "true").GET().build(), HttpResponse.BodyHandlers.ofString())).get("access_token");
        }
        String url = secretUrl.replaceAll("/+$", "") + (secretUrl.contains("?") ? "&" : "?") + "api-version=7.4";
        return (String) json(HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).GET().build(),
            HttpResponse.BodyHandlers.ofString())).get("value");
    }

    // ---------- Google Secret Manager ----------

    @SuppressWarnings("unchecked")
    static String gcp(String name) throws Exception {
        if (!name.matches("projects/[^/]+/secrets/[^/]+/versions/[^/]+")) throw new OoaException("CR.VAULT_KEY", "The secret is projects/PROJECT/secrets/NAME/versions/latest");
        String token = (String) json(HTTP.send(HttpRequest.newBuilder(URI.create(gcpMetadata + "/computeMetadata/v1/instance/service-accounts/default/token"))
            .timeout(Duration.ofSeconds(20)).header("Metadata-Flavor", "Google").GET().build(), HttpResponse.BodyHandlers.ofString())).get("access_token");
        Map<String, Object> r = json(HTTP.send(HttpRequest.newBuilder(URI.create(gcpSecrets + "/v1/" + name + ":access")).timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString()));
        return new String(Base64.getDecoder().decode(String.valueOf(((Map<String, Object>) r.get("payload")).get("data"))), StandardCharsets.UTF_8);
    }

    // ---------- AWS Secrets Manager ----------

    @SuppressWarnings("unchecked")
    static String aws(String spec) throws Exception {
        int slash = spec.indexOf('/');
        if (slash <= 0) throw new OoaException("CR.VAULT_KEY", "The AWS secret is REGION/SECRET-ID, for example eu-west-1/ooa/master-key");
        String region = spec.substring(0, slash), id = spec.substring(slash + 1);
        if (!region.matches("[a-z]{2}(-[a-z]+)+-\\d")) throw new OoaException("CR.VAULT_KEY", "Not an AWS region: " + region);
        String[] creds = awsCredentials();
        String host = "secretsmanager." + region + ".amazonaws.com";
        String body = JsonLite.write(Map.of("SecretId", id));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/x-amz-json-1.1");
        headers.put("x-amz-target", "secretsmanager.GetSecretValue");
        if (creds[2] != null) headers.put("x-amz-security-token", creds[2]);
        Map<String, String> signed = ExternalVaults.SigV4.sign("POST", URI.create("https://" + host + "/"), headers, body, creds[0], creds[1], region, "secretsmanager", ZonedDateTime.now(ZoneOffset.UTC));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(awsEndpoint != null ? awsEndpoint : "https://" + host + "/")).timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body));
        for (var h : signed.entrySet()) if (!h.getKey().equals("host")) b.header(h.getKey(), h.getValue());
        return (String) json(HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString())).get("SecretString");
    }

    /** Access key, secret key and session token (or null): from the environment, the container's task role, or the instance role. */
    @SuppressWarnings("unchecked")
    static String[] awsCredentials() throws Exception {
        if (env.apply("AWS_ACCESS_KEY_ID") != null && env.apply("AWS_SECRET_ACCESS_KEY") != null)
            return new String[] { env.apply("AWS_ACCESS_KEY_ID"), env.apply("AWS_SECRET_ACCESS_KEY"), env.apply("AWS_SESSION_TOKEN") };
        Map<String, Object> c;
        if (env.apply("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI") != null) {
            c = json(HTTP.send(HttpRequest.newBuilder(URI.create(awsEcs + env.apply("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"))).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString()));
        } else {
            // EC2 instance metadata, version 2 (a session token first)
            String t = HTTP.send(HttpRequest.newBuilder(URI.create(awsImds + "/latest/api/token")).timeout(Duration.ofSeconds(5)).header("X-aws-ec2-metadata-token-ttl-seconds", "300")
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString()).body().trim();
            String role = HTTP.send(HttpRequest.newBuilder(URI.create(awsImds + "/latest/meta-data/iam/security-credentials/")).timeout(Duration.ofSeconds(5))
                .header("X-aws-ec2-metadata-token", t).GET().build(), HttpResponse.BodyHandlers.ofString()).body().trim().split("\\s+")[0];
            c = json(HTTP.send(HttpRequest.newBuilder(URI.create(awsImds + "/latest/meta-data/iam/security-credentials/" + role)).timeout(Duration.ofSeconds(5))
                .header("X-aws-ec2-metadata-token", t).GET().build(), HttpResponse.BodyHandlers.ofString()));
        }
        return new String[] { (String) c.get("AccessKeyId"), (String) c.get("SecretAccessKey"), (String) c.get("Token") };
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(HttpResponse<String> r) {
        if (r.statusCode() / 100 != 2) throw new OoaException("CR.VAULT_KEY", "HTTP " + r.statusCode() + " from " + r.uri().getHost());
        return (Map<String, Object>) JsonLite.parse(r.body());
    }
}
