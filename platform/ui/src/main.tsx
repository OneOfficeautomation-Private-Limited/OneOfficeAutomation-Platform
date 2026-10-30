import { setNonce } from "get-nonce";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./theme.css";
import "./components/components.css";
import "./studio/studio.css";
import { App } from "./studio/App";

// Dialogs add one style element (to stop the page scrolling behind them). Studio's security policy refuses inline
// styles unless they carry the page's nonce, which the server puts in this meta tag.
const nonce = document.querySelector<HTMLMetaElement>('meta[name="ooa-style-nonce"]')?.content;
if (nonce && nonce !== "__CSP_NONCE__") setNonce(nonce);

createRoot(document.getElementById("root")!).render(<StrictMode><App /></StrictMode>);
