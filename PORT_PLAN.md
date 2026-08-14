# Career Dugout — Unity / C# port plan

Written before any code. Everything below that carries a number was measured in
this container, not quoted from a document. The command that produced each
number is given so it can be re-run.

---

## 0. What is actually in front of me

### 0.1 Both zips are now here

`careerdugoutunity.zip` arrived after the first draft of this plan and the
blocker it recorded is closed. It is **13 files, 41,978 bytes, 7 C# files, 745
lines** — the brief says 23 files; the 42 KB is exact. No `.meta` files, no
`.asmdef`, no scene (the Editor script builds it), Unity `6000.0.0f1`.

`Assets/Scripts/Core/DeterministicMatchEngine.cs` is there and is as described:
84 lines, `for (minute = 6; minute < 90; minute += random.Next(5, 10))`, a
`roll < 16 / < 29 / < 41 …` ladder, and no ball, no positions and no 22 players.
It goes, per decision 1.

Section 3.1 below is what actually survives, file by file, measured.

### 0.2 `GAME.md` is not in the zip

The brief says to read `GAME.md` first and that its section 7 is the honest
half. There is no `GAME.md`. The project documents its own state in:

| file | size | what it is |
|---|---:|---|
| `AUDIT.md` | 66 KB, 13 sections | the measured external audit — the closest thing to the described document |
| `PROJECT_STATE.md` | 16 KB | older; §5 is the list of reverted attempts |
| `HANDOFF.md` | 14 KB | older still |
| `CLAUDE.md` | 6 KB | the working rules |
| `TASKS.md` | 10 KB | the live queue |
| `docs/DEAD_CODE.md` | 6 KB | the graveyard |

`AUDIT.md` §7 is "Suggested order of work from here" — a five-item work order,
not a candid-limitations section. I have read all six documents and planned
against them. If `GAME.md` exists somewhere else, send it; it may change the
weighting below but not the shape.

### 0.3 The numbers in the brief check out

Everything I could verify, I verified:

```
app/ Kotlin                24,636 lines   (brief: ~24,500)
ui/ + MainActivity + Save   9,426 lines   (brief: 9,426 — exact)
harnesses in tools/            124        (brief: 124 — exact)
AndroidX dependencies            3        core-ktx, appcompat, recyclerview
binary files                    10        all ic_launcher*.png
assets / data files              0
```

World generation, measured here:

```
$ java -cp build/measure WorldCheckKt
world built in 436 ms: 1236 clubs, 32136 players
duplicate club names: 0      duplicate player names: 0
clubs with no division: 0
clubs listed in the wrong division: 0
```

1,236 and 32,136 confirmed. 436 ms rather than 357 ms is this container being
slower than the owner's machine; it is the same work.

### 0.4 I got the Kotlin original running headless here

This was not asked for and it changes the plan more than anything else in this
document.

`kotlinc` is not installed in this container and the .NET installer host is
blocked by egress policy, but Maven Central is reachable, so the Kotlin compiler
came from there, and the .NET 8 SDK came from the Ubuntu archive. Neither is a
project dependency — no `.csproj`, no `asmdef` and no Gradle file gains an
entry. They are toolchain, the same as `javac`.

The whole `core/` + `sim/` tree plus the Java2D shim compiles and runs:

```
$ java -cp build/measure GateKt
=== gate: 200 matches, world 20260727, 24s ===
goals 2.730   shots 16.46   cards 0.860   corners 4.54
FINGERPRINT da1caad36bfeca9b
```

`da1caad36bfeca9b` is exactly the hash `docs/DEAD_CODE.md` records after task 4b.
The oracle reproduces the documented state of the tree.

**So the port does not have to be done blind.** Every C# function can be run
against its Kotlin original on the same seed and the difference read off. On a
15,000-line translation that is worth more than any amount of care.

### 0.5 The gate has no baseline

`tools/gate/baseline.properties` does not exist. `tools/gate/` contains
`Gate.kt` and nothing else, and `GateKt` exits 2 with *"FAIL: no baseline …"*.
`TASKS.md` task 1, "Bring the gate up", is still `[ ]`.

The brief describes "a fingerprint gate that fails the build when a change meant
to be cosmetic moves a scoreline". The gate is written and correct; it has never
been armed. I have not run `--record` and will not.

---

## 1. Four things in the brief I think are wrong

### 1.1 `ShapeAudit` does not exist, and neither does the 0.50

There is no `ShapeAudit` harness in the tree and the string `togetherness` does
not appear in any file — not in `tools/`, not in `docs/`, not in any `.md`.
There is `ShapeCheck.kt`, and it measures something else entirely. Run here over
20 matches, 44,954 samples:

```
back to front, everyone     45.2 m
back to front, the block    38.4 m   (furthest man dropped)
widest to widest            44.6 m
nearest team mate           10.8 m

a real side keeps 30-40 m between its lines and 45-55 m wide;
the nearest team mate is usually 12-18 m away
```

The block is **38.4 m against a real 30–40 — inside the band.** What is out of
band is width (44.6 against 45–55, narrow) and spacing (10.8 against 12–18, too
close). That is a side that is squeezed together, which is consistent with the
brief's diagnosis, but it is *not* the number the brief quotes and the brief's
number cannot be reproduced from this tree.

Two consequences:

- The mean-pairwise-cosine metric has to be **written**, not ported. And it has
  to be written in **Kotlin first**, so there is a reference value before any C#
  exists. Otherwise the first C# reading has nothing to be wrong against — which
  is the exact failure `CLAUDE.md` rule 4 is about.
- `ShapeCheck` should be ported too. It is the harness that actually exists and
  it already has numbers on the record.

I will do both, in that order, and report the Kotlin cosine before writing the
C# one.

### 1.2 `OffBall` is not the fix, and `docs/DEAD_CODE.md` says so

The brief: *"`OffBall.cs` (twelve jobs…) is the fix, and it must be ported with
`ENABLED = false`."* Port it off — agreed, and for the reason given. But the
document's own closing measurement, eight matches, on against off:

| | goals | shots | men in the middle fifth | ball carried to |
|---|---:|---:|---:|---:|
| off | 3.88 | 23.5 | 52.0% | x = 38.6 |
| on | 0.75 | 8.0 | 33.8% | x = 45.3 |

and its conclusion: *"The missing thing is build-up, not finishing, and build-up
is what `Pitch.kt` was written for."*

`OffBall` makes the defending side defend. It does not give the attacking side a
way through, and switching it on without that is the 1.00-goal game. The fix is
`OffBall` **plus** a build-up model. Porting it off is right; carrying the belief
that a later `ENABLED = true` closes the shape defect is not, and it would cost a
session to rediscover.

### 1.3 The C# fingerprint *can* equal the Kotlin one, and it should

The brief: *"The C# fingerprint will not equal the Kotlin one; floating-point and
iteration order will differ. That is expected."*

I do not think that is true here, and treating it as true throws away the single
best tool available for this port. Taking the two causes in turn:

**Floating point.** Java and C# are both IEEE-754 with the same `float` and
`double`. `Rng` is integer xorshift on a 64-bit signed value — `xor`, `shl`,
`ushr`, and SplitMix64 constants — all of which are exact in C# with `long` and
`>>>`. `Rng.f()` is `(next() ushr 11).toDouble() / (1L shl 53).toDouble()`:
a 53-bit integer divided by a power of two, exactly representable, exact in both.
Arithmetic reproduces if, and only if, every site keeps its Kotlin width —
`Float` stays `float`, `Double` stays `double`, and the `.toDouble()` /
`.toFloat()` casts land in the same places. That is a discipline, not a hope, and
it is checkable one file at a time against the oracle.

**Iteration order.** Sixteen hash containers exist across `World.kt` and
`MatchEngine.kt`. I checked the ones on the result path: `v2Support`,
`v2Cooldown`, `partOf`, `claimed`, `usedLines` are all keyed lookups and
membership tests — nothing iterates them. This needs auditing to the end, but it
is sixteen sites, not a structural problem, and the rule going in is: no C#
`Dictionary`/`HashSet` is ever enumerated on a path that reaches a result.

**The one real hazard is `hypot`.** `kotlin.math.hypot` on `Double` is Java's
`Math.hypot`, which is fdlibm, and it is not `sqrt(a² + b²)` — it is a scaled
algorithm accurate to under 1 ulp, and the last bit differs from the naive form.
It is called **55 times** across `MatchEngine.kt` / `Physics.kt` / `World.kt`,
and `v2m` — the distance function the whole of engine v2 is built on — is a
one-line wrapper around it. .NET's `netstandard2.1` has no `Math.Hypot` at all.
So the port has to carry an fdlibm `Hypot` in C#, about 40 lines, and prove it
bit-identical over a few million random pairs against the JVM before anything
else is written.

**What I am proposing is not that a matching fingerprint is a shipping
requirement.** It is that it is the *acceptance test for the translation*. If
C# `GateKt` prints `da1caad36bfeca9b`, then 200 matches' worth of every score,
shot, card and corner agree exactly, and the entire result path is proven correct
in one line of output. If it prints something else, I diff per-match scorelines
against the oracle, bisect to the fixture, and find the file that drifted — which
is a bounded search rather than a hunt through football.

If it comes out different for a reason I can name and defend, I will say so, show
the divergence, and fall back to the brief's acceptance band. But starting from
"it will differ" guarantees it differs, and gives up the ability to tell a
translation bug from a translation choice.

### 1.4 `Pitch.kt` will be dead on arrival in C# too

The brief says to port `Pitch.kt` because it is needed. It is — but nothing
except the v3 adapter calls it, and the adapter is being left behind. So on the
day it lands, `Pitch.cs` is 169 lines of correct code on a path that does not
run: the exact shape `docs/DEAD_CODE.md` catalogues seven times.

It should still be ported. But it lands **with a harness on the same commit** —
feed it a live v2 frame, print the space map, and check it answers. A flag that
is false for two sessions is switched on or deleted; a file that nothing calls
gets a caller or a harness on day one.

---

## 2. What this container can and cannot do

| | |
|---|---|
| .NET 8 SDK | **installed**, from the Ubuntu archive |
| Kotlin 2.0.21 compiler | **installed**, from Maven Central — the oracle |
| Java 17 | present |
| Unity 6 Editor | **absent**, and not installable here |
| Android SDK / NDK | **absent** |

So phases 0–4 are fully measurable here: the simulation compiles as a plain class
library and every harness runs with `dotnet run` in seconds, exactly as the brief
intends. Phases 5–7 — the broadcast, the manager screens, the APK — I can
**write** but cannot **compile, run, render or ship** from this container.

That collides directly with `CLAUDE.md` rules 1 and 2, and I would rather say so
now than hand over Unity code with "should work" attached to it. It is also the
one thing the arriving zip did not fix: the skeleton's own README ends on
*"Unity Editor validation and Android device build (requires a local Unity
installation)"* — unticked — so nothing in this project has ever been through a
compiler that knows what a `MonoBehaviour` is.

Options, for you to pick from later: a self-hosted runner with the Editor and
Android SDK; a GitHub Actions workflow on a licensed Unity image; or you build
locally and I work from your output. **Nothing needs deciding until phase 5.**

---

## 3. The Unity skeleton, read file by file

### 3.1 What survives

745 lines arrived. **About 280 of them are worth keeping**, and I want to be
plain about that number rather than let "it replaces ~9,400 lines of Kotlin
rendering" stand unqualified. Unity replaces those 9,426 lines. The skeleton
contributes a starting point of roughly 280. Both things are true and only the
first one is a large number.

| file | lines | verdict |
|---|---:|---|
| `LowPolyFootballer.cs` | 96 | **keep**, two fixes. The best file in the zip. |
| `MatchBroadcastDirector.cs` | 254 | **split** — see below |
| `ManagerDashboard.cs` | 129 | **keep the builders** (`Panel`/`Label`/`Button`, 46 lines); the four panels are placeholder content |
| `CareerDugoutSceneBuilder.cs` | 29 | **keep**, extend |
| `GameBootstrap.cs` | 67 | **rewrite** — the shape survives as ~15 lines, `PlayerPrefs` → JSON |
| `GameModels.cs` | 86 | **delete** — an 11-man squad with no bench, string club names and six hard-coded opponents, against a world of 1,236 clubs and 32,136 players |
| `DeterministicMatchEngine.cs` | 84 | **delete** — decision 1 |

`MatchBroadcastDirector.cs` is not one thing and should not be judged as one:

- `BuildStadium`, `CreateLine`, `CreateGoal`, `SetMaterial` — ~73 lines,
  **keep and rescale** (§3.2)
- the camera in `Update`, 5 lines — **keep**. This is the whole of "the
  broadcast camera": a lerped elevated follow with a `LookAt` biased 75% of the
  way from the centre circle to the focus. It is a good five lines.
- `CreateBroadcastOverlay` — 30 lines, **keep**
- `BuildPlayers` — 28 lines, **rewrite** for 22 men out of the world
- `PlaySequence`, `RunPhase`, `ResetShape`, `Duration`, `EventColor` — ~85 lines,
  **delete**. This is the replay-a-decided-list architecture itself, not just the
  engine that feeds it. `PlaySequence` is `foreach (var e in result.Events) { …
  yield return new WaitForSeconds(Duration(e.Kind)); }` and `RunPhase` moves the
  other twenty men with `Mathf.Sin((index + minute) * 1.9f)` — a decorative
  wiggle around a fixed slot. Deleting the engine and keeping the director would
  keep the thing decision 1 is actually about.

### 3.2 Three defects in the part being kept

**The pitch is less than half size, and nothing else is.** The `Plane` primitive
is 10×10 units, scaled `(4.5, 1, 2.9)`, and the painted lines sit at x = ±22.35
and z = ±14.25 — a **44.7 × 28.5** pitch. A real one is 105 × 68, which is what
`ShapeCheck.kt` says in its own comment and what every distance in the engine is
denominated in. Everything else in the scene is at roughly real scale: the goals
are 6.6 wide × 2.4 high against a regulation 7.32 × 2.44, and a player's head
tops out at 1.98–2.39 units (`bodyHeight` is `0.91 + (BodySeed % 19) * 0.012`,
the head sits at `1.91 × bodyHeight` and its sphere adds 0.24).

So a footballer occupies 2.2/44.7 = **4.9% of the pitch length, against a real
1.85/105 = 1.76%** — men about **2.8× too big for the ground they are on**. Feed
engine positions straight in and a 38.4 m block would be drawn across most of the
pitch. The fix is one line — the pitch becomes 105 × 68 and the line positions
follow — and it has to happen before the first frame is judged, or every shape
reading taken off the screen is wrong by a factor of two and a half.

**`Shader.Find` at runtime is an Editor-only habit.** Both call sites are
`Shader.Find("Universal Render Pipeline/Lit") ?? Shader.Find("Standard")`, and
`Packages/manifest.json` contains no `com.unity.render-pipelines.universal` — URP
is not installed, so the first `Find` always returns null and the fallback always
runs. That is survivable. What is not: in a player build `Shader.Find` returns
null for any shader not referenced by a material in a scene or listed under
Always Included Shaders. This works in the Editor and gives magenta or null
materials in the APK — which is precisely the class of bug this project has been
burned by seven times, correct code on a path that does not run, except here the
path that does not run is the shipped one. Materials become assets, resolved
once, not searched for per part.

*Adding URP would be adding a package. Per the rule, I am not doing that — I am
telling you it is missing and waiting.*

**One `Material` per part.** 13 parts × 22 players + 14 stadium pieces + the ball
≈ **301 unique material instances**, so 301 draw calls with no batching possible,
because every instance is unique by construction. On an Android-first game that
is the first thing to show up in a frame capture. A handful of shared materials
plus a per-renderer property block gives the same look and batches.

None of these three is a reason to throw the file away. They are the difference
between a skeleton and a build.

---

## 4. Architecture

```
CareerDugout.Unity/                     the one project
  Assets/
    Scripts/
      Sim/                              CareerDugout.Sim.asmdef
        CareerDugout.Sim.asmdef           references: []  -- no Unity, enforced
        Core/     Rng Model Names World Manager Staff DressingRoom Synth
        Sim/      MatchEngine MatchEvents Physics Pitch OffBall League Career
        Save/     SaveModel.cs            plain data, no I/O
        Math/     Fd.cs                   fdlibm Hypot, and nothing else
      Game/                             CareerDugout.Game.asmdef
        Broadcast/  driven live at ~36 Hz from engine state
        Screens/    squad tactics fixtures table transfers loans dressing
                    room board staff training academy finance inbox record
        Persist/    JSON to Application.persistentDataPath
  Tools/
    CareerDugout.Sim.csproj             netstandard2.1, same .cs files
    Harness/CareerDugout.Harness.csproj  net8.0 console, dotnet run
      Gate BalanceBig EventCensus ShapeCheck ShapeAudit ObCheck PoseTime
      WorldCheck ...
```

Two facts do the work:

- **`CareerDugout.Sim.asmdef` has an empty `references` array and
  `"noEngineReferences": true`.** Unity itself then fails the compile on the
  first `using UnityEngine`. The rule is enforced by the build, not by
  discipline.
- **`CareerDugout.Sim.csproj` compiles the identical `.cs` files** to
  `netstandard2.1` with `LangVersion 9.0` — Unity 6's C# level — so nothing can
  be written that the Editor will reject. The harness console project references
  it. `dotnet run --project Tools/Harness -- gate` and the answer is on screen in
  seconds.

A CI job runs the harnesses on every push. It needs no Unity licence, because
the thing being tested has never heard of Unity.

---

## 5. Determinism

Three streams, kept apart, ported exactly:

| stream | seeded | decides |
|---|---|---|
| `rng` | `world.seed + fixture.id * 7919 + world.week` | anything reaching a result |
| `sfxRng` | `fixture.id * 104729 + 17` | anything cosmetic |
| `physRng` | `fixture.id * 96137 + 43` | the ball |

`Rng` ports as `long` with `>>` and `>>>` matching Kotlin's `shr` / `ushr`, and
the SplitMix64 finalizer keeps its exact constants — that scramble is not
decoration, it is why 460 clubs stopped sharing one badge shape.
`UnityEngine.Random` appears nowhere; `System.Random` appears nowhere either.

Enforced mechanically. A CI grep fails the build on `UnityEngine`,
`System.Random`, `DateTime.Now` or `Guid.NewGuid` anywhere under `Scripts/Sim/`.

---

## 6. The order, with the gate on each step

Each phase ends with a number or it has not ended. Numbers marked **(oracle)**
are produced by running the Kotlin original here and are the target the C# has
to hit.

**Phase 0 — the floor.** Unity 6 project, the two asmdefs, the two csprojs, a
CI job. `Fd.Hypot` ported from fdlibm and proven bit-identical to
`Math.hypot` over 10⁷ random pairs against the JVM.
*Done when:* `dotnet build` is clean and the hypot check reports 0 mismatches.

**Phase 1 — `Rng`, `Model`, `Names`, `World`.** ~3,400 lines.
*Done when:* C# `WorldCheck` prints **1,236 clubs, 32,136 players, 0 duplicate
names, 0 clubs in the wrong division** — and, additionally, the club and player
name lists are byte-identical to the oracle's. Same seed, same world, or the
port of `Rng` is wrong and everything after it is built on sand.

**Phase 2 — `MatchEngine` v2, `MatchEvents`, `Physics`, `Pitch`, `OffBall`.**
~7,200 lines, the bulk. v1 (`newPhase`/`stepPhase`/`resolvePhase`, ~670) and the
v3 adapter (~395) are **not** translated. `OffBall` lands with `ENABLED = false`
and its two fixed defects carried across. `Pitch` lands with a harness.
*Method:* one file at a time, each diffed against the oracle before the next
starts.

**Phase 3 — the harnesses.** `Gate`, `BalanceBig`, `EventCensus`, `ShapeCheck`,
`ShapeAudit` (new), `ObCheck`, `PoseTime`, `WorldCheck`.
*Done when, in order:*
- C# `Gate` over 200 matches prints `da1caad36bfeca9b` **(oracle)**. If it does
  not, phase 2 is not finished and I bisect rather than proceed.
- C# `BalanceBig` over 9,120 matches lands in **2.5–2.7 goals, home ~45%**.
  The oracle, measured here over the full 4 worlds × 6 seasons:

  ```
  === top division, 4 worlds x 6 seasons = 9120 matches ===
  chance quality     0.104
  goals per game     2.54  +/- 0.03 at 95%   target 2.5 - 2.7
  home wins          45.9%     target ~45%
  draws              25.0%     target ~25%
  away wins          29.1%     target ~30%
  cards per game     0.91
  INSIDE THE TARGET BAND
  ```

  2.54 and 45.9% are the same figures `docs/DEAD_CODE.md` records after 4b, so
  the oracle agrees with the written record on the aggregate as well as on the
  fingerprint. Note `chance quality 0.104`, not the 0.113 in `BalanceBig.kt`'s
  own comment — the comment is stale, and this is `CLAUDE.md` rule 8 in the
  wild. The C# port targets **2.54 ± 0.03**, which is a tighter and more useful
  test than the 2.5–2.7 band: hitting the band is necessary, matching the mean
  is what says the translation is faithful.
- C# `EventCensus` reproduces the oracle's 90 rows, including which are 0.00.
- Only then is the baseline recorded — **by you, not by me** — and the gate
  protects everything after it.

**Nothing is drawn before this phase passes.**

**Phase 4 — `League`, `Career`, save.** ~2,900 lines plus a JSON writer to
`Application.persistentDataPath`. The save is a rewrite, not a port: `Save.kt` is
positional rows and `read()` discards a wrong version in silence. The format is
listed in `CLAUDE.md` as **your** decision, and `TASKS.md` task 10 asks for a
comparison before anyone chooses — so I will write you that one-pager and stop.

**Phase 5 — the broadcast, live.** `MonoBehaviour`s that read
`engine.players[i].x/y`, `engine.ball`, `engine.scene`, `engine.evCaption` every
frame and move transforms. The engine steps at a fixed 1/36 s; rendering
interpolates between steps and never drives them. No list of pre-decided events
anywhere — that is decision 1, and it applies to the Unity side as much as to
the file being deleted. Poses from `Ev4.SHOWN`. Commentary drains highest-weight
first and drops the rest.
*Starts from the kept two thirds of `MatchBroadcastDirector` (§3.1), with the
pitch rescaled to 105 × 68 and the materials shared (§3.2), and with
`PlaySequence` / `RunPhase` gone.* Writable here, not runnable here.

**Phase 6 — the manager screens.** Fourteen of them, specified by
`MainActivity.kt`, built on `ManagerDashboard`'s `Panel` / `Label` / `Button`.

**Phase 7 — Android.** IL2CPP, ARM64, an APK.

---

## 7. What I need from you

1. **A decision on §1.3** — whether to aim for a bit-identical fingerprint as the
   translation's acceptance test. I recommend yes, and I will tell you inside a
   day of phase 1 whether `Rng` and `World` reproduce, which settles it early and
   cheaply.
2. **URP: in or out.** Both `Shader.Find` sites ask for it and the manifest does
   not have it, so today it silently falls back. Adding
   `com.unity.render-pipelines.universal` is adding a package, which is yours to
   approve. Say no and I build against the built-in pipeline, which is fine for
   flat-shaded low-poly and cheaper on Android.
3. **`GAME.md`, if it exists.** I planned from `AUDIT.md` and the rest.
4. **Eventually, a Unity build machine.** Not until phase 5.

None of these blocks starting. Items 1 and 2 are answered before they are needed
if you answer them at all.
