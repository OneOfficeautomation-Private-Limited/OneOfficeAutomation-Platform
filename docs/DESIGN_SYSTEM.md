# ONEOFFICEAUTOMATION design system

One visual language for **One CommandCenter** (the console) and **One AgentStudio**. It is plain CSS: no framework,
no build step, no external fonts or CDNs (the console's content-security policy is `default-src 'self'`).

The new Studio (`platform/ui`, [architecture/12-ui-redesign.md](architecture/12-ui-redesign.md)) uses the denser,
higher-contrast look approved on 2026-10-29: its colours and sizes are in `platform/ui/src/theme.css`, its components in
`platform/ui/src/components`. The Command Center moves to it after Studio.

## Files

| File | What it holds | Served at |
|---|---|---|
| `platform/control-room/src/main/resources/ooa-ui/ooa-tokens.css` | Design tokens: palette, semantic colours, spacing, radius, shadows, type, sizes, layers, motion; light and dark | `/ooa-tokens.css` |
| `platform/control-room/src/main/resources/ooa-ui/ooa-components.css` | Shared components (see below) | `/ooa-components.css` |
| `platform/control-room/src/main/resources/console/cr.css` | Console layout and pages | `/cr.css` |
| `platform/ui/src/theme.css`, `src/components/components.css`, `src/studio/studio.css` | Studio's tokens (light, dark, compact), components and screens | built into Studio's page |

There is one copy of the console's shared files: `ControlRoomServer.serveStatic` serves any `ooa-*.css` from
`/ooa-ui/`. Studio (`platform/ui`) has its own tokens in `src/theme.css`; Classic Studio, which
served the console's sheets too, was removed on 2026-10-30.

Load order in each `index.html`: tokens, then components, then the application sheet. Every sheet starts with a
numbered table of contents; find a rule by its section heading.

## Tokens

Three layers, in `ooa-tokens.css`:

1. **Primitives** (`--ooa-gray-*`, `--ooa-blue-*`, `--ooa-navy-*`, `--ooa-green/red/amber/violet-*`). Raw values only;
   components never use them directly (the exceptions are a few fixed hues such as the logo gradient).
2. **Semantic tokens**, which components use:
   - surfaces: `--surface-canvas` (page), `--surface-1` (cards, panels, tables, dialogs), `--surface-2` (table and
     panel headers), `--surface-hover`, `--surface-selected`, `--surface-sunken` (code, tracks), `--surface-inverse`
     (toasts, tooltips), `--overlay` (behind dialogs)
   - text: `--text-1` (primary, 15:1 on white), `--text-2` (secondary, 7:1), `--text-3` (hints and placeholders,
     at least 4.5:1), `--text-on-accent`, `--text-inverse`
   - borders: `--border-1` (dividers), `--border-2` (card and table outlines), `--border-control` (inputs, 3:1 against
     white, as WCAG asks of control boundaries), `--border-strong`
   - accent: one brand blue, `--accent`, `--accent-hover`, `--accent-active`, `--accent-subtle`, `--accent-subtle-2`,
     `--accent-text`; `--brand-2` (the logo violet) is for decoration only
   - status, each with a text colour, a subtle background and a solid fill: `--success*`, `--danger*`, `--warning*`,
     `--info*`, `--special*` (locked, vault), `--neutral*`
   - frame (the dark top bar, menu and Studio rail): `--frame-bg`, `--frame-bg-2`, `--frame-border`, `--frame-text`,
     `--frame-text-2`, `--frame-hover`, `--frame-active`, `--frame-accent`
   - focus: `--focus-ring` (a 2 px ring with a gap, for every keyboard-focusable element), `--focus-ring-inset`
3. **Scales**
   - spacing on a 4 px grid: `--space-1` 4, `--space-2` 8, `--space-3` 12, `--space-4` 16, `--space-5` 20,
     `--space-6` 24, `--space-8` 32, `--space-10` 40, `--space-12` 48 (plus `--space-05` 2 and `--space-15` 6)
   - radius: `--radius-xs` 3, `-sm` 4, `-md` 6 (controls), `-lg` 8 (cards, tables), `-xl` 12 (dialogs), `-pill`
   - elevation: `--shadow-xs` (resting cards), `-sm`, `-md` (hover), `-lg` (menus, toasts), `-overlay` (dialogs, drawer)
   - type: `--font-sans` (Segoe UI Variable / Segoe UI / system), `--font-display`, `--font-mono` (Cascadia Mono /
     Consolas); sizes `--text-2xs` 10.5 … `--text-3xl` 28 (body: 14 px in the console, 13 px in Studio); weights
     400/500/600/700; tabular numbers in tables and metrics
   - sizes: `--control-h` 32 px (default buttons and inputs), `--control-h-sm` 26, `--control-h-lg` 38
   - layers: `--z-sticky` < `--z-dropdown` < `--z-drawer` < `--z-float` < `--z-modal` < `--z-toast` < `--z-popover`
   - motion: `--dur-fast` 100 ms, `--dur` 160 ms, `--dur-slow` 240 ms, `--ease`; everything stops under
     `prefers-reduced-motion`

**Themes.** Light is the default. The dark theme redefines only semantic tokens, under `[data-theme="dark"]`
(console) and `[data-theme="midnight"]` (Studio's One Dark). The console follows the operating system until the user
picks a theme. Studio's colour themes (`teal`, `graphite`, `sunrise`) change only the accent and frame tokens in
`studio.css` section 1.

**Compatibility names.** Older rules used short names (`--panel`, `--ink`, `--muted`, `--line`, `--tint`, `--ok`,
`--frame`, …). Section 5 of the tokens file maps them to the semantic tokens so nothing breaks; new rules should use
the semantic names.

## Components (`ooa-components.css`)

| Component | Selectors | Notes |
|---|---|---|
| Buttons | `button`, `a.button-link`, `.primary`, `.danger`, `.link` / `.link-btn`, `.small`, `.btn-group` | Secondary (outlined) by default. One `.primary` per area. `.danger` stays outlined with red text until hovered. Height comes from padding and line height, so explicit icon-button sizes still win. |
| Form controls | `input`, `select`, `textarea`, checkboxes, `.invalid`, `.req`, `fieldset` | 32 px, `--border-control` outline, accent border and soft ring on focus, quiet disabled and read-only states. |
| Cards | `.card`, `.card-box`, `.card-head` | 8 px radius, `--border-2` outline, `--shadow-xs`. |
| Tables | `.table-wrap table`, `table.list`, `th .th-sort`, `tr.clickable`, `.empty-cell` | Sticky `--surface-2` header in 12 px semibold, 8×12 px cells, row hover, keyboard focus outline on rows, tabular numbers. |
| Pills and badges | `.pill` (+ `success`, `failed`, `running`, `queued`, `paused`, `stopped`, `locked`…), `.badge`, `.count`, `.tag`, `.chips` | Status pills carry a dot, so state never relies on colour alone. |
| Tabs and segments | `.tabs .tab`, `.tab`, `.ctab`, `.seg` | Tabs underline the active item in the accent; segmented controls show the chosen option as a raised white button. |
| Toolbars | `.toolbar`, `.row-actions`, `.spacer` | Wrapping rows with an 8 px gap. |
| Menus and popovers | `.menu`, `.ctx-menu`, `.popover`, `.var-pop`, `.menu-item`, `.qa-item` | `--shadow-lg`, 8 px radius, 4 px inset items. |
| Dialogs | `.modal` / `.modal-card` (console), `.dialog-backdrop` / `.dialog` (Studio) | 12 px radius, title in 18 px, a footer bar with the first action on the right. |
| Drawer | `.drawer`, `.drawer-panel`, `.drawer-head`, `.drawer-body`, `.icon-close` | Side sheet for details (console runs, users, agents). |
| Toasts | `.toast`, `.toast.error` | Inverse surface with a green or red leading edge. |
| States | `.empty-state`, `.canvas-empty`, `.var-empty`, `.spinner`, `.skeleton`, `.error-box`, `.error`, `.note`, `.note.warn` | Empty: dashed outline, centred message. Loading: spinner and shimmer. Error: tinted box with the error code in mono. |
| Utilities | `.muted`, `.faint`, `.small`, `.mono`, `.sr-only`, `.ok-text` | |

Page-level states in the console need no JavaScript: while `go()` shows "Loading..." the view shows a spinner, and a
page that fails shows its single error line as an error panel (`cr.css` section 14).

## Layout

- **Console**: a dark charcoal frame (44 px top bar, 220 px menu with monochrome icons; only the current page carries
  the accent) around a light, dense work area: 13 px body text, 16–20 px padding up to 1600 px wide, compact tables
  with small upper-case headers, and 18 px page titles (`cr.css` section 15). Under 760 px the menu collapses to icons
  and the brand text hides.
- **Studio**: One Dark (`midnight`, blue-grey charcoal with the same blue accent) is the default theme, and Compact the
  default density, with a 42 px top bar and a 44 px rail. Explorer, canvas and Action settings keep their minimum widths (the
  UI test checks them). Steps keep their action-group colour as a 3 px edge and icon tile, which helps scanning long
  agents. Under 700 px the left panel opens over the canvas, Action settings hide and the top bar wraps.

## Accessibility

- Text tokens meet WCAG AA on their surfaces in both themes; control borders meet 3:1.
- Every focusable element shows `--focus-ring` on keyboard focus (`:focus-visible`); mouse clicks show no ring.
- Status is shown by text and a dot, not by colour alone; dark mode re-tunes status colours for contrast.
- Motion is short and is switched off under `prefers-reduced-motion`.

## Adding a style

1. Use semantic tokens (`var(--surface-1)`, `var(--space-3)`, `var(--radius-lg)`), never raw hex values.
2. If two applications need it, add it to `ooa-components.css`; otherwise put it in the right numbered section of
   `cr.css` or `studio.css`.
3. Check it in light and dark, at desktop and phone widths, and with the keyboard.
