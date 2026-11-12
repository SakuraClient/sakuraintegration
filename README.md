# Sakura Integration

A plugin that lets your server work with [Sakura](https://sakuraclient.com) players.

- **Turn off Sakura mods** you don't want on your server.
- **See who plays on Sakura.**
- **Verify your server's address** with Sakura.
- **Record an arena**, so the players who were in it can watch it again in Sakura's Studio.

It works on **Paper, Spigot, Purpur and other forks from 1.13 to the newest**, and on **Velocity**.

## Install

1. Download the jar from [Releases](../../releases).
2. Put `SakuraIntegration-Paper` in your server's `plugins` folder. On a Velocity network, also put `SakuraIntegration-Velocity` in the proxy's `plugins` folder.
3. Restart.

That's it: it needs no account and no setup.

## Turn off mods

Open `plugins/SakuraIntegration/config.yml` and list the mods:

```yaml
disabled-mods: [freelook, minimap]
```

Then run `/sakura reload`. Sakura players see those mods switched off while they're on your server, and get them back when they leave.

You can give a world its own list:

```yaml
worlds:
  arena:
    disabled-mods: [freelook, minimap, camera-zoom]
```

On Velocity, the proxy's config has the same `disabled-mods`, plus a list per server:

```yaml
disabled-mods: [freelook]
servers:
  bedwars:
    disabled-mods: [minimap]
```

**Mod ids you'll most likely want:**

`freelook`, `camera-zoom`, `minimap`, `world-map`, `waypoints`, `light-lens`, `hitboxes`, `reach`, `brightness`, `clear-water`, `photo-mode`, `team-pings`, `capes`, `auras`, `dance`

`/sakura status` tells you if an id isn't a Sakura mod, so a typo never fails silently.

Sakura never lets a server turn off **streamer mode**, **break reminders** or **subtitles**.

## See who plays on Sakura

- `/sakura players` lists them.
- With [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi):

| Placeholder | Shows |
| --- | --- |
| `%sakura_badge%` | `✿ ` for Sakura players, nothing for others |
| `%sakura_using%` | `true` or `false` |
| `%sakura_online%` | How many Sakura players are online |

Put `%sakura_badge%` in your tab list or chat format to mark Sakura players. You can change the mark in the config.

## Verify your address

```
/sakura verify play.example.com
```

It takes a few seconds and proves to Sakura that this address leads to your server. Run it where the address points; on a Velocity network, that's the proxy (`/sakuravelocity verify play.example.com`). On the Paper side it needs Paper or a fork of it.

Your server's identity is saved in `plugins/SakuraIntegration/credentials.json`. **Keep that file private.**

## Record an arena

```
/sakura record start radius=6 Bed Wars 4
/sakura record stop
```

That records every player and every block in the chunks round you: how they moved, what they held, their hits and deaths, public chat, and every block placed or broken. Record a box of blocks instead with `box=<x1>,<z1>,<x2>,<z2>`, and another world with `world=<name>`. The rest is its name.

When it stops, the players who were in it and play on Sakura get it in Sakura's Studio, where they can watch it from any angle and make a video of it. It reaches them only after it stops, never during a match, and only from your server: nothing is sent anywhere else.

Recordings are kept in `plugins/SakuraIntegration/recordings`, the newest 50 for 14 days. The `recordings` part of the config changes that, who gets them (`participants`, `everyone` or `nobody`) and how fast. A recording holds players' names and skins, their movement and public chat, so keep them as you'd keep your logs.

Servers on 1.17 and later record the server's own clock too, so a player's own recording of the same game lines up with yours to the millisecond.

## Commands

All need the `sakura.admin` permission (ops have it). On Velocity, use `/sakuravelocity`.

| Command | Does |
| --- | --- |
| `/sakura status` | Version, Sakura players online, mods off, verified addresses |
| `/sakura players` | Who's on Sakura |
| `/sakura verify <address>` | Verifies your address |
| `/sakura reload` | Reloads the config |
| `/sakura record start [radius=<chunks> \| box=<x1>,<z1>,<x2>,<z2>] [world=<name>] [name]` | Starts recording |
| `/sakura record stop [id]` | Stops and saves it |
| `/sakura record mark <words>` | Marks this moment |
| `/sakura record list` | What's recording, and what's kept |

`sakura.recordings.receive` (everyone has it) decides who can be given recordings.

## For developers

Add the plugin jar as a `compileOnly` dependency, then:

```java
SakuraIntegration sakura = SakuraIntegration.get();

boolean onSakura = sakura.isSakura(player.getUniqueId());

// Turn freelook off for one player during a match...
sakura.player(player.getUniqueId()).ifPresent(p -> p.disableMods(List.of("freelook")));
// ...and give it back after.
sakura.player(player.getUniqueId()).ifPresent(SakuraPlayer::clearMods);
```

`SakuraPlayerJoinEvent` fires when a player's game says it's Sakura, a second or so after they join. That's the game's word, not a proof: fine for showing a mark, not for handing out rewards.

## Privacy

The plugin sends nothing anywhere: no stats, no update checks. It only talks to Sakura when you run `/sakura verify`. Recordings go only to the players in them, over their own connection to your server.

## Building

```
./gradlew build
```

The jars end up in `paper/build/libs` and `velocity/build/libs`.

## License

MIT
