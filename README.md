# Anaka

A client-side Fabric mod that exposes a local HTTP API so an external program (e.g. Claude and other LLMs) can read the game and play through normal player inputs.

Minecraft 1.21.11 · Fabric Loader ≥ 0.16 · Fabric API

## Setup

1. Put the jar in your `mods` folder together with Fabric API.
2. Launch once. `config/anaka.json` is created:

   ```json
   { "port": 27599, "token": "<random>", "allowMultiplayer": false, "showHud": true }
   ```

3. Send the token with every request: `Authorization: Bearer <token>`.

Singleplayer only. The server binds to `127.0.0.1` and rejects requests carrying an `Origin` header.

Press **K** (rebindable under Controls) to take the controls back from the agent, and again to hand them over.

Endpoints and tasks: [docs/API.md](docs/API.md).

## Building

```sh
./gradlew build   # jar in build/libs/
```

## License

MIT
