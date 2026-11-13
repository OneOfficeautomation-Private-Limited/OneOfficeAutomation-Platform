import { useId } from "react";

/**
 * The ONEOFFICEAUTOMATION mark: the two linked rings of the product logo (OOA-Logo-Master.svg, 1 Nov 2026), drawn as
 * vectors so it stays sharp at any size. The blue ring is dark, so on the dark top bar it sits on a white tile (.st-logo).
 */
/** `light`: the rings in light blue and light violet, for the dark top bars (improvement list 13 Nov 2026, item 12: no white tile). */
export function BrandMark({ size = 22, title, light = false }: { size?: number; title?: string; light?: boolean }) {
  const id = useId().replace(/:/g, "");
  const blue = "b" + id, violet = "v" + id;
  if (light) return (
    <svg viewBox="39 28 157 101" width={size * 157 / 101} height={size} role={title ? "img" : undefined} aria-label={title} aria-hidden={title ? undefined : true}>
      <circle cx="89.5" cy="78.5" r="43.3" fill="none" stroke="#7fb8f0" strokeWidth="11" />
      <circle cx="145.5" cy="78.5" r="43.3" fill="none" stroke="#b79cf5" strokeWidth="11" />
      <path d="M131.325,89.707 A43.3,43.3 0 0 1 107.799,117.743" fill="none" stroke="#7fb8f0" strokeWidth="11" />
    </svg>
  );
  return (
    <svg viewBox="39 28 157 101" width={size * 157 / 101} height={size} role={title ? "img" : undefined} aria-label={title} aria-hidden={title ? undefined : true}>
      <defs>
        <radialGradient id={blue} gradientUnits="userSpaceOnUse" cx="89.5" cy="78.5" r="48.5">
          <stop offset="0.7856" stopColor="#004F80" /><stop offset="0.8242" stopColor="#07649C" /><stop offset="0.9099" stopColor="#1C77B0" />
          <stop offset="0.9614" stopColor="#07649C" /><stop offset="1" stopColor="#004F80" />
        </radialGradient>
        <radialGradient id={violet} gradientUnits="userSpaceOnUse" cx="145.5" cy="78.5" r="48.5">
          <stop offset="0.7856" stopColor="#6A3FBF" /><stop offset="0.8242" stopColor="#8458D8" /><stop offset="0.9099" stopColor="#9668E2" />
          <stop offset="0.9614" stopColor="#8458D8" /><stop offset="1" stopColor="#6A3FBF" />
        </radialGradient>
      </defs>
      <circle cx="89.5" cy="78.5" r="43.3" fill="none" stroke={`url(#${blue})`} strokeWidth="10.4" />
      <circle cx="145.5" cy="78.5" r="43.3" fill="none" stroke={`url(#${violet})`} strokeWidth="10.4" />
      {/* the blue ring passes over the violet one here, so the rings link */}
      <path d="M131.325,89.707 A43.3,43.3 0 0 1 107.799,117.743" fill="none" stroke={`url(#${blue})`} strokeWidth="10.4" />
    </svg>
  );
}
