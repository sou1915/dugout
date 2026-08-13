# Prompt for a new Claude Code session — Career Dugout, Unity/C# port

Paste everything below the line into a fresh session, and attach both zips:
`careerdugout20.zip` (the Kotlin project) and `careerdugoutunity.zip` (the Unity
skeleton).

---

I own **Career Dugout**, a football *management* game — the player picks the
club, the team and the tactics, and then watches. He never controls a player.

I am attaching two projects and I want them merged into one, rebuilt entirely in
**C# for Unity 6 LTS, Android-first**.

- `careerdugout20.zip` — the real game. ~24,500 lines of Kotlin, Android, 2D.
  Three AndroidX dependencies, ten binary files (all of them the launcher icon),
  no assets, no data files: a world of 1,236 clubs and 32,136 players is
  generated from one seed in 357 ms.
- `careerdugoutunity.zip` — a Unity skeleton another agent produced. 23 files,
  42 KB. Low-poly player models, a broadcast camera, a dashboard, a scene
  builder.

**Read `GAME.md` in the Kotlin zip before you plan anything.** It is the whole
project measured rather than described, and its section 7 is the honest half.
Then read `CLAUDE.md`, `docs/DEAD_CODE.md` and `TASKS.md`.

## What I want

One Unity project, everything in C#, that keeps the Kotlin project's *brain* and
replaces its *rendering* with Unity — and that is finished properly, not left as
a prototype.

## Decisions already made — do not re-litigate these

**1. The Unity project's match engine gets deleted.**
`Assets/Scripts/Core/DeterministicMatchEngine.cs` is ~90 lines that loop from
minute 6 to 90 in 5–9 minute steps, roll `random.Next(0,100)` and pick a line of
commentary off a ladder. It has no ball, no positions and no 22 players. It
produces a list of ~13 strings, and `MatchBroadcastDirector` then animates that
already-decided list.

That is exactly the architecture of my project's **engine v1**, which I
abandoned for measured reasons written up in `docs/ENGINE_V2.md`. Do not merge
the two engines. Delete theirs.

**2. Keep from the Unity project:** the low-poly player construction
(`LowPolyFootballer.cs`), the procedural pitch and stadium, the broadcast
camera, the scene builder, the dashboard as a starting point. That work is
genuinely useful and it replaces ~9,400 lines of hand-written Kotlin rendering.

**3. The port is a translation, not a copy.** Kotlin cannot run in Unity. About
15,200 lines have to be rewritten in C#:

```
core/World.kt        1,575   1,236 clubs, 32,136 players from a seed
core/Model.kt          908   players, clubs, tactics, traits, 17 attributes
core/Names.kt          768   per-nation name assembly, 0 duplicates
core/Manager.kt        279
core/DressingRoom.kt   146
core/Staff.kt          143
sim/MatchEngine.kt   6,450   the match
sim/Career.kt        1,967   weeks, transfers, loans, board, press
sim/League.kt          959   fixtures, tables, cups
sim/MatchEvents.kt     489   the 90-row event table
sim/OffBall.kt         671   what the other 21 are doing
sim/Physics.kt         278
sim/Pitch.kt           169
sim/RoleTree.kt        205
```

`ui/`, `MainActivity.kt` and `Save.kt` (9,426 lines) are **not** ported — Unity
does that work. Save moves to a JSON file in `Application.persistentDataPath`,
not `PlayerPrefs`.

## The one rule that must not be broken

**The simulation assembly must never reference `UnityEngine`.**

Put every ported file in an assembly definition — `CareerDugout.Sim.asmdef` —
that has no Unity references. No `Vector3`, no `Transform`, no `MonoBehaviour`,
no `UnityEngine.Random`, no `Time.deltaTime`. Use `System.Numerics` or plain
floats. Unity reads the simulation's state; the simulation never knows Unity
exists.

This is not style. In the Kotlin project the whole app compiles against a Java2D
shim and runs headless on a desktop JVM, and that is the single most valuable
thing in the repository — it is why a visual claim can be *measured* instead of
asserted. There are **124 measurement harnesses** and a fingerprint gate that
fails the build when a change meant to be cosmetic moves a scoreline.

If the C# simulation stays Unity-free it compiles as a plain .NET class library
and every one of those harnesses can be ported as a console project and run with
`dotnet run` in seconds. If it does not, you will be debugging football in an
environment with **zero** measurement, and I know exactly what that costs:
`docs/DEAD_CODE.md` lists **seven** separate times correct code sat on a path
that no longer ran, each undetected for months.

Port these harnesses first, before the game is playable:

| harness | what it answers |
|---|---|
| `Gate` | 200 matches, FNV-1a over every score, shot, card, corner |
| `BalanceBig` | 9,120 matches — goals per game, home advantage |
| `EventCensus` | all 90 event rows; which never happen |
| `ShapeAudit` | do the 22 move like a team or a shoal |
| `ObCheck` | the off-ball layer, on against off |
| `PoseTime` | seconds on screen, not counts |

## Determinism

Three separate random streams, and they must stay separate:

| stream | decides |
|---|---|
| `rng` | anything that reaches a result |
| `sfxRng` | anything cosmetic |
| `physRng` | the ball |

Port `Rng` exactly — same algorithm, same seeding — so the same seed gives the
same world. Never use `UnityEngine.Random` anywhere in the simulation.

The C# fingerprint will not equal the Kotlin one; floating-point and iteration
order will differ. That is expected. What must hold after the port:

- `BalanceBig`: **2.5 – 2.7 goals a game**, home wins **~45%**, over 9,120
  matches
- `WorldCheck`: **1,236 clubs, 32,136 players, 0 duplicate names, 0 clubs in
  the wrong division**
- then record a new baseline and the gate protects it from there

## Do not port the broken parts blindly

`docs/DEAD_CODE.md` says roughly **2,400 of 7,800 lines of match code never
execute**. Do not translate them.

- **Engine v1** (`newPhase`, `stepPhase`, `resolvePhase`, ~670 lines) — dead
  `else` branch. Do not port.
- **Engine v3** (~395 lines, plus `RoleTree.kt` and `Pitch.kt`) — `ENGINE_V3 =
  false`, does not finish a match. Port `Pitch.kt` (the space model) because it
  is needed; leave the v3 adapter behind.
- **Engine v2** is the live one. Port it.

## The open defect — carry it, do not hide it

`ShapeAudit` reads **togetherness 0.50** against a real 0.2–0.3. It is the mean
cosine between every pair of outfielders' velocities: 0 is ten men going ten
ways, 1 is one object being dragged. At 0.50 they are half a shoal, and that one
number is the whole of "the players move wrongly".

The cause is that the movement model is exactly one rule — *a formation slot,
plus a pull toward the ball* — where the slot is shared and the pull is the same
vector for everybody, so two players' movement differs only by where they
started.

**Unity does not fix this.** NavMesh, physics and animation blending improve how
a man *executes* a move; the defect is in what he *decides*. Do not assume the
3D shell makes it go away.

`OffBall.cs` (twelve jobs: press, cover, mark, recover, support, run beyond,
hold width, attack the box, second ball, chase, show short, shape) is the fix,
and it must be ported with `ENABLED = false`, because switching it on today
gives a 1.00-goal game. `docs/DEAD_CODE.md` has the full table and the five
attempts that were tried and reverted.

## The Unity side, done properly

The broadcast must be driven by **live engine state at ~36 Hz** — read player
positions, the ball, the current scene and `evCaption` every frame — never by
replaying a list of pre-decided events. That distinction is the whole point.

Then finish what the skeleton left open:

- Manager screens: squad, tactics, fixtures, table, transfers, loans, dressing
  room, board, staff, training, academy, finance, inbox, career record. The
  Kotlin `MainActivity.kt` is 4,507 lines and every screen is in there — use it
  as the specification.
- Animation: run, pass, shot, header, tackle, slide, dive, celebrate, down.
  `Ev4.SHOWN` already says which pose each of the 90 events wants and on whom.
- The caption and commentary: `evCaption` and the `spoken` queue already exist
  in the engine, with weights. Highest weight wins and the rest are dropped —
  never queued, or the voice ends up behind the football.

## How to work

From `CLAUDE.md`, and these are not negotiable:

- **Measure, never assert.** Render the frames and look at them.
- **One change at a time**, so the fingerprint names the culprit.
- **A change meant to be cosmetic must leave the fingerprint identical.** If it
  moved, it was not cosmetic.
- **Revert what does not work, and write down what it measured.** The reverts in
  `docs/` are worth more than most of the changes.
- **Do not add a package or a dependency.** If you think one is needed, stop and
  ask me.
- Never edit by line index. Use anchored string replacement.

## Order

1. Unity project skeleton, `CareerDugout.Sim.asmdef` with no Unity reference, a
   console test project that compiles against it.
2. `Rng`, `Model`, `Names`, `World` — then `WorldCheck` must print 1,236 clubs,
   32,136 players, 0 duplicates.
3. `MatchEngine` v2, `MatchEvents`, `Physics`, `Pitch`, `OffBall` (off).
4. `Gate`, `BalanceBig`, `EventCensus`, `ShapeAudit`, `ObCheck` — then
   `BalanceBig` must land in 2.5–2.7 goals before anything is drawn.
5. `League`, `Career` — the season, transfers, board, the rest.
6. The Unity broadcast, driven live.
7. The manager screens.
8. Android APK.

Give me a plan before you start writing code, and tell me anything in here you
think is wrong.

## One more thing

Talk to me in **Darija**. The code, the comments and the docs stay in English.
