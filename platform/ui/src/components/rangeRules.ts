/**
 * The time a page or a column is filtered by (tables and filters, item 2b, 27 Nov 2026): a quick choice such as
 * "Last 7 days", or chosen dates. One set of rules for every page, so "Last 7 days" means the same everywhere: today
 * and the six days before it, in the browser's time zone, up to now. Dates are shown in the browser's own language.
 *
 * A value is kept as { key, from?, to? }; from and to are only for "custom" and are local dates ("2026-11-21") or
 * local times ("2026-11-21T09:30"). resolveRange turns a value into the instants a server is asked for: from is
 * included, to is the first moment no longer included.
 */
export type RangeKey = "24h" | "today" | "yesterday" | "7d" | "30d" | "90d" | "365d" | "month" | "last-month" | "year" | "any" | "custom"
  | "tomorrow" | "next7" | "next30";
export type DateRangeValue = { key: RangeKey; from?: string; to?: string };

/** The quick choices looking back, as the approved design lists them (a page may leave some out or add 24h / 365d / year). */
export const BACK: RangeKey[] = ["today", "yesterday", "7d", "30d", "90d", "month", "last-month", "any", "custom"];
/** Looking ahead, for what is still to come (upcoming runs). */
export const AHEAD: RangeKey[] = ["today", "tomorrow", "next7", "next30", "custom"];

export const RANGE_NAMES: Record<RangeKey, string> = {
  "24h": "Last 24 hours", today: "Today", yesterday: "Yesterday", "7d": "Last 7 days", "30d": "Last 30 days", "90d": "Last 90 days", "365d": "Last year",
  month: "This month", "last-month": "Last month", year: "This year", any: "Any time", custom: "Custom…", tomorrow: "Tomorrow", next7: "Next 7 days", next30: "Next 30 days",
};
const WORDS: Partial<Record<RangeKey, string>> = {
  "24h": "in the last 24 hours", today: "today", yesterday: "yesterday", "7d": "in the last 7 days", "30d": "in the last 30 days", "90d": "in the last 90 days",
  "365d": "in the last year", month: "this month", "last-month": "last month", year: "this year", any: "in all", tomorrow: "tomorrow", next7: "in the next 7 days", next30: "in the next 30 days",
};

const DAY = 86_400_000;
const startOfDay = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate());
const addDays = (d: Date, n: number) => new Date(d.getFullYear(), d.getMonth(), d.getDate() + n, d.getHours(), d.getMinutes(), d.getSeconds(), d.getMilliseconds());
/** A local date or time typed or chosen ("2026-11-21" or "2026-11-21T09:30"), as a Date; null when it is not one. */
export function localTime(s?: string | null): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?$/.exec((s ?? "").trim());
  if (!m) return null;
  const d = new Date(+m[1], +m[2] - 1, +m[3], +(m[4] ?? 0), +(m[5] ?? 0), +(m[6] ?? 0));
  return d.getMonth() === +m[2] - 1 ? d : null;
}
/** A Date as a local date for a date field ("2026-11-21"). */
export const isoDay = (d: Date) => d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");

/** The instants a value covers: from (included) and to (not included); null for an open end. */
export function resolveRange(v: DateRangeValue, now: Date = new Date()): { from: Date | null; to: Date | null } {
  const today = startOfDay(now);
  switch (v.key) {
    case "24h": return { from: new Date(now.getTime() - DAY), to: now };
    case "today": return { from: today, to: addDays(today, 1) };
    case "yesterday": return { from: addDays(today, -1), to: today };
    case "7d": return { from: addDays(today, -6), to: now };
    case "30d": return { from: addDays(today, -29), to: now };
    case "90d": return { from: addDays(today, -89), to: now };
    case "365d": return { from: new Date(today.getFullYear() - 1, today.getMonth(), today.getDate() + 1), to: now };
    case "month": return { from: new Date(today.getFullYear(), today.getMonth(), 1), to: now };
    case "last-month": return { from: new Date(today.getFullYear(), today.getMonth() - 1, 1), to: new Date(today.getFullYear(), today.getMonth(), 1) };
    case "year": return { from: new Date(today.getFullYear(), 0, 1), to: now };
    case "tomorrow": return { from: addDays(today, 1), to: addDays(today, 2) };
    case "next7": return { from: now, to: addDays(today, 7) };
    case "next30": return { from: now, to: addDays(today, 30) };
    case "custom": {
      const from = localTime(v.from), toRaw = localTime(v.to);
      // a date alone includes its whole day; a time is the end itself
      const to = toRaw && v.to && !v.to.includes("T") ? addDays(toRaw, 1) : toRaw;
      return { from, to };
    }
    default: return { from: null, to: null };
  }
}

/** The query a server is asked for: from and to as ISO instants (nothing for Any time). */
export function rangeParams(v: DateRangeValue, now: Date = new Date()): Record<string, string> {
  const { from, to } = resolveRange(v, now), p: Record<string, string> = {};
  if (from) p.from = from.toISOString();
  if (to) p.to = to.toISOString();
  return p;
}

const sameYear = (a: Date, b: Date) => a.getFullYear() === b.getFullYear();
/** A day as the browser's language writes it, short ("21 Nov" or "Nov 21"), with the year when it is not this one. */
export function dayText(d: Date, now: Date = new Date(), locale?: string) {
  return d.toLocaleDateString(locale, { day: "numeric", month: "short", ...(sameYear(d, now) ? {} : { year: "numeric" as const }) });
}
const timeText = (d: Date, locale?: string) => d.toLocaleTimeString(locale, { hour: "numeric", minute: "2-digit" });
/** The days a value covers, as shown on the button: "21 Nov – 27 Nov"; one day alone; times when custom ones were given. */
export function rangeDates(v: DateRangeValue, now: Date = new Date(), locale?: string): string {
  if (v.key === "any") return "";
  const { from, to } = resolveRange(v, now);
  if (v.key === "24h" && from) return dayText(from, now, locale) + " " + timeText(from, locale) + " – now";
  const withTime = (s: string | undefined, d: Date) => s?.includes("T") ? " " + timeText(d, locale) : "";
  const a = from ? dayText(from, now, locale) + (v.key === "custom" ? withTime(v.from, from) : "") : "";
  // the last day included: a moment before an exclusive midnight end
  const last = to ? (v.key === "custom" && v.to?.includes("T") ? to : new Date(to.getTime() - 1)) : null;
  const b = last ? dayText(last, now, locale) + (v.key === "custom" ? withTime(v.to, last) : "") : "";
  if (a && b) return a === b ? a : a + " – " + b;
  return a ? "From " + a : b ? "Until " + b : "";
}
/** The whole button text: "Last 7 days · 21 Nov – 27 Nov", or the dates alone for chosen ones. */
export function rangeLabel(v: DateRangeValue, now: Date = new Date(), locale?: string): string {
  const dates = rangeDates(v, now, locale);
  if (v.key === "custom") return dates || "Choose dates";
  return RANGE_NAMES[v.key] + (dates ? " · " + dates : "");
}
/** For a sentence: "in the last 7 days", "this month", "between 21 Nov and 27 Nov". */
export function rangeWords(v: DateRangeValue, now: Date = new Date(), locale?: string): string {
  if (v.key !== "custom") return WORDS[v.key] ?? "";
  const d = rangeDates(v, now, locale);
  return d.includes(" – ") ? "between " + d.replace(" – ", " and ") : d ? (d.startsWith("From") || d.startsWith("Until") ? d.charAt(0).toLowerCase() + d.slice(1) : "on " + d) : "";
}
/** For a figure's caption: "last 7 days", "this month", or the dates when chosen. */
export function rangeShort(v: DateRangeValue, now: Date = new Date(), locale?: string): string {
  return v.key === "custom" ? rangeDates(v, now, locale) : RANGE_NAMES[v.key].charAt(0).toLowerCase() + RANGE_NAMES[v.key].slice(1);
}
/** The length of a value in whole days (at least 1), for pages that also ask for a number of days. */
export function rangeDays(v: DateRangeValue, now: Date = new Date()): number | null {
  const { from, to } = resolveRange(v, now);
  return from ? Math.max(1, Math.round(((to ?? now).getTime() - from.getTime()) / DAY)) : null;
}

/**
 * A value from an address or an older saved choice: a key, or a number of days as pages used to keep it
 * ("1" = Last 24 hours, "7", "30", "90", "365"), "all" for Any time, "dates" for chosen dates.
 */
export function rangeFrom(s: string | null | undefined, from?: string | null, to?: string | null, fallback: DateRangeValue = { key: "7d" }): DateRangeValue {
  const legacy: Record<string, RangeKey> = { "1": "24h", "7": "7d", "30": "30d", "90": "90d", "365": "365d", all: "any", dates: "custom", week: "next7" };
  const k = (s && (legacy[s] ?? s)) as RangeKey | undefined;
  if (!k || !(k in RANGE_NAMES)) return fallback;
  return k === "custom" ? { key: k, ...(from ? { from } : {}), ...(to ? { to } : {}) } : { key: k };
}

/** The first day of the week in the browser's language (1 = Monday ... 7 = Sunday); Monday when the browser does not say. */
export function firstWeekday(locale?: string): number {
  try {
    const l = new Intl.Locale(locale ?? navigator.language) as Intl.Locale & { weekInfo?: { firstDay: number }; getWeekInfo?: () => { firstDay: number } };
    const w = l.getWeekInfo?.() ?? l.weekInfo;
    if (w?.firstDay) return w.firstDay;
  } catch { /* an old browser: Monday */ }
  return 1;
}

/** The weeks of a month for a calendar, each a list of 7 days (days of the months either side fill the first and last week). */
export function monthWeeks(year: number, month: number, weekStart = 1): Date[][] {
  const first = new Date(year, month, 1);
  const lead = ((first.getDay() || 7) - weekStart + 7) % 7;
  const start = new Date(year, month, 1 - lead);
  const weeks: Date[][] = [];
  for (let w = 0; w < 6; w++) {
    const week = Array.from({ length: 7 }, (_, i) => new Date(start.getFullYear(), start.getMonth(), start.getDate() + w * 7 + i));
    if (w > 3 && week[0].getMonth() !== month) break;
    weeks.push(week);
  }
  return weeks;
}
