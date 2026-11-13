/**
 * "Learn more" on an error (13 Nov 2026 round 3, item 6): the help catalogue (shell/helpCatalogue.ts) says where an
 * error code's page is; components ask here, so they need not know the catalogue.
 */
let resolve: (code: string) => string | undefined = () => undefined;
export function setErrorLinkResolver(f: (code: string) => string | undefined) { resolve = f; }
/** The documentation address for an error code such as RUN.INPUT_MISSING, if the catalogue has one. */
export function errorLink(code: string): string | undefined { return resolve(code); }
/** The error code at the start of a message ("RUN.INPUT_MISSING: ..."), if there is one. */
export function codeIn(text: string): string | undefined { return /^([A-Z][A-Z0-9]*(?:\.[A-Z0-9_]+)+)\b/.exec(text.trim())?.[1]; }
