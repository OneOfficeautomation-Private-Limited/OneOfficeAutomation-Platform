/** Line icons (24 px grid, drawn with the current text colour). Names describe the picture, not where it is used. */
export const ICONS = {
  folder: "M3 7h6l2 2h10v10H3z",
  bolt: "M13 2L4 14h7l-1 8 9-12h-7z",
  spark: "M12 3v4M12 17v4M3 12h4M17 12h4M6 6l2.5 2.5M15.5 15.5L18 18M6 18l2.5-2.5M15.5 8.5L18 6",
  mail: "M3 6h18v12H3zM3 7l9 7 9-7",
  loop: "M17 2l3 3-3 3M4 11V9a4 4 0 0 1 4-4h12M7 22l-3-3 3-3M20 13v2a4 4 0 0 1-4 4H4",
  doc: "M6 3h9l4 4v14H6zM9 12h7M9 16h7",
  branch: "M6 3v12M18 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM18 9c0 6-12 3-12 9",
  person: "M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21c0-4 4-6 8-6s8 2 8 6",
  queue: "M4 5h16v4H4zM4 11h16v4H4zM4 17h16v3H4z",
  grid: "M3 3h18v18H3zM3 9h18M3 15h18M9 3v18",
  globe: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM3 12h18M12 3c3 3 3 15 0 18M12 3c-3 3-3 15 0 18",
  db: "M4 6c0-2 16-2 16 0v12c0 2-16 2-16 0zM4 6c0 2 16 2 16 0M4 12c0 2 16 2 16 0",
  desk: "M3 4h18v12H3zM8 20h8M12 16v4",
  code: "M8 8l-5 4 5 4M16 8l5 4-5 4",
  home: "M3 11l9-8 9 8M5 10v10h14V10",
  pulse: "M3 12h4l3-8 4 16 3-8h4",
  gear: "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM12 2v3M12 19v3M2 12h3M19 12h3",
  search: "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4",
  close: "M6 6l12 12M18 6L6 18",
  play: "M6 4l14 8-14 8z",
  save: "M5 3h12l4 4v14H5zM8 3v6h8V3M8 21v-7h8v7",
  bug: "M8 8h8v10a4 4 0 0 1-8 0zM12 8V5M9 5h6M4 13h4M16 13h4M5 19l3-2M19 19l-3-2",
  form: "M5 3h14v18H5zM8 7h8M8 11h3M13 11h3M8 15h8",
  process: "M4 4h6v6H4zM14 14h6v6h-6zM7 10v5h7",
  plus: "M12 5v14M5 12h14",
  chevronDown: "M6 9l6 6 6-6",
  chevronRight: "M9 6l6 6-6 6",
  chevronLeft: "M15 6l-6 6 6 6",
  trash: "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3",
} as const;

export type IconName = keyof typeof ICONS;

export function Icon({ name, size = 16, stroke = 2, filled = false }: { name: IconName; size?: number; stroke?: number; filled?: boolean }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" fill={filled ? "currentColor" : "none"}
      stroke={filled ? "none" : "currentColor"} strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round">
      <path d={ICONS[name]} />
    </svg>
  );
}

/** A coloured square with a white icon: the toolkit mark used in the action library and on steps. */
export function IconTile({ name, color, size = 22 }: { name: IconName; color: string; size?: number }) {
  return (
    <span className="ui-tile" style={{ width: size, height: size, background: color }}>
      <span style={{ color: "#ffffff", display: "inline-flex" }}><Icon name={name} size={Math.round(size * 0.55)} stroke={2.3} /></span>
    </span>
  );
}
