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

Player chat and access denials share an Adventure `Vestri > ` prefix, with uniform
aqua-blue text, including nested text and join tooltips. Existing language and literal text are
preserved, including interactive join components. UUIDs and internal `mcgame-`
names/addresses are hidden from player text; console diagnostics retain their
operator details. Paper/Spigot uses the bundled Bukkit Adventure audience adapter.

## Plugin Data Folder

Velocity stores all plugin configuration and state in `plugins/vestri-dynamic/`:

```text
plugins/vestri-dynamic/
  vestri-dynamic-config.json
  vestri-dynamic-servers.json
  vestri-player-servers.json
  vestri-player-policy.json
  vestri-players.json
  vestri-player-logs/
```

Existing files beside `velocity.toml` move automatically on the first startup
with this plugin version. Configuration, ownership, policy, journals, and log
archives are preserved. If both locations contain the same file name, startup
fails closed rather than overwriting either copy; resolve the conflict while
the proxy is stopped. Back up the whole plugin folder on persistent storage.
Deploy the updated Vestri backend before restarting the upgraded proxy so panel
file access follows the new location; older plugins retain legacy-path support.
Paper/Spigot already keeps its bridge configuration in
`plugins/VestriConsoleBridge/config.yml`.

## Local Country Lookup

Deploy the updated plugin JAR and restart the Velocity proxy to enable country
lookup for player management; older installed plugins do not support it. The
plugin automatically downloads the current UTC month's DB-IP Country Lite
database from `https://download.db-ip.com/free/dbip-country-lite-YYYY-MM.mmdb.gz`
in the background and stores it at
`plugins/vestri-dynamic/vestri-country.mmdb`. No account or API key is required,
and player IP addresses are never sent to external HTTP services. The shaded
JAR includes the MaxMind DB reader; lookups use only the local database in memory.

Startup loads an existing valid database immediately. A single daemon checks
daily and skips downloads when the local file's modification time is in the
current UTC month. Monthly replacements are validated before atomic installation.
Failed or unavailable downloads retain the previous database and retry on the
next daily check, so existing data continues to work offline. Downloads have
bounded connection/read timeouts and a 64 MiB decompressed limit.

Missing data, invalid addresses, hostnames, private/local addresses, and unknown
countries produce no country code. Unknown countries do not match a country
ban, but cannot satisfy an enabled country whitelist. Country rules complement
the existing UUID/IP rules; they are not a reliable identity or location proof.

Country data is provided by [DB-IP](https://db-ip.com/db/download/ip-to-country-lite)
and licensed under [Creative Commons Attribution 4.0 International (CC BY 4.0)](https://creativecommons.org/licenses/by/4.0/).
Keep this attribution when distributing or using the downloaded data.

Default GeoIP tests use a synthetic MMDB fixture and never access the network.
An optional real download/restart check runs with
`VESTRI_GEOIP_TEST=1 mvn -Dtest=CountryLookupTest test` and requires DB-IP HTTPS
access and availability of the current month's download.

## Proxy Player Management (1.1.0)

Deploy the 1.1.0 JAR to the Velocity proxy and restart the proxy. The panel's
**Player Management** section replaces the former main-subserver whitelist UI.
It requires server-management access and does not require a subserver to exist.
No template API key or external HTTP endpoint is needed for this feature.

The panel writes `vestri-player-policy.json` in `plugins/vestri-dynamic/`
(`data/plugins/vestri-dynamic/vestri-player-policy.json` from the worker/server-root perspective).
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
plugin does not balance players for ordinary templates; minigame matchmaking
and roster assignment are described separately below. Plain spawn commands do not
wait for readiness or move players; for ordinary templates, `--send-player` waits
for a Minecraft status ping and connects the player to that exact instance.
Minigame instances also require roster admission; a ping cannot bypass that gate.
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
  Ordinary connection attempts enforce the owned-server ACL: only the owner,
  an invited player where supported, or an admin with join access can wake it.
  Listing and explicit joins follow owned/invited/admin access; pause changes
  and deletion remain owner-scoped. Normal backend
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
`plugins/vestri-dynamic/vestri-player-servers.json` using atomic replacement.
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
`target/vestri-dynamic-servers-1.1.0.jar` in the proxy's `plugins` directory and
restart the proxy to load the updated plugin.

In Vestri's node Minecraft library, create a saved dynamic server template linked
to this Velocity proxy. Create a Minecraft API key scoped to that template. Repeat
for each template the console should manage. These are saved templates with server
files, not the built-in software choices such as `minecraft-vanilla`.

On startup, the plugin creates `vestri-dynamic-config.json` in
`plugins/vestri-dynamic/`, beside `vestri-dynamic-servers.json`, if it is
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
- For ordinary templates, `spawn ... --send-player <player name>` first checks
  that the player is online on this proxy, then spawns normally. After the API
  returns, it waits up to five
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
  For minigame templates, this readiness wait alone does not authorize entry;
  see **Direct Instance Joins** below.
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

`VESTRI_DYNAMIC_SYNC_OK` confirms manifest synchronization only, not a successful
spawn or Minecraft readiness. It can come from the preflight proxy-bridge check
before copying a template; synchronization also runs after starting the new
instance. Check the final API error for the failing
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

## Parties and Minigames

The Velocity entry point provides transient parties and template-based minigame
queues. The Paper entry point coordinates readiness, roster admission, startup
commands, and end signals for each generated match server. Minigame
`instanceMode` is separate from player-owned permanent/temporary servers and
the console's ownership opt-in `--type`.

### Player Commands and Membership

Run these as players on the proxy:

```text
/party invite Friend
/party accept Leader
/party leave
/party kick Friend
/party list
/party disband
/party leader Friend
/minigame join Arena
/minigame join Bed Wars --variant 2v2
/minigame leave
/minigame list
```

Invitations require acceptance; the leader invites, kicks, disbands, or transfers
leadership with `/party leader <member>`. Party membership and invitations are
in memory only. Disconnecting removes the player from their party; if the leader
leaves or disconnects, a remaining member is elected. A proxy restart discards
all parties, invitations, and queues. These are not persistent server invitations.

Grant the exact Velocity permission `vestri.minigames` to every participant.
The party leader queues or leaves for the whole group; a player without a party
queues as a one-player group. `/minigame join <template>` takes a template, **not
a player argument**. All members must be connected, permitted, and neither queued
nor playing elsewhere. With configured teams, parties must fit within both
`teamSize` and `maxPlayers`.
When teams are omitted, `null`, or empty, the match is team-free (FFA):
`teamSize` is not required, and whole parties need only fit within `maxPlayers`.
Changing membership or disconnecting invalidates that group's queued entry;
the remaining players must queue again. `/minigame leave` withdraws a queue entry
or cancels a match still joining/starting, not a running game's gameplay session.

### Direct Instance Joins

A dashboard/web spawn creates a server, **not a player roster**. In
`lobbyMode: vestri`, players must use `/minigame join <template>`; `minPlayers`
remains the queue threshold even when a manually spawned instance is ready.
The command reports that the group is queued. Until the threshold is met and
the roster is assigned, an ordinary server connection cannot enter it.

The earlier manual-join failure came from attempting an ordinary transfer
without satisfying the minigame roster gate. A running server or successful ping
does not authorize entry. The current implementation routes a direct
native-lobby server request from a player already connected to a backend, and
console `spawn ... --send-player`, through minigame admission for the **exact
requested instance**, not another server from the template's pool.

Native admission enforces participant permissions, party leadership and whole
party capacity, waits for a fresh agent report, and applies a full roster update.
Paper must acknowledge the matching match ID and `rosterVersion` before Velocity
connects the admitted players. An ordinary ping is never a roster bypass.
Specific-instance readiness waits are bounded to 300 seconds; a full or
unavailable target reports the failure rather than silently choosing another
instance. `vestri.minigames` is required for each participant, including console
minigame admissions; trusted console ownership spawning is a separate feature.

For managed lobbies, console `--send-player` enters the normal template queue
and reports that it is waiting for `minPlayers`, with `/minigame join` guidance;
it does not force entry to the spawned instance. Ordinary non-roster connections
remain denied. Use `/minigame join <template>` for the managed workflow.

Manifest reconciliation adopts each manually created minigame instance
independently. It matches a pending preparation only by slug, server ID, or the
same caller `spawnRequestId` and template; sharing a template does not merge a
manual instance with another pending spawn. Adoption tracks the server but does
not assign players or grant owned-server access.

### Template Settings and Startup Commands

Create or edit a saved template in the dashboard's **minigame mode**, linked to
the intended Velocity proxy. Configure optional teams, player limits, the ready-server
pool, readiness, startup actions, and end behavior there. Minigame templates are
discovered through the backend-generated proxy manifest, including templates
with no instances yet. API keys are still configured **manually**: issue a
template-scoped Minecraft API key for each template and add it to the same
`templates` map in `plugins/vestri-dynamic/vestri-dynamic-config.json`. Keep aliases
equal to the dashboard template names for matchmaking. Use the existing
`backendUrl` setting and routing rules described above; this is not a second API
configuration, and no player name is stored in it. Do not put template API keys
in Paper configs.

Dashboard edits use `PATCH /api/nodes/{nodeRef}/minecraft/resources/{resourceId}`
with a full `minigame` replacement and optional `maxInstances`. Changed game
settings apply to **new instances only**: existing instances and saved-request
retries keep their pinned spawn-time settings. Incompatible ready instances are
drained rather than reconfigured; active matches retain their original lifecycle.
Setting `minigame.enabled: false` stops new queue admissions and replenishment
and removes queued entries; it does not end active matches. Deleting a template
that still has instances first disables its pool and, once published, returns
HTTP **409**: delete the instances, then retry template deletion. See
**Dashboard Deletion and Cleanup Recovery** below for committed HTTP 502 recovery.

This YAML illustrates the saved-template settings represented by the dashboard;
it is **not** a file to place in the plugin directory. The backend stores these
settings as JSON. Retain the template's existing base, world, plugin, and proxy
references when applying them:

```yaml
mode: minigames
maxInstances: 4
minigame:
  enabled: true
  lobbyMode: vestri
  instanceMode: temporary
  teamPolicy: roles
  teams: [speedrunner, hunter]
  teamSize: 8
  teamCounts: { speedrunner: 2 }
  remainderTeam: hunter
  minPlayers: 2
  maxPlayers: 8
  joinTimeout: 20s
  readyServers: 1
  lifecycle:
    ready:
      trigger: serverLoad
    onAllJoined: []
    teamCommands:
      - team: speedrunner
        command: "YOUR_SPEEDRUNNER_COMMAND {player}"
      - team: hunter
        command: "YOUR_HUNTER_COMMAND {player}"
    startCommands:
      - "manhuntstart"
    end:
      whenEmpty: true
      emptyGrace: 10s
      returnPlayersTo: lobby
      deleteServer: true
```

This is a minimal two-speedrunner Manhunt settings example, not a gameplay
plugin configuration. Replace `YOUR_SPEEDRUNNER_COMMAND` and
`YOUR_HUNTER_COMMAND` with that installed plugin's actual role-assignment console
commands; `manhuntstart` is the example final start command and must also match
the installed plugin. Commands have no leading slash. The bridge does not
implement game rules; the gameplay plugin actually starts the game.

`teamPolicy: roles` requires `lobbyMode: vestri`. Here exactly two individually
selected players become `speedrunner` and everyone else becomes `hunter`.
`teamSize` is each team's maximum capacity, not its required size; `maxPlayers`
is the total cap. Keep `minPlayers >= 2` for two fixed speedrunners. At exactly
two players there are no hunters; raise `minPlayers` if hunters must be present.
Roles intentionally split parties to satisfy exact counts. Selection shuffles
individual players, not whole parties; this is ordinary randomized assignment,
not a security-grade randomness guarantee.

Startup order is `lifecycle.onAllJoined` setup actions, then
`lifecycle.teamCommands` entries (each `{team, command}` runs once for every
player assigned to that exact team), then `lifecycle.startCommands` once-only
final commands. Team commands support `{player}`, `{uuid}`, `{team}`, and
`{matchId}`; final start commands accept only `{matchId}`. Together these arrays
are limited to 100 configured commands.

An action without `forEach` runs once and can use `{matchId}`. `forEach: team`
runs once per configured team and can also use `{team}`. `forEach: player` runs
once per roster player and can use `{player}`, `{uuid}`, and `{team}` as well as
`{matchId}`. Here `{player}` is the roster's Minecraft name, not an NPC placeholder.
For team-based matches, the proxy precomputes assignments; balanced policy keeps
whole parties on one team, whereas roles may split them. The per-player action
uses that assignment, rather than choosing teams
again on Paper. `{player}` and `{uuid}` are invalid outside player scope, and
`{team}` requires team or player scope.

`teamPolicy: balanced` is the default. Twelve solos selected together for four
teams of capacity four are distributed three per team. A party of four stays
intact on one team rather than being split to even the counts. Assignment uses
bounded packing to seek balanced sizes within capacity and randomizes ties; it
does not guarantee a mathematically optimal packing for every party combination.

### Named Bed Wars Variants

For a template named **Bed Wars**, this base supports four teams of four and a
smaller named variant. As above, retain the template's existing resource/proxy
references; these settings are saved through the dashboard/API, not a YAML file:

```yaml
mode: minigames
maxInstances: 4
minigame:
  enabled: true
  lobbyMode: vestri
  instanceMode: temporary
  teamPolicy: balanced
  teams: [red, blue, green, yellow]
  teamSize: 4
  minPlayers: 2
  maxPlayers: 16
  joinTimeout: 20s
  readyServers: 1
  variants:
    2v2:
      teams: [red, blue]
      teamSize: 2
      minPlayers: 2
      maxPlayers: 4
    4v4v4v4: {}
  lifecycle:
    ready:
      trigger: serverLoad
    onAllJoined:
      - command: "team add {team}"
        forEach: team
      - command: "team join {team} {player}"
        forEach: player
    teamCommands: []
    startCommands: []
    end:
      whenEmpty: true
      emptyGrace: 10s
      returnPlayersTo: lobby
      deleteServer: true
```

The generic per-player `team join {team} {player}` uses each variant's resolved
team list. Use a template without preexisting scoreboard teams for the `team add`
example. These vanilla commands set up scoreboard teams only: configure the
installed Bed Wars plugin's actual setup/start commands as needed. Do not add
base `teamCommands` for `green`/`yellow` when `2v2` removes those teams: every
resolved variant validates the shared lifecycle, so that configuration is rejected.
`4v4v4v4: {}` inherits the base settings unchanged.

```text
/minigame join Bed Wars --variant 2v2
vestridynamic spawn Bed Wars --variant 2v2 --send-player Player
```

The first command is player-side; the second is proxy console/NPC-side (or the
signed Paper console bridge). Variant selection is carried by the signed bridge;
existing commands/flags without `--variant` remain compatible and select the base.
Named variants have separate queues and on-demand pools. `readyServers` warms
only the unnamed base pool, not even an identical named variant. Base and all
variants share the aggregate template `maxInstances` cap.

### Managed and Native Startup

Teams are optional. Omit `teams` or use `teams: []` for FFA and omit `teamSize`.
Team-scoped actions and `{team}` placeholders require configured teams; player
actions without `{team}` remain usable in a Vestri-managed FFA match.

`lobbyMode` defaults to `vestri`, preserving the existing managed lifecycle:
the queue waits for `minPlayers`, assigns a roster, waits for all players to
join, and dispatches startup actions before marking the match running.
Use `lobbyMode: plugin` when the installed gameplay plugin owns its native lobby,
countdown, teams, and game state. This mode bypasses Vestri's `minPlayers`
queue threshold and startup commands; `lifecycle.onAllJoined` **must be empty**.
`lifecycle.teamCommands` and `lifecycle.startCommands` must also be empty or
omitted: the backend rejects nonempty startup arrays in plugin mode rather than
silently ignoring them. Native game start is controlled by the gameplay plugin.
Player bounds and `maxPlayers` still apply. For example, a minimal team-free
native lobby with an explicit end signal is:

```yaml
mode: minigames
maxInstances: 4
minigame:
  lobbyMode: plugin
  instanceMode: temporary
  teams: []
  minPlayers: 1
  maxPlayers: 8
  joinTimeout: 20s
  readyServers: 1
  lifecycle:
    ready:
      trigger: serverLoad
    onAllJoined: []
    end:
      returnPlayersTo: lobby
      deleteServer: true
```

The gameplay plugin or its adapter issues `vestridynamic end` when the game ends.
Use explicit readiness instead of `serverLoad` if plugin initialization needs it.

Native lobbies accept incremental whole-party admissions into an existing match,
subject to `maxPlayers` and any configured team capacities. Each addition is a
full-roster atomic update with an incremented `rosterVersion`; Paper must apply
the allowed roster and acknowledge the matching match ID and version before
Velocity transfers the newly admitted players. Existing players are not sent
again, and pending admissions must finish before another roster addition.
The initial acknowledgement enters running state without a Vestri startup phase;
later admissions remain in that state. `joinTimeout` bounds acknowledgement and
joining. Temporary instances cancel and clean up on failure; permanent instances
release failed reservations without deleting the server, as described below.

`instanceMode` defaults to `temporary`, including older saved specifications
without this field. Use `instanceMode: permanent` **only with
`lobbyMode: plugin`** for a long-lived native lobby. Its `onAllJoined` must be
empty; end settings must have `deleteServer: false`, `whenEmpty: false`, and an
absent or empty `logPattern`. For example, change the native example above to:

```yaml
instanceMode: permanent
lifecycle:
  ready:
    trigger: serverLoad
  onAllJoined: []
  end:
    returnPlayersTo: lobby
    deleteServer: false
    whenEmpty: false
```

Permanent instances are not automatically deleted on game end or when empty.
Paper ignores their explicit end signal. Failures, stale heartbeats, or
incompatible template changes can mark them unavailable rather than delete
them; fresh valid agent reports are required for recovery. Explicit dashboard
deletion still removes them.

After a permanent admission completes, a departure confirmed absent by both
Paper and Velocity removes the player from the dynamic roster and frees
capacity. Removal increments `rosterVersion`; Paper must acknowledge the updated
full roster before capacity is reused for another admission. Existing members
keep their identities and assignments. An expired admission releases and, where
still valid, requeues the failed whole group without removing established
players or deleting the instance.

Vestri does not impose an explicit game-start admission lock in plugin mode.
The gameplay plugin controls whether its game state permits a join, including
kicking or rejecting admitted players under its own restrictions. Roster
authorization and a version acknowledgement are not promises of gameplay
acceptance. The Vestri-managed lifecycle is unchanged.

In Vestri-managed mode, matching can proceed as soon as the queued roster meets
`minPlayers` and a compatible instance is ready; there is no fixed queue countdown
to fill to `maxPlayers`. This threshold is not a game-start timer. Actual startup
actions run only after the entire assigned roster has joined Paper, not on a
status ping or merely because the threshold was reached. Paper dispatches them
as console commands on its main thread, once per match under the durable startup
fence. Setup, per-team commands, and final start commands run in that order.
A rejected command
fails startup and stops later commands. **Command ordering is not an asynchronous
completion guarantee**: a command returning successfully can still have work in
flight. Use a gameplay-plugin adapter or explicit signal when initialization must
finish before the next phase, rather than assuming sequential commands await it.

Managed roster players remain guarded until successful dispatch of the final
startup command (or successful completion of an empty command list). Coordinate
movement, PvP/damage, world interactions, inventory actions, and player commands
are blocked during joining/startup. Looking around is allowed, as are trusted
plugin teleports needed for map setup. A startup failure keeps players guarded
until evacuation/cleanup, not released into gameplay. Native plugin-owned
lobbies do not install this pre-start guard or run Vestri's command phases.

In Vestri-managed mode, `joinTimeout` is the maximum roster-join wait after the server acknowledges its
assignment. A no-show or joining/starting timeout cancels the match, returns
present players, and schedules instance cleanup. Only intact groups whose exact
membership is still connected and permitted are automatically requeued; a group
with a disconnected member or changed leadership/membership is not reconstructed
or partially requeued. The startup phase also has a bounded acknowledgement wait.

### Readiness, Pool Capacity, and End Signals

`readyServers` is a **count**, not a list of names. Temporary mode targets spare
empty ready servers. Permanent mode targets the minimum **total permanent
instance count**, including reserved, running, and unavailable instances, not
spares. Reserving a permanent lobby does not replenish a spare pool.
Confirmed readiness requires the Paper agent's ready report with
no match and no players, not merely a running container or a Minecraft status
ping. Provisioning instances count as credits toward that pool target so each
sweep does not create duplicate replacements. All tracked instances, including
active matches and pending cleanup, count toward the template's `maxInstances`
cap; backend node/template limits still apply. Queued demand can provision a
server even with `readyServers: 0`, subject to those limits.

With `ready.trigger: serverLoad`, the Paper server-load event schedules readiness.
Use `explicit` when a gameplay plugin must finish initialization first, then run
the following in the **generated Paper match server's console** (the console
equivalents of `/vestridynamic ready` and `/vestridynamic end`):

```text
vestridynamic ready
vestridynamic end
vestridynamic status
```

Run `vestridynamic status` in the generated Paper server console to inspect its
actual saved startup configuration and runtime state. It reports roster/frozen
counts, setup/team/start command counts, and configured/verified end logger,
without exposing the instance token. A managed instance with no startup commands
finishes startup immediately after everyone joins and releases protection; it
now warns about that configuration. Editing a template does not update the
saved configuration of an existing instance. Startup and rejected-command logs
are emitted on Paper where commands execute, not only on Velocity.

These are local minigame-agent signals, not proxy lifecycle commands and not
player commands. `ready` signals application readiness; `end` explicitly ends
a temporary match and is ignored for permanent instances. The configured
`lifecycle.end.logPattern` is a Java regular expression applied with `find()` to
the raw **JUL (`java.util.logging`) `LogRecord.getMessage()`** while running.
Anchors work: `^xy$` matches the message `xy`, without a timestamp, level, or
formatted console prefix. An unanchored pattern can match a substring. This is
not a console-output, stdout, Log4j, or SLF4J scraper.

Set `lifecycle.end.loggerName` explicitly to the exact, case-sensitive
`getLogger().getName()` of the installed gameplay plugin, and verify that value
against that plugin's source or runtime logger. The agent verifies it against
installed plugin loggers and excludes Vestri, root/global, and server loggers;
ordinary chat/server records do not qualify. A missing logger in a legacy spec,
or an unverified logger, disables automatic log-end matching and emits a warning;
it does not enable broad matching. Explicit and empty-server end remain available.
Logger identity cannot distinguish a genuine outcome from malicious player text
that the gameplay plugin itself logs through its own trusted logger. For a
stronger integration, use a gameplay-plugin adapter that issues the console-only
`vestridynamic end` command from actual game state, not player-supplied text.
Use that explicit adapter as well if the plugin does not emit its end message
through JUL. In temporary mode, `whenEmpty` optionally
ends an empty running match after `emptyGrace`. Temporary end handling returns
players to the exact
Velocity registry name in `returnPlayersTo`, then deletes the empty instance;
an unavailable lobby or failed return can disconnect players instead.

### Durable State

The proxy atomically persists provisioning, ready, and active instance records
in `plugins/vestri-dynamic/vestri-minigames.json`. On proxy restart, temporary active
joining/starting/running records are cancelled and scheduled for evacuation and
cleanup, **not resumed or replayed**. Permanent native instances retain their
match and roster across proxy restarts and recover from fresh agent reports;
pending admissions remain bounded by the join timeout. Provisioning/ready
records remain tracked for reconciliation. Cleanup failures retain records for retry rather than
forgetting instances. Persist this ledger alongside the existing proxy files;
do not remove it to reset a queue or edit it while running. Invalid ledgers fail
closed to avoid duplicate provisioning.

The protected proxy ledger pins the original API origin (the configured
`backendUrl`, including any deployment prefix) and template API key for each
preparation/instance. Retries, lookup, and cleanup use that saved credential
binding, not a replacement key or origin from the current configuration.
Changing the config does not transfer backend ownership or repair a revoked,
expired, or unauthorized pinned key. On POSIX filesystems, protect the ledger
with file mode `0600` and its directory with `0700`; it contains sensitive
credentials, as do backups. Legacy records whose original credentials are
unknown remain tracked and fail closed: do not silently forget them, substitute
a current key, or remove the ledger to bypass recovery. Resolve their original
credentials or use authorized dashboard deletion and authoritative proxy sync.

Each generated Paper instance receives
`plugins/VestriConsoleBridge/minigame.json` and maintains its durable execution
ledger/fence at `plugins/VestriConsoleBridge/minigame-execution.json`. The fence
binds a match ID and roster and records the startup claim **before** dispatching
actions. For temporary instances, restarting after that claim fails the agent
instead of executing startup commands again, including after a crash partway
through the command list. This
prevents startup replay; it does not roll back commands or promise exactly-once
completion of their external effects. Preserve existing instance fences on
retries/restarts, but do not bake a used instance's fence into a reusable template.

Permanent native Paper instances restore their durable match and dynamic roster
instead of aborting solely because of a startup claim. Recovery requires the
original generated credentials/configuration and execution fence: the permanent
fence validates its credential binding and rejects another instance's
credentials. Retain the original template API key for backend operations too.
This does not restore the gameplay plugin's own state or automatically reconnect
players.

Offline recovery is supported for permanent native instances: persisted roster
reservations survive proxy/Paper restarts even when their players are offline.
Fresh matching agent reports and roster acknowledgements reconcile those
reservations; absence confirmed by both Paper and Velocity releases departed
players and requires the updated roster acknowledgement before capacity reuse.
Transient parties and queues are still not restored, and recovery does not
automatically transfer offline players or relax the admission handshake.

### Dashboard Deletion and Cleanup Recovery

Dashboard instance deletion uses the backend's instance deletion path. After
files and the database record are deleted, the backend publishes a complete
authoritative proxy snapshot and triggers synchronization with an independent
30-second timeout. Template creation/update/deletion also synchronize after commit.
An authoritative snapshot retires previously bound ledger entries whose server
IDs are missing, evacuates remaining known occupants, and removes local tracking
without another template-key deletion. A non-authoritative snapshot cannot
prove deletion and does not retire entries. Unmatched, unbound pending
preparations are not discarded merely because they are absent.

If post-commit synchronization fails, the API returns HTTP **502** with
`committed: true`: the mutation is committed, not rolled back. A node
administrator can retry authoritative synchronization through
`POST /api/nodes/{nodeRef}/minecraft/proxies/{proxyRef}/sync`. Retry that sync,
not template creation or deletion. Proxy console `vestridynamic sync` only reads
the local manifest; it does not regenerate it from the backend database.
For a committed template PATCH whose worker template-manifest write failed,
retry the PATCH to repair that file; for a proxy publication/reload failure,
retry the sync endpoint. Do not treat either committed 502 as a rollback.

Automatic cleanup uses the pinned original API origin and template key. For a
dashboard-created **minigame**, known-ID lifecycle operations also allow a key
whose creator is exactly the dashboard server's creator, with matching node and
template and current node owner/admin permission. This is narrow delegation,
not authority for every key on that template: a different creator account gets
HTTP 403. Ordinary dashboard instances remain excluded, and exact-key isolation
for API-created instances, list, and request lookup is unchanged. Adoption of a
manual/web minigame does not itself establish cleanup authorization.
An absent instance (HTTP 404)
is treated as already deleted; HTTP 403 is **not blanket-ignored** because it
can mean the instance belongs to another key. Lookup/deletion failures retain
the record and log `Minigame cleanup pending` with template, instance ID, request
UUID, exception class, and sanitized error detail. Cleanup retries use a
**60-second backoff**. Only a valid authoritative snapshot establishes external
deletion independently of template-key access.

### Deployment

Deploy the updated **backend first**, then the new plugin JAR on **Velocity and
the Paper templates and current Paper instances** used for minigames, restarting the proxy and applicable
servers to load it. Updating only Velocity is insufficient; existing template
JARs must also be updated so newly generated instances have the agent. Maintain
the manually configured template keys so provisioning and cleanup remain usable.

Deploy the updated frontend as well so newly generated template specs contain
the intended `instanceMode`, lobby, and lifecycle settings; regenerate and verify
the spec before creating replacement instances. Resynchronize the proxy after
deployment or template changes. A current instance uses its pinned spawn-time
spec copy: changing a template or regenerating its spec cannot change that
instance's mode in place. A temporary instance cannot become permanent by a
template edit or a JAR update; recreate it with the intended spec.

Templates and provisioning jobs retain saved configuration snapshots; changing
the template does not rewrite an old job or automatically upgrade a current
match instance. For recovery, upgrade the backend, Velocity, and Paper template
JARs together, then stop and recreate incompatible old temporary matches through
operator-managed evacuation and cleanup. Retain the original request UUID after preparation
failure so retries reconcile the same instance. Compatible permanent instances
can recover across restarts with original credentials and durable files; do not
treat them as temporary matches that must always be recreated. Do not manually
delete either ledger to force provisioning or bypass an execution fence.

### Preparation and Agent Diagnostics

Correlate Velocity's `Minigame preparation pending` log by template and
`requestId` with backend/worker logs. It reports the exception class and a
redacted message and retains the same request for retry. An `IOException` alone
does not identify the live failure's root cause; a preflight
`VESTRI_DYNAMIC_SYNC_OK` is manifest-only confirmation, not spawn readiness.

Current live retry or cleanup failures may be rooted in the pinned original
credentials or origin: check key expiry/revocation, creator/node permissions,
template binding, and reachability of that saved origin. A working replacement
key in the current config does not prove the pinned credentials work. Correlate
the actual HTTP status and sanitized backend/worker logs before assigning a
root cause; never print keys or publish the ledger to diagnose it.

Check Paper's startup configuration log to confirm the minigame agent loaded its
generated configuration. Missing or invalid configuration/execution fences are
reported separately and deny entry. `Minigame control unavailable` reports an
HTTP status or transport/response exception class without credentials or response
bodies; repeated warnings are rate-limited to once per 30 seconds, and recovery
is logged. Velocity's `Minigame startup stalled` warning means no agent report
arrived after 30 seconds: inspect Paper startup and agent configuration, then
verify private proxy DNS and port 25580 reachability from that instance.
The current implementation logs connection results/reasons; deploy the updated
JAR before relying on them to distinguish failed transfers from a gameplay
plugin's login rejection. Do not infer a successful connection from a roster
acknowledgement alone. These diagnostics do not establish that the root cause
of an uncorrelated live preparation error has been fixed.

The proxy's private control HTTP listener binds port **25580**. Allow generated
Paper instances to reach it on the private Docker network, but **do not publicly
publish this port**, add public reverse-proxy routing, or expose it to players.
Generated per-instance bearer tokens authorize the bound server/slug; they are
not player credentials, template API keys, or the console bridge secret.
Protect the generated config and proxy manifest as secrets.

The generated `controlUrl` uses the private proxy-container hostname
`http://vestri-<parent Docker root>:25580`, which is valid in the shipped Compose
configuration. Verify that the generated hostname resolves to the proxy container
and that HTTP is reachable from the match container on your deployment. This is
private agent-to-proxy HTTP, distinct from `backendUrl` and the Minecraft port.

Deployment and restarts are operator actions; this documentation update does not
deploy a JAR or automatically restart Velocity or Paper. This section documents
the current implementation and deployment requirements, not a claim of live
testing. Validate private DNS/HTTP reachability,
readiness signals, gameplay command integration, no-show cancellation,
permanent restart recovery, and temporary restart cleanup on your
own deployment before opening queues to players.
