# Event Bridge

A RuneLite plugin that turns in-game events into structured JSON and POSTs them
to an HTTP endpoint you choose: your own Discord bot, a website, a spreadsheet
script, any backend.

The plugin does not care who is listening. It sends one well-defined JSON
document per event; your receiver decides what to do with it.

**Nothing leaves your client until you set an Endpoint URL.**

## Setup

1. Install **Event Bridge** from the RuneLite Plugin Hub.
2. Open its settings.
3. Under **Connection**, set:
   - **Endpoint URL**: the full `http://` or `https://` URL that should receive
     the events. It is used exactly as entered; nothing is appended.
   - **Auth token** (optional): sent as `Authorization: Bearer <token>`. Leave
     it empty for receivers that need no authentication.
4. Under **Events**, choose what to send.

| Setting | Events | Default |
|---|---|---|
| Clan chat | `CLAN_CHAT` | off |
| Achievements | `QUEST_COMPLETED`, `COLLECTION_LOG`, `COMBAT_ACHIEVEMENT`, `CLUE_COMPLETED`, `ACHIEVEMENT_DIARY` | on |
| Levels and XP | `LEVEL_UP`, `XP_MILESTONE` | on |
| Loot | `LOOT` | on |
| Minimum loot value | skips `LOOT` events with a `totalValue` below this | 0 |
| PvP kills and deaths | `PLAYER_KILL`, `DEATH` | on |

Clan chat is off by default because it forwards what other people say.

## Request format

Every event is a separate request:

```
POST <Endpoint URL>
Content-Type: application/json; charset=utf-8
Authorization: Bearer <token>        (only when a token is set)
```

### Envelope

All events share the same outer shape; only `data` differs per `type`.

```json
{
  "schema": 1,
  "pluginVersion": "3.0.0",
  "type": "LEVEL_UP",
  "timestamp": 1795200000000,
  "player": { "name": "Zezima", "world": 302 },
  "location": { "x": 3222, "y": 3218, "plane": 0 },
  "data": { "skill": "Attack", "level": 70, "xp": 737627 }
}
```

| Field | Meaning |
|---|---|
| `schema` | Envelope version. Only changes on a breaking change to the envelope or an existing `data` shape. New event types and new optional fields do not change it. |
| `pluginVersion` | Version of the plugin that sent the event. |
| `type` | Event type, see below. Ignore types you do not know. |
| `timestamp` | When the event was detected, in epoch milliseconds. A retried request keeps the original value. |
| `player` | The logged-in player's name and current world. |
| `location` | World tile of the local player when known. For `PLAYER_KILL` it is the victim's tile. May be missing. |
| `data` | Event-specific fields. |

Tier values (`tier`) are always lowercase: `easy`, `medium`, `hard`, `elite`,
`master`, `grandmaster`, `beginner`.

`pvpArea` is `true` when the player is in the Wilderness or on a PvP or
Deadman world. Events are sent everywhere; filter on `pvpArea` if you only care
about PvP.

### Event types

#### `CLAN_CHAT`

A line in your clan's chat channel. `system` is `true` for clan broadcasts
(level-ups, drops, members joining), which have an empty `sender`.

```json
{ "sender": "Zezima", "message": "anyone up for barrows?", "system": false }
```

#### `QUEST_COMPLETED`

```json
{ "quest": "Dragon Slayer I" }
```

#### `COLLECTION_LOG`

A new collection log slot.

```json
{ "item": "Abyssal whip" }
```

#### `COMBAT_ACHIEVEMENT`

```json
{ "tier": "elite", "task": "Perfect Zulrah" }
```

#### `CLUE_COMPLETED`

```json
{ "tier": "hard" }
```

#### `ACHIEVEMENT_DIARY`

`area` is the area as the game names it.

```json
{ "tier": "hard", "area": "Varrock" }
```

#### `LEVEL_UP`

```json
{ "skill": "Attack", "level": 70, "xp": 737627 }
```

Level changes replayed on login and world hop are not reported.

#### `XP_MILESTONE`

Sent for skills at level 99 or higher, once per 100,000 XP gained.

```json
{ "skill": "Fishing", "xp": 13100000 }
```

#### `LOOT`

One event per drop, with every item in it.

```json
{
  "source": { "type": "NPC", "name": "Vorkath", "id": 8061 },
  "items": [
    { "id": 1753, "name": "Green dragonhide", "quantity": 2, "gePrice": 1450 },
    { "id": 11286, "name": "Draconic visage", "quantity": 1, "gePrice": 3100000 }
  ],
  "totalValue": 3102900,
  "pvpArea": false
}
```

- `source.type` is `NPC`, `PLAYER` (loot from a player you killed) or `LOOT_KEY`
  (the contents of a Wilderness loot key).
- `source.name` is the NPC or victim name, `source.id` the NPC id. Both are
  missing for `LOOT_KEY`.
- `gePrice` is the Grand Exchange price **per item**. `totalValue` is the sum of
  `gePrice * quantity`.

#### `PLAYER_KILL`

Another player died shortly after you damaged them. `damage` is the damage you
dealt in that fight, so a receiver can decide who gets credit when several
players attacked the same victim. `location` is the victim's tile.

```json
{ "victim": "Some Pker", "damage": 87, "pvpArea": true }
```

#### `DEATH`

You died. `killer` is the player who last damaged you, when there was one.

```json
{ "killer": "Some Pker", "pvpArea": true }
```

## Delivery

- Requests are sent in the background and never slow down the game.
- If a request fails because of a network error, it is retried up to 3 times
  (at most 50 waiting, 2 resent per game tick).
- A response other than 2xx is not retried. Your receiver should answer quickly
  with any 2xx status.
- An invalid Endpoint URL means nothing is sent; a warning is written to the
  RuneLite log once.

## Privacy

When an Endpoint URL is set, the plugin sends to that URL, and only to that URL:

- your player name and world, and your position on the map;
- the events you enabled, with the details shown above, including names of
  other players (kills, deaths, loot from players) and, if clan chat is on, what
  other clan members say in clan chat;
- your IP address, as with any web request.

Whoever runs the endpoint receives this data. Only use an endpoint you trust.
While the Endpoint URL is empty, nothing is sent at all.

## Building a receiver

Any web server that accepts a JSON `POST` works. A minimal example in Node.js:

```js
import http from "node:http";

http.createServer((req, res) => {
  if (req.method !== "POST") return res.writeHead(405).end();
  if (req.headers.authorization !== "Bearer my-secret") return res.writeHead(401).end();

  let body = "";
  req.on("data", (chunk) => (body += chunk));
  req.on("end", () => {
    res.writeHead(204).end(); // answer first, process after

    const event = JSON.parse(body);
    switch (event.type) {
      case "LEVEL_UP":
        console.log(`${event.player.name} reached ${event.data.level} ${event.data.skill}`);
        break;
      case "LOOT":
        console.log(`${event.player.name} got ${event.data.totalValue} gp from ${event.data.source.type}`);
        break;
      default:
        break; // ignore types you do not handle
    }
  });
}).listen(8080);
```

Tips:

- Check `schema` and ignore unknown `type` values, so new plugin versions do not
  break your receiver.
- Check the token if your endpoint is reachable from the internet.
- The plugin does not deduplicate. Use `player.name` + `type` + `timestamp` if
  you need to detect repeats after a retry.
