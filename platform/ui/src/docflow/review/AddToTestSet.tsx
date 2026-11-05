import { useState } from "react";
import { Button, Dialog, Field, Select } from "../../components";
import type { DocFlowApi, TestSet } from "../api";
import { HINTS } from "../help";
import { Hint, reportError } from "../ui";

/**
 * Add to test set, on an approved document: its file and the approved values become a test document of a set of the
 * same document type. Small, beside the document's status.
 */
export function AddToTestSet({ api, documentId, docTypeId, docType }: { api: DocFlowApi; documentId: string; docTypeId: string; docType: string }) {
  const [sets, setSets] = useState<TestSet[] | null>(null);
  const [chosen, setChosen] = useState("");
  const [added, setAdded] = useState<TestSet | null>(null);
  const [saving, setSaving] = useState(false);
  const open = () => api.testSets(docTypeId).then((s) => { setSets(s); setChosen(s[0]?.id ?? ""); }, reportError);
  const add = async () => {
    setSaving(true);
    try { await api.addToTestSet(chosen, documentId); setAdded(sets!.find((s) => s.id === chosen)!); setSets(null); }
    catch (e) { reportError(e); } finally { setSaving(false); }
  };

  if (added) return <span className="df-small" role="status">Added to <a href={"#/testlab/sets/" + encodeURIComponent(added.id)}>{added.name}</a></span>;
  return (<>
    <Button kind="plain" icon="plus" onClick={open}>Add to test set</Button><Hint about="Add to test set" text={HINTS.addToTestSet} />
    <Dialog open={!!sets} onClose={() => setSets(null)} title="Add to test set" width={460}
      footer={<><Button onClick={() => setSets(null)}>Cancel</Button><Button kind="primary" disabled={!chosen || saving} onClick={add}>Add</Button></>}>
      {sets?.length ? (<>
        <p style={{ margin: 0 }}>The file and the values you approved become a test document with its right values.</p>
        <Field label="Test set">{(id) => <Select id={id} value={chosen} onChange={(e) => setChosen(e.target.value)}
          options={sets.map((s) => ({ value: s.id, label: `${s.name} (${s.itemCount} documents)` }))} />}</Field>
      </>) : (
        <p style={{ margin: 0 }}>No test set for {docType} yet. <a href="#/testlab/sets">Make one in the Test lab</a>, then come back.</p>
      )}
    </Dialog>
  </>);
}
