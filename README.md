# maintenance-bridge

A Velocity plugin that mirrors [kennytv's Maintenance](https://github.com/kennytv/Maintenance)
plugin state to the filesystem, so other tooling can read and set maintenance mode without
going through RCON.

## About this project

This was built with heavy Claude Code assistance — most of the implementation is AI-generated,
with the design and review driven by me. It has unit test coverage (`StatusFileTest`,
`MaintenanceRequestTest`, `MaintenanceStatusServiceTest`) and runs on my own production Velocity
proxy, so it sees real day-to-day use, not just its own test suite. This one is a bit more
security-sensitive than its sibling plugins (it acts on external input to toggle maintenance
mode, not just export status — see SECURITY.md's Scope section), so extra scrutiny on the
`request.json` handling path is especially welcome. Read the source and file issues if something
looks off.

## Why

Maintenance mode is normally read and set via RCON (`maintenance status`, `maintenance on/off`).
That works, but every external script or service that wants to know "is the server in
maintenance, and why" has to pay an RCON round trip for it, and there's no way to attach a
reason or an ETA without also driving that through RCON. This plugin listens to Maintenance's
own API events on the proxy and writes the current state to a JSON file instead, and watches a
second file for requests to change that state.

## How it works

If [Maintenance](https://github.com/kennytv/Maintenance) (the Velocity build) is installed, the
bridge activates automatically - it's a soft dependency, so if Maintenance isn't present it just
logs a warning and stays inactive. Once active, it writes two files under its own plugin data
directory (`plugins/maintenance-bridge/`, relative to wherever Velocity runs):

### `status.json` (written by the plugin)

Refreshed on every `MaintenanceChangedEvent`/`ServerMaintenanceChangedEvent`, and once
immediately on startup so it always reflects reality, not just the next toggle:

```json
{
  "maintenance": true,
  "reason": "Planned restart",
  "plannedEndsAtEpochSeconds": 1784567890,
  "servers": { "lobby": true, "survival": false },
  "updated": "2026-07-21T18:49:52.189653072Z"
}
```

`plannedEndsAtEpochSeconds` is purely informational - it's only ever set by a `request.json`
drop that includes a `minutes` field, and nothing in this plugin ever clears maintenance
automatically based on it. Whatever turns maintenance on stays responsible for turning it back
off.

### `request.json` (read by the plugin, polled every 2 seconds)

Drop this to toggle maintenance without RCON:

```json
{
  "maintenance": true,
  "reason": "Planned restart",
  "minutes": 15,
  "server": null
}
```

- `server: null` targets the whole proxy; a server name (as registered in Velocity) targets
  just that backend.
- `reason` and `minutes` are optional, and only valid alongside `server: null` - `status.json`
  has no per-server slot for either, so a per-server request that sets them is rejected and
  moved to `request.json.rejected`.
- `minutes` must be non-negative and at most 43200 (30 days).
- The file must be at most 64 KiB. A malformed, oversized, or otherwise rejected request is
  moved aside to `request.json.rejected` instead of crashing the poll loop, and the file is
  deleted once successfully processed.
- **Write the file with correct permissions from the start** (e.g. `install -m 644` or an
  equivalent atomic write+chmod), not a plain write followed by a separate `chmod`. The plugin
  runs as whatever user owns the proxy process; if the file is briefly unreadable to that user,
  each poll attempt during that window logs an error and retries on the next cycle rather than
  silently waiting.

## Requirements

- JDK 25 to build (Gradle toolchain-managed)
- Velocity 4.x
- [Maintenance](https://github.com/kennytv/Maintenance), Velocity build, installed alongside

## Building

```bash
./gradlew build
```

## Testing a release build

Tagging `main` with `vX.Y.Z` (or running the `Release` workflow manually with a `tag` input)
builds the jar and attaches it to a GitHub Release. Download and drop it into a Velocity
server's `plugins/` directory to test:

```bash
gh release download vX.Y.Z -R miikkak/maintenance-bridge -p '*.jar' -D /path/to/velocity/plugins/
```

There is no automated deploy yet - this is manual, on-demand testing only.

## Design notes

- Dependency versions are pinned in `gradle.lockfile` (`dependencyLocking` in `build.gradle.kts`)
  so CI vulnerability scanning has a real dependency graph to check. Gradle fails the build if a
  declared dependency's resolved version drifts from the lock - after bumping a version in
  `build.gradle.kts`, regenerate it with `./gradlew dependencies --write-locks` and commit the
  result alongside the change.
  - `com.velocitypowered:velocity-brigadier:1.0.0-SNAPSHOT` is a transitive dependency of
    `velocity-api:4.0.0` and is locked like everything else, but SNAPSHOT artifacts are mutable
    on the remote repo - the lock pins the version string, not the artifact content, so this one
    dependency doesn't get the same reproducibility guarantee as the rest of the lockfile. This
    is inherited from Velocity's own POM, not something fixable here.
- `gson` is deliberately pinned to `2.14.0` and not shaded - it's the version Gradle actually
  resolves `velocity-api:4.0.0`'s own gson dependency to at runtime (verify with `./gradlew
dependencies --configuration compileClasspath`). Bumping it requires re-verifying against
  whatever Velocity actually resolves, not an automated dependency update (see `renovate.json`,
  which excludes it from Renovate for this reason).
- The plugin's reported version (shown in Velocity's "Loaded plugin ..." log line) is generated
  from the Gradle project version at build time, so it can't drift from the jar filename.

## License

[MIT License](LICENSE) - Copyright (c) 2026 Miikka Karhuluoma
