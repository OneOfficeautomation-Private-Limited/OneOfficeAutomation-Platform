import { useEffect, useRef, useState } from "react";
import { Icon } from "./Icon";

/**
 * The bar at the top for what just happened (usage review item 27, 9 Nov 2026). A notice ("Saved Invoice.") closes by
 * itself after a few seconds, unless the pointer or the keyboard focus is on it, so it can still be read or copied; an
 * error stays until it is closed, with a labelled Close. A newer notice takes the place of the one before.
 */
export function Notice({ text, kind = "ok", seconds = 6, onClose }: { text: string; kind?: "ok" | "error"; seconds?: number; onClose: () => void }) {
  const [held, setHeld] = useState(false);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    if (kind !== "ok" || held) return;
    const t = setTimeout(() => close.current(), seconds * 1000);
    return () => clearTimeout(t);
  }, [text, kind, held, seconds]);
  const error = kind === "error";
  return (
    <div className={(error ? "st-alert" : "st-notice") + " ui-notice"} role={error ? "alert" : "status"}
      onMouseEnter={() => setHeld(true)} onMouseLeave={() => setHeld(false)} onFocus={() => setHeld(true)} onBlur={() => setHeld(false)}>
      <span>{text}</span>
      {error
        ? <button type="button" className="ui-btn ui-btn-secondary ui-notice-close" onClick={onClose}><Icon name="close" size={14} /><span>Close</span></button>
        : <button type="button" className="ui-notice-x" aria-label="Close the notice" title="Close (it also closes by itself)" onClick={onClose}><Icon name="close" size={14} /></button>}
    </div>
  );
}
