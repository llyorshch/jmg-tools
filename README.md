# jmg-tools

JBang catalog for `llyorshch` (use aliases as `alias@llyorshch/jmg-tools`).

## Add the catalog to JBang

You can use any alias directly as `alias@llyorshch/jmg-tools` without setting anything up.
To use a shorter name instead, add the catalog to your JBang installation once:

```bash
jbang catalog add --name jmg llyorshch/jmg-tools
```

Now the aliases work as `alias@jmg`:

```bash
jbang alias list jmg      # show the tools in the catalog
jbang tz-now@jmg          # run one
```

To pick up new or changed aliases later, run `jbang catalog update`.
To remove the catalog, run `jbang catalog remove jmg`.

## Install the scripts as local commands

`jbang app install` turns an alias into a command on your `PATH`, so you can type `tz-now`
instead of `jbang tz-now@jmg`. The examples use the `jmg` catalog name from above; without
it, use `@llyorshch/jmg-tools` instead.

```bash
jbang app setup                       # once: adds ~/.jbang/bin to your PATH (open a new shell after)

jbang app install tz-now@jmg          # install one tool
for c in tz-now tz-meet tz-holidays tz-find tz-map; do jbang app install $c@jmg; done   # install all

tz-now                                # run it like any other command
```

Useful options and commands:

| Command                                      | What it does                                                    |
|----------------------------------------------|-----------------------------------------------------------------|
| `jbang app install --native tz-now@jmg`      | Build a GraalVM native executable: starts instantly, needs GraalVM |
| `jbang app install --name now tz-now@jmg`    | Install under a different command name                          |
| `jbang app install --force tz-now@jmg`       | Reinstall, e.g. after `jbang catalog update`                    |
| `jbang app list`                             | Show installed commands                                         |
| `jbang app uninstall tz-now`                 | Remove a command                                                |

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
```

To install them as commands, see [Install the scripts as local commands](#install-the-scripts-as-local-commands).

![tz-now](tzu/docs/tz-now.png)
