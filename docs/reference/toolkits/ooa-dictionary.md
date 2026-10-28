# Dictionary

Toolkit `ooa.dictionary`, version 1.0.0. Put, get and remove key/value pairs.

[All toolkits](README.md)

## Contents

- **Dictionary**: Get, Get keys, Merge, Put, Remove, Get size, Get values
- **Conditions**: Contains key, Contains value, Dictionary is empty, Key has value

## Dictionary

### Get

`ooa.dictionary/get` (action)

Read the value for a key, or a default when the key is missing.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Dictionary | `dictionary` | Dictionary | Yes |  | Main | Source dictionary. |
| Key | `key` | Text | Yes |  | Main | Key. |
| Default | `default` | Any | No |  | More options | Value to return when the key is missing. Shown when: `{"fail_if_missing": ["false"]}` |
| Ignore case | `ignore_case` | Yes/No | No | No | More options | Find the key without regard to upper/lower case. |
| Fail if missing | `fail_if_missing` | Yes/No | No | No | More options | Fail with DICT.KEY_NOT_FOUND instead of returning the default. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | Any | Value or default. |
| `found` | Yes/No | True when the key exists. |

*Time limit 60 s; safe to repeat.*

### Get keys

`ooa.dictionary/keys` (action)

List all keys.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Dictionary | `dictionary` | Dictionary | Yes |  | Main | Source dictionary. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `keys` | List | Keys in insertion order. |

*Time limit 60 s; safe to repeat.*

### Merge

`ooa.dictionary/merge` (action)

Combine two dictionaries; for keys in both, keep the second value unless overwrite is off.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Dictionary | `dictionary` | Dictionary | Yes |  | Main | First dictionary. |
| Other | `other` | Dictionary | Yes |  | Main | Dictionary to merge in. |
| Overwrite | `overwrite` | Yes/No | No | Yes | Main | Let values from the second dictionary replace existing ones. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dictionary` | Dictionary | Merged dictionary. |

*Time limit 60 s; safe to repeat.*

### Put

`ooa.dictionary/put` (action)

Add or replace the value for a key.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Dictionary | `dictionary` | Dictionary | Yes |  | Main | Source dictionary. |
| Key | `key` | Text | Yes |  | Main | Key. |
| Value | `value` | Any | No |  | Main | Value. |
| Only if missing | `only_if_missing` | Yes/No | No | No | More options | Keep the existing value when the key is already there. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dictionary` | Dictionary | Updated dictionary. |
| `previous` | Any | The value the key had before, empty when the key is new. |
| `existed` | Yes/No | True when the key was already there. |

*Time limit 60 s; safe to repeat.*

### Remove

`ooa.dictionary/remove` (action)

Remove a key.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Dictionary | `dictionary` | Dictionary | Yes |  | Main | Source dictionary. |
| Key | `key` | Text | Yes |  | Main | Key to remove. |
| Fail if missing | `fail_if_missing` | Yes/No | No | No | More options | Fail with DICT.KEY_NOT_FOUND when the key is not there. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `dictionary` | Dictionary | Updated dictionary. |
| `removed` | Any | The value that was removed, empty when the key was not there. |
| `found` | Yes/No | True when the key was there. |

*Time limit 60 s; safe to repeat.*

### Get size

`ooa.dictionary/size` (action)

Number of keys.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Dictionary | `dictionary` | Dictionary | Yes |  | Main | Source dictionary. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `count` | Number | Number of keys. |

*Time limit 60 s; safe to repeat.*

### Get values

`ooa.dictionary/values` (action)

List all values.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Dictionary | `dictionary` | Dictionary | Yes |  | Main | Source dictionary. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `values` | List | Values in insertion order. |

*Time limit 60 s; safe to repeat.*

## Conditions

Used in If, Loop while and Wait until steps.

### Contains key

`ooa.dictionary/contains_key` (condition)

True when the dictionary has the key.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Dictionary | `dictionary` | Dictionary | Yes |  | Main | Dictionary. |
| Key | `key` | Text | Yes |  | Main | Key. |

### Contains value

`ooa.dictionary/contains_value` (condition)

True when any key of the dictionary holds this value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Dictionary | `dictionary` | Dictionary | Yes |  | Main | Dictionary. |
| Value | `value` | Any | Yes |  | Main | Value to look for. |

### Dictionary is empty

`ooa.dictionary/is_empty` (condition)

True when the dictionary has no keys.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Dictionary | `dictionary` | Dictionary | Yes |  | Main | Dictionary. |

### Key has value

`ooa.dictionary/key_has_value` (condition)

True when the key exists and holds this value.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Dictionary | `dictionary` | Dictionary | Yes |  | Main | Dictionary. |
| Key | `key` | Text | Yes |  | Main | Key. |
| Value | `value` | Any | No |  | Main | Expected value. |
