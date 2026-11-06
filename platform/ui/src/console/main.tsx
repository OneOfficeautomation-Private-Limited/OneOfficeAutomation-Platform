import { setNonce } from "get-nonce";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "../theme.css";
import "../components/components.css";
import "../studio/studio.css";
import "./console.css";
import { ConsoleApp } from "./ConsoleApp";
import { EmbedApp } from "./EmbedApp";

// Dialogs add one style element (to stop the page scrolling behind them). The security policy refuses inline styles
// unless they carry the page's nonce, which the server puts in this meta tag.
const nonce = document.querySelector<HTMLMetaElement>('meta[name="ooa-style-nonce"]')?.content;
if (nonce && nonce !== "__CSP_NONCE__") setNonce(nonce);

// /embed/<link id>: an app shown inside another site (G68), with no sign-in and no menus
const embedded = window.location.pathname.startsWith("/embed/");
createRoot(document.getElementById("root")!).render(<StrictMode>{embedded ? <EmbedApp /> : <ConsoleApp />}</StrictMode>);
