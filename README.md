# maintenance-bridge

A Velocity plugin bridging maintenance-mode state with
[`mc-healthcheck`](https://github.com/miikkak/mc-healthcheck) and
[`minecraft-limbo-waiting-container`](https://github.com/miikkak/minecraft-limbo-waiting-container).

## Status

Early scaffold — infra and CI are wired up, plugin logic is not implemented yet.

## Building

```bash
./gradlew build
```

Requires JDK 25.

## Testing a release build

Tagging `main` with `vX.Y.Z` (or running the `Release` workflow manually with a `tag` input)
builds the jar and attaches it to a GitHub Release. Download and drop it into a Velocity
server's `plugins/` directory to test:

```bash
gh release download vX.Y.Z -R miikkak/maintenance-bridge -p '*.jar' -D /path/to/velocity/plugins/
```

There is no automated deploy yet — this is manual, on-demand testing only.

## License

TBD
