# jmg-tools

JBang catalog for `llyorshch` (use aliases as `alias@llyorshch/jmg-tools`).

## tzu: timezone utilities

Five colourful terminal tools for working across timezones. Each one is a JBang script
built with the [TamboUI](https://tamboui.dev) TUI library, and each can be exported to a
native executable. See [tzu/README.md](tzu/README.md) for screenshots and all options.

| Alias         | What it does                                                                     |
|---------------|----------------------------------------------------------------------------------|
| `tz-now`      | World clock: your time and a configurable group of timezones (`-w` live)         |
| `tz-meet`     | Meeting planner: a day's timetable across the group, best slots highlighted      |
| `tz-holidays` | Public holidays of the group's countries for a week, month, quarter or year      |
| `tz-find`     | Timezone of a city, country, abbreviation or UTC offset                          |
| `tz-map`      | World timezone map with day/night and the daylight saving time rules             |

```bash
jbang tz-now@llyorshch/jmg-tools
jbang tz-meet@llyorshch/jmg-tools fri --at 16:00
jbang tz-holidays@llyorshch/jmg-tools 2026-Q4
jbang tz-find@llyorshch/jmg-tools "san jose, costa rica"
jbang tz-map@llyorshch/jmg-tools --mode dst

# install as commands (add --native for a GraalVM native executable)
for c in tz-now tz-meet tz-holidays tz-find tz-map; do jbang app install $c@llyorshch/jmg-tools; done
```

![tz-now](tzu/docs/tz-now.png)
