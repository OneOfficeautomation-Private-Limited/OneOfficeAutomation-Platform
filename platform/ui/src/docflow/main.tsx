import { setNonce } from "get-nonce";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "../theme.css";
import "../components/components.css";
import "../studio/studio.css";
import "./docflow.css";
import { SampleApi } from "./api";
import { App } from "./App";

// Same security policy as Studio: the dialogs' one style element needs the page's nonce (see ../main.tsx).
const nonce = document.querySelector<HTMLMetaElement>('meta[name="ooa-style-nonce"]')?.content;
if (nonce && nonce !== "__CSP_NONCE__") setNonce(nonce);

// Sample data until the Command Center forwards /api/docflow/ to the DocFlow service (the screens then get the real client).
createRoot(document.getElementById("root")!).render(<StrictMode><App api={new SampleApi()} /></StrictMode>);
