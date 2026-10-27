# BlockHunt

A hide-and-seek mini-game plugin for Minecraft servers where hiders turn into blocks.

Hiders are disguised as a block that follows them around. Standing still, they snap onto the grid
and become indistinguishable from the map. Seekers hit suspicious blocks to find them: a hider who
is found joins the seekers. Seekers win when nobody is left hiding, hiders win when the clock runs
out.

![BlockHunt: a hider turns into a block, snaps onto the grid and copies a bookshelf, a seeker finds them, the sonar and the hot or cold bar, a taunt](docs/blockhunt.gif)

## Building

Ready-made jars are on the [Releases](../../releases) page, each with its SHA-256 and a build
provenance attestation (`gh attestation verify BlockHunt-<version>.jar --repo oodinhoo/BlockHunt`).

Building from source requires **JDK 25** (mandatory since Minecraft 26.1).

```powershell
.\gradlew.bat build      # -> build/libs/BlockHunt-0.1.0.jar
.\gradlew.bat runServer  # starts a local Paper 26.2 test server with the plugin
```

Built against `io.papermc.paper:paper-api:26.2.build.135-stable` (Gradle 9.8.2). Drop the jar into
the `plugins/` folder of a **Paper 26.2** server.

## Running a game

1. Stand in the arena and run `/bh setspawn`.
2. Every player runs `/bh join` (at least two).
3. An admin runs `/bh start`.

Joining shows a title and a lobby sidebar that stays on screen: the status (*Need 1 more*,
*Ready*, or *No spawn set* for the admin), the players who joined, the length of both phases, and
how to leave. During the game, the same sidebar shows your role, and the lobby comes back at the
end.

Everyone is teleported to the arena. Each player's inventory and game mode are put aside and given
back at the end of the game, even after a server crash (on their next join). During the game,
everyone is in adventure mode and cannot break, place, drop or pick up anything.

| Phase | Default | What happens |
|---|---|---|
| Hiding | 30 s | Seekers are blinded and frozen, hiders run and hide |
| Seeking | 5 min | Seekers hunt. Time out: hiders win |

## Playing as a hider

You are invisible: the others only see the block you are disguised as, following you.

- **Stand still for 3 seconds** on solid ground: you snap onto the grid and become a real-looking
  block. Moving again turns you back. The XP bar fills up as you stay still.
- **Right-click a block of the map** to take its look, once every 30 seconds (the cooldown shows on
  slot 1). Chests, signs, beds and other blocks with their own renderer cannot be copied.
- **Taunt** (rocket, slot 2): a loud goat scream at your position, paid back by 15 seconds off the
  clock. Two per game.

## Playing as a seeker

- **Hit a suspicious block.** If it is a hider, they are found and join the seekers.
- **Sonar** (compass, slot 2): right-click to hear a bell at every hider's position. Every 45 seconds.
- **Hot or cold**: your XP level is the distance to the nearest hider, the action bar says *Hot!*,
  *Warm...* or *Cold*.

Both roles get a sidebar with the game state, and a boss bar with the time left.

## Commands and permissions

| Command | Permission | Default |
|---|---|---|
| `/bh join`, `/bh leave` | none | everyone |
| `/bh start` | `blockhunt.admin` | operators |
| `/bh setspawn` | `blockhunt.admin` | operators |
| `/bh disguise <block>` | `blockhunt.admin` | operators. Toggles a disguise outside a game, for testing |

`/blockhunt` is the long form of `/bh`.

## Configuration

`plugins/BlockHunt/config.yml`:

| Key | Default | Meaning |
|---|---|---|
| `hide-seconds` | `30` | Length of the hiding phase |
| `seek-seconds` | `300` | Length of the seeking phase |
| `solidify-seconds` | `3` | Time standing still before turning solid |
| `change-cooldown-seconds` | `30` | Cooldown between two block changes |
| `seekers` | `1` | Seekers at the start (at least one hider is always left) |
| `sonar-cooldown-seconds` | `45` | Cooldown of the seekers' sonar |
| `taunts` | `2` | Taunts per hider and per game |
| `taunt-seconds` | `15` | Time a taunt takes off the clock |
| `blocks` | 8 full blocks | Starting disguises, one picked at random per hider |
| `spawn` | none | Arena spawn, written by `/bh setspawn` |

## Known limits

- **One arena per server.**
- **F3+B is turned off in the arena world during a game** (game rule `reducedDebugInfo`), or it
  would draw every hider's hitbox. A crash mid-game leaves the rule on:
  `/gamerule reducedDebugInfo false` puts it back.
- **The fake block of a solid hider is resent every second**: a block update right next to it can
  make it flicker for up to a second.

## Documentation

- [Contributing](CONTRIBUTING.md) — building, testing and getting a change merged.
- [Security policy](SECURITY.md) — how to report a vulnerability privately.

## License

[MIT](LICENSE)
