# Changelog

All notable changes to this project are documented here. Versions follow
[Semantic Versioning](https://semver.org/).

## Unreleased

## 0.1.0 - 2026-11-22

First public beta.

### Added

- Tick time (MSPT), TPS, CPU, memory, GC, chunk and entity monitoring.
- Samples every 10 seconds, stored as one CSV file per day.
- Capacity estimate from real gameplay: recommended and limit player counts with intervals and a
  confidence level; a curved model near saturation; hour-block bootstrap for honest intervals.
- Benchmark with virtual players in a dedicated world: adaptive stage sizes, waits for the server to settle
  before measuring, stays within the memory budget, rolls back steps that are too heavy, optional walking
  virtual players and repeated runs.
- Calibration of benchmarks against real gameplay data.
- Play style (exploration) and peak hour analysis.
- Bottleneck analysis with advice, and a shortcut to a spark profile.
- `/tc` dashboard with clickable screens for capacity, performance, benchmark and admin tasks.
- Live display in the boss bar, action bar and tab list.
- Alerts to admins, the console and Discord.
- Markdown and HTML reports with charts.
- English and Japanese messages.
- Support for Paper 1.20.6 through 26.x.

### Known limitations

- Virtual players are lighter than real ones (no mob spawning or random ticks without a real player
  nearby), so benchmark results are a rough guide. Real gameplay data is more reliable.
- Folia and Spigot are not supported.
