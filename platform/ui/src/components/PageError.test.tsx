import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useEffect, useState } from "react";
import { afterEach, describe, expect, it } from "vitest";
import { PageError, PageRetry } from "./PageError";

/** Error batch E2b: a page that could not load says why, and Retry loads it again. */
describe("page error with Retry", () => {
  afterEach(cleanup);

  it("Retry mounts the page again, so it loads again", () => {
    let loads = 0;
    function Page() {
      const [error, setError] = useState<string | null>(null);
      useEffect(() => { loads++; setError(loads === 1 ? "One CommandCenter is not answering. Check that it is running." : null); }, []);
      return error ? <PageError text={error} /> : <p>Loaded</p>;
    }
    function App() {
      const [n, setN] = useState(0);
      return <PageRetry.Provider value={() => setN(n + 1)}><Page key={n} /></PageRetry.Provider>;
    }
    render(<App />);
    expect(screen.getByRole("alert").textContent).toBe("One CommandCenter is not answering. Check that it is running.");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(screen.getByText("Loaded")).toBeTruthy();
    expect(loads).toBe(2);
  });

  it("offers no Retry where the app gives no way to reload", () => {
    render(<PageError text="Could not load." />);
    expect(screen.getByRole("alert").textContent).toBe("Could not load.");
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull();
  });
});
