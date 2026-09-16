# Anaka HTTP API

## While the agent is in control

- Your mouse and keyboard are ignored so they don't fight the agent.
- The action bar shows what the agent is doing.
- Press **K** (rebindable under Controls → Anaka) to toggle control. While the agent drives, K pauses it
  and gives you the controls; the API then refuses new tasks (`"paused": true` in `/status`), so no script can grab
  control back. Press K again (with no screen open) to hand control back to the agent.
- The game keeps running when the window is in the background.

## Setup

1. Put the jar in your `mods` folder with Fabric API.
2. Launch once. `config/anaka.json` is created:

```json
{ "port": 27599, "token": "<random>", "allowMultiplayer": false, "showHud": true }
```

3. Send the token with every request: `Authorization: Bearer <token>`.

The server binds to `127.0.0.1` only and rejects requests that carry an `Origin` header, so web pages can't reach it.

## API

All responses are JSON. Errors are `{"error": "..."}` with status 400/401/403/409/500.

### Reading

| Endpoint | Description |
|---|---|
| `GET /status` | Mod version, whether you're in a world, whether automation is allowed. |
| `GET /state` | Position, look, health, food, air, armor, XP, time, held item, open screen, crosshair target, current task. |
| `GET /inventory` | Non-empty slots (0–8 hotbar, 9–35 main) and equipment. |
| `GET /find?blocks=coal_ore,iron_ore&radius=32&limit=50&exposed=true` | Nearest matching blocks. `exposed` keeps only blocks touching air. |
| `GET /blocks?from=x,y,z&to=x,y,z&props=1` | Non-air blocks in a region (≤ 32768 blocks), palette-encoded. With `props=1`, blocks that have state properties get a 5th element such as `{"facing": "west", "powered": "false"}`. |
| `GET /entities?radius=16` | Nearby entities: id, type, position, health, hostile, dropped item. |
| `GET /container` | Slots of the open screen (or the player inventory), cursor stack. |

### Tasks

`POST /task` starts a task and replaces the current one. Add `"append": true` to queue it instead.
Send `{"tasks": [ ... ]}` to queue a whole chain at once (validated before anything starts). Add
`"stopOnFailure": true` when later steps depend on earlier ones (e.g. digging a tunnel): a failed step cancels the
rest of that chain.
Add `?wait=60` to block until the task (or the last task of a chain) finishes. `GET /task?id=N&wait=30` polls or
waits; `GET /tasks` lists recent tasks. `POST /stop` cancels everything.

Tasks are built for efficiency: `goto` sprints and walks straight lines where the ground allows; `mine_many`
chooses the nearest next block (never undermining a pending block above) and sweeps up drops once at the end;
`build` finishes each layer before the next; `craft` fills the grid for the whole count and shift-clicks once.
Failed steps in `mine_many`/`build` are retried once after the rest. A failed walk reports the `closest` reachable
position. If a tool breaks mid-block, mining switches to the next best tool automatically.

| `type` | Fields | What it does |
|---|---|---|
| `goto` | `x y z`, `range`=1, `sprint`=false, `partial`=true | A* pathfinding, walks/jumps/swims there. |
| `mine` | `x y z`, `collect`=true, `requireDrops`=true | Walks into reach, picks the best tool, breaks the block, picks up drops. |
| `mine_many` | `blocks: [{x,y,z}]`, `collect`, `requireDrops` | Mines a list top-down. |
| `place` | `x y z`, `item`, `against` {x,y,z}, `yaw`, `pitch` | Places a block from the inventory against a neighbouring face. `against` picks the neighbour to click (a hopper outputs into it); `yaw`/`pitch` hold that body orientation during the click (pistons, observers, furnaces, repeaters take their facing from it). |
| `build` | `blocks: [{x,y,z,item,against,yaw,pitch}]` | Places a list bottom-up, skipping blocks already correct, retrying failures once. |
| `travel` | `x y z`, `range`=1.5, `break`=true, `place`=true, `placeBudget`=64, `avoid: [{x,y,z}]` | Gets there by any legal means with one planner: walk, swim, climb, break through (only blocks a carried tool can harvest, never next to lava/water), bridge and pillar with carried building blocks. A failed step blacklists its cell and replans. |
| `pillar` | `item` | Centres, looks down, jumps and places the block underneath: ends one block higher. |
| `use` | `x y z` | Right-clicks a block (chest, crafting table, furnace, door, bed…). |
| `attack` | `entity` (id) | Chases and hits the entity with full attack cooldown. |
| `eat` | `item` (optional) | Eats the given food, or any food. |
| `craft` | `pattern` (4 or 9 item ids / null, row-major), `count`=1 | Fills the open crafting grid and takes the result. 3×3 needs `use` on a crafting table first. |
| `collect` | `radius`=8 | Picks up nearby dropped items. |
| `use_item` | `item`, `x y z` or `yaw pitch`, `onBlock`=false, `holdTicks`=0 | Aims and right-clicks with an item: on the block under the crosshair (flint and steel, bucket placement) or in the air (fill bucket, throw eye of ender); `holdTicks` holds then releases (bow). |
| `look` | `x y z` or `yaw pitch` | Turns the camera. |
| `wait` | `ticks` | Waits. |

### Direct actions

| Endpoint | Body |
|---|---|
| `POST /takeover`, `POST /release` | Take or return control without a task. |
| `POST /resume` | Close the pause menu. |
| `POST /respawn` | Respawn after death. |
| `POST /hotbar` | `{"slot": 0-8}` |
| `POST /click` | `{"slot": n, "button": 0, "action": "PICKUP"}` — `PICKUP`, `QUICK_MOVE`, `SWAP`, `THROW`, `PICKUP_ALL`, `QUICK_CRAFT`. |
| `POST /close` | Close the open container. |
| `POST /chat` | `{"message": "hello"}`; messages starting with `/` are sent as commands. |

## Example

```sh
TOKEN=$(jq -r .token ~/.minecraft/config/anaka.json)
H="Authorization: Bearer $TOKEN"

curl -s -H "$H" "localhost:27599/find?blocks=coal_ore&exposed=true&limit=1"
curl -s -H "$H" -X POST "localhost:27599/task?wait=120" -d '{"type":"mine","x":9,"y":75,"z":-5}'
curl -s -H "$H" -X POST "localhost:27599/task?wait=30" \
  -d '{"type":"craft","pattern":["minecraft:oak_log",null,null,null],"count":4}'
```

