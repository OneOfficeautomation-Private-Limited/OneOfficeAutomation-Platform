import { Field, Select, TextInput } from "../components";

/**
 * A1 (command review, batch 7): the sign-in of a saved connection for toolkits that take an access token (Microsoft
 * 365, Google, Email with OAuth). Kept in the connection's settings and vault references with sign_in_ names
 * (runner: OAuthSignIn); the runner gets the token when a step needs one and renews it before it runs out. Used by
 * One AgentStudio's and One CommandCenter's connection forms.
 *
 * K1 (command review, batch 10): every connector that marks its token input, and the sign-ins unattended agents of
 * those services need: a service account's key (Google, DocuSign), an app that signs with a private key (Okta), a
 * GitHub App, a Snowflake key pair, and extra fields for the token request (Box).
 */
export type SignInBags = { values: Record<string, unknown>; credentials: Record<string, string> };

export const SIGN_IN_VALUE_KEYS = ["sign_in_provider", "sign_in_grant", "sign_in_tenant", "sign_in_client_id", "sign_in_scope", "sign_in_token_url",
  "sign_in_client_auth", "sign_in_key_id", "sign_in_subject", "sign_in_audience", "sign_in_installation_id", "sign_in_account", "sign_in_user", "sign_in_extra_fields"];
export const SIGN_IN_SECRET_KEYS = ["sign_in_client_secret", "sign_in_refresh_token", "sign_in_private_key"];

type Props = Record<string, { "x-ooa-type"?: string; "x-ooa-sign-in-token"?: boolean }>;

/** True for toolkits whose steps take a token a sign-in can give. */
export function takesAccessToken(actions: { inputs?: { properties?: Props } }[]) {
  // R2 (batch 8): web steps take the token as their bearer token; K1 (batch 10): any input a toolkit marks for it
  return actions.some((a) => a.inputs?.properties?.access_token?.["x-ooa-type"] === "Credential" || a.inputs?.properties?.bearer_token?.["x-ooa-type"] === "Credential"
    || Object.values(a.inputs?.properties ?? {}).some((p) => p["x-ooa-sign-in-token"] === true));
}

/** The bags with one sign-in key set (empty text removes it); choosing no sign-in clears all of them. */
export function withSignIn(b: SignInBags, key: string, value: string): SignInBags {
  const values = { ...b.values }, credentials = { ...b.credentials };
  const bag: Record<string, unknown> = SIGN_IN_SECRET_KEYS.includes(key) ? credentials : values;
  if (value.trim()) bag[key] = value.trim(); else delete bag[key];
  if (key === "sign_in_provider" && !value.trim()) {
    for (const k of SIGN_IN_VALUE_KEYS) delete values[k];
    for (const k of SIGN_IN_SECRET_KEYS) delete credentials[k];
  }
  return { values, credentials };
}

/** The kind of sign-in a provider uses unless another is chosen (as the runner decides). */
export function defaultGrant(provider: string) {
  return provider === "github" ? "github_app" : provider === "snowflake" ? "snowflake_key_pair" : provider === "docusign" ? "jwt_bearer" : "client_credentials";
}

const str = (v: unknown) => (v === undefined || v === null ? "" : String(v));
const VAULT = "vault folder/credential/attribute";

export function SignInFields({ bags, onChange }: { bags: SignInBags; onChange: (b: SignInBags) => void }) {
  const v = (k: string) => str(bags.values[k]);
  const c = (k: string) => str(bags.credentials[k]);
  const set = (k: string) => (e: { target: { value: string } }) => onChange(withSignIn(bags, k, e.target.value));
  const provider = v("sign_in_provider"), grant = v("sign_in_grant") || defaultGrant(provider);
  const keyAuth = v("sign_in_client_auth") === "private_key_jwt";
  const text = (key: string, label: string, info: string, placeholder?: string) =>
    <Field label={label} info={info}>{(id) => <TextInput id={id} aria-label={label} value={v(key)} placeholder={placeholder} onChange={set(key)} />}</Field>;
  const secret = (key: string, label: string, info: string) =>
    <Field label={label} info={info + " A reference to it in the vault (folder/credential/attribute), not the secret itself."}>{(id) =>
      <TextInput id={id} aria-label={label} value={c(key)} placeholder={VAULT} onChange={set(key)} />}</Field>;
  const grants = provider === "github" ? [{ value: "github_app", label: "A GitHub App (its id, installation and private key)" }]
    : provider === "snowflake" ? [{ value: "snowflake_key_pair", label: "A user's key pair (the private key signs a token for each hour)" }]
    : [{ value: "client_credentials", label: "The app itself (client id and secret or key), for unattended agents" },
       { value: "refresh_token", label: "One person (a refresh token they gave once)" },
       { value: "jwt_bearer", label: "A service account's key (Google, DocuSign), optionally acting as a person" }];
  return <div className="st-signin" role="group" aria-label="Sign-in">
    <Field label="Sign in" info="With a sign-in the runner gets the token itself and renews it before it runs out, so steps need no token.">{(id) =>
      <Select id={id} aria-label="Sign in" value={provider} onChange={set("sign_in_provider")} options={[
        { value: "", label: "No sign-in: a token from the vault" }, { value: "microsoft", label: "Microsoft (Entra ID app)" },
        { value: "google", label: "Google (OAuth client or service account)" }, { value: "docusign", label: "DocuSign (JWT grant)" },
        { value: "github", label: "GitHub App" }, { value: "snowflake", label: "Snowflake key pair" },
        { value: "box", label: "Box (server app)" }, { value: "other", label: "Another OAuth server (Okta, Salesforce, ServiceNow, Slack...)" }]} />}</Field>
    {provider && <>
      {grants.length > 1 && <Field label="Who signs in">{(id) => <Select id={id} aria-label="Who signs in" value={grant} onChange={set("sign_in_grant")} options={grants} />}</Field>}
      {provider === "microsoft" && text("sign_in_tenant", "Tenant", "The organisation's domain or directory id, e.g. contoso.onmicrosoft.com.", "contoso.onmicrosoft.com")}
      {(provider === "other" || provider === "docusign") && text("sign_in_token_url", "Token address",
        provider === "docusign" ? "Empty for production (account.docusign.com); https://account-d.docusign.com/oauth/token for the demo environment."
          : "Where the server gives tokens, e.g. https://acme.okta.com/oauth2/v1/token.", "https://…/token")}
      {grant === "snowflake_key_pair" ? <>
        {text("sign_in_account", "Account", "The account identifier, e.g. MYORG-MYACCOUNT.", "MYORG-MYACCOUNT")}
        {text("sign_in_user", "User", "The Snowflake user the key belongs to.")}
      </> : text("sign_in_client_id", grant === "github_app" ? "App id" : grant === "jwt_bearer" ? "Account or integration key" : "Client id",
        grant === "github_app" ? "The GitHub App's id (App settings)." : grant === "jwt_bearer"
          ? "Google: empty to take the account from its key file. DocuSign: the integration key." : "The app's id (Microsoft: Application (client) ID).")}
      {grant === "github_app" && text("sign_in_installation_id", "Installation id", "The number at the end of the App installation's settings address.")}
      {grant === "client_credentials" && <Field label="The app proves itself with">{(id) =>
        <Select id={id} aria-label="The app proves itself with" value={v("sign_in_client_auth")} onChange={set("sign_in_client_auth")} options={[
          { value: "", label: "A client secret" }, { value: "private_key_jwt", label: "A private key (Okta service apps)" }]} />}</Field>}
      {grant === "client_credentials" && !keyAuth && secret("sign_in_client_secret", "Client secret", "The app's secret.")}
      {grant === "refresh_token" && secret("sign_in_client_secret", "Client secret (if the app has one)", "The app's secret.")}
      {grant === "refresh_token" && secret("sign_in_refresh_token", "Refresh token", "The person's refresh token; the runner uses the newest the server gives.")}
      {(grant === "jwt_bearer" || grant === "github_app" || grant === "snowflake_key_pair" || keyAuth) &&
        secret("sign_in_private_key", "Private key", "The private key in PEM, or Google's JSON key file.")}
      {(keyAuth || grant === "jwt_bearer") && text("sign_in_key_id", "Key id (optional)", "The kid the server knows the key by, when it asks for one.")}
      {grant === "jwt_bearer" && text("sign_in_subject", "Act as (optional)", "Google: a person's email (domain-wide delegation). DocuSign: the user id to act for.")}
      {grant !== "github_app" && grant !== "snowflake_key_pair" && text("sign_in_scope", "Scope (optional)", provider === "microsoft"
        ? "Empty asks for Microsoft Graph (https://graph.microsoft.com/.default); for IMAP use https://outlook.office365.com/.default."
        : provider === "docusign" ? "Usually: signature impersonation." : "What the token may do; the server's default when empty.")}
      {(provider === "box" || provider === "other") && grant !== "jwt_bearer" && text("sign_in_extra_fields", "Extra fields (optional)",
        "More fields for the token request, e.g. box_subject_type=enterprise, box_subject_id=12345.", "name=value, name=value")}
    </>}
  </div>;
}
