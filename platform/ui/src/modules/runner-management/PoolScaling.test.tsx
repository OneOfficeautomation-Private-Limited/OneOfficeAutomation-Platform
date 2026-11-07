import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { CrApi } from "../../api/cr";
import { PoolScalingDialog } from "./PoolScaling";

describe("pool auto-scaling (G52)", () => {
  afterEach(cleanup);
  it("shows what the pool would ask for, and saves the cloud's details for the chosen provider", async () => {
    const puts: unknown[] = [];
    const f = (async (_url: string, init: RequestInit = {}) => {
      const now = { queued: 4, running: 1, refused_recently: 0, online: 2, desired: 3 };
      if (init.method === "PUT") { puts.push(JSON.parse(init.body as string)); return new Response(JSON.stringify({ scaling: puts[0], now }), { status: 200 }); }
      return new Response(JSON.stringify({ scaling: { enabled: false }, now }), { status: 200 });
    }) as unknown as typeof fetch;
    const saved: string[] = [];
    render(<PoolScalingDialog api={new CrApi("/api/", f)} pool={{ id: "pool_1", name: "Cloud", devices: [] }} onClose={() => {}} onSaved={(t) => saved.push(t)} />);
    expect((await screen.findByRole("status")).textContent).toContain("It would ask for 3 machines");
    fireEvent.click(screen.getByRole("checkbox", { name: /Scale this pool/ }));
    fireEvent.change(screen.getByLabelText("Where the size goes"), { target: { value: "aws_asg" } });
    fireEvent.change(screen.getByLabelText("Auto Scaling group"), { target: { value: "ooa-runners" } });
    fireEvent.change(screen.getByLabelText("Most machines"), { target: { value: "6" } });
    expect(screen.queryByLabelText("Webhook address")).toBeNull();
    await act(async () => { fireEvent.click(screen.getByRole("button", { name: "Save" })); });
    expect(puts[0]).toMatchObject({ enabled: true, provider: "aws_asg", group: "ooa-runners", max: 6, min: 0 });
    expect(saved).toEqual(["Saved the auto-scaling of Cloud."]);
  });
});
