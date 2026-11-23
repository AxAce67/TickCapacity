<div align="center">

<img src=".github/icon.png" width="96" height="96" alt="">

# TickCapacity

Measures how your Minecraft server performs and estimates how many players it can handle

[![build](https://img.shields.io/github/actions/workflow/status/AxAce67/TickCapacity/ci.yml?branch=main&label=build)](https://github.com/AxAce67/TickCapacity/actions/workflows/ci.yml) [![release](https://img.shields.io/github/v/release/AxAce67/TickCapacity?include_prereleases&label=release)](https://github.com/AxAce67/TickCapacity/releases) [![license](https://img.shields.io/github/license/AxAce67/TickCapacity)](LICENSE)<br>
![Minecraft](https://img.shields.io/badge/Minecraft-1.20.6%E2%80%9326.3-62B47A) ![server](https://img.shields.io/badge/server-Paper%20%7C%20Purpur-1E88E5) ![Java](https://img.shields.io/badge/Java-21%2B-E76F00)

**English** | [日本語](README.ja.md)

</div>

Download: [GitHub Releases](https://github.com/AxAce67/TickCapacity/releases)

A Minecraft server plugin (Paper / Purpur) that measures how your server performs and estimates how many players it can handle:
a **recommended** count (still smooth) and a **limit** (where 20 TPS can no longer be held), each with an
interval and a confidence level. When there is not enough data, it says so instead of guessing.

```
━━━━ TickCapacity ━━━━
● Smooth  TPS 20.0 · load 24%
Players 12 / rec. 28 / limit 49 (medium)
✔ No problems
➤ ~2 more day(s) to high confidence [open]
[Capacity] [Perf] [Live]
[Bench] [Report] [Admin]
```

## Requirements

- Paper 1.20.6 – 26.x, or a fork such as Purpur
- Java 21 or newer (whatever your Paper version needs)
- Not supported: Spigot (no accurate tick timing), Folia (no single server-wide tick)

## Getting started

1. Put the jar in `plugins/` and start the server. Set `language: ja` in `config.yml` for Japanese
   (`auto` follows the server locale).
2. That's it. Three minutes after startup the plugin records a sample every 10 seconds.
3. Type `/tc`. The last line always tells you what to do next.

Two ways to get a capacity estimate:

| | Real gameplay | Benchmark |
|---|---|---|
| What you do | nothing, just keep the server running | `/tc bench start` while nobody is online |
| Time | a few days of normal play | about 3–6 minutes |
| Accuracy | good: measures your players on your world | rough: virtual players are lighter than real ones |

Real data is preferred automatically once it is good enough. The benchmark is useful before launch or to
compare hardware.

## Commands

Everything is reachable from `/tc` (alias of `/tickcapacity`) through clickable buttons. Hover over values
for details.

| Command | Permission | |
|---|---|---|
| `/tc` | `tickcapacity.use` | Overview: state, players vs. capacity, top issue, next step |
| `/tc capacity [details]` | `tickcapacity.use` | How many players fit: real data, benchmark, play style, peak hours |
| `/tc perf` | `tickcapacity.use` | TPS, tick time, CPU, memory, world load, causes and advice |
| `/tc monitor [bossbar\|actionbar\|tab\|off]` | `tickcapacity.use` | Live display, remembered per player |
| `/tc bench` | `tickcapacity.use` | Benchmark overview and last result |
| `/tc bench start [runs] [force]`, `stop` | `tickcapacity.admin` | Run or stop a benchmark |
| `/tc bench list`, `compare <id> <id>` | `tickcapacity.use` | Saved results |
| `/tc report` | `tickcapacity.admin` | Markdown and HTML report with charts |
| `/tc admin reload\|alerts\|alert-test\|calibration-reset\|profile [s]` | `tickcapacity.admin` | Maintenance |

Both permissions default to operators.

## How capacity is estimated

### From real gameplay

Each 10-second sample stores the online player count with the tick time distribution, CPU, memory, GC,
loaded chunks, entities and newly generated chunks.

- Samples are grouped by player count and the median tick time (MSPT) of each group is used, so autosaves
  and other spikes do not distort the result.
- A weighted line `MSPT = base + k × players` is fitted. With enough data an upward-curving model is also
  tried and used when it fits clearly better, so the limit is not optimistic on servers that degrade
  quickly near saturation.
- **Recommended**: mean MSPT ≤ 30 ms and 95th percentile ≤ 50 ms. **Limit**: mean MSPT 50 ms.
- The interval is the wider of the regression band and a bootstrap over one-hour blocks. Samples taken in
  the same hour are not independent, and ignoring that makes intervals far too narrow.
- Confidence depends on how far the answer is extrapolated beyond the busiest measured hour, how many
  different player counts were seen, the interval width and the length of the history (less than 3 days
  caps it at "medium").

`/tc capacity` also shows how capacity changes with play style (quiet, typical, lots of exploration) and
which hours are busiest.

### Benchmark

`/tc bench start` adds virtual players in a separate world (`tickcapacity_benchmark`, fixed seed). A
virtual player is a square of ticking chunks the size of your simulation distance plus 45 mobs (configurable).

- Stage sizes adapt: large steps while there is headroom, small ones near the limit.
- Before each measurement it waits until the server has settled after loading the new chunks.
- It keeps going until mean MSPT reaches the limit, the heap would no longer fit more players, or the
  configured maximum (40) is reached. Load that is too heavy while being added is rolled back and a smaller
  step is tried. These are normal endings and the result says which limit was hit.
- It stops right away if someone else joins (unless started with `force`) or the heap is about to run out.
- Mobs are never saved to disk, your worlds are never touched, and the benchmark world stays loaded but
  empty afterwards so the next run can reuse its terrain.
- `/tc bench start 3` pools three runs for a narrower interval.

**Limitation.** Without a real player nearby, Minecraft skips mob spawning and random ticks in a chunk, so
virtual players are lighter than real ones and the load often stops growing after a dozen or so. The plugin
detects this, marks the result as low confidence and shows it as a rough guide. Real gameplay data is
always the better source.

For more realistic benchmarks, disable entity activation range for the benchmark world in `spigot.yml` and
restart:

```yaml
world-settings:
  tickcapacity_benchmark:
    entity-activation-range:
      animals: 0
      monsters: 0
      raiders: 0
      misc: 0
```

### Calibration

Once real data reaches medium confidence and a benchmark exists on the same hardware and view/simulation
distance, the ratio between real and virtual per-player cost is stored in `calibration.json`. Later
benchmarks, for example after a hardware upgrade, are then converted to real-player numbers.

## Live display and alerts

`/tc monitor` shows `TPS 20.0 | MSPT 12.3 ms | 14/28` (online / recommended) in the boss bar, action bar
and/or tab list. Benchmark progress can use the same displays.

Alerts go to online admins, the console and optionally a Discord webhook when tick time stays high, when
it recovers, and when the player count passes the recommended or limit estimate (only with medium or
better confidence). `/tc admin alert-test` sends a test alert.

## Configuration

`plugins/TickCapacity/config.yml`. Every option is commented; the ones you are most likely to change:

| Option | Default | |
|---|---|---|
| `language` | `auto` | `auto`, `en` or `ja` |
| `estimation.comfortable-mspt` | `30.0` | Mean tick time considered smooth |
| `estimation.limit-mspt` | `50.0` | Mean tick time at which 20 TPS breaks |
| `benchmark.max-virtual-players` | `40` | Upper bound for the benchmark |
| `benchmark.movement-blocks-per-second` | `0` | Let virtual players walk and generate terrain (e.g. `4.3`) |
| `benchmark.progress-display` | `[bossbar]` | Any of `bossbar`, `actionbar`, `tab` |
| `monitor.default-display` | `[bossbar]` | Used by `/tc monitor` without arguments |
| `alerts.mspt-threshold` | `45.0` | Alert when the 30-second average stays above this |
| `alerts.discord-webhook` | empty | Discord webhook URL |

## Compatibility

The plugin is built against the Paper 1.20.6 API. Newer features are detected at startup and used when
present (the log shows `Server features: ...`).

| Feature | Used on | Older versions |
|---|---|---|
| Pause-when-empty handling | 1.21.2+ | servers never pause, nothing needed |
| `GameRules` constants | 26.x | classic `GameRule` constants |
| `Mob#setPersistenceRequired` | 26.3+ | `setRemoveWhenFarAway(false)` |
| Command hidden without permission | 1.21+ | still enforced, but listed in tab completion |

Tested on 1.20.6, 1.21.1, 1.21.4, 1.21.8, 1.21.11, 26.1.2, 26.2 and 26.3.

## Data files

```
plugins/TickCapacity/
├── config.yml
├── samples/YYYY-MM-DD.csv      samples, one file per day (deleted after 30 days)
├── results/<id>.json           benchmark results
├── calibration.json            benchmark calibration
└── reports/report-<time>.md / .html
```

## Building and testing

```bash
./gradlew build                                   # compile, unit tests, build/libs/TickCapacity-<version>.jar
./gradlew runServer                               # local Paper test server with the plugin
scripts/smoke-test.sh 1.20.6 1.21.4 26.3          # real servers: every command and a short benchmark
```

The smoke test downloads Paper (checksum verified) into `run/smoke/<version>/`. GitHub Actions runs the
unit tests and the smoke test for every supported version; versions before 26 run on Java 21.

| Package | |
|---|---|
| `core` | Statistics and estimation, no Bukkit dependency, unit tested |
| `bench` | Benchmark: runner, virtual players, terrain loading, world handling |
| `monitor`, `sampling` | Tick timing, JVM metrics, sampling to CSV |
| `capacity` | Cached estimates and calibration |
| `display`, `alert` | Boss bar / action bar / tab list, alerts |
| `report`, `command` | Chat screens, reports, the `/tc` command |
| `compat` | Differences between server versions |

## License

[MIT](LICENSE)
