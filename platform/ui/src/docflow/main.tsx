import { setNonce } from "get-nonce";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "../theme.css";
import "../components/components.css";
import "../studio/studio.css";
import "./docflow.css";
import { SampleApi } from "./api";
import { App } from "./App";
import { HttpDocFlowApi } from "./http";

// Same security policy as Studio: the dialogs' one style element needs the page's nonce (see ../main.tsx).
const nonce = document.querySelector<HTMLMetaElement>('meta[name="ooa-style-nonce"]')?.content;
if (nonce && nonce !== "__CSP_NONCE__") setNonce(nonce);

const root = createRoot(document.getElementById("root")!);

// Served by the Command Center at /documents/: the real service, as the signed-in person. Anywhere else (the
// design preview, npm run dev) the screens run on sample data.
if (location.pathname.startsWith("/documents")) {
  void fetch("/api/auth/me", { credentials: "same-origin" }).then(async (r) => {
    if (!r.ok) { location.assign("/"); return; }
    const me = await r.json() as { username: string; display_name?: string; tenant?: string };
    root.render(<StrictMode><App api={new HttpDocFlowApi()} user={me.display_name || me.username} tenants={me.tenant ? [me.tenant] : []} /></StrictMode>);
  });
} else {
  root.render(<StrictMode><App api={new SampleApi()} /></StrictMode>);
}
