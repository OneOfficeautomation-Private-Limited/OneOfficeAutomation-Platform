import { Button } from "./Button";
import { Dialog } from "./Dialog";
import { usePersistent } from "./usePersistent";
import { keyOf, shortcutsFor } from "./shortcuts";

/**
 * The Keyboard shortcuts dialog (? or the Help menu), grouped by area, as AA's editor lists its shortcuts. It holds the
 * per-person switch for single-character shortcuts (WCAG 2.1.4), kept in this browser.
 */
export function ShortcutsDialog({ app, onClose }: { app: "studio" | "console"; onClose: () => void }) {
  const [single, setSingle] = usePersistent("display.singleKeys", true);
  return (
    <Dialog open title="Keyboard shortcuts" width={680} onClose={onClose} footer={<><span className="st-bar-fill" /><Button kind="primary" onClick={onClose}>Close</Button></>}>
      <label className="st-check-line ui-keys-switch"><input type="checkbox" checked={single} onChange={(e) => setSingle(e.target.checked)} />
        Single-key shortcuts ([, ], / and ?). Switch them off if you use speech input or press keys by mistake.</label>
      <div className="ui-keys">
        {shortcutsFor(app).map(([area, list]) => <section key={area} aria-label={area}>
          <h3>{area}</h3>
          <table className="st-table"><tbody>{list.map((s) => <tr key={s.id}><td>{s.label}</td>
            <td className="ui-keys-k">{keyOf(s.id).split(" or ").map((k, i) => <span key={k}>{i > 0 && " or "}<kbd>{k}</kbd></span>)}</td></tr>)}</tbody></table>
        </section>)}
      </div>
      <p className="st-muted">Keys work while the page has focus; text fields keep their own. Keys the browser keeps for itself are never used.</p>
    </Dialog>
  );
}
