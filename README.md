# ANX Network Bridge for Paper

English · [Русский](README_RU.md)

The official [anx.run](https://anx.run) network agent for Paper. It reports aggregate availability and player counts,
serves temporary MOTD verification codes, detects Floodgate players, and can deliver configured vote rewards.

## Requirements

- Paper 1.21.4 or a compatible fork; Java 21
- Java 21
- An agent pairing code created in the anx.run network dashboard

## Install

1. Download `anx-network-bridge-paper-1.0.0.jar` and its `.sha256` file from the latest [release](https://github.com/CtrlAltBogdan/anx-network-bridge-paper/releases).
2. Verify it with `sha256sum -c anx-network-bridge-paper-1.0.0.jar.sha256`.
3. Copy the jar to the server's `plugins/` directory and restart Paper.
4. Run `anx pair <code>` in the server console. The credential is written to `plugins/ANXNetworkBridge/agent.key`.
5. Review the generated `config.yml`. A Paper installation reports the local server as one mode rather than probing backends.

Do not publish `agent.key`. The plugin never includes player names, UUIDs, addresses or chat in telemetry. Nicknames are
received only for vote rewards and are used to expand commands configured by the server owner.

## Commands and configuration

- `anx status` — connection, queue, proof and last-error status
- `anx pair <code>` — pair or replace the local agent credential
- Permission: `anx.admin`

The generated configuration documents every option. Geyser/Floodgate integration is optional and discovered at runtime.
When the API is unavailable, at most 10 recent measurements (no older than four minutes) are retained and replayed in order.

## Build and test

```sh
./gradlew clean build
```

The Java 21 build compiles the adapter and the pinned core with warnings treated as errors, runs the core integration test,
checks the core lock and inspects the resulting jar. The artifact is in `build/libs/`.

The exact core version is recorded in `core.lock`; update it only with `scripts/update-core.sh vX.Y.Z`, review the vendored
source diff, and commit `core/` together with the lock.

## Support and security

Use GitHub Issues for reproducible bugs. Report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).

## License

MIT. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
