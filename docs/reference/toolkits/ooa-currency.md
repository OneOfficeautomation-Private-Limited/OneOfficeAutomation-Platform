# Currency

Toolkit `ooa.currency`, version 1.0.0. Convert amounts between currencies and look up exchange rates from the ECB, a JSON rates service or your own table.

[All toolkits](README.md)

## Contents

- **Currency**: Convert amount, Get exchange rate, List currencies

## Currency

### Convert amount

`ooa.currency/convert` (action)

Convert an amount from one currency to another using the chosen rate source.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Amount | `amount` | Number | Yes |  | Main | Amount to convert. |
| From currency | `from_currency` | Text | Yes |  | Main | Currency of the amount. Example: `USD` |
| To currency | `to_currency` | Text | Yes |  | Main | Currency to convert into. Example: `EUR` |
| Date | `date` | DateTime | No |  | More options; tick Use the rates of a past day | Convert at the rates of this past day (ecb source only); on a weekend or holiday the last working day before it is used. Today's rates when empty. Example: `2026-03-31` |
| Decimals | `decimals` | Number | No | `2` | More options | Decimal places in the result. Range: 0 to 10 |
| Source | `source` | Text | No | `ecb` | More options | ecb: European Central Bank daily reference rates (base EUR). json: a JSON rates service at 'url'. fixed: the 'rates' Dictionary. One of: `ecb`, `json`, `fixed` |
| Address (URL) | `url` | Text | No |  | More options | For json: the address returning the rates. For ecb: leave empty to use the ECB's public file. Shown when: `{"source": ["json"]}` |
| Rates path | `rates_path` | Text | No |  | More options | For json: where the rates object sits in the answer, e.g. rates, $.data.rates or results[0].rates. Empty = the whole answer. Shown when: `{"source": ["json"]}` |
| Base currency | `base_currency` | Text | No |  | More options | Currency the rates are quoted against (rate 1). ecb: always EUR. json: taken from the answer's 'base' field when empty. fixed: required. Shown when: `{"source": ["json", "fixed"]}` |
| Rates | `rates` | Dictionary | No |  | More options | For fixed: currency code to rate against the base, e.g. {"USD": 1.08, "GBP": 0.85}. Shown when: `{"source": ["fixed"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `amount` | Number | Converted amount, rounded to 'decimals'. |
| `rate` | Number | Rate used: units of the target currency for one unit of the source currency. |
| `rate_date` | Text | Date of the rates when the source gives one, otherwise empty. |

*Time limit 60 s; needs: network.*

### Get exchange rate

`ooa.currency/get_rate` (action)

Return how many units of the target currency one unit of the source currency buys.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| From currency | `from_currency` | Text | Yes |  | Main | Currency you have. Example: `GBP` |
| To currency | `to_currency` | Text | Yes |  | Main | Currency you want. Example: `INR` |
| Source | `source` | Text | No | `ecb` | More options | ecb: European Central Bank daily reference rates (base EUR). json: a JSON rates service at 'url'. fixed: the 'rates' Dictionary. One of: `ecb`, `json`, `fixed` |
| Address (URL) | `url` | Text | No |  | More options | For json: the address returning the rates. For ecb: leave empty to use the ECB's public file. Shown when: `{"source": ["json"]}` |
| Rates path | `rates_path` | Text | No |  | More options | For json: where the rates object sits in the answer, e.g. rates, $.data.rates or results[0].rates. Empty = the whole answer. Shown when: `{"source": ["json"]}` |
| Base currency | `base_currency` | Text | No |  | More options | Currency the rates are quoted against (rate 1). ecb: always EUR. json: taken from the answer's 'base' field when empty. fixed: required. Shown when: `{"source": ["json", "fixed"]}` |
| Rates | `rates` | Dictionary | No |  | More options | For fixed: currency code to rate against the base, e.g. {"USD": 1.08, "GBP": 0.85}. Shown when: `{"source": ["fixed"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `rate` | Number | Exchange rate. |
| `rate_date` | Text | Date of the rates when the source gives one, otherwise empty. |

*Time limit 60 s; needs: network.*

### List currencies

`ooa.currency/list_currencies` (action)

List the currency codes the chosen rate source can convert, with each rate against its base currency.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Source | `source` | Text | No | `ecb` | Main | ecb: European Central Bank daily reference rates (base EUR). json: a JSON rates service at 'url'. fixed: the 'rates' Dictionary. One of: `ecb`, `json`, `fixed` |
| Address (URL) | `url` | Text | No |  | Main | For json: the address returning the rates. For ecb: leave empty to use the ECB's public file. Shown when: `{"source": ["json"]}` |
| Rates path | `rates_path` | Text | No |  | Main | For json: where the rates object sits in the answer, e.g. rates, $.data.rates or results[0].rates. Empty = the whole answer. Shown when: `{"source": ["json"]}` |
| Base currency | `base_currency` | Text | No |  | Main | Currency the rates are quoted against (rate 1). ecb: always EUR. json: taken from the answer's 'base' field when empty. fixed: required. Shown when: `{"source": ["json", "fixed"]}` |
| Rates | `rates` | Dictionary | No |  | Main | For fixed: currency code to rate against the base, e.g. {"USD": 1.08, "GBP": 0.85}. Shown when: `{"source": ["fixed"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `currencies` | List | Currency codes, sorted, including the base currency. |
| `rates` | Dictionary | Code to rate against the base currency. |
| `base_currency` | Text | Currency the rates are quoted against. |
| `rate_date` | Text | Date of the rates when the source gives one, otherwise empty. |

*Time limit 60 s; needs: network.*
