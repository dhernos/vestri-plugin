# Vestri Dynamic Servers for Velocity

This Java 17 plugin bridges Vestri's dynamic Minecraft instances into a running
Velocity proxy. The same JAR includes an optional Paper/Spigot console bridge.
Neither entry point provisions Docker containers itself.

## Builds and Latest Release

Build with Java 17 and `mvn clean package`. The installable shaded artifact is
`target/vestri-dynamic-servers-1.1.0.jar`, not `target/original-*.jar`. It includes
both `plugin.yml` and `velocity-plugin.json`, plus the Bukkit Adventure adapter.
Server APIs remain provided by Paper/Spigot or Velocity.

GitHub Actions builds and tests `main` on pushes and manual dispatch, then
uploads only the verified shaded JAR as a workflow artifact. Serialized publishing
updates only the fixed `latest` tag/release, preserving versioned releases. A
build superseded by a newer `main` commit skips publishing. The release asset
is named `vestri-dynamic-servers-<version>.jar`, and the release title is the Maven
version number. Rebuilding the same version replaces its asset if the contents
changed; older JAR assets on `latest` are removed. The backend installer consumes
`dhernos/vestri-plugin/releases/tags/latest` and accepts this dual-platform JAR.
The repository must be hosted or mirrored on GitHub with Actions enabled; the
local Gitea remote alone does not execute GitHub workflows.

Player chat and access denials share an Adventure `Vestri > ` prefix, with gray
separators and semantic status colors. Existing language and literal text are
preserved, including interactive join components. UUIDs and internal `mcgame-`
names/addresses are hidden from player text; console diagnostics retain their
operator details. Paper/Spigot uses the bundled Bukkit Adventure audience adapter.

## Proxy Player Management (1.1.0)

Deploy the 1.1.0 JAR to the Velocity proxy and restart the proxy. The panel's
**Player Management** section replaces the former main-subserver whitelist UI.
It requires server-management access and does not require a subserver to exist.
No template API key or external HTTP endpoint is needed for this feature.

The panel writes `vestri-player-policy.json` in the proxy working directory
(`data/vestri-player-policy.json` from the worker/server-root perspective).
The plugin checks IP bans at pre-login, then authenticated UUID bans and the
optional UUID whitelist during login. Bans take precedence over the whitelist.
It rereads policy on every login and every five seconds; active players who
are no longer permitted are disconnected on the next sweep. Missing policy on
first installation means whitelist disabled, with no bans. An invalid policy
denies new logins until repaired, without mass-disconnecting existing players.
Removing a previously loaded non-default policy also fails closed.

For existing deployments, the panel offers to migrate the first configured
subserver's enabled whitelist, retaining its UUID entries and enabled state.
A fresh, healthy journal from the new plugin is required before migration.
Saving first writes the proxy policy and then disables the old subserver
whitelist in `server.properties`. **Restart that subserver once after migration**
to apply the changed Minecraft setting. New subservers delegate whitelisting
to the proxy from creation. Independent access rules on other backends remain
independent. Deploy the updated plugin before creating such subservers.

### Login Journal

- `vestri-players.json` is an atomic snapshot refreshed every five seconds and
  on orderly shutdown: up to 10,000 recently observed authenticated UUIDs and
  the 1,000 most recently completed login events. Known players retain separate
  last-successful-login time/IP and last-attempt time/IP across proxy restarts.
- `vestri-player-logs/YYYY-MM-DD.jsonl` receives every completed event, including
  failures. Across all daily UTC files, each proxy retains at most 1,000 events:
  event 1,001 removes the oldest event, not all events for that player. Files
  older than today minus 29 days are also deleted (30 calendar days inclusive).
  Both limits apply at startup and before/after appends; age cleanup also runs
  on the five-second sweep. Archives can be downloaded in the server file browser.
  Startup streams existing archives to count lines, including legacy oversized
  files. Counts are cached during the day; retention deletes whole oldest files
  and atomically replaces only the boundary file with its retained newest lines.
  After startup that file has at most 1,001 lines, so appends never rescan huge
  historical archives. This directory is plugin-owned: do not edit dated files
  while the proxy runs. Unrelated files and symbolic links are not pruned.
- A successful login is recorded only after the first backend connection.
  Pre-login denials, UUID/whitelist denials, early disconnects, and backend
  connection rejections are recorded with their outcome and available reason.
  A successful fallback after a rejected backend can generate a separate success.
- When authentication closes the connection before a player object exists,
  the five-second sweep records a failed attempt with the supplied username,
  remote IP, start time, and a generic connection/authentication failure reason.
  Pending attempts are also finalized on orderly shutdown, or after five minutes
  without completion. A client-supplied pre-login UUID is never treated as an
  authenticated identity; the UUID field stays absent until Velocity verifies it.
- Packets rejected before Velocity exposes a username-bearing pre-login event
  cannot supply a player identity to the plugin. Historical attempts from before
  the update cannot be reconstructed. In offline mode, identity guarantees are
  those of Velocity's configured authentication scheme.

IP addresses are Velocity's remote addresses. If a load balancer is in front,
configure trusted address forwarding at the proxy/network layer; do not expose
trusted PROXY-protocol listeners directly to arbitrary clients. UUIDs and IPs
are sensitive data: restrict server-file and management access, protect backups,
and account for snapshot retention separately from the archive's combined
1,000-event/30-day retention. Operational logs and operator-created backups are
not covered by player-event retention and require their own retention policy.

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
  "deletionGraceSeconds": 300,
  "preparationTimeoutSeconds": 300
}
```

Newly generated configuration includes this section with `enabled: false`.
Existing configuration is not overwritten. Set `enabled: true` explicitly to
allow owned spawning. Settings are reread without a restart. Grant players the
Velocity base permission `vestri.servers` and the action-specific permissions
listed below. The base permission alone does not grant every command.
Console/NPC owned spawns are trusted console
actions and do not require the selected player to hold these permissions, but
still obey configuration and per-user limits.

```text
/vestriservers spawn permanent Survival
/vestriservers spawn temporary Bed Wars
/vestriservers list
/vestriservers join Survival-1
/vestriservers delete BedWars-1 --confirm
/vestriservers delete Survival-pending-1 --confirm
/vestriservers retry
/vestriservers retry Survival-pending-1
/vestripause BedWars-1
/vestriservers invite Survival-1 Friend
/vestriservers revoke Survival-1 Friend
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
- Player messages use colored status text and friendly server names, not UUIDs
  or internal Docker addresses. List output includes ownership/invited access,
  lifecycle type, state, and pause status, with a remaining-seconds countdown
  for pending deletion. Permitted joins are clickable in the list. Pending
  spawns have a temporary name such as `Survival-pending-1`; successful spawns
  use the backend's actual name such as `Survival-1`. Old saved records are
  assigned safe local names and resolved to registered names when available.
  Completion only offers names visible to that player. Deletion requires
  `--confirm` and refuses occupied servers. `/vestriservers retry` without a
  name retries the owner's single pending spawn; if more than one is pending,
  choose its name from the list. Legacy IDs still work as input for authorized
  users, but are never shown in player-facing output.
- **Failed preparations** can be deleted immediately by their pending name with
  `/vestriservers delete <name> --confirm`; retrying first is not required.
  Otherwise maintenance cleans them up after `preparationTimeoutSeconds`
  (300 seconds by default), for both permanent and temporary requests. The list
  shows the remaining cleanup countdown. Cleanup resolves the original request
  UUID using the original template key, deletes any created empty instance and
  its files, and then releases the quota reservation. It never creates or starts
  an instance just to delete it. Occupied instances are preserved; lookup or
  deletion failures retain the record and retry with maintenance backoff.

### Invitations and Join Access

Owners can invite an online player to a created **permanent** server:

```text
/vestriservers invite Survival-1 Friend
```

The invitation is stored by Minecraft player UUID in the ownership registry and
survives proxy restarts. The invited player can use
`/vestriservers join Survival-1` even when the owner is offline. They need the
base and ordinary join permissions; an invitation grants access to the server,
not command permissions. The first invitation requires the guest to be online
so the proxy can obtain their authenticated UUID. Player names changing later
do not change UUID-based access.

Only the owner can issue or revoke invitations. Guests cannot delete, pause,
invite others, or retry another owner's pending spawn, even if they hold those
command permissions. `/vestriservers revoke Survival-1 Friend` removes an
invitation and works while the guest is offline; use the guest's saved name
shown in the owner's server list. Revocation blocks subsequent joins and
cancels a join still waiting for readiness. It does not kick players who have
already connected. Temporary servers do not accept persistent invitations.

The ordinary join command accepts **only owned or invited** servers. Admin join
permission bypasses this ownership/invitation check but does not grant ownership
or other management rights. Both normal and admin joins still respect expired
deletion windows and unavailable/deleting servers. Access and join permissions
are rechecked while the transfer waits for Minecraft readiness.

The plugin does not grant or register `/server`. Trusted transfers initiated by
your party plugin or another proxy plugin remain separate from these player
commands. Keep `/server` restricted in its owning plugin as before.

### Velocity Permissions

Configure these on the proxy, for example through Velocity LuckPerms. All are
explicit checks; the base permission is required alongside an action permission.

| Permission                       | Allows                                                                                     |
| -------------------------------- | ------------------------------------------------------------------------------------------ |
| `vestri.servers`                 | Base access to player server commands                                                      |
| `vestri.servers.list`            | List owned and invited servers (all managed servers for join admins)                       |
| `vestri.servers.spawn.permanent` | Create permanent servers                                                                   |
| `vestri.servers.spawn.temporary` | Create temporary servers                                                                   |
| `vestri.servers.retry`           | Retry an owner's pending spawn; reconnecting an existing server also needs join permission |
| `vestri.servers.join`            | Join owned or invited servers                                                              |
| `vestri.servers.join.admin`      | Join any managed server; replaces the ordinary join permission for admins                  |
| `vestri.servers.delete`          | Delete owned, empty servers with confirmation                                              |
| `vestri.servers.pause`           | Toggle retention for owned temporary servers                                               |
| `vestri.servers.invite`          | Invite players to owned permanent servers                                                  |
| `vestri.servers.revoke`          | Revoke invitations on owned servers                                                        |

For a guest-only role, grant `vestri.servers`, `vestri.servers.list`, and
`vestri.servers.join`. Grant `vestri.servers.join.admin` only to staff who should
access every server. There is no default OP/player bypass. Console bridge
commands remain console-only and trusted; clicking a console-action NPC is
controlled by that NPC plugin's interaction permissions, not by the selected
player's spawn permissions.

For an owned **console or NPC** spawn, select both a lifecycle type and owner:

```text
vestridynamic spawn Survival --type permanent --send-player {player}
vestridynamic spawn Bed Wars --type temporary --send-player {player}
vestridynamic spawn Survival --type permanent --send-player {player} --request-id <request UUID>
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
spawn outcomes keep their quota reservation until retried successfully or safely
cleaned up. Use `/vestriservers retry`, or delete the pending name with
`/vestriservers delete <name> --confirm`, rather than creating a fresh request.
Each failed preparation starts a persisted `preparationTimeoutSeconds` cleanup
deadline; another failed retry resets it, and success cancels it. Requests still
in progress are serialized with cleanup, so maintenance cannot delete a spawn
while it is running. Existing pending records without a deadline receive a full
timeout on the first maintenance pass after upgrading. Saved deadlines survive
restarts and are not extended by downtime or later setting changes. Requests and pause state survive
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

Failed-preparation cleanup additionally requires the updated backend's
template-key-scoped `GET /api/minecraft/v1/instances/requests/{requestId}` lookup.
Deploy the backend before enabling the updated proxy JAR. An unavailable or
older backend leaves pending records intact rather than assuming their instances
do not exist. `preparationTimeoutSeconds` must be between 1 and 86400 seconds;
omitting it uses 300. Successfully prepared servers retain their normal lifecycle.

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
vestridynamic spawn Bed Wars --send-player {player}
vestridynamic spawn Bed Wars --send-player {player} --request-id <request UUID>
vestridynamic sync
```

- `templates` lists configured aliases, not all templates in the panel.
- `spawn` copies the saved template, starts the server, and synchronizes Velocity
  through the existing backend/worker pipeline. Success reports a server UUID and
  means **starting**, not ready for players. The plugin automatically retries
  HTTP 502/503/504 spawn responses up to two times with the exact same request
  UUID and template key, waiting five seconds before the first retry and ten
  seconds before the second. This gives transient failures time to clear and
  can recover post-creation start/reload failures without allocating a second
  instance. Authorization, validation, and quota
  errors are not automatically retried. If all attempts fail, owned instances
  remain reserved and can be retried by name. Operator diagnostic logs retain
  request UUIDs for untyped console retries and backend correlation; player
  chat never includes those UUIDs.
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
tokens. Recognized backend failure messages also identify the failing operation,
such as copying the template, starting the instance, or reloading Velocity;
unrecognized response text is never printed. Consult backend logs for details.

### Console Spawn Returns HTTP 502

If the backend console prints `Forwarding` and Velocity prints `Preparing`, the
console bridge delivered the command. A later HTTP 502 is a backend/upstream
failure, not a missing player command permission. Owned console spawns and
`/vestriservers retry` use the same API and saved request UUID; a successful
manual retry can indicate a transient failure that cleared between attempts.

`VESTRI_DYNAMIC_SYNC_OK` alone does not mean spawning succeeded: the backend
checks the proxy bridge before copying a template and synchronizes it again
after starting the new instance. Check the final API error for the failing
operation and correlate the proxy's `Vestri spawn diagnostic` request UUID with
backend/worker logs from the same time. Backend `post_commit_failure` records
identify `stage=start` or `stage=reload` for failures after the instance is saved.

Retry the pending server with `/vestriservers retry` (or its name when several
are pending), or repeat the forwarded console command with its original
`--request-id`. Clicking the NPC again normally generates a **new** request UUID
and can create a separate server. Deploy the newly built JAR to Velocity and
restart the proxy to activate delayed retries and improved error diagnostics.

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
and disconnect cancellation. Owned-server tests cover named chat/completion,
per-action permissions, permanent invitations, offline-owner joins, admin join
bypass, owner-only management, persisted invitation revocation, queued transfer
cancellation, and automatic same-request recovery of an initial NPC spawn 502.
Preparation cleanup tests cover confirmed owner deletion without provisioning,
lost-response instance lookup, occupied-server protection, configurable deadlines,
restart persistence, retry resets, successful-retry cancellation, and retained
quota/backoff when lookup or deletion fails.
The Go backend logs post-commit failures with their start/reload stage and safe
worker error classification. The exact cause of a live post-creation 502 requires
correlated backend/worker logs; fake API tests verify recovery, not a claim that
every worker or network failure is eliminated. Validate
actual container startup, proxy forwarding, and NPC interaction on your network
after deploying the updated JAR to both the proxy and the NPC's backend.
