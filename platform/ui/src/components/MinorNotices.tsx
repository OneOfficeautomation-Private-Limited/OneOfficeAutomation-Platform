import { useEffect, useRef, useState } from "react";
import { Icon } from "./Icon";

/**
 * Minor notices (error batch E2, 13 Nov 2026): something a page loads on the side (a list for a drop-down, a count, a
 * status line) could not be loaded. The page still works, so it is not the red bar of a major error: an amber line
 * that says what is missing and why, and closes by itself after 8 seconds (held while the pointer or focus is on it).
 * The same text twice shows once. Pages call reportMinor; the app shows them with <MinorNotices />.
 */
const EVENT = "ooa-minor-notice";
const SECONDS = 8;

/** Says that `what` ("the list of agents") could not be loaded, and why. */
export function reportMinor(what: string, e: unknown) {
  const why = e instanceof Error ? e.message : e == null ? "" : String(e);
  const text = "Could not load " + what + (why ? ": " + why : ".");
  if (typeof window !== "undefined") window.dispatchEvent(new CustomEvent<string>(EVENT, { detail: text }));
}

export function MinorNotices() {
  const [items, setItems] = useState<{ id: number; text: string }[]>([]);
  const next = useRef(1);
  useEffect(() => {
    const on = (e: Event) => {
      const text = (e as CustomEvent<string>).detail;
      setItems((l) => l.some((x) => x.text === text) ? l : [...l.slice(-2), { id: next.current++, text }]);
    };
    window.addEventListener(EVENT, on);
    return () => window.removeEventListener(EVENT, on);
  }, []);
  if (!items.length) return null;
  return <div className="ui-minor-stack">{items.map((x) => <Minor key={x.id} text={x.text} onClose={() => setItems((l) => l.filter((y) => y.id !== x.id))} />)}</div>;
}

function Minor({ text, onClose }: { text: string; onClose: () => void }) {
  const [held, setHeld] = useState(false);
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    if (held) return;
    const t = setTimeout(() => close.current(), SECONDS * 1000);
    return () => clearTimeout(t);
  }, [held]);
  return (
    <div className="ui-minor" role="status" onMouseEnter={() => setHeld(true)} onMouseLeave={() => setHeld(false)} onFocus={() => setHeld(true)} onBlur={() => setHeld(false)}>
      <Icon name="bell" size={14} /><span>{text}</span>
      <button type="button" className="ui-notice-x" aria-label="Close the notice" title="Close (it also closes by itself)" onClick={onClose}><Icon name="close" size={14} /></button>
    </div>
  );
}
