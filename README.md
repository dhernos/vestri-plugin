# Vestri Dynamic Servers for Velocity

This Java 17 plugin bridges Vestri's dynamic Minecraft instances into a running
Velocity proxy. The same JAR includes an optional Paper/Spigot console bridge.
Neither entry point provisions Docker containers itself.

## Existing responsibilities

- On proxy startup, read `vestri-dynamic-servers.json` if it exists.
- Handle the console-only `vestridynamic sync` command sent by Vestri's worker
  after dynamic instance changes, without restarting Velocity.
- Register new dynamic backends and unregister stale ones in Velocity's live
  server registry. Internal IDs must match `mcgame-<32 hexadecimal characters>`,
  hosts must be `vestri-<internal ID>`, and ports must be 25565. New instances have
  friendly registry names such as `BedWars-1`, independent of their internal IDs.
- Reject oversized or invalid manifests before changing the registry, and leave
  ordinary servers such as a manually configured lobby alone.

The backend writes the manifest and the worker triggers synchronization. The
plugin does not balance players. Plain spawn commands do not
wait for readiness or move players; the optional `--send-player` flag waits for
a Minecraft status ping and connects the selected player to that exact instance.
Player-owned instances additionally have automatic empty-server maintenance and
on-demand wake-up as described below.

## Player-owned permanent and temporary servers

Add `playerServers` alongside `backendUrl`, `templates`, and `consoleBridge` in
the proxy's existing `vestri-dynamic-config.json`:

```json
"playerServers": {
  "enabled": true,
  "maxPermanentPerUser": 1,
  "maxTemporaryPerUser": 2,
  "pauseEnabled": true,
  "maxPausedPerUser": 1,
  "emptyGraceSeconds": 30,
  "deletionGraceSeconds": 300
}
```

Newly generated configuration includes this section with `enabled: false`.
Existing configuration is not overwritten. Set `enabled: true` explicitly to
allow owned spawning. Settings are reread without a restart. Grant players the
Velocity permission `vestri.servers`, and additionally `vestri.servers.pause`
for the separate pause command. Console/NPC owned spawns are trusted console
actions and do not require the selected player to hold these permissions, but
still obey configuration and per-user limits.

```text
/vestriservers spawn permanent Survival
/vestriservers spawn temporary Bed Wars
/vestriservers list
/vestriservers join <server UUID>
/vestriservers delete <server UUID> --confirm
/vestriservers retry <request UUID>
/vestripause <server UUID>
```

- **Permanent** servers remain registered and running when empty. Owners may
  explicitly delete them; they cannot be paused.
- **Temporary** servers shut down after all players leave and
  `emptyGraceSeconds` has elapsed. They then remain registered with their files
  intact in `pending_deletion` for `deletionGraceSeconds` (five minutes by
  default). Rejoining during that window restarts the server and cancels
  deletion. After the deadline, maintenance permanently deletes the server and
  its files; an expired window cannot be revived by joining. Switching to
  another backend and disconnecting from the proxy both make the server empty.
  Occupancy includes every player, not just the owner. A later departure starts
  a fresh shutdown/deletion cycle.
- Starting a **fresh owned server**, permanent or temporary, immediately
  deletes that owner's empty, unpaused temporary servers pending deletion.
  This happens before allocating the new instance, freeing temporary quota
  slots. Player and console/NPC spawning use the same rule. Paused, occupied,
  permanent, and other players' servers are preserved. Retrying a saved request
  UUID rejoins/retries the existing server instead of discarding pending ones.
  Invalid configuration, unknown templates, and rejected local quota checks
  do not trigger cleanup. Deletion must succeed before provisioning starts;
  the old files cannot be restored if the new spawn subsequently fails.
- `/vestripause` toggles retention for an owned temporary server. A paused server
  is stopped when empty, but its files and registration remain. Joining through
  `/vestriservers join` or attempting an ordinary Velocity connection to that
  backend starts it again and uses the existing readiness wait and transfer.
  Ordinary connection attempts can wake it for any player; listing, explicit
  join commands, pause changes, and deletion remain owner-scoped. Normal backend
  whitelist and access rules still apply. Pause stays enabled after joining,
  so the next empty period stops the server again rather than deleting it.
  Pausing a pending-deletion server cancels its deadline and keeps it stopped.
  Toggling pause off while it is stopped enables the normal shutdown/deletion
  window again.
- Limits are per Minecraft player UUID and lifecycle type, across **all**
  configured templates. Pending spawns, pending-deletion servers, and paused servers count toward their
  type's limit. Paused servers additionally count toward `maxPausedPerUser`.
  Zero disables that allocation. Reducing limits does not delete existing
  servers, and disabling pause does not remove existing retention; owners can
  still toggle it off. Disabling `enabled` prevents new owned spawns but does
  not disable management or cleanup of existing owned servers.
- List output includes template, lifecycle type, state, pause status, and server
  UUID, with a remaining-seconds countdown for pending deletion. A pending spawn
  shows its request UUID instead. Join/delete/retry accept
  an owner's saved request UUID as well as the instance UUID. Deletion requires
  `--confirm` and refuses occupied servers. Completion offers only your IDs.

For an owned **console or NPC** spawn, select both a lifecycle type and owner:

```text
vestridynamic spawn Survival --type permanent --send-player Luis
vestridynamic spawn Bed Wars --type temporary --send-player {player}
vestridynamic spawn Survival --type permanent --send-player Luis --request-id <request UUID>
```

`{player}` is an NPC placeholder, not literal Velocity syntax. The owner must be
connected to this proxy. `--type` requires `--send-player`. These commands use
the same limits and ownership records as player commands. Retrying the same
request UUID reuses the owned instance and transfer instead of allocating
another server. A request UUID cannot be reused for another owner, template, or
lifecycle type.

The proxy persists ownership, request UUIDs, pause flags, power state, and deletion deadlines in
`vestri-player-servers.json` in its working directory using atomic replacement.
Keep this file on persistent storage and back it up. An invalid registry fails
closed for player management instead of losing ownership or deleting unknown
servers. Never edit or remove it while the proxy is running. Original template
API keys are still required to operate their instances; replacing or revoking
a key does not transfer its ownership in the backend.

Maintenance checks every five seconds. A new spawn or waking server has a
six-minute join allowance while the five-minute readiness transfer runs;
successful connection ends that allowance. A cancelled or failed initial
transfer therefore leaves a brief window to retry before an unpaused temporary
instance is shut down and enters its rejoin window. `emptyGraceSeconds` controls
the running-empty delay before shutdown; `deletionGraceSeconds` controls the
stopped-server rejoin window after successful shutdown. Both must be at least
one. Existing configuration defaults to 300 seconds when the new setting is
omitted. Changing the setting affects future windows, not deadlines already
saved. Deadlines survive proxy restarts and are not extended by downtime;
expired instances are cleaned up after the proxy resumes. Failed power
or deletion requests preserve retryable records and maintenance backs off for
one minute. A missing backend instance is treated as already deleted. Unknown
spawn outcomes keep their quota reservation: retry the saved request UUID before
deleting rather than creating a fresh request. Requests and pause state survive
proxy restarts; automatic empty maintenance runs only while this proxy is up.

Untyped console spawns (including existing `--send-player` commands), existing
panel instances, and instances created before this feature are **not** adopted
automatically. They retain their existing lifecycle and do not count toward
player limits. Add `--type` to NPC commands to opt in.

Deploy the updated Go backend and plugin JAR together. Pausing uses new
template-key-scoped `POST /api/minecraft/v1/instances/{instanceId}/stop` and
`/start` endpoints. They use the same exact-key ownership checks as deletion and
preserve files and proxy registration. No worker API key is exposed to players
or backend plugins. Existing API-key expiry, rate limits, and node/template
instance limits still apply.

## Numbered server names

New saved-template instances use `TemplateName-number` in the panel, Velocity's
configuration, and Velocity's live server registry. For example, `Bed Wars`
produces `BedWars-1`, `BedWars-2`, and so on, so tab-list plugins reading the
Velocity server name see those names instead of an opaque UUID.

Spaces and unsafe characters are removed from the template prefix; case is
preserved, prefixes are limited to 64 characters, and an empty prefix falls back
to `Minecraft`. Allocation uses the lowest free positive number on that proxy,
checks names case-insensitively, and runs under the existing node lock. Deleting
an instance frees its number for reuse. Templates whose sanitized names collide
share the available numbering space. Internal Docker stacks, paths, and addresses
still use UUID IDs. Existing instances retain their old registry names until
recreated. The new backend and proxy JAR must be deployed together.

## Install and configure

Build from this directory with `mvn clean package`. Install
`target/vestri-dynamic-servers-1.0.0.jar` in the proxy's `plugins` directory and
restart the proxy to load the updated plugin.

In Vestri's node Minecraft library, create a saved dynamic server template linked
to this Velocity proxy. Create a Minecraft API key scoped to that template. Repeat
for each template the console should manage. These are saved templates with server
files, not the built-in software choices such as `minecraft-vanilla`.

On startup, the plugin creates `vestri-dynamic-config.json` in Velocity's working
directory, beside `velocity.toml` and `vestri-dynamic-servers.json`, if it is
missing. Existing files are never overwritten. The generated file has an empty
`templates` object and a random bridge secret with an empty backend allowlist;
commands remain unconfigured until you fill in the URL and template keys. On
POSIX filesystems the generated file is readable/writable only by its owner.

Set `backendUrl` to your **public frontend origin**, even though the setting retains
its original name. Fill `templates` with real saved-template API keys, for example:

```json
{
  "backendUrl": "https://panel.example.com",
  "templates": {
    "Bed Wars": "vmc_<64 hexadecimal characters from the template API key>",
    "Survival": "vmc_<64 hexadecimal characters from another template API key>"
  }
}
```

Replace the example URL and keys. Names are local command aliases; use the same
names as the saved templates to keep them recognizable. Matching is
case-insensitive and supports spaces. The API key, not the alias, determines the
actual template and node. Only configure templates belonging to this proxy.

The frontend already proxies `/api/*` to the Go backend using `GO_API_URL`,
including these Minecraft endpoints, request bodies, and Bearer authorization.
The backend does not need to be publicly exposed. For a Docker deployment, the
frontend must be able to reach the backend internally (the bundled Compose stack
sets `GO_API_URL` to `http://backend:8080` with the default backend port). Apply
that setting when building/deploying the frontend because its rewrites are
generated from Next.js configuration.

Use `https://your-panel.example`, not `https://your-panel.example/en` or a URL
ending in `/api`. The plugin appends `/api/minecraft/v1/instances`. A deployment
prefix is supported if your frontend is actually hosted below that prefix.
Direct backend origins still work when reachable. HTTPS is required except for HTTP on
`localhost`, `127.0.0.1`, or `::1`. Inside a proxy container, loopback refers to that
container, not the host or backend container. The configured URL must be reachable
from the proxy. Redirects are not followed, so use the final frontend URL.

`Invalid plugin configuration` means validation failed **before sending an API
request**, not that Docker blocked the connection. New errors identify malformed
JSON, missing fields, invalid URLs, or invalid template-key formats without
printing secrets. Replace `vmc_<...>` examples with the actual issued token:
`vmc_` followed by exactly 64 hexadecimal characters. An empty `templates` object
also needs at least one key before lifecycle commands can run.

To check frontend-to-backend routing without using a secret, request
`https://your-panel.example/api/minecraft/v1/instances` without authorization.
The backend should return HTTP 401 with a Bearer-key error. HTTP 500/502 indicates
a routing/upstream problem; HTML or a redirect usually means the URL points at a
page rather than the API.

Protect this file like a password: restrict it to the proxy service account, do
not commit it, and never put a worker-wide API key in it. Configuration is read on
every lifecycle command, so changes do not require a restart. API keys can expire
or be revoked, and require their creator to retain node administration access.

## Console commands

Run these in the **Velocity console**, without a leading slash. Players cannot
run them, including players with wildcard permissions.

```text
vestridynamic templates
vestridynamic spawn Bed Wars
vestridynamic list Bed Wars
vestridynamic delete Bed Wars --confirm
vestridynamic delete Bed Wars --instance <server UUID> --confirm
vestridynamic spawn Bed Wars --request-id <request UUID>
vestridynamic spawn Bed Wars --send-player Luis
vestridynamic spawn Bed Wars --send-player Luis --request-id <request UUID>
vestridynamic sync
```

- `templates` lists configured aliases, not all templates in the panel.
- `spawn` copies the saved template, starts the server, and synchronizes Velocity
  through the existing backend/worker pipeline. Success reports a server UUID and
  means **starting**, not ready for players. The command prints a request UUID
  before contacting the API. If it fails or times out, retry with that same UUID
  and the same template/key instead of issuing a fresh spawn.
- `spawn ... --send-player <player name>` first checks that the player is online
  on this proxy, then spawns normally. After the API returns, it waits up to five
  minutes for that exact instance to appear in Velocity and answer a Minecraft
  status ping. It retries readiness checks every two seconds, with a five-second
  maximum for each ping. Successful checks must span at least six seconds before
  a private five-second countdown begins: `Teleporting in 5...` through `1...`.
  The proxy pings again before each tick and before connecting, scheduling checks
  one second apart during the countdown. Slow pings can extend the countdown.
  A failed ping or changed registry entry resets both the warmup and countdown.
  Player connections have a ten-second maximum within the remaining
  readiness deadline. Refused or unreachable network connections restart the
  readiness checks; explicit login rejections remain final. Waiting and transfer
  results appear privately in the selected player's chat, regardless of OP
  status, with short colored messages and no UUIDs or internal addresses.
  Instance UUIDs and transient retry diagnostics stay in the proxy console.
  Plain spawn behavior is
  unchanged when the flag is omitted. The flag is only valid for `spawn`.
- `list` shows server UUIDs accessible to the configured template key.
- `delete` stops the instance, unregisters it, and permanently removes its files
  and record. `--confirm` is mandatory. Without `--instance`, exactly one instance
  must exist; zero or multiple instances cause refusal, never a bulk deletion.
  With `--instance`, the UUID must appear in that key's instance list.
- `sync` only reconciles the local manifest; it does not create or delete servers.

Console tab completion suggests subcommands (`vestridynamic s` completes to
`spawn` or `sync`), configured template aliases (`vestridynamic spawn Ben`
completes to `BentoBox`), and action-specific flags such as `--confirm`.
Template matching is case-insensitive and preserves configured spelling.
Multiword names are supported without quotes. UUID values are not completed.
The proxy rereads template names from its config when completing; no API request
is made, and players receive no suggestions. `--send-player` is suggested on
both proxy and backend consoles, but player-name values are not completed.

Docker reporting a container as running is not Minecraft readiness. Status pings
use Velocity's Minecraft protocol implementation, not a TCP-only port check;
leave `enable-status=true` in the template's `server.properties`. A successful
ping still does not guarantee login: whitelist, bans, a full server, incompatible
versions, or incorrect proxy forwarding can reject the connection. Such failures
are reported instead of claiming a successful transfer. This does not wait for
arbitrary gameplay plugins to finish their own initialization.

The selected player session is captured before spawning. A disconnect cancels
the transfer, including a disconnect during provisioning; reconnecting does not
resume it. A readiness timeout or connection failure never deletes the created
instance. Inspect it in the panel and retain the request UUID. Retrying spawn
with the same request UUID and `--send-player` reuses the instance and starts a
new readiness wait for the currently connected player. Do not issue a fresh
spawn to retry a transfer. Multiple independent spawn commands remain separate
requests; configure NPC cooldowns to avoid spawning multiple instances per player.

Lifecycle requests run on Velocity's scheduler so the console remains available
for the worker's `sync` callbacks. The backend enforces permissions, template and
node instance limits, request idempotency, and a 30-request/minute API-key limit.
Listing and deleting count separately toward that limit.

A key can only list/delete instances created with that exact key. Panel-created
instances and instances created under a replaced key will not appear; manage
those in the panel or with their original valid key. Keep the printed request UUID
for a failed spawn, since provisioning or a proxy reload may have partially
succeeded. API errors show an HTTP status without exposing response bodies or
tokens; consult backend logs for details.

## Backend console and FancyNPCs

Velocity commands are not automatically available in a Paper server's console.
An **unknown command** on a subserver means its local command is not registered:
install the JAR there and restart that backend. Installing it only on Velocity
cannot add a command to Paper's console registry. Other plugins may provide their
own proxy-specific NPC actions or forward plugin messages, but a standard
execute-console action runs locally and needs this bridge.
For an NPC plugin's **execute console command** action, install the same JAR in
the Paper/Spigot backend's `plugins` directory as well. On that server it loads as
`VestriConsoleBridge` and registers a console-only `vestridynamic` forwarding
command. Do not use a player-command action; players are rejected even with OP
or wildcard permissions. All lifecycle commands forwarded through this bridge
execute as the Velocity console and therefore use the proxy's template-key
configuration, regardless of which allowed backend sent them.

Generate a shared bridge secret outside your logs, for example with
`openssl rand -hex 32`. Add the following section to the proxy's existing
`vestri-dynamic-config.json`, alongside `backendUrl` and `templates`:

```json
"consoleBridge": {
  "secret": "<same 64-character hexadecimal bridge secret>",
  "allowedServers": ["lobby", "BedWars-1"]
}
```

`allowedServers` contains exact Velocity registry names of trusted servers that
may originate commands, not template aliases. Add every backend that needs NPC
actions. The bridge is disabled when this section is absent. Keep the allowlist
narrow; any trusted backend console can use every template configured on the
proxy. On each allowed backend, put the same secret in the generated
`plugins/VestriConsoleBridge/config.yml`:

```yaml
bridge-secret: "<same 64-character hexadecimal bridge secret>"
template-names:
  - "BentoBox"
  - "Bed Wars"
```

`template-names` is optional and used only for backend console completion; it
does not grant access or select the actual template. Use the aliases configured
on Velocity. Backend subcommands and flags complete even without this list.
No API keys are needed in the backend config. Existing backend config files are
not overwritten; add this list manually to enable template-name completion.

The bridge secret is separate from the `vmc_` template API keys. Never distribute
those API keys to backend servers. Both sides reread their configuration when
handling a command; restrict all secret files to their service accounts. Restart
both servers after first installing the JAR.

In FancyNPCs, select its execute-console-command action and use this command text:

```text
vestridynamic spawn Bed Wars
```

To spawn and then send the clicking player to the new instance, use this command
instead:

```text
vestridynamic spawn Bed Wars --send-player {player}
```

FancyNPCs replaces `{player}` with the clicking player's name before executing
the console command. For example, configure an NPC named `BedWarsNpc` with:

```text
/npc action BedWarsNpc RIGHT_CLICK add console_command vestridynamic spawn Bed Wars --send-player {player}
```

Replace the existing spawn action rather than adding a second spawn. Keep the
console bridge installed and configured on the NPC's backend. No separate
FancyNPCs `wait` or `send_to_server` action is needed, and this transfer does not
require Velocity's BungeeCord plugin-message compatibility channel. The proxy
uses the spawn response's internal slug to find the exact registered address;
it never guesses a numbered name such as `BedWars-1`. All lifecycle commands
remain console-only, even with `--send-player`.

For deletion, use `vestridynamic delete Bed Wars --confirm`, or include
`--instance <server UUID>` when more than one instance exists. Configure NPC
interaction permissions/cooldowns in your NPC plugin: console actions are trusted
automation, so an unrestricted clickable NPC can deliberately trigger the action
for any player. The bridge itself does not grant players command permissions.

This uses authenticated plugin messaging on `vestri:console`. A player must be
connected to the sending backend to carry the message, which is normally true
when an NPC is clicked. On an empty backend the command reports that it cannot
send; use the Velocity console directly for empty-server automation. A backend
console message confirms sending, not API success. Results and errors appear in
the proxy console. Spawn prints its forwarded command including `--request-id`
in the backend console, so failures can be retried with that same ID.

Velocity rejects player-originated messages, unlisted backend sources, invalid
signatures, timestamps outside a 30-second window, and replayed requests. Keep
server clocks synchronized. Only the validated lifecycle commands are forwarded;
arbitrary proxy commands and `sync` are not exposed through this channel.

## Verification

`mvn test` covers manifest synchronization and friendly-name conflicts,
console-only dispatch, multiword names, confirmation and retry parsing,
configuration loading, HTTP create/list/delete with scoped keys, signed-message
authentication, replay/expiry rejection, backend source restrictions, and shared
protocol loading without either server API. HTTP tests use a local fake backend;
they do not provision real Minecraft containers or simulate live NPC clicks.
Player-transfer tests cover the optional flag, authenticated forwarding,
offline-player refusal, exact spawn-target resolution, late registration,
failed pings, readiness expiry, disconnected/reconnected sessions, registry
changes during a ping, stable-readiness warmup and resets, refused-connection
retries, UUID-free player messages, successful transfers, and rejected connections.
Countdown tests also cover tick order, one-second scheduling, readiness resets,
and disconnect cancellation. Validate
actual container startup, proxy forwarding, and NPC interaction on your network
after deploying the updated JAR to both the proxy and the NPC's backend.
