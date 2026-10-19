# OOA Flow conformance suite

Language-neutral test cases that every Runner implementation (Python reference,
Java production Runner) must pass identically. Each case is one JSON file:

| Field | Meaning |
| --- | --- |
| `flow` | An OOA Flow v1 document |
| `inputs` | Run inputs. `{"$credential": "test/app/password"}` must resolve to a secret with value `s3cr3t` |
| `expect.status` | `success`, `failed` or `stopped` |
| `expect.outputs` | Exact output values after JSON serialisation (Numbers compare numerically) |
| `expect.errorCode` / `expect.errorStepId` | Code and step of the run error, when failed |

Harness requirements: first-party packages at their 1.x versions, an in-memory vault
with locker `test`, credential `app`, attribute `password` = `s3cr3t`, and a working
directory with no `does/` folder. Cases are additive: never change an existing case's
expectations within ooa.flow/v1.
