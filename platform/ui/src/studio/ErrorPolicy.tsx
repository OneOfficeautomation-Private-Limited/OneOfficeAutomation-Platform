import { useState } from "react";
import type { RetryPolicy } from "../api/client";
import { Field, Select, TextInput } from "../components";

/**
 * What happens when a step fails (command review C3, 9 Nov 2026), as UiPath's ContinueOnError and Retry Scope and Blue
 * Prism's Recover attempts: stop (a Try around the step, or On error, then handles it) or log it and go on; and trying
 * the step again a few times, waiting between tries, for temporary errors, any error or the errors named. The same
 * fields set the agent's default (Details, with no step selected); a step's own setting wins.
 */
export function RetryFields({ value, onChange, idPrefix }: { value?: RetryPolicy; onChange: (v: RetryPolicy | undefined) => void; idPrefix: string }) {
  const max = value?.max ?? 0;
  const which = value?.codes === undefined ? "temporary" : value.codes.length === 1 && value.codes[0] === "*" ? "any" : "these";
  const [codes, setCodes] = useState((value?.codes ?? []).filter((c) => c !== "*").join(", "));
  const put = (patch: Partial<RetryPolicy>) => {
    const next: RetryPolicy = { ...(value ?? {}), ...patch };
    if (!next.max) { onChange(undefined); return; }
    for (const k of Object.keys(next) as (keyof RetryPolicy)[]) if (next[k] === undefined) delete next[k];
    onChange(next);
  };
  return <>
    <Field label="Try again" layout="beside" hint="How many more times the step runs after it fails; 0 does not try again (up to 10).">{(id) =>
      <TextInput id={id} type="number" min={0} max={10} aria-label="Times to try again" value={String(max)}
        onChange={(e) => put({ max: Math.max(0, Math.min(10, Math.trunc(Number(e.target.value) || 0))) })} />}</Field>
    {max > 0 && <>
      <Field label="Wait between tries (seconds)" layout="beside" hint="Before each new try.">{(id) =>
        <TextInput id={id} type="number" min={0} step="0.1" value={String((value?.delayMs ?? 500) / 1000)}
          onChange={(e) => put({ delayMs: Math.max(0, Math.round((Number(e.target.value) || 0) * 1000)) })} />}</Field>
      <label className="st-check-line"><input type="checkbox" checked={value?.backoff === "exponential"}
        onChange={(e) => put({ backoff: e.target.checked ? "exponential" : undefined })} /> Wait twice as long each time</label>
      <Field label="For which errors" layout="beside" hint={which === "temporary" ? "Errors the toolkit marks as temporary: time-outs, a busy service, a dropped connection."
        : which === "any" ? "Any error the step raises." : "Error codes or kinds (NOT_FOUND, TIMEOUT, BROWSER.*), separated by commas."}>{(id) => <>
        <Select id={id} value={which} onChange={(e) => {
          const w = e.target.value;
          put({ codes: w === "temporary" ? undefined : w === "any" ? ["*"] : codes.split(",").map((c) => c.trim()).filter(Boolean) });
        }} options={[{ value: "temporary", label: "Temporary errors only" }, { value: "any", label: "Any error" }, { value: "these", label: "These errors…" }]} />
        {which === "these" && <TextInput id={idPrefix + "-codes"} aria-label="Errors to try again for" mono value={codes} placeholder="TIMEOUT, OBJECT_NOT_FOUND"
          onChange={(e) => setCodes(e.target.value)} onBlur={() => put({ codes: codes.split(",").map((c) => c.trim()).filter(Boolean) })} />}
      </>}</Field>
    </>}
  </>;
}

/** A step's own "If this step fails": go on or stop, and trying again (actions only: blocks are not tried again). */
export function StepErrorFields({ stepId, continueOnError, retry, canRetry, onChange }: { stepId: string; continueOnError?: boolean; retry?: RetryPolicy; canRetry: boolean;
  onChange: (patch: { continueOnError?: boolean; retry?: RetryPolicy | undefined }) => void }) {
  return <>
    <Field label="If it fails" layout="beside" hint={continueOnError ? "The error is written to the run log and the next step runs."
      : "The error goes to the Try around the step, or to On error; without either, the agent stops."}>{(id) =>
      <Select id={id} value={continueOnError ? "continue" : "stop"} onChange={(e) => onChange({ continueOnError: e.target.value === "continue" })}
        options={[{ value: "stop", label: "Stop (or go to Catch)" }, { value: "continue", label: "Log it and continue with the next step" }]} />}</Field>
    {canRetry && <RetryFields idPrefix={stepId} value={retry} onChange={(r) => onChange({ retry: r })} />}
  </>;
}
