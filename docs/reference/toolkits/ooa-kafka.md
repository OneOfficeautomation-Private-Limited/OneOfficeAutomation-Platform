# Kafka

Toolkit `ooa.kafka`, version 1.0.0. Send messages to Kafka topics, read messages, and create or inspect topics through a Kafka REST Proxy.

[All toolkits](README.md)

## Contents

- **Messages**: Mark messages as read up to an offset, Send message, Read messages
- **Topics**: Create topic, Get topic info, List topics

## Messages

### Mark messages as read up to an offset

`ooa.kafka/commit_offsets` (action)

Record, for a consumer group, that one partition of a topic has been handled up to and including an offset, so the next read starts after it. Use it with Read messages and mark_as_read off to mark messages only once they are processed, or to skip ahead or go back. Run it while no other reader of the group is active. Needs a REST Proxy that supports consumer instances.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Endpoint address | `base_url` | Text | Yes |  | Main | Kafka REST Proxy address, e.g. https://pkc-12345.us-east-1.aws.confluent.cloud:443 or http://kafka-rest.internal:8082. |
| API key | `api_key` | Text | No |  | Main; choice Sign in with = API key and secret | API key or user name, used with api_secret. |
| API secret | `api_secret` | Credential (vault) | No |  | Main; choice Sign in with = API key and secret | API secret or password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = A bearer token | Bearer token from the vault, used instead of an API key and secret. |
| Consumer group | `consumer_group` | Text | Yes |  | Main | Reader group whose position is changed. Example: `ooa-order-agent` |
| Topic | `topic` | Text | Yes |  | Main | Topic of the messages. Example: `orders` |
| Partition | `partition` | Number | Yes |  | Main | Partition number, as shown in the partition column of Read messages. |
| Offset | `offset` | Number | Yes |  | Main | Offset of the last message handled in that partition; the group carries on with the message after it. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `next_offset` | Number | Offset the group reads next in that partition (offset + 1). |

*Time limit 60 s; needs: network.*

### Send message

`ooa.kafka/produce_message` (action)

Write one message to a topic, with an optional key and headers.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Endpoint address | `base_url` | Text | Yes |  | Main | Kafka REST Proxy address, e.g. https://pkc-12345.us-east-1.aws.confluent.cloud:443 or http://kafka-rest.internal:8082. |
| API key | `api_key` | Text | No |  | Main; choice Sign in with = API key and secret | API key or user name, used with api_secret. |
| API secret | `api_secret` | Credential (vault) | No |  | Main; choice Sign in with = API key and secret | API secret or password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = A bearer token | Bearer token from the vault, used instead of an API key and secret. |
| Topic | `topic` | Text | Yes |  | Main | Topic to write to. Example: `orders` |
| Partition | `partition` | Number | No |  | Main; tick Write to one partition | Write to this partition. Leave empty to let Kafka choose. |
| Key | `key` | Text | No |  | Main | Message key. Messages with the same key always go to the same partition, so they stay in order. |
| Value | `value` | Any | Yes |  | Main | Message content: text, or a dictionary or list sent as JSON. |
| Headers | `headers` | Dictionary | No |  | More options | Extra message headers, e.g. {"source": "oneofficeautomation"}. |
| Value format | `value_format` | Text | No | `json` | More options | Send the value as JSON or as plain text. One of: `json`, `text` |
| Cluster ID | `cluster_id` | Text | No |  | More options | Kafka cluster id. Leave empty to use the only (first) cluster the proxy serves. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `partition` | Number | Partition the message was written to. |
| `offset` | Number | Position of the message in that partition. |
| `timestamp` | Text | Time Kafka recorded for the message. |

*Time limit 60 s; needs: network.*

### Read messages

`ooa.kafka/read_messages` (action)

Read new messages from one or more topics as a consumer group, so the next read carries on where this one stopped. Needs a REST Proxy that supports consumer instances (self-managed Confluent REST Proxy).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Endpoint address | `base_url` | Text | Yes |  | Main | Kafka REST Proxy address, e.g. https://pkc-12345.us-east-1.aws.confluent.cloud:443 or http://kafka-rest.internal:8082. |
| API key | `api_key` | Text | No |  | Main; choice Sign in with = API key and secret | API key or user name, used with api_secret. |
| API secret | `api_secret` | Credential (vault) | No |  | Main; choice Sign in with = API key and secret | API secret or password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = A bearer token | Bearer token from the vault, used instead of an API key and secret. |
| Topics | `topics` | List | Yes |  | Main | Topics to read, e.g. ["orders"]. |
| Consumer group | `consumer_group` | Text | Yes |  | Main | Reader group name. Agents with the same group share the work and never get the same message twice. Example: `ooa-order-agent` |
| Maximum messages | `max_messages` | Number | No | `100` | Main | Stop once this many messages have arrived. The last batch is kept whole, so a few more can come back. |
| Wait (seconds) | `wait_seconds` | Number | No | `10` | Main | Stop when no new message arrives within this time. |
| Start from | `start_from` | Text | No | `earliest` | Main | For a new group only: start at the oldest message kept (earliest) or only new messages (latest). One of: `earliest`, `latest` |
| Mark as read | `mark_as_read` | Yes/No | No | Yes | Main | Remember what was read so the group does not read it again. |
| Value format | `value_format` | Text | No | `json` | More options | Read values as JSON or as plain text. One of: `json`, `text` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: topic, partition, offset, key, value. JSON values are shown as text. |
| `messages` | List | Each message as a dictionary; JSON values stay structured. |
| `count` | Number | Number of messages read. |

*Time limit 900 s; needs: network.*

## Topics

### Create topic

`ooa.kafka/create_topic` (action)

Create a topic with a number of partitions and optional settings such as how long messages are kept.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Endpoint address | `base_url` | Text | Yes |  | Main | Kafka REST Proxy address, e.g. https://pkc-12345.us-east-1.aws.confluent.cloud:443 or http://kafka-rest.internal:8082. |
| API key | `api_key` | Text | No |  | Main; choice Sign in with = API key and secret | API key or user name, used with api_secret. |
| API secret | `api_secret` | Credential (vault) | No |  | Main; choice Sign in with = API key and secret | API secret or password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = A bearer token | Bearer token from the vault, used instead of an API key and secret. |
| Topic | `topic` | Text | Yes |  | Main | Name of the new topic. |
| Partitions | `partitions` | Number | No | `6` | Main | Number of partitions; more partitions let more readers work in parallel. |
| Replication factor | `replication_factor` | Number | No |  | Main | Copies of each message kept on different servers. Leave empty for the cluster default. |
| Settings | `settings` | Dictionary | No |  | More options | Topic settings, e.g. {"retention.ms": "604800000", "cleanup.policy": "delete"}. |
| Fail if exists | `fail_if_exists` | Yes/No | No | No | More options | Stop with an error if the topic is already there, instead of carrying on. |
| Cluster ID | `cluster_id` | Text | No |  | More options | Kafka cluster id. Leave empty to use the only (first) cluster the proxy serves. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `created` | Yes/No | True if the topic was created, false if it was already there. |

*Time limit 60 s; needs: network.*

### Get topic info

`ooa.kafka/get_topic` (action)

Read a topic's partition count, replication and settings.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Endpoint address | `base_url` | Text | Yes |  | Main | Kafka REST Proxy address, e.g. https://pkc-12345.us-east-1.aws.confluent.cloud:443 or http://kafka-rest.internal:8082. |
| API key | `api_key` | Text | No |  | Main; choice Sign in with = API key and secret | API key or user name, used with api_secret. |
| API secret | `api_secret` | Credential (vault) | No |  | Main; choice Sign in with = API key and secret | API secret or password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = A bearer token | Bearer token from the vault, used instead of an API key and secret. |
| Topic | `topic` | Text | Yes |  | Main | Topic to look up. |
| Cluster ID | `cluster_id` | Text | No |  | More options | Kafka cluster id. Leave empty to use the only (first) cluster the proxy serves. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `info` | Dictionary | topic, partitions, replication_factor, internal. |
| `settings` | Dictionary | Topic settings, e.g. retention.ms and cleanup.policy. |

*Time limit 60 s; safe to repeat; needs: network.*

### List topics

`ooa.kafka/list_topics` (action)

Return the topics in the cluster.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Endpoint address | `base_url` | Text | Yes |  | Main | Kafka REST Proxy address, e.g. https://pkc-12345.us-east-1.aws.confluent.cloud:443 or http://kafka-rest.internal:8082. |
| API key | `api_key` | Text | No |  | Main; choice Sign in with = API key and secret | API key or user name, used with api_secret. |
| API secret | `api_secret` | Credential (vault) | No |  | Main; choice Sign in with = API key and secret | API secret or password from the vault. |
| Access token | `access_token` | Credential (vault) | No |  | Main; choice Sign in with = A bearer token | Bearer token from the vault, used instead of an API key and secret. |
| Include internal | `include_internal` | Yes/No | No | No | Main | Also list Kafka's own internal topics, whose names usually start with an underscore. |
| Cluster ID | `cluster_id` | Text | No |  | More options | Kafka cluster id. Leave empty to use the only (first) cluster the proxy serves. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `table` | Table | Columns: topic, partitions, replication_factor, internal. |
| `names` | List | Topic names only. |

*Time limit 60 s; safe to repeat; needs: network.*
