# Business rules

Toolkit `ooa.rules`, version 1.0.0. Decide with a decision table kept as a CSV file (or a table): conditions in in: columns, results in out: columns.

[All toolkits](README.md)

## Contents

- **Decision tables**: Evaluate decision table

## Decision tables

### Evaluate decision table

`ooa.rules/evaluate_decision_table` (action)

Find the rules of a decision table that match the inputs and return what they give.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| File | `file` | File path | No |  | Main; choice Decision table = A CSV file | A CSV file with a header row of in:NAME, out:NAME and rule columns. Example: `Rules/approval limits.csv`; Shown when: `{"table": [""]}` |
| Table | `table` | Table | No |  | Main; choice Decision table = A table value | The decision table as a table value, with the same columns. Shown when: `{"file": [""]}` |
| Inputs | `inputs` | Dictionary | Yes |  | Main | The values the in: columns test, by name. Example: `{"amount": 4200, "region": "EU"}` |
| Hit policy | `hit_policy` | Text | No | `first` | Main | When several rules match. One of: `first`, `unique`, `collect` |
| Default outputs | `default_outputs` | Dictionary | No |  | More options | What to give when no rule matches. |
| Fail when none | `fail_when_none` | Yes/No | No | No | More options | Fail the step when no rule matches. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `outputs` | Dictionary | The out: values of the matching rule (first and unique); the first rule's for collect. |
| `matched` | Yes/No | True when a rule matched. |
| `rule` | Text | The matching rule's name (its rule column, else its row number). |
| `all` | List | With collect: the outputs of every matching rule, in order. |

*Time limit 60 s; safe to repeat; needs: filesystem.*
