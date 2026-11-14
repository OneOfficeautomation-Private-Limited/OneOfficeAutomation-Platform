import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { Icon } from "./Icon";
import "./pickers.css";

/**
 * Usability round (14 Nov 2026, item 8): one picker for "who" fields (vault folder members, work queue members, alert
 * and dashboard recipients, workflow assignees). People search the users, teams and roles, pick several, and see each
 * as a chip ("Role: admin") with a Remove button. The stored text is unchanged: "role:NAME, team:NAME, user:NAME"
 * separated by commas (bare user names where a field holds only people), so nothing on the server changes.
 * When the lists cannot be loaded (OneAgentStudio without One CommandCenter, or a person without the right to see
 * users), the same box takes a typed name and asks whether it is a user, a team or a role.
 */
export type PrincipalKind = "user" | "team" | "role";
/** Users, teams and roles to choose from; a list that could not be loaded is null (a typed name is then offered). */
export type PrincipalDirectory = { users: { name: string; label?: string }[] | null; teams: string[] | null; roles: string[] | null };

type ApiLike = {
  users?: () => Promise<{ username: string; display_name?: string | null }[]>;
  teams?: () => Promise<{ name: string }[]>;
  roles?: () => Promise<{ name: string }[]>;
};
const cache = new WeakMap<object, Promise<PrincipalDirectory>>();
/** The users, teams and roles of One CommandCenter, read once per connection; a list the person may not read is null. */
export function principalDirectory(api: ApiLike): Promise<PrincipalDirectory> {
  let p = cache.get(api);
  if (!p) {
    const get = <T, R>(f: (() => Promise<T[]>) | undefined, map: (x: T) => R): Promise<R[] | null> =>
      f ? f.call(api).then((xs) => xs.map(map), () => null) : Promise.resolve(null);
    p = Promise.all([get(api.users, (u) => ({ name: u.username, label: u.display_name || undefined })), get(api.teams, (t) => t.name), get(api.roles, (r) => r.name)])
      .then(([users, teams, roles]) => ({ users, teams, roles }));
    cache.set(api, p);
  }
  return p;
}

const KIND_WORD: Record<PrincipalKind, string> = { user: "User", team: "Team", role: "Role" };

/** The comma-separated text as tokens. */
export const principalTokens = (value: string) => value.split(",").map((x) => x.trim()).filter(Boolean);

/** How a stored token reads on its chip: "Role: admin", "Team: finance", "User: jane", "Everyone", "E-mail: a@b.com". */
export function principalLabel(token: string, bareUsers = false, everyone?: string): string {
  if (everyone && token === everyone) return "Everyone";
  const m = /^(user|team|role):(.*)$/.exec(token);
  if (m) return m[2] === "*" && m[1] === "role" ? "Everyone" : KIND_WORD[m[1] as PrincipalKind] + ": " + m[2];
  if (token.includes("@")) return "E-mail: " + token;
  return bareUsers ? "User: " + token : token;
}

type Option = { token: string; label: string; description?: string };

export function PrincipalPicker({ id, value, onChange, kinds = ["user", "team", "role"], source, everyone, emails, bareUsers, single, disabled, label = "Who", placeholder }: {
  id?: string; value: string; onChange: (value: string) => void;
  /** What may be chosen; bareUsers keeps people as plain user names (no "user:"). */
  kinds?: PrincipalKind[]; bareUsers?: boolean;
  /** Where the lists come from; none (or one that fails) means typed names only. */
  source?: (() => Promise<PrincipalDirectory>) | null;
  /** The token for everyone ("*" or "role:*"), when everyone may be chosen. */
  everyone?: string;
  /** E-mail addresses may be typed too. */
  emails?: boolean;
  /** Only one choice: picking replaces it. */
  single?: boolean; disabled?: boolean; label?: string; placeholder?: string;
}) {
  const listId = useId();
  const input = useRef<HTMLInputElement>(null);
  const [dir, setDir] = useState<PrincipalDirectory | null | undefined>(source ? undefined : null);
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  useEffect(() => {
    if (!source) { setDir(null); return; }
    let live = true;
    source().then((d) => { if (live) setDir(d); }, () => { if (live) setDir(null); });
    return () => { live = false; };
  }, [source]);

  const tokens = principalTokens(value);
  const kindKey = kinds.join();
  const options = useMemo(() => {
    const tokenOf = (k: PrincipalKind, name: string) => k === "user" && bareUsers ? name : k + ":" + name;
    const q = query.trim();
    const ql = q.toLowerCase();
    const out: Option[] = [];
    const has = (t: string) => tokens.includes(t);
    if (everyone && !has(everyone) && (!ql || "everyone".includes(ql))) out.push({ token: everyone, label: "Everyone" });
    for (const k of kinds) {
      const list: { name: string; label?: string }[] | null = !dir ? null
        : k === "user" ? dir.users : (k === "team" ? dir.teams : dir.roles)?.map((n) => ({ name: n })) ?? null;
      if (list) {
        for (const x of list) {
          const t = tokenOf(k, x.name);
          if (!has(t) && (!ql || (x.name + " " + (x.label ?? "")).toLowerCase().includes(ql)))
            out.push({ token: t, label: KIND_WORD[k] + ": " + x.name, description: x.label });
        }
      } else if (q && !/[,\s]/.test(q) && !(emails && q.includes("@")) && !has(tokenOf(k, q))) {
        out.push({ token: tokenOf(k, q), label: "Add " + KIND_WORD[k].toLowerCase() + " " + q });
      }
    }
    if (emails && /^[^\s@,]+@[^\s@,]+$/.test(q) && !has(q)) out.push({ token: q, label: "Add e-mail " + q });
    return out;
  }, [dir, query, value, kindKey, everyone, emails, bareUsers]);   // eslint-disable-line react-hooks/exhaustive-deps

  const write = (next: string[]) => onChange(next.join(", "));
  const pick = (o: Option) => { write(single ? [o.token] : [...tokens, o.token]); setQuery(""); setActive(0); if (single) setOpen(false); };
  const remove = (t: string) => { write(tokens.filter((x) => x !== t)); input.current?.focus(); };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown") { setOpen(true); setActive((a) => Math.min(a + 1, options.length - 1)); }
    else if (e.key === "ArrowUp") setActive((a) => Math.max(a - 1, 0));
    else if (e.key === "Enter") { if (open && options[active]) pick(options[active]); }
    else if (e.key === "Escape" && open) { e.stopPropagation(); setOpen(false); }
    else if (e.key === "Backspace" && !query && tokens.length) write(tokens.slice(0, -1));
    else return;
    e.preventDefault();
  };
  const offline = dir === null || (dir !== undefined && kinds.some((k) => (k === "user" ? dir.users : k === "team" ? dir.teams : dir.roles) === null));
  const words = kinds.map((k) => KIND_WORD[k].toLowerCase());
  const kindText = words.length > 1 ? words.slice(0, -1).join(", ") + " or " + words[words.length - 1] : words[0];
  const shown = open && !disabled && (options.length > 0 || !!query.trim());
  const typing = !disabled && (!single || !tokens.length || open);

  return (
    <div className={"ui-who" + (disabled ? " is-off" : "")}>
      {tokens.length > 0 && <ul className="ui-who-chips" aria-label={label + ": chosen"}>
        {tokens.map((t) => {
          const l = principalLabel(t, bareUsers, everyone);
          return <li key={t} className="ui-who-chip"><span>{l}</span>
            {!disabled && <button type="button" className="ui-who-remove" aria-label={"Remove " + l} title={"Remove " + l} onClick={() => remove(t)}><Icon name="close" size={12} stroke={2.4} /></button>}</li>;
        })}
        {single && !typing && <li><button type="button" className="ui-who-change" onClick={() => { setOpen(true); setTimeout(() => input.current?.focus()); }}>Change</button></li>}
      </ul>}
      {typing && <div className="ui-search-select">
        <div className="ui-search-select-field">
          <Icon name="search" size={14} />
          <input id={id} ref={input} role="combobox" aria-label={id ? undefined : label} aria-expanded={shown} aria-controls={listId} aria-autocomplete="list"
            aria-activedescendant={shown && options[active] ? listId + "-" + active : undefined}
            value={query} autoComplete="off" placeholder={placeholder ?? (dir === undefined ? "Loading the lists…" : "Search for a " + kindText + (emails ? ", or type an e-mail address" : ""))}
            onFocus={() => setOpen(true)} onClick={() => setOpen(true)} onBlur={() => setOpen(false)} onKeyDown={onKey}
            onChange={(e) => { setQuery(e.target.value); setActive(0); setOpen(true); }} />
        </div>
        {shown && <ul id={listId} role="listbox" aria-label={label + ": choices"} className="ui-search-select-list">
          {options.map((o, i) => <li key={o.token} id={listId + "-" + i} role="option" aria-selected={i === active} aria-label={o.label + (o.description ? ", " + o.description : "")} className={i === active ? "is-active" : ""}
            onMouseDown={(e) => { e.preventDefault(); pick(o); }} onMouseEnter={() => setActive(i)}>
            <strong>{o.label}</strong>{o.description && <span>{o.description}</span>}</li>)}
          {!options.length && <li className="ui-search-select-none" role="presentation">Nothing matches “{query}”.</li>}
        </ul>}
      </div>}
      {offline && typing && <div className="ui-field-hint">{kinds.length > 1 ? "The lists" : "The list"} could not be loaded here: type a name, then choose whether it is a {kindText}.</div>}
    </div>
  );
}
