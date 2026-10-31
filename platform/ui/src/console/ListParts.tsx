import { Menu, type MenuItem } from "../components";

/**
 * Small parts of the design plan's list pattern (step 3), shared by the list pages: the refresh icon button at the end
 * of the toolbar, and the ⋯ menu at the end of each row.
 */
export function RefreshButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" className="cc-list-icon" aria-label="Refresh" title="Refresh" onClick={onClick}>
      <svg aria-hidden="true" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2"><path d="M21 12a9 9 0 1 1-3-6.7L21 8" /><path d="M21 3v5h-5" /></svg></button>
  );
}

export function RowMenu({ label, items }: { label: string; items: MenuItem[] }) {
  return (
    <Menu label={label} align="end" items={items}
      trigger={<button type="button" className="cc-list-icon is-row" aria-label={label} title="Actions">
        <svg aria-hidden="true" width="15" height="15" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></svg></button>} />
  );
}

/**
 * A row click opens the row, unless it landed on a tick box, a button or a link inside it, or outside the row: a row
 * menu's items sit in a portal, and React passes their clicks up to the row too.
 */
export const rowClick = (open: () => void) => (e: { target: EventTarget; currentTarget: EventTarget }) => {
  const t = e.target as HTMLElement;
  if ((e.currentTarget as Node).contains(t) && !t.closest("input, button, a, select")) open();
};
