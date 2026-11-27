/**
 * What a column's funnel offers depends on what the column holds (tables and filters, item 2c, 27 Nov 2026), worked
 * out from its cells: dates and times get the date choices and From / To; numbers and durations get From / To in the
 * column's unit; a few different values (up to VALUES_MAX) get a tick list with counts; many values get Contains,
 * Starts with or Is and a text box. These are the rules, without the page: TableTools draws the panels.
 *
 * Dates are read as the browser's language writes them ("Nov 1, 8:30 AM", "27/11/2026, 14:05", "27 Nov 2026"), with
 * the order of day, month and year and the month names taken from the browser, so no country's format is assumed.
 */
export type ColumnKind = "date" | "duration" | "number" | "values" | "text";
export type ColumnFilter =
  | { kind: "values"; allowed: Set<string> }
  | { kind: "range"; type: "date" | "duration" | "number"; from: number | null; to: number | null; label?: string }
  | { kind: "text"; op: "contains" | "starts" | "is"; text: string };

export const VALUES_MAX = 15;
export const EMPTY = "(empty)";

type DateParts = { order: ("day" | "month" | "year")[]; months: Map<string, number>; pm: string[]; am: string[]; words: Set<string> };
const partsCache = new Map<string, DateParts>();
function dateParts(locale?: string): DateParts {
  const key = locale ?? "";
  const hit = partsCache.get(key);
  if (hit) return hit;
  const order = new Intl.DateTimeFormat(locale, { year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(new Date(2026, 10, 27))
    .map((p) => p.type).filter((t): t is "day" | "month" | "year" => t === "day" || t === "month" || t === "year");
  const months = new Map<string, number>();
  const norm = (s: string) => s.toLowerCase().replace(/\./g, "").trim();
  for (let m = 0; m < 12; m++) {
    for (const style of ["short", "long"] as const) {
      const d = new Date(2026, m, 15);
      months.set(norm(new Intl.DateTimeFormat(locale, { month: style }).format(d)), m);
      const inDate = new Intl.DateTimeFormat(locale, { month: style, day: "numeric" }).formatToParts(d).find((p) => p.type === "month")?.value;
      if (inDate) months.set(norm(inDate), m);
    }
    for (const style of ["short", "long"] as const) months.set(norm(new Intl.DateTimeFormat("en", { month: style }).format(new Date(2026, m, 15))), m);
  }
  const period = (h: number) => new Intl.DateTimeFormat(locale, { hour: "numeric", hour12: true }).formatToParts(new Date(2026, 0, 1, h)).find((p) => p.type === "dayPeriod")?.value ?? "";
  // the other words a date may carry: weekday names, the time of day's marks, "at"
  const words = new Set<string>(["at", "am", "pm", norm(period(9)), norm(period(15))]);
  for (let d = 0; d < 7; d++) for (const l of [locale, "en"]) for (const style of ["short", "long"] as const) words.add(norm(new Intl.DateTimeFormat(l, { weekday: style }).format(new Date(2026, 10, 22 + d))));
  const out = { order: order.length === 3 ? order : ["month", "day", "year"] as DateParts["order"], months, am: [norm(period(9)), "am"], pm: [norm(period(15)), "pm"], words };
  partsCache.set(key, out);
  return out;
}

/** A date or time in a cell, as milliseconds; null when the text is not one (a time alone is not a date). */
export function parseCellDate(text: string, now: Date = new Date(), locale?: string): number | null {
  const t = text.replace(/\s+/g, " ").trim();
  if (!t || t.length > 48 || !/\d/.test(t) || /^[-+]?\d+([.,]\d+)?$/.test(t)) return null;   // "1.5" is a number
  const iso = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?)?$/.exec(t);
  if (iso) { const ms = Date.parse(iso[7] || !iso[4] ? t : t.replace(" ", "T")); return Number.isNaN(ms) ? null : iso[4] ? ms : new Date(+iso[1], +iso[2] - 1, +iso[3]).getTime(); }
  const p = dateParts(locale);
  let rest = t.toLowerCase();
  let hour = 0, minute = 0, second = 0;
  // a time: hh:mm(:ss), or hh.mm(.ss) after the date where a language writes times with dots ("27.11.2026 14.05")
  const mark = "(?:\\s*([^\\d\\s,]+(?:\\s?[^\\d\\s,]+)?))?";
  const time = new RegExp("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?" + mark).exec(rest)
    ?? new RegExp("(?<=[\\s,])(\\d{1,2})\\.(\\d{2})(?:\\.(\\d{2}))?(?![.\\d])" + mark).exec(rest);
  if (time && (time.index > 0 || /[/.\-\s]\d/.test(rest.slice(time[0].length)))) {
    hour = +time[1]; minute = +time[2]; second = +(time[3] ?? 0);
    const half = (time[4] ?? "").replace(/\./g, "").trim();
    if (half && p.pm.includes(half) && hour < 12) hour += 12;
    else if (half && p.am.includes(half) && hour === 12) hour = 0;
    rest = rest.slice(0, time.index) + " " + rest.slice(time.index + time[0].length);
  } else if (time) return null;
  let month: number | null = null;
  for (const word of rest.match(/\p{L}[\p{L}.]*/gu) ?? []) {
    const w = word.replace(/\./g, ""), m = p.months.get(w);
    if (m != null) { if (month == null) month = m; }
    else if (!p.words.has(w)) return null;   // "3 min 4 s" or "v2" is not a date
  }
  const nums = (rest.match(/\d+/g) ?? []).map(Number);
  let day: number | undefined, year: number | undefined;
  if (month != null) {
    for (const n of nums) { if (n > 31 || (day != null && year == null)) year = n; else if (day == null) day = n; }
  } else {
    if (nums.length < 2 || nums.length > 3 || !/\d\s?[/.-]\s?\d/.test(rest)) return null;
    const order = nums.length === 3 ? p.order : p.order.filter((o) => o !== "year");
    const got: Record<string, number> = {};
    order.forEach((o, i) => { got[o] = nums[i]; });
    if (nums.length === 3 && nums[0] > 31) { got.year = nums[0]; got.month = nums[1]; got.day = nums[2]; }
    month = got.month - 1; day = got.day; year = got.year;
  }
  if (day == null || month == null || month < 0 || month > 11 || day < 1 || day > 31) return null;
  if (year != null && year < 100) year += 2000;
  const d = new Date(year ?? now.getFullYear(), month, day, hour, minute, second);
  return d.getDate() === day ? d.getTime() : null;
}

/** A duration in a cell ("45 s", "3 min 4 s", "1 h 2 min", "2 days", "350 ms"), in seconds; null when it is not one. */
export function parseDuration(text: string): number | null {
  const t = text.trim().toLowerCase();
  if (!t) return null;
  const unit: Record<string, number> = { ms: 0.001, s: 1, sec: 1, secs: 1, second: 1, seconds: 1, m: 60, min: 60, mins: 60, minute: 60, minutes: 60,
    h: 3600, hr: 3600, hrs: 3600, hour: 3600, hours: 3600, d: 86400, day: 86400, days: 86400 };
  const re = /(\d+(?:[.,]\d+)?)\s*([a-z]+)/g;
  let total = 0, any = false, m: RegExpExecArray | null;
  while ((m = re.exec(t))) {
    if (!(m[2] in unit)) return null;
    total += parseFloat(m[1].replace(",", ".")) * unit[m[2]];
    any = true;
  }
  return any && !t.replace(re, "").trim() ? total : null;
}

/** A number in a cell with its unit ("1,234", "12.5 %", "3 KB"); null when it is not one. Separators are the browser's. */
export function parseNumber(text: string, locale?: string): { n: number; unit: string } | null {
  const t = text.trim();
  const m = /^([-+−]?[\d\s.,  ']*\d)\s*(%|[A-Za-z]{1,3})?$/.exec(t);
  if (!m) return null;
  const dec = new Intl.NumberFormat(locale).formatToParts(1.5).find((x) => x.type === "decimal")?.value ?? ".";
  let s = m[1].replace(/[\s  ']/g, "").replace("−", "-");
  s = dec === "," ? s.replace(/\./g, "").replace(",", ".") : s.replace(/,/g, "");
  const n = Number(s);
  return Number.isFinite(n) ? { n, unit: m[2] ?? "" } : null;
}

/** What a column holds, from its cells' text (empty cells do not count). */
export function columnKind(cells: string[], now: Date = new Date(), locale?: string): ColumnKind {
  const vals = cells.filter((c) => c && c !== EMPTY && c !== "–" && c !== "-");
  if (!vals.length) return "values";
  const most = (ok: number) => ok >= Math.max(1, Math.ceil(vals.length * 0.8));
  if (most(vals.filter((v) => parseCellDate(v, now, locale) != null).length)) return "date";
  if (vals.some((v) => /[a-z]/i.test(v)) && most(vals.filter((v) => parseDuration(v) != null).length)) return "duration";
  const nums = vals.map((v) => parseNumber(v, locale));
  if (most(nums.filter(Boolean).length) && new Set(nums.filter(Boolean).map((x) => x!.unit)).size <= 1) return "number";
  return new Set(cells).size <= VALUES_MAX ? "values" : "text";
}

/** The column's unit for From and To ("min" for durations, "%" or "KB" for numbers), shown beside the boxes. */
export function columnUnit(kind: ColumnKind, cells: string[], locale?: string): string {
  if (kind === "duration") return "min";
  if (kind !== "number") return "";
  return cells.map((c) => parseNumber(c, locale)?.unit).find((u) => u) ?? "";
}

/** The value a range filter compares: milliseconds for dates, seconds for durations, the number for numbers. */
export function cellValue(type: "date" | "duration" | "number", text: string, now: Date = new Date(), locale?: string): number | null {
  return type === "date" ? parseCellDate(text, now, locale) : type === "duration" ? parseDuration(text) : parseNumber(text, locale)?.n ?? null;
}

/** True when a cell passes the filter. */
export function passes(f: ColumnFilter, text: string, now: Date = new Date(), locale?: string): boolean {
  if (f.kind === "values") return f.allowed.has(text);
  if (f.kind === "text") {
    const a = text.toLowerCase(), b = f.text.trim().toLowerCase();
    if (!b) return true;
    return f.op === "is" ? a === b : f.op === "starts" ? a.startsWith(b) : a.includes(b);
  }
  const v = cellValue(f.type, text, now, locale);
  if (v == null) return false;
  return (f.from == null || v >= f.from) && (f.to == null || v < f.to || (f.type !== "date" && v <= f.to));
}

/** Quick choices for a duration column, in seconds. */
export const DURATION_CHIPS: [string, number | null, number | null][] = [["Under 1 min", null, 60], ["1 to 5 min", 60, 300], ["Over 5 min", 300, null], ["Over 1 hour", 3600, null]];
