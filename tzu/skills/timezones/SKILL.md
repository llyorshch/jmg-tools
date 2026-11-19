---
name: timezones
description: Answers time questions with computed data instead of mental arithmetic. Use for the current time in a place, converting a time between timezones, finding a meeting slot across locations, public holidays by country, finding a place's IANA timezone, UTC offsets, timezone abbreviations, and daylight saving rules or clock changes.
allowed-tools:
  - Bash(tz-now --json)
  - Bash(tz-now --json *)
  - Bash(tz-meet --json)
  - Bash(tz-meet --json *)
  - Bash(tz-holidays --json)
  - Bash(tz-holidays --json *)
  - Bash(tz-find --json *)
  - Bash(tz-map --json)
  - Bash(tz-map --json *)
---

# Timezone tools

Use these commands rather than working out offsets or daylight saving yourself. Put `--json` first:
stdout is then one JSON document, and these invocations cannot change the user's configuration.

The first run of each command can take a minute while JBang compiles it, so allow a 180 second
timeout. If a command reports that JBang is missing, tell the user to install it from
https://www.jbang.dev/download/.

## Choosing a command

| Question | Command |
|---|---|
| What time is it in Tokyo and Lima? | `tz-now --json -z Tokyo,Lima` |
| What time is it elsewhere at a time in the user's zone? | `tz-now --json -z Tokyo,Lima --at "2026-12-24 18:00"` |
| What is 15:00 in Tokyo for New York and Madrid? | `tz-meet --json 2026-12-03 -r Tokyo --at 15:00 -z "New York,Madrid"` |
| When can these places meet? | `tz-meet --json fri -z "New York,Madrid,Tokyo" -d 45` |
| Which holidays fall in a period? | `tz-holidays --json 2026-Q4 -c US,GB,DE,JP` |
| What timezone is this place, abbreviation or offset in? | `tz-find --json "san jose, costa rica"` |
| What are the daylight saving rules here? | `tz-map --json -z Madrid,Sydney` |
| Which countries follow which DST rules? | `tz-map --json --rules` |

## Inputs

- Places accept cities, countries, abbreviations, IANA ids and offsets. `Label=Place` sets a label.
- Without `-z`, commands use the user's configured zones. Pass `-z` when the user names places.
- `tz-now --at` and `tz-map --at` are in the user's local zone. To start from another zone, use
  `tz-meet -r PLACE --at TIME` and read `slot.zones`.
- `tz-meet` dates: `yyyy-MM-dd`, `today`, `tomorrow`, `+3`, or a weekday such as `fri` (next
  occurrence, today included). `--work 8-17` overrides working hours.
- `tz-holidays` periods: `2026`, `2026-Q4`, `Q2`, `2026-12`, `dec`, `2026-W51`, `W51`. Use `-c` for
  countries, `--national` to drop regional holidays, `-r PLACE` for the zone of `starts_at`/`ends_at`.
- `tz-find`: `-n` limits results, `--online` also searches smaller towns, `--offline` avoids the network.

## Reading the output

- `tz-now`: `local` and `zones[]` with `datetime`, `time`, `utc_offset`, `day_offset`, `abbreviation`,
  `dst_active` and `period`.
- `tz-meet`: `slot` is the requested time (or the best one) with a `verdict` per zone: `working_hours`,
  `outside_working_hours` or `night`. `best_slots` lists the best ranges in the reference zone.
  `hours` has the full day; read it only if you need more than those two.
- `tz-holidays`: `holidays[]` and `countries[].status`. If `complete` is false, say which countries
  have status `failed` or `no_data` instead of implying they have no holidays.
- `tz-find`: `results[0]` is the best match. If several results differ, mention the ambiguity.
- `tz-map`: `zones[]` has the offsets, rules and `next_change`.
- `period` values are `work`, `shoulder` (early morning or evening) and `night`.

Errors go to stderr with a non-zero exit code. `tz-holidays` exits 1 when a country could not be
loaded but still prints what it has. `tz-find` exits 1 when nothing matches.

## Boundaries

Do not run `--watch` or `--interactive`, which need a terminal. Only run `--add`, `--remove` or
`--reset` when the user explicitly asks to change their saved zones.
