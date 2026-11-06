# Date and time

Toolkit `ooa.datetime`, version 1.0.0. Current time, add, difference, format, time zone conversion and working days with business calendars.

[All toolkits](README.md)

## Contents

- **Date and time**: Add to date, Add working days, Convert time zone, Difference between dates, End of period, Format date, Get part of date, Is after, Is before, Is equal, Is leap year, Is working day, Next working day, Get current date and time, Text to date, Previous working day, Start of period, Subtract from date, Count working days
- **Conditions**: Date is after, Date is before, Date is between, Within business hours, Dates are equal, Text is a valid date, Is weekend, Is working day

## Date and time

### Add to date

`ooa.datetime/add` (action)

Add (or subtract, with a negative amount) a duration to a date.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Start date. |
| Amount | `amount` | Number | Yes |  | Main | Amount to add; negative to subtract. Fractions are exact for milliseconds to weeks (1.5 days = 36 hours); months, years and business days take whole numbers. |
| Unit | `unit` | Text | No | `days` | Main | Unit of the amount; business_days skips Saturdays, Sundays and the holidays. One of: `milliseconds`, `seconds`, `minutes`, `hours`, `days`, `weeks`, `months`, `years`, `business_days` |
| Holidays | `holidays` | List | No |  | Main | Dates that are not working days, e.g. ["2026-12-25"]. Shown when: `{"unit": ["business_days"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | DateTime | New date and time. |

*Time limit 60 s; safe to repeat.*

### Add working days

`ooa.datetime/add_working_days` (action)

Move a date by a number of working days, skipping the weekend and holidays of a business calendar; a negative number moves back. The start date itself is not counted (1 from Friday is Monday) and 0 keeps the date. The time of day is kept.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Start date. |
| Days | `days` | Number | Yes |  | Main | Working days to add, a whole number; negative to go back. |
| Calendar | `calendar` | Text | No |  | Main; choice Working days from = A holiday calendar | Name of a holiday calendar in One CommandCenter (its holidays and weekend). Read once per run as the run's user; needs the agent to run from One CommandCenter. Leave empty to give the holidays and weekend here. |
| Weekend | `weekend` | Text | No |  | Main; choice Working days from = Weekend and holidays I give | Days of the week that are not working days, e.g. SAT,SUN (the default) or FRI,SAT; NONE when every day is a working day. When given with a calendar, it replaces the calendar's weekend. |
| Holidays | `holidays` | List | No |  | Main; choice Working days from = Weekend and holidays I give | Dates that are not working days, as yyyy-MM-dd, e.g. ["2026-12-25"]. Added to the calendar's dates when a calendar is named too. |
| Time zone | `time_zone` | Text | No | `UTC` | More options | Time zone whose calendar date counts; the time of day is kept in this zone. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | DateTime | The date that many working days later (or earlier). |

*Time limit 60 s; safe to repeat.*

### Convert time zone

`ooa.datetime/convert_time_zone` (action)

Show a UTC date as local time in another zone, as ISO text with offset.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date (UTC). |
| Time zone | `time_zone` | Text | Yes |  | Main | Target IANA zone. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Local time with offset, e.g. 2026-10-18T20:00+05:30. |

*Time limit 60 s; safe to repeat.*

### Difference between dates

`ooa.datetime/difference` (action)

Return end minus start in the chosen unit (may be fractional).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Start | `start` | DateTime | Yes |  | Main | Earlier date. |
| End | `end` | DateTime | Yes |  | Main | Later date. |
| Unit | `unit` | Text | No | `days` | Main | Result unit; business_days counts working days after start up to and including end. One of: `milliseconds`, `seconds`, `minutes`, `hours`, `days`, `weeks`, `months`, `years`, `business_days` |
| Holidays | `holidays` | List | No |  | Main | Dates that are not working days, e.g. ["2026-12-25"]. Shown when: `{"unit": ["business_days"]}` |
| Absolute | `absolute` | Yes/No | No | No | More options | Return the size of the difference without a minus sign when end is before start. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Number | Difference. |

*Time limit 60 s; safe to repeat.*

### End of period

`ooa.datetime/end_of` (action)

Return the last second (23:59:59) of the day, week (Sunday), month or year containing a date.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date. |
| Unit | `unit` | Text | No | `day` | Main | Period. One of: `day`, `week`, `month`, `year` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | DateTime | End of the period. |

*Time limit 60 s; safe to repeat.*

### Format date

`ooa.datetime/format` (action)

Format a date as text in a time zone, using a pattern such as dd/MM/yyyy HH:mm.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date to format (UTC). |
| Standard | `standard` | Text | No |  | Main; choice Format = A standard format | A predefined ISO or RFC format to use instead of the pattern. One of: `ISO_LOCAL_DATE`, `ISO_LOCAL_TIME`, `ISO_LOCAL_DATE_TIME`, `ISO_OFFSET_DATE_TIME`, `ISO_ZONED_DATE_TIME`, `ISO_INSTANT`, `ISO_DATE`, `ISO_DATE_TIME`, `ISO_ORDINAL_DATE`, `ISO_WEEK_DATE`, `BASIC_ISO_DATE`, `RFC_1123_DATE_TIME` |
| Pattern | `pattern` | Text | No | `yyyy-MM-dd` | Main; choice Format = My own pattern | Pattern, e.g. dd/MM/yyyy, yyyy-MM-dd HH:mm, MMM d, yyyy. Shown when: `{"standard": [""]}` |
| Time zone | `time_zone` | Text | No | `UTC` | More options | IANA time zone for display, e.g. Asia/Kolkata. |
| Locale | `locale` | Text | No | `en` | More options | Language for month and day names, e.g. en, de-DE, fr-FR. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `text` | Text | Formatted date. |

*Time limit 60 s; safe to repeat.*

### Get part of date

`ooa.datetime/get_part` (action)

Return one part of a date, such as the year, month, day of week, week of year or quarter, in a time zone.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date (UTC). |
| Part | `part` | Text | Yes |  | Main | Which part to return; day_of_week is 1 for Monday to 7 for Sunday, week_of_year follows ISO 8601. One of: `year`, `month`, `day`, `hour`, `minute`, `second`, `millisecond`, `day_of_week`, `day_name`, `month_name`, `day_of_year`, `week_of_year`, `quarter`, `days_in_month`, `days_in_year` |
| Time zone | `time_zone` | Text | No | `UTC` | More options | Time zone the part is read in. |
| Locale | `locale` | Text | No | `en` | More options | Language for day_name and month_name. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Any | The part: a Number, or text for day_name and month_name. |

*Time limit 60 s; safe to repeat.*

### Is after

`ooa.datetime/is_after` (action)

Check whether the first date is later than the second.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date to check. |
| Other | `other` | DateTime | Yes |  | Main | Date to compare with. |
| Include time | `include_time` | Yes/No | No | Yes | More options | Compare the time of day too; when off only the calendar dates (in time_zone) are compared. |
| Time zone | `time_zone` | Text | No | `UTC` | More options | Time zone whose calendar dates are compared when include_time is off. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Yes/No | True when the first date is later. |

*Time limit 60 s; safe to repeat.*

### Is before

`ooa.datetime/is_before` (action)

Check whether the first date is earlier than the second.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date to check. |
| Other | `other` | DateTime | Yes |  | Main | Date to compare with. |
| Include time | `include_time` | Yes/No | No | Yes | More options | Compare the time of day too; when off only the calendar dates (in time_zone) are compared. |
| Time zone | `time_zone` | Text | No | `UTC` | More options | Time zone whose calendar dates are compared when include_time is off. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Yes/No | True when the first date is earlier. |

*Time limit 60 s; safe to repeat.*

### Is equal

`ooa.datetime/is_equal` (action)

Check whether two dates are the same moment.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date to check. |
| Other | `other` | DateTime | Yes |  | Main | Date to compare with. |
| Include time | `include_time` | Yes/No | No | Yes | More options | Compare the time of day too; when off only the calendar dates (in time_zone) are compared. |
| Time zone | `time_zone` | Text | No | `UTC` | More options | Time zone whose calendar dates are compared when include_time is off. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Yes/No | True when the dates are equal. |

*Time limit 60 s; safe to repeat.*

### Is leap year

`ooa.datetime/is_leap_year` (action)

Check whether the year of a date, or a year number, is a leap year.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | No |  | Main; choice Check = A date | Date whose year to check. |
| Year | `year` | Number | No |  | Main; choice Check = A year number | Year number; used when no date is given. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Yes/No | True for a leap year. |

*Time limit 60 s; safe to repeat.*

### Is working day

`ooa.datetime/is_working_day` (action)

Check whether a date is a working day in a business calendar: not on its weekend and not one of its holidays.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date to check. |
| Calendar | `calendar` | Text | No |  | Main; choice Working days from = A holiday calendar | Name of a holiday calendar in One CommandCenter (its holidays and weekend). Read once per run as the run's user; needs the agent to run from One CommandCenter. Leave empty to give the holidays and weekend here. |
| Weekend | `weekend` | Text | No |  | Main; choice Working days from = Weekend and holidays I give | Days of the week that are not working days, e.g. SAT,SUN (the default) or FRI,SAT; NONE when every day is a working day. When given with a calendar, it replaces the calendar's weekend. |
| Holidays | `holidays` | List | No |  | Main; choice Working days from = Weekend and holidays I give | Dates that are not working days, as yyyy-MM-dd, e.g. ["2026-12-25"]. Added to the calendar's dates when a calendar is named too. |
| Time zone | `time_zone` | Text | No | `UTC` | More options | Time zone whose calendar date counts. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Yes/No | True on a working day. |
| `reason` | Text | Why it is not a working day: holiday or weekend (holiday when both); empty on a working day. |

*Time limit 60 s; safe to repeat.*

### Next working day

`ooa.datetime/next_working_day` (action)

Return the first working day after a date in a business calendar, or the date itself when it is a working day and same_day is on. The time of day is kept.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date to start from. |
| Calendar | `calendar` | Text | No |  | Main; choice Working days from = A holiday calendar | Name of a holiday calendar in One CommandCenter (its holidays and weekend). Read once per run as the run's user; needs the agent to run from One CommandCenter. Leave empty to give the holidays and weekend here. |
| Weekend | `weekend` | Text | No |  | Main; choice Working days from = Weekend and holidays I give | Days of the week that are not working days, e.g. SAT,SUN (the default) or FRI,SAT; NONE when every day is a working day. When given with a calendar, it replaces the calendar's weekend. |
| Holidays | `holidays` | List | No |  | Main; choice Working days from = Weekend and holidays I give | Dates that are not working days, as yyyy-MM-dd, e.g. ["2026-12-25"]. Added to the calendar's dates when a calendar is named too. |
| Same day | `same_day` | Yes/No | No | No | More options | Return the date itself when it is a working day (e.g. a due date that moves only off holidays and weekends). |
| Time zone | `time_zone` | Text | No | `UTC` | More options | Time zone whose calendar date counts; the time of day is kept in this zone. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | DateTime | The next working day. |

*Time limit 60 s; safe to repeat.*

### Get current date and time

`ooa.datetime/now` (action)

Return the current date and time in UTC. Every DateTime in OOA is UTC; use Format date with a time zone to show local time, or the ISO_INSTANT / ISO_OFFSET_DATE_TIME standard for text with Z or an offset for other systems.

No settings.

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | DateTime | Current date and time in UTC. |

*Time limit 60 s.*

### Text to date

`ooa.datetime/parse` (action)

Read a date from text using a pattern, interpreting it in a time zone; the result is UTC.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text such as 18/10/2026. |
| Standard | `standard` | Text | No |  | Main; choice Format = A standard format | A predefined ISO or RFC format to read instead of the pattern; an offset in the text wins over time_zone. One of: `ISO_LOCAL_DATE`, `ISO_LOCAL_DATE_TIME`, `ISO_OFFSET_DATE_TIME`, `ISO_ZONED_DATE_TIME`, `ISO_INSTANT`, `ISO_DATE`, `ISO_DATE_TIME`, `BASIC_ISO_DATE`, `RFC_1123_DATE_TIME` |
| Pattern | `pattern` | Text | No |  | Main; choice Format = My own pattern | Pattern of the text, e.g. dd/MM/yyyy; needed unless a standard format is chosen. Shown when: `{"standard": [""]}` |
| Time zone | `time_zone` | Text | No | `UTC` | More options | Time zone the text is in. |
| Locale | `locale` | Text | No | `en` | More options | Language of month and day names in the text, e.g. en, de-DE. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `value` | DateTime | Parsed date (UTC). |

*Time limit 60 s; safe to repeat.*

### Previous working day

`ooa.datetime/previous_working_day` (action)

Return the last working day before a date in a business calendar, or the date itself when it is a working day and same_day is on. The time of day is kept.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date to start from. |
| Calendar | `calendar` | Text | No |  | Main; choice Working days from = A holiday calendar | Name of a holiday calendar in One CommandCenter (its holidays and weekend). Read once per run as the run's user; needs the agent to run from One CommandCenter. Leave empty to give the holidays and weekend here. |
| Weekend | `weekend` | Text | No |  | Main; choice Working days from = Weekend and holidays I give | Days of the week that are not working days, e.g. SAT,SUN (the default) or FRI,SAT; NONE when every day is a working day. When given with a calendar, it replaces the calendar's weekend. |
| Holidays | `holidays` | List | No |  | Main; choice Working days from = Weekend and holidays I give | Dates that are not working days, as yyyy-MM-dd, e.g. ["2026-12-25"]. Added to the calendar's dates when a calendar is named too. |
| Same day | `same_day` | Yes/No | No | No | More options | Return the date itself when it is a working day. |
| Time zone | `time_zone` | Text | No | `UTC` | More options | Time zone whose calendar date counts; the time of day is kept in this zone. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | DateTime | The previous working day. |

*Time limit 60 s; safe to repeat.*

### Start of period

`ooa.datetime/start_of` (action)

Return midnight at the start of the day, week (Monday), month or year containing a date.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date. |
| Unit | `unit` | Text | No | `day` | Main | Period. One of: `day`, `week`, `month`, `year` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | DateTime | Start of the period. |

*Time limit 60 s; safe to repeat.*

### Subtract from date

`ooa.datetime/subtract` (action)

Subtract an amount of time from a date.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Start date. |
| Amount | `amount` | Number | Yes |  | Main | Amount to subtract. Fractions are exact for milliseconds to weeks; months, years and business days take whole numbers. |
| Unit | `unit` | Text | No | `days` | Main | Unit of the amount; business_days skips Saturdays, Sundays and the holidays. One of: `milliseconds`, `seconds`, `minutes`, `hours`, `days`, `weeks`, `months`, `years`, `business_days` |
| Holidays | `holidays` | List | No |  | Main | Dates that are not working days, e.g. ["2026-12-25"]. Shown when: `{"unit": ["business_days"]}` |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | DateTime | New date and time. |

*Time limit 60 s; safe to repeat.*

### Count working days

`ooa.datetime/working_days_between` (action)

Count the working days between two dates in a business calendar: the days after the start up to and including the end (the start too, when chosen). The count is negative when the end is before the start.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Start | `start` | DateTime | Yes |  | Main | First date. |
| End | `end` | DateTime | Yes |  | Main | Last date. |
| Calendar | `calendar` | Text | No |  | Main; choice Working days from = A holiday calendar | Name of a holiday calendar in One CommandCenter (its holidays and weekend). Read once per run as the run's user; needs the agent to run from One CommandCenter. Leave empty to give the holidays and weekend here. |
| Weekend | `weekend` | Text | No |  | Main; choice Working days from = Weekend and holidays I give | Days of the week that are not working days, e.g. SAT,SUN (the default) or FRI,SAT; NONE when every day is a working day. When given with a calendar, it replaces the calendar's weekend. |
| Holidays | `holidays` | List | No |  | Main; choice Working days from = Weekend and holidays I give | Dates that are not working days, as yyyy-MM-dd, e.g. ["2026-12-25"]. Added to the calendar's dates when a calendar is named too. |
| Include start | `include_start` | Yes/No | No | No | More options | Count the start date too when it is a working day (Monday to Friday of one week is then 5, not 4). |
| Time zone | `time_zone` | Text | No | `UTC` | More options | Time zone whose calendar date counts. |

**Results**

| Name | Type | Description |
| --- | --- | --- |
| `result` | Number | Number of working days. |

*Time limit 60 s; safe to repeat.*

## Conditions

Used in If, Loop while and Wait until steps.

### Date is after

`ooa.datetime/is_after` (condition)

True when the first date is later than the second.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date to check. |
| Other | `other` | DateTime | Yes |  | Main | Date to compare with. |
| Include time | `include_time` | Yes/No | No | Yes | Main | Compare the time of day too; when off only the calendar dates (in time_zone) are compared. |
| Time zone | `time_zone` | Text | No | `UTC` | Main | Time zone whose calendar dates are compared when include_time is off. |

### Date is before

`ooa.datetime/is_before` (condition)

True when the first date is earlier than the second.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date to check. |
| Other | `other` | DateTime | Yes |  | Main | Date to compare with. |
| Include time | `include_time` | Yes/No | No | Yes | Main | Compare the time of day too; when off only the calendar dates (in time_zone) are compared. |
| Time zone | `time_zone` | Text | No | `UTC` | Main | Time zone whose calendar dates are compared when include_time is off. |

### Date is between

`ooa.datetime/is_between` (condition)

True when the date lies between two dates, both included.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date to check. |
| From | `from` | DateTime | Yes |  | Main | Earliest date. |
| To | `to` | DateTime | Yes |  | Main | Latest date. |
| Include time | `include_time` | Yes/No | No | Yes | Main | Compare the time of day too; when off only the calendar dates (in time_zone) are compared. |
| Time zone | `time_zone` | Text | No | `UTC` | Main | Time zone whose calendar dates are compared when include_time is off. |

### Within business hours

`ooa.datetime/is_business_hours` (condition)

True between the start and end hours in the time zone, on weekdays (or every day).

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Start hour | `start_hour` | Number | No | `9` | Main | Start hour (24h). Range: 0 to 24 |
| End hour | `end_hour` | Number | No | `17` | Main | End hour (24h). Range: 0 to 24 |
| Time zone | `time_zone` | Text | No | `UTC` | Main | Time zone. |
| Weekdays only | `weekdays_only` | Yes/No | No | Yes | Main | Only Monday to Friday count; turn off for teams that work every day. |

### Dates are equal

`ooa.datetime/is_equal` (condition)

True when both dates are the same moment, or the same day when the time is not included.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date to check. |
| Other | `other` | DateTime | Yes |  | Main | Date to compare with. |
| Include time | `include_time` | Yes/No | No | Yes | Main | Compare the time of day too; when off only the calendar dates (in time_zone) are compared. |
| Time zone | `time_zone` | Text | No | `UTC` | Main | Time zone whose calendar dates are compared when include_time is off. |

### Text is a valid date

`ooa.datetime/is_valid` (condition)

True when the text can be read as a date with the pattern.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Text | `text` | Text | Yes |  | Main | Text to check. |
| Pattern | `pattern` | Text | No | `yyyy-MM-dd` | Main | Expected pattern, e.g. dd/MM/yyyy. |

### Is weekend

`ooa.datetime/is_weekend` (condition)

True when the date falls on Saturday or Sunday in the given time zone.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date (UTC). |
| Time zone | `time_zone` | Text | No | `UTC` | Main | Time zone. |

### Is working day

`ooa.datetime/is_working_day` (condition)

True when the date is a working day in a business calendar: not on its weekend and not one of its holidays.

**Settings**

| Field | Name | Type | Required | Default | Where | Description |
| --- | --- | --- | --- | --- | --- | --- |
| Value | `value` | DateTime | Yes |  | Main | Date to check. |
| Calendar | `calendar` | Text | No |  | Main | Name of a holiday calendar in One CommandCenter (its holidays and weekend). Read once per run as the run's user; needs the agent to run from One CommandCenter. Leave empty to give the holidays and weekend here. |
| Holidays | `holidays` | List | No |  | Main | Dates that are not working days, as yyyy-MM-dd, e.g. ["2026-12-25"]. Added to the calendar's dates when a calendar is named too. |
| Weekend | `weekend` | Text | No |  | Main | Days of the week that are not working days, e.g. SAT,SUN (the default) or FRI,SAT; NONE when every day is a working day. When given with a calendar, it replaces the calendar's weekend. |
| Time zone | `time_zone` | Text | No | `UTC` | Main | Time zone whose calendar date counts. |
