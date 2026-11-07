import { useState } from "react";
import { Button, Dialog } from "../../../components";
import { HINTS } from "../help";
import { Hint } from "../ui";

/** Cloud opt-in: off keeps every page on this server. Turning it on asks first, because it is a contract matter. */
export function OptInSwitch({ on, onChange }: { on: boolean; onChange: (on: boolean) => void }) {
  const [asking, setAsking] = useState(false);
  return (
    <div className="df-optin">
      <b id="df-optin-label">Cloud opt-in</b><Hint about="Cloud opt-in" text={HINTS.cloudOptIn} />
      <button type="button" role="switch" className="df-switch" aria-checked={on} aria-labelledby="df-optin-label"
        onClick={() => on ? onChange(false) : setAsking(true)} />
      <span className="df-muted">{on ? "On" : "Off"}</span>
      <Dialog open={asking} onClose={() => setAsking(false)} title="Turn on cloud opt-in?" width={520}
        footer={<><Button onClick={() => setAsking(false)}>Cancel</Button><Button kind="primary" onClick={() => { setAsking(false); onChange(true); }}>Turn on</Button></>}>
        <p style={{ margin: 0 }}>Weak pages and field extraction may then be sent to the cloud engines set up in OneConnect and OneMind.</p>
        <p style={{ margin: 0 }} className="df-text-warn"><b>Only turn this on for clients who agreed to it in their contract.</b></p>
      </Dialog>
    </div>
  );
}
