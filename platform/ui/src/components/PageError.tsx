import { createContext, useContext } from "react";

/**
 * A page that could not load (error batch E2b, 13 Nov 2026): the red bar says what happened and what to do, and Retry
 * loads the page again. The app gives the way to reload (PageRetry); a page shown without one gets no Retry.
 */
export const PageRetry = createContext<(() => void) | null>(null);

export function PageError({ text }: { text: string }) {
  const retry = useContext(PageRetry);
  return (
    <div className="ui-page-error">
      <p className="st-alert" role="alert">{text}</p>
      {retry && <button type="button" className="ui-btn ui-btn-secondary ui-page-retry" onClick={retry}>Retry</button>}
    </div>
  );
}
