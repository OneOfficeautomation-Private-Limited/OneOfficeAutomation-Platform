import { Field, Select, TextInput } from "../components";

/**
 * A1 (command review, batch 7): the sign-in of a saved connection for toolkits that take an access token (Microsoft
 * 365, Google, Email with OAuth). Kept in the connection's settings and vault references with sign_in_ names
 * (runner: OAuthSignIn); the runner gets the token when a step needs one and renews it before it runs out. Used by
 * One AgentStudio's and One CommandCenter's connection forms.
 */
export type SignInBags = { values: Record<string, unknown>; credentials: Record<string, string> };

export const SIGN_IN_VALUE_KEYS = ["sign_in_provider", "sign_in_grant", "sign_in_tenant", "sign_in_client_id", "sign_in_scope", "sign_in_token_url"];
export const SIGN_IN_SECRET_KEYS = ["sign_in_client_secret", "sign_in_refresh_token"];

/** True for toolkits whose steps take an access token from the vault. */
export function takesAccessToken(actions: { inputs?: { properties?: Record<string, { "x-ooa-type"?: string }> } }[]) {
  return actions.some((a) => a.inputs?.properties?.access_token?.["x-ooa-type"] === "Credential");
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

const str = (v: unknown) => (v === undefined || v === null ? "" : String(v));

export function SignInFields({ bags, onChange }: { bags: SignInBags; onChange: (b: SignInBags) => void }) {
  const v = (k: string) => str(bags.values[k]);
  const c = (k: string) => str(bags.credentials[k]);
  const set = (k: string) => (e: { target: { value: string } }) => onChange(withSignIn(bags, k, e.target.value));
  const provider = v("sign_in_provider"), grant = v("sign_in_grant") || "client_credentials";
  return <div className="st-signin" role="group" aria-label="Sign-in">
    <Field label="Sign in" info="With a sign-in the runner gets the access token itself and renews it before it runs out (tokens last about an hour), so steps need no token.">{(id) =>
      <Select id={id} aria-label="Sign in" value={provider} onChange={set("sign_in_provider")} options={[
        { value: "", label: "No sign-in: an access token from the vault" }, { value: "microsoft", label: "Microsoft 365 (Entra ID app)" },
        { value: "google", label: "Google (OAuth client)" }, { value: "other", label: "Another OAuth server" }]} />}</Field>
    {provider && <>
      <Field label="Who signs in">{(id) => <Select id={id} aria-label="Who signs in" value={grant} onChange={set("sign_in_grant")} options={[
        { value: "client_credentials", label: "The app itself (client id and secret), for unattended agents" },
        { value: "refresh_token", label: "One person (a refresh token they gave once)" }]} />}</Field>
      {provider === "microsoft" && <Field label="Tenant" info="The organisation's domain or directory id, e.g. contoso.onmicrosoft.com.">{(id) =>
        <TextInput id={id} aria-label="Tenant" value={v("sign_in_tenant")} placeholder="contoso.onmicrosoft.com" onChange={set("sign_in_tenant")} />}</Field>}
      {provider === "other" && <Field label="Token address" info="Where the server gives tokens, e.g. https://login.example.com/oauth2/token.">{(id) =>
        <TextInput id={id} aria-label="Token address" value={v("sign_in_token_url")} placeholder="https://…/token" onChange={set("sign_in_token_url")} />}</Field>}
      <Field label="Client id" info="The app's id (Microsoft: Application (client) ID; Google: OAuth client ID).">{(id) =>
        <TextInput id={id} aria-label="Client id" value={v("sign_in_client_id")} onChange={set("sign_in_client_id")} />}</Field>
      <Field label={grant === "refresh_token" ? "Client secret (if the app has one)" : "Client secret"}
        info="A reference to the secret in the vault (folder/credential/attribute), not the secret itself.">{(id) =>
        <TextInput id={id} aria-label="Client secret" value={c("sign_in_client_secret")} placeholder="vault folder/credential/attribute" onChange={set("sign_in_client_secret")} />}</Field>
      {grant === "refresh_token" && <Field label="Refresh token" info="A reference to the person's refresh token in the vault. Microsoft gives a new one with each token; the runner uses the newest.">{(id) =>
        <TextInput id={id} aria-label="Refresh token" value={c("sign_in_refresh_token")} placeholder="vault folder/credential/attribute" onChange={set("sign_in_refresh_token")} />}</Field>}
      <Field label="Scope (optional)" info={provider === "microsoft" ? "Empty asks for Microsoft Graph (https://graph.microsoft.com/.default); for IMAP use https://outlook.office365.com/.default."
        : "What the token may do; the server's default when empty."}>{(id) =>
        <TextInput id={id} aria-label="Scope" value={v("sign_in_scope")} onChange={set("sign_in_scope")} />}</Field>
    </>}
  </div>;
}
