import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { useFullTextOnHover } from "./useFullTextOnHover";

function Table() {
  useFullTextOnHover();
  return <table><tbody><tr><td data-testid="cut">RUN.INPUT_MISSING: Required input 'password' has no value</td><td data-testid="fits">ok</td><td data-testid="own" title="Mine">x</td></tr></tbody></table>;
}

/** Improvement list 13 Nov 2026, item 11: text cut off with "…" shows in full on hover. */
describe("full text on hover", () => {
  afterEach(cleanup);

  it("gives a cut-off cell its text as its title, and leaves cells that fit or have their own title alone", () => {
    render(<Table />);
    const cut = screen.getByTestId("cut"), fits = screen.getByTestId("fits"), own = screen.getByTestId("own");
    Object.defineProperty(cut, "scrollWidth", { value: 400 }); Object.defineProperty(cut, "clientWidth", { value: 120 });
    Object.defineProperty(fits, "scrollWidth", { value: 20 }); Object.defineProperty(fits, "clientWidth", { value: 60 });
    Object.defineProperty(own, "scrollWidth", { value: 400 }); Object.defineProperty(own, "clientWidth", { value: 10 });
    fireEvent.mouseOver(cut); fireEvent.mouseOver(fits); fireEvent.mouseOver(own);
    expect(cut.getAttribute("title")).toBe("RUN.INPUT_MISSING: Required input 'password' has no value");
    expect(fits.hasAttribute("title")).toBe(false);
    expect(own.getAttribute("title")).toBe("Mine");
  });
});
