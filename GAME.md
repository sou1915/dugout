# Career Dugout

A football management game for Android. You pick a club, you pick a team, you
pick a shape — and then you sit in the dugout and watch. You never kick a ball.

Every figure in this document was measured off the code in this repository, not
estimated. Where something is broken it says so, with the number.

---

## 1. What it is

| | |
|---|---|
| Platform | Android, Kotlin, no game engine |
| Source | ~24,500 lines of Kotlin |
| Third-party libraries | **three**, all AndroidX: `core-ktx`, `appcompat`, `recyclerview` |
| Binary assets | **10 files**, all of them the launcher icon at five densities |
| Art, faces, kits, crests, crowds, pitch | drawn in code at run time |
| Sound | none |
| World data | none shipped — generated from a seed |
| Network | none. It never talks to anything |

The whole product is 1.3 MB of source and a launcher icon. There is no sprite
sheet, no audio bank, no player database, no licence, no download.

### Why it is built that way

A management game is mostly *names, numbers and consequences*, and all three
can be generated. What cannot be generated is a good simulation, so everything
saved on assets is spent there instead. The constraint is also a design rule:
if a thing cannot be produced from a seed, it does not go in.

---

## 2. The world

Built from one 64-bit seed in **357 ms**:

| | |
|---|---|
| Clubs | **1,236** |
| Players | **32,136** |
| Countries | 34 |
| Divisions | English pyramid 5 tiers deep; 21 nations at 3 tiers; 12 at 1 |
| Squad size | 26 |
| Clubs per division | 20 (England), 12 or 10 elsewhere |
| Duplicate club names | **0** |
| Duplicate player names | **0** |
| Clubs in the wrong division | **0** |

Names are assembled per nation, so a Moroccan club reads like a Moroccan club
and a Japanese one like a Japanese one — `Ait Melloul CAY`, `Yamagase Vissel`,
`Kolbadou Genereation`, `Sporting Castellera`, `Saltholm Town`. Kits, crests
and faces come from the same seed, so the same world is the same world on any
phone.

Rivalries are derived rather than declared: the generator finds clubs that
share a city or sit adjacent in the pyramid and names the derby.

### A player

17 attributes — Pace, Acceleration, Finishing, Long Shots, Passing, Vision,
Dribbling, Technique, Tackling, Marking, Positioning, Strength, Stamina,
Composure, Reflexes, Handling, Distribution — plus a personality, traits
(`FLAIR`, `HOTHEAD`, `SET_PIECE`, …), a potential ceiling, a contract, a wage,
a morale and a face.

Positions: `GK DC DL DR DM MC ML MR AM ST`, grouped keeper / defence /
midfield / attack.

---

## 3. The career

You apply for a job, or start from nothing. Then, week by week:

- **Squad, tactics, training** — formation, mentality, width, line, pressing,
  tackling, counter, time-wasting, per-slot instructions, marking, a nominated
  penalty taker
- **Transfers** — bids, asking prices, release clauses, agent noise, AI clubs
  buying from each other, a press reaction when you sign somebody
- **Loans** — send players out, field requests, recall early, share wages
- **The dressing room** — talk to players, make promises, keep them or don't;
  influence, morale, cliques
- **The board** — objectives, confidence, and being sacked
- **Staff** — a backroom team that changes what training does
- **Youth intake** — a yearly crop from your own academy
- **Finance** — wages, gate receipts, weekly balance, forced sales
- **Press** — questions after signings and defeats, with answers that land
- **The world going on without you** — AI transfers, other results, news
- **Cups**, foreign leagues, a career record, and continental structure

Saving is a single file written by `Save.kt`, with a `.bak` alongside it.

---

## 4. The match

This is the part the project actually is.

The pitch is 100 × 100 internal units. **1 x-unit = 1.05 m, 1 y-unit = 0.68 m.**
Twenty two men and a ball are simulated continuously; nothing is pre-rolled and
no sequence is scripted.

### Three random streams, on purpose

| stream | what it decides |
|---|---|
| `rng` | anything that reaches a result |
| `sfxRng` | anything cosmetic — which of four commentary lines, which way the keeper dives |
| `physRng` | the ball |

A cosmetic change must not be able to move a scoreline. Keeping them apart is
what makes that provable rather than hoped for.

### Engines

Three exist in the file. **One runs.**

| | state | lines |
|---|---|---:|
| **v2** — continuous possession | **LIVE** (`ENGINE_V2 = true`) | 972 |
| v1 — phase engine | dead `else` branch | ~670 |
| v3 — Physics → Pitch → RoleTree → Utility | `ENGINE_V3 = false`, does not finish a match | 395 |

### What v2 does

The ball is an object. Whoever gets there first takes it — not whoever is
inside a radius. The ball's path is sampled a few tenths of a second ahead,
every player is asked how long *he* would take to reach each point given the
speed and direction he is already travelling, and the lowest claim wins. A ball
nobody can reach stays loose because nobody's claim is low, not because a
threshold said no.

The man on the ball gets a **think** of his own, from his composure and vision,
set at the instant he receives it. He knows he has it. The man a pass is aimed
at knows it is coming. Nobody is left believing he is about to receive a ball
somebody else has already won.

Offside is judged **when the ball is played**, and becomes an offence only when
the man who was offside touches it — which is the law, and is why a man can be
offside 44 times a match and be flagged 6.

### Measured, per match

| | |
|---|---|
| Goals | **2.60 ± 0.03** over 9,120 matches |
| Home wins | 45.4% |
| Draws / away | 25.0% / 29.6% |
| Cards | 0.92 |
| Shots | ~24 |
| Interceptions | 29.9 |
| Offsides | 7.3 |
| Corners | 4.8 |
| Goal kicks | 19.6 |

---

## 5. The event table

Every event a football match contains is written down in one place —
`Ev4.SHOWN`, **90 rows**. Each row says what that event looks like: which
scene, which pose, on whom, what caption, what the crowd does, what the referee
does.

```kotlin
Shown(Scene.SHOT, Act.HEADER, caption = "HEADER", crowd = 0.80f)   // SHOT_HEADER
```

`EventCensus` walks all 90 and prints the rate of each. **A row that reads 0.00
is a named job.** That list is the roadmap.

`Ev4.DRAWN` decides which rows may draw themselves yet, grouped by what they
can break:

| group | contains | can it move a result? |
|---|---|---|
| **4a** | caption and crowd only | no — proven byte-identical |
| **4b** | rows that bring a scene | yes: a scene changes dead-ball speed |
| 4c | rows that bring a **pose** | yes, by design — needs approval |

4c is separate because `act(Act.TACKLE)` and `act(Act.DOWN)` **stop a man
moving**, and who can play the next ball is a result.

There is also a commentary bank per event with a **weight**. The highest weight
waiting is spoken and *the rest are thrown away* — a queue would say them late,
which is exactly how a commentator in a game sounds broken.

---

## 6. How it is proved

The whole app — engine, renderer and `MainActivity` — compiles against a Java2D
shim and runs **headless on a desktop JVM**. This is the most valuable thing in
the repository: a visual claim can be *measured* instead of asserted.

**124 measurement harnesses.** Some of them:

| harness | question |
|---|---|
| `Gate.kt` | 200 matches, FNV-1a over every score, shot, card and corner |
| `BalanceBig` | 9,120 matches — goals per game, home advantage |
| `EventCensus` | all 90 rows; which never happen |
| `ShapeAudit` | do the 22 move like a team or a shoal |
| `PoseTime` | **seconds on screen**, not counts |
| `ObCheck` | the off-ball layer, on against off |
| `UiFlow` | 33 screens driven for real, counting crashes |
| `MatchFilm` | renders actual frames to look at |

### The rules

- **Measure, never assert.** Render the frames and look.
- **A change meant to be cosmetic must leave the fingerprint byte-identical.**
  If it moved, it was not cosmetic — something on the presentation path froze a
  player, and positions feed the outcome.
- **One change at a time**, so the fingerprint names the culprit.
- **Revert what does not work, and write down what it measured.** The reverts
  in `docs/` are worth more than most of the changes.
- Never edit by line index. Never add a dependency.

---

## 7. What is broken, with numbers

This section is the honest half.

### The defect that keeps recurring

**Correct code on a path that is no longer the path that runs.** Found **seven**
times:

1. `v2Defend` — the tackle logic, never called from the tick. 56 tackles and
   *zero* fouls a match.
2. `CHANCE_QUALITY` — swept by the balance harness while dead under v2.
3. Skill moves and `Scene.TAKE_ON` — reachable only from v1. 0.00 a match.
4. `L_DRIBBLE` — an index off the end of an array, waiting months to crash a
   match the first time it was ever called.
5. `fire(show = true)` — 90 authored rows, called from **zero** places.
6. `OffBall.tick` — the entire off-ball layer, a **no-op**: it decided every
   0.22 s and `updateTargets` overwrote it every frame in between.
7. `LivePlayer.vx/vy` — *"Engine v3 integrates this"*, and nothing writes them
   under v2.

**≈ 2,400 of ~7,800 lines of match code never execute.** A third.

### The movement

`ShapeAudit`, 8 matches:

| | measured | real |
|---|---:|---|
| average speed per man | 2.56 m/s | 1.9 |
| **togetherness** | **0.50** | **0.2 – 0.3** |
| nearest team mate | 10.7 m | 15 – 20 m |
| two men inside 3 m | 10.4% | rare |
| team width | 44.0 m | 35 – 45 m |
| within 5 m of his formation slot | 7.2% | a small share |

Togetherness is the mean cosine between every pair of outfielders' velocities.
0 is ten men going ten ways; 1 is one object being dragged. **At 0.50 they are
half a shoal, and that one number is the whole of "the players move wrongly".**

It follows from the model being exactly one rule:

```
a formation slot, plus a pull toward the ball
```

The slot is shared and the pull is the same vector for everybody, so two
players' movement differs only by where they started. They are not doing
different things; they are the same thing, offset.

### The football

- v2 plays **one** pass. Four different rules for "through ball" read 52.50,
  44.65, 47.25 and 0.70 a match — the first three are each about a third of
  every pass, because nearly every ball v2 plays *is* a forward ground pass
  down an open lane into the final third.
- A third of its passes go to a man **already beyond the last defender**.
- **It does not cross.** 1.00 a match. It does not play down the flanks.
- **75 long passes against 61 short.** It advances by hoofing it and hoping,
  and its ~24 shots are the yield of that lottery, struck from an average carry
  position in its own half.
- Corners are won 4.8 times a match and produce **no header at goal at all**.

### Why the fix is not switched on

`OffBall.kt` — 671 lines, twelve jobs (press, cover, mark, recover, support,
run beyond, hold width, attack the box, second ball, chase, show short, shape).
Once its two defects were fixed it works:

| | layer off | layer on |
|---|---:|---:|
| men in the middle fifth | 52.0% | **33.8%** |
| ball carried to | x = 38.6 | **x = 44.7** |
| short pass | 61 | 238 |
| long pass | 75 | 6 |
| shots | 25.8 | **6.75** |
| goals | 3.88 | **1.00** |

The men spread out and the ball goes further up the pitch. What collapses is
shots — because the defending side now *actually defends*, and v2's attack
cannot break down a defence that works. The side keeps the ball beautifully and
never goes anywhere with it.

**Five attempts have been reverted onto the same conclusion:** `Pitch.kt` into
the pass scorer, `Pitch.kt` into the carry, role-dependent pull coefficients,
the `V2_PROGRESS_W` sweep, and moving the hurried clearance. Each is written up
with its numbers in `docs/DEAD_CODE.md`.

---

## 8. What is next

One job.

> **Runs that break a line.** `OffBall`'s defensive half is finished. Its
> attacking half offers the carrier a man *beside* him and nothing *past*
> anybody — `RUN_BEYOND` fires for 0.1% of player-time, `ATTACK_BOX` for 0.4%.
> Give it runs timed against the carrier's think, so a forward pass has a
> receiver worth more than the safe sideways one.

**Done when**, all with `OffBall.ENABLED = true`:

- `BalanceBig` inside 2.5 – 2.7 goals
- `ShapeAudit` togetherness under **0.35**
- shots near 24

That is design, not tuning. The sweep already proved a lever cannot buy it: at
weight 7.50 it recovers shots and hands the spread straight back, 33.0% → 44.0%
of men in the middle fifth, with goals still at 1.83.

---

## 9. Where things live

```
app/src/main/java/com/dugout/career/
  MainActivity.kt        4,507   every screen
  core/
    World.kt             1,575   1,236 clubs and 32,136 players from a seed
    Model.kt               908   players, clubs, tactics, traits
    Names.kt               768   per-nation name assembly
    Manager.kt             279   you
    DressingRoom.kt        146   talks, promises, influence
    Staff.kt               143
  sim/
    MatchEngine.kt       6,450   the match
    Career.kt            1,967   weeks, transfers, loans, board, press
    League.kt              959   fixtures, tables, cups
    MatchEvents.kt         489   the 90-row event table
    OffBall.kt             671   what the other twenty one are doing (OFF)
    Save.kt                478
    Physics.kt             278
    RoleTree.kt            205   (v3 only — dead)
    Pitch.kt               169   space model (v3 only — dead)
  ui/
    MatchView.kt         1,582   the pitch, the men, the ball, the camera
    BroadcastRenderer.kt 1,494
    FaceGen.kt             565   a face from an id
    Theme.kt               568
    Icons.kt               232

tools/
  gate/Gate.kt                   200 matches, one fingerprint
  harness/                       124 measurement programs
docs/
  DEAD_CODE.md                   what is written and not running, with numbers
  ENGINE_V2.md / V3 / V4         each engine, and why
  ART_DIRECTION_REPORT.md
  PIXEL_ART.md
TASKS.md                         the queue, one at a time, in order
CLAUDE.md                        the rules
```

---

## 10. The short version

A football management game with **no assets, no data files and three
libraries**, where a world of 1,236 clubs and 32,136 players is built from one
number in a third of a second, and 2.60 goals a game has been proved over 9,120
matches.

The simulation is honest about itself: 124 harnesses, a 90-row table of every
event football contains, and a fingerprint that fails the build when a change
meant to be cosmetic moves a scoreline.

And it is honest about what is wrong. A third of the match code does not run.
The twenty two men move as half a shoal, and that is one number — 0.50 — with
one cause and one fix, which is written down and measurable and not yet done.
