import { useState } from "react";

/**
 * A small viewer preference (a panel folded away, a section closed) remembered in this browser. Storage can be
 * missing or blocked (private windows, locked-down machines), so every read and write may fail and the default stands.
 */
export function usePersistent<T>(key: string, initial: T): [T, (v: T) => void] {
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = localStorage.getItem("ooa.studio." + key);
      return raw === null ? initial : (JSON.parse(raw) as T);
    } catch { return initial; }
  });
  const set = (v: T) => {
    setValue(v);
    try { localStorage.setItem("ooa.studio." + key, JSON.stringify(v)); } catch { /* not remembered; still works */ }
  };
  return [value, set];
}
