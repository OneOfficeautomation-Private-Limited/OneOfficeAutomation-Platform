import { createContext, useContext } from "react";
import { codeIn, errorLink } from "./errorLink";

/**
 * A page that could not load (error batch E2b, 13 Nov 2026): the red bar says what happened and what to do, and Retry
 * loads the page again. The app gives the way to reload (PageRetry); a page shown without one gets no Retry.
 */
export const PageRetry = createContext<(() => void) | null>(null);

export function PageError({ text }: { text: string }) {
  const retry = useContext(PageRetry);
  const code = codeIn(text), more = code ? errorLink(code) : undefined;   // round 3, item 6: Learn more on a coded error
  return (
    <div className="ui-page-error">
      <p className="st-alert" role="alert">{text}{more && <> <a className="ui-learn-more" href={more} target="_blank" rel="noopener noreferrer">Learn more</a></>}</p>
      {retry && <button type="button" className="ui-btn ui-btn-secondary ui-page-retry" onClick={retry}>Retry</button>}
    </div>
  );
}
