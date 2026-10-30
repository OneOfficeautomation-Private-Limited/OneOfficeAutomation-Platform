import { useId, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { Icon } from "./Icon";

export type SearchOption = { value: string; label: string; description?: string };

/**
 * A choice from a long list that people can search: type to filter (name and description), then click, or use Up and
 * Down and Enter; Esc closes the list and keeps the choice. It follows the ARIA combobox pattern, so screen readers
 * announce the list and the highlighted option.
 */
export function SearchSelect({ id, value, options, onChange, placeholder = "Type to search" }:
  { id?: string; value: string; options: SearchOption[]; onChange: (value: string) => void; placeholder?: string }) {
  const listId = useId();
  const input = useRef<HTMLInputElement>(null);
  const chosen = options.find((o) => o.value === value) ?? null;
  const [query, setQuery] = useState<string | null>(null);   // null: not searching, the field shows the choice
  const [active, setActive] = useState(0);
  const open = query !== null;
  const shown = useMemo(() => {
    const q = (query ?? "").trim().toLowerCase();
    return q ? options.filter((o) => (o.label + " " + (o.description ?? "")).toLowerCase().includes(q)) : options;
  }, [options, query]);

  const pick = (o: SearchOption) => { onChange(o.value); setQuery(null); };
  const show = () => { if (!open) { setQuery(""); setActive(Math.max(0, options.indexOf(chosen!))); } };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown") { if (!open) setQuery(""); setActive((a) => Math.min(a + 1, shown.length - 1)); }
    else if (e.key === "ArrowUp") setActive((a) => Math.max(a - 1, 0));
    else if (e.key === "Enter" && open) { if (shown[active]) pick(shown[active]); }
    else if (e.key === "Escape" && open) { e.stopPropagation(); setQuery(null); }   // closes the list, not the dialog
    else return;
    e.preventDefault();
  };

  return (
    <div className="ui-search-select">
      <div className="ui-search-select-field">
        <Icon name="search" size={14} />
        <input id={id} ref={input} role="combobox" aria-expanded={open} aria-controls={listId} aria-autocomplete="list"
          aria-activedescendant={open && shown[active] ? listId + "-" + active : undefined}
          value={open ? query! : chosen?.label ?? ""} placeholder={placeholder} autoComplete="off"
          onFocus={show} onClick={show}
          onChange={(e) => { setQuery(e.target.value); setActive(0); }}
          onBlur={() => setQuery(null)} onKeyDown={onKey} />
        <button type="button" tabIndex={-1} aria-label="Show all" className="ui-search-select-open"
          onMouseDown={(e) => { e.preventDefault(); input.current?.focus(); show(); }}><Icon name="chevronDown" size={14} stroke={2.4} /></button>
      </div>
      {open && (
        <ul id={listId} role="listbox" className="ui-search-select-list">
          {shown.map((o, i) => (
            <li key={o.value} id={listId + "-" + i} role="option" aria-selected={o.value === value}
              className={(i === active ? "is-active " : "") + (o.value === value ? "is-chosen" : "")}
              onMouseDown={(e) => { e.preventDefault(); pick(o); }} onMouseEnter={() => setActive(i)}>
              <strong>{o.label}</strong>{o.description && <span>{o.description}</span>}
            </li>
          ))}
          {!shown.length && <li className="ui-search-select-none" role="presentation">Nothing matches “{query}”.</li>}
        </ul>
      )}
    </div>
  );
}
