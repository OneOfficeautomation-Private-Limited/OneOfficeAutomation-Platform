# Communications mining

Toolkit `ooa.comms`, version 1.0.0. Find the topics in many e-mails, tickets or messages, and route each message to a queue by keywords.

[All toolkits](README.md)

## Contents

- **Communications mining**: Discover topics, Route messages

## Communications mining

### Discover topics

`ooa.comms/discover_topics` (action)

Group many messages (e-mails, tickets, chats) into topics by the words they use: each topic's keywords, size and examples, and each message's topic.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| File | `file` | File path | No |  | Main; choice Messages = A CSV file | A CSV file with a header row. Example: `mail/inbox.csv`; Shown when: `{"table": [""]}` |
| Table | `table` | Table | No |  | Main; choice Messages = A table | The messages as a table (for example from E-mail: Get messages). Shown when: `{"file": [""]}` |
| Text columns | `text_columns` | Text | No | `subject,body` | Main | The columns with the text, comma separated. |
| ID column | `id_column` | Text | No |  | Main | The column with the message id (empty: the row number). |
| Topics | `topics` | Number | No | `0` | More options | How many topics (0: chosen from the number of messages). Range: 0 to 50 |
| Minimum word count | `min_word_count` | Number | No | `2` | More options | Leave out words used in fewer messages than this. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `topics` | Table | topic, label (its top words), keywords, messages, share (%), examples (message ids); largest first. |
| `assignments` | Table | id, topic, label, score (0-1, how close the message is to its topic). |
| `rules` | Dictionary | Topic label to its keywords: a start for Route messages (rename the labels to your queues). |
| `messages` | Number | Messages read. |

*Time limit 900 s; safe to repeat; needs: filesystem.*

### Route messages

`ooa.comms/route_messages` (action)

Give each message the queue whose keywords or phrases it matches best (a subject match counts double); messages that match none go to the default queue.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| File | `file` | File path | No |  | Main; choice Messages = A CSV file | A CSV file with a header row. Example: `mail/inbox.csv`; Shown when: `{"table": [""]}` |
| Table | `table` | Table | No |  | Main; choice Messages = A table | The messages as a table. Shown when: `{"file": [""]}` |
| Rules | `rules` | Dictionary | Yes |  | Main | Queue to its keywords or phrases (a list). Discover topics gives a start. Example: `{"Invoices": ["invoice", "payment", "overdue"], "Returns": ["return", "refund", "damaged"]}` |
| Text columns | `text_columns` | Text | No | `subject,body` | Main | The columns with the text, comma separated. |
| ID column | `id_column` | Text | No |  | Main | The column with the message id (empty: the row number). |
| Subject column | `subject_column` | Text | No | `subject` | Main | Matches in this column count double (empty: none). |
| Default queue | `default_queue` | Text | No | `Unsorted` | More options | Where messages that match no queue go. |
| Minimum score | `min_score` | Number | No | `1` | More options | Matches a queue needs to win. Range: 1 to  |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `routed` | Table | id, queue, score, matched (the keywords found); in the messages' order. Add it to a queue with Work queues: Add work items from table. |
| `counts` | Dictionary | Queue to how many messages went to it. |

*Time limit 600 s; safe to repeat; needs: filesystem.*
