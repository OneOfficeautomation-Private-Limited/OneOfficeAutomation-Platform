import { useState } from "react";
import { Icon } from "../components";
import { useHelpLinks } from "./helpCatalogue";

/**
 * Docs and Video for a help key (round 3, item 6), the same two small links everywhere: Docs opens the documentation in a
 * new tab; Video plays beside the page so people keep their place, with Open in a new tab. `compact` shows icons only
 * (in tight spots such as a command in the Actions list), with the words on hover.
 */
export function HelpLinks({ k, compact = false, label, long = false }: { k: string; compact?: boolean; label?: string; long?: boolean }) {
  const links = useHelpLinks(k);
  const [playing, setPlaying] = useState(false);
  const what = label ? " for " + label : "";
  return (
    <span className={"st-help-links" + (compact ? " is-compact" : "")}>
      {links.doc && <a className="st-help-link" href={links.doc} target="_blank" rel="noopener noreferrer" title={"Read the documentation" + what}
        aria-label={"Docs" + what} onClick={(e) => e.stopPropagation()}><Icon name="doc" size={13} />{!compact && (long ? "Read the documentation" : "Docs")}</a>}
      {links.video && <button type="button" className="st-help-link" title={"Watch how to use it" + what} aria-label={"Video" + what}
        onClick={(e) => { e.stopPropagation(); setPlaying(true); }}><Icon name="play" size={13} />{!compact && (long ? "Watch how to use it (video)" : "Video")}</button>}
      {playing && links.video && <VideoPanel src={links.video} title={label ?? "How to use it"} onClose={() => setPlaying(false)} />}
    </span>
  );
}

function VideoPanel({ src, title, onClose }: { src: string; title: string; onClose: () => void }) {
  return (
    <aside className="st-video-panel" role="complementary" aria-label={"Video: " + title} onKeyDown={(e) => { if (e.key === "Escape") onClose(); }}>
      <div className="st-video-head">
        <strong>{title}</strong><span style={{ flex: 1 }} />
        <a href={src} target="_blank" rel="noopener noreferrer">Open in a new tab</a>
        <button type="button" className="st-top-icon" aria-label="Close the video" title="Close" onClick={onClose}><Icon name="close" size={14} /></button>
      </div>
      <iframe src={src} title={"Video: " + title} allow="fullscreen; picture-in-picture" referrerPolicy="no-referrer" />
    </aside>
  );
}
