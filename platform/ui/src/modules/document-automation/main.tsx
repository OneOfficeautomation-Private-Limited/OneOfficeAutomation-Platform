import { setNonce } from "get-nonce";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "../../theme.css";
import "../../components/components.css";
import "../../studio/studio.css";
import "../../console/console.css";
import "./docflow.css";
import { SampleApi } from "./api";
import { App } from "./App";

// Same security policy as Studio: the dialogs' one style element needs the page's nonce (see ../main.tsx).
const nonce = document.querySelector<HTMLMetaElement>('meta[name="ooa-style-nonce"]')?.content;
if (nonce && nonce !== "__CSP_NONCE__") setNonce(nonce);


// Served by the Command Center at /documents/: since 27 Nov 2026 (fix list item 3c) its pages live in the console's frame,
// so an old link (a task's Review, a bookmark) opens there. Anywhere else (the design preview, npm run dev) the screens
// run on sample data.
if (location.pathname.startsWith("/documents")) {
  location.replace("/next/#/docautomation/" + location.hash.replace(/^#\/?/, ""));
} else {
  createRoot(document.getElementById("root")!).render(<StrictMode><App api={new SampleApi()} /></StrictMode>);
}
