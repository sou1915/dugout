# The brief — a football management game where the tactics are visible

Hand this file to Claude as the founding document. It describes what is being
built and, more importantly, what "working" means. Read all of it before writing
any code.

---

## 0. What this is

A football management game for Android, in Kotlin, no game engine and no
third-party libraries. The player is a manager. He never controls a footballer,
and he never will.

**The whole product is one loop:** the manager makes a tactical decision, then
watches a match and *sees* that decision in what eleven men do. If a setting
cannot be seen on the pitch, it is not a feature — it is a number in a menu.

The predecessor to this project failed at exactly that point. It had 90 authored
event types, a broadcast renderer, faces, a 32,000-player world — and a match
that played one pass over and over, because the engine picked the highest-scoring
forward pass every time it had the ball. It was measured properly and the
measurements said so:

| what the census found | rate | real football |
|---|---|---|
| crosses | 1.0 a match | 28–34 |
| headers at goal | ~0 | 8–12 |
| blocks, deflections, interceptions | 0 | modelled at all |
| corners producing a header | 0 of 3.8 | most |
| defenders goal-side of a ball in their own box | 1.25 of 10 | 8–9 |
| passes played to a man already beyond the last defender | ~⅓ | rare |

**The lesson to carry, and the reason for this rebuild:** variety in what you see
does not come from the presentation layer. It comes from the players having
several different things they are *trying* to do, and being able to fail at them
in several different ways. Build that first, and the pictures follow.

---

## 1. Carried over, non-negotiable

These are the things the old project got right. They cost nothing to keep and
they are the reason anything here can be trusted.

1. **The whole app compiles headless.** A Java2D shim stands in for the Android
   `Canvas`, `Paint`, `Path` and `Bitmap`, so the engine *and the renderer* run
   on a JVM with no device. Every claim about what appears on screen is then a
   thing you can measure or render to a PNG and look at.
2. **A result fingerprint, gated in CI.** Hash every score, shot, card and
   corner over 200 fixed-seed matches. A change meant to be cosmetic must leave
   it byte-identical. If it moved, the cosmetic path is touching the football.
3. **Separate RNG streams.** One stream decides outcomes, one decides
   presentation. A new cosmetic draw must never shift the outcome stream.
4. **Measure, never assert.** "Fixed" means a number from a harness, and that
   is still not "fixed on the device". Render frames and look at them.
5. **Know your standard error.** Goals a game is roughly Poisson, mean ~2.7, so
   400 matches carries ±0.16 at 95%. A sweep moving less than its interval has
   measured nothing.
6. **A measurement with no control row is worthless.**
7. **One system per commit.** If a change does not do what you intended, revert
   it and write down what it measured.

---

## 2. The tactical system

This is the product. Everything else serves it.

The design principle throughout: **a tactic is a set of instructions about
where to be and what to try, not a modifier on a dice roll.** No setting may be
implemented as `if (pressingHigh) chanceOfWinningBall += 0.1`. Every setting
below changes *positions, targets or intent* — and the effect on results is
whatever emerges from that.

### 2.1 Layer one — shape

- **Formation** as a positional grid, not a name. Each of the ten outfield slots
  is placed on a 5-lane × 6-band pitch grid (left wing, left half-space, centre,
  right half-space, right wing × own box → their box). "4-3-3" is a preset that
  fills the grid; the manager can drag any man anywhere.
- **Lane occupancy rules.** The engine reads the grid, not the name: how many
  men in the half-spaces, whether both full-backs are high, whether the centre
  is overloaded. These are what the opposition AI reacts to.
- **Width and depth** of the block, in metres, separately for in and out of
  possession. Four numbers, not one slider.
- **Asymmetry is first-class.** Left-back high and right-back tucked in must be
  expressible, because it is half of modern coaching.

### 2.2 Layer two — roles and duties

Each slot carries a **role** (what he tries to do) and a **duty** (how far he
goes to do it: defend / support / attack). A role is a bundle of intents with
weights, not a stat bonus.

Minimum role set to ship, each with a *visible* signature:

| role | what you should see |
|---|---|
| ball-playing defender | steps into midfield with it, plays the line-breaking pass |
| stopper | follows the striker out of the line, high recovery challenges |
| cover | drops behind the line, sweeps, few duels |
| inverted full-back | moves into midfield in possession, lane change is visible |
| overlapping full-back | outside the winger, reaches the byline |
| holding midfielder | stays in front of the back line, does not cross the halfway line |
| deep playmaker | receives facing his own goal, switches play |
| box-to-box | arrives in the box late, and gets back |
| advanced playmaker | receives between the lines in a half-space |
| inside forward | starts wide, ends central, shoots from the half-space |
| touchline winger | stays chalked to the line, takes his man on, crosses |
| target man | back to goal, holds it, brings others in |
| poacher | plays on the shoulder, in the box, few touches |
| false nine | drops out of the line and leaves the centre empty |

**Test for every role:** two sides identical except for one role change must
produce visibly different heat maps and different event rates. If they do not,
the role is not implemented — it is decoration.

### 2.3 Layer three — phase instructions

Football is five phases. The manager sets each one separately, and the engine
must actually be in exactly one of them at any moment.

**Build-up (own third)**
- goalkeeper distribution: play out / mixed / long
- number of men in the first line (2, 3, back three plus keeper)
- whether a midfielder drops in, and which
- invitation to press: do we bait them forward?

**Progression (middle third)**
- through the middle / down the flanks / switch and attack the far side
- tempo, and pass length distribution
- who is the free man we look for
- risk: how bad a pass we are willing to attempt

**Final third**
- cross early / work the byline / cut inside / shoot on sight
- how many men in the box on a cross, and who they are
- half-space runs, third-man runs, near-post vs far-post movement
- patience: how many passes before we accept a half-chance

**Transition to defence (rest defence)**
- counter-press for N seconds, or drop and shape up
- how many men stay behind the ball while we attack (this is a real setting and
  it is why teams get countered)
- foul to stop the break: never / cynical

**Defending**
- line height in metres, block width in metres
- pressing trigger set (see 2.4)
- marking scheme: zonal / man / hybrid, per zone
- offside trap: on / off, and how disciplined
- what we do when we win it: hold it / go long / go direct

### 2.4 Layer four — triggers, the part that makes it a tactics game

A trigger is `WHEN <condition> THEN <action>`. This is where tactical depth
actually lives, and almost no manager game exposes it properly.

**Pressing triggers** — press when: the ball goes backwards / it goes to a
named weak passer / it goes wide / a man receives facing his own goal / the
first touch is heavy / the ball is in a designated zone. Multiple can be armed
at once; each has a visible consequence (a man sprints, the line steps up).

**Situational instructions** — a small rules engine the manager edits:

```
WHEN  we lead by one AND minute > 75
THEN  line height -8m, tempo slow, full-backs duty = defend

WHEN  their left-back is on a yellow
THEN  target our right winger at him, take-on risk high

WHEN  we trail AND minute > 80
THEN  centre-back joins the attack for set pieces, cross early, box men = 5
```

These must be **inspectable and editable**, and the match feed must say when one
fires: *"You told them to sit — the line has dropped ten metres."* A rule that
fires invisibly is the same failure as before.

### 2.5 Layer five — the opposition

- **Opposition instructions per man**: show onto weaker foot, tight/loose
  marking, always press, never press.
- **Specific man-marking assignments**, including a midfielder assigned to their
  playmaker for the whole match, visible as a shadow on the pitch.
- **The opposition manager is a real agent.** He reads your lane occupancy and
  your last twenty minutes and changes his own shape. He must be running the
  same tactical system you are — never a special case, never a table of
  cheats. If the AI needs an instruction you cannot give, that instruction is
  missing from the game.

### 2.6 Layer six — set pieces

A routine designer, not a dice roll:
- corners: near post / far post / short / edge of the box, who attacks which
  zone, who stays back, whether the keeper goes up late
- free kicks by zone: shoot / cross / work it short, wall size for defending
- throw-ins: long throw specialist, quick throw permission
- penalty taker order, and a taker's tendency the goalkeeper can read

**Acceptance:** a corner routine change must be visible in one rendered frame.
Different men in different boxes. This is the exact thing the old project got
wrong for months.

### 2.7 Layer seven — in-match

- shouts with an emotional model behind them, not a morale +5
- tactical changes take **time to bed in**, and a side that has just changed
  shape is briefly disorganised — this is a real cost and must be visible
- substitutions with a plan attached ("he comes on to press their pivot")
- the touchline: the manager's own body language, because it costs nothing and
  sells the fantasy

### 2.8 Layer eight — players do not obey perfectly

The most important layer, and the reason the same tactic looks different with
different men.

- **Attributes gate execution.** A low-vision midfielder does not *see* the
  switch, so he plays the simple ball instead. He is not "worse at" the switch —
  he does something else.
- **Traits create disobedience.** "Shoots from distance", "tries killer balls",
  "hugs the line", "dives into tackles". A trait overrides an instruction some
  of the time, and the commentary should notice.
- **Familiarity.** A side that has drilled a shape for a season executes it; a
  new signing stands in the wrong place, and you can see it.
- **Fatigue and morale change what a man attempts**, not just how well he does
  it. A tired full-back stops making the overlap. That is why he becomes a
  problem — you can watch him stop.

---

## 3. The rule that binds it all

> **Every tactical setting must have (a) a visible signature on the pitch and
> (b) a measurable signature in a harness. A setting with neither is not
> implemented, whatever the code says.**

Every setting ships with a harness that runs the same fixture twice, changing
only that setting, and prints the difference. Every setting ships with two
rendered frames. No exceptions, no "obviously it works".

---

## 4. What the engine must be able to model

The old engine could not produce the pictures because the *events did not
exist*. Build these as first-class outcomes, from the start:

- **On the ball:** carry, shield, turn, take-on, nutmeg, backheel, flick-on,
  first touch good and heavy, dispossessed.
- **Passing:** short, long, switch, through ball, cross (early, byline,
  cut-back), one-two, clipped, driven, **misplaced** — a pass that fails is
  the single most common event in football and it did not exist before.
- **Shooting:** placed, driven, chipped, volley, header, from distance, first
  time, **blocked, deflected, off the woodwork, rebound**.
- **Defending:** interception, block, clearance headed and hoofed, tackle
  standing and sliding, last-ditch, recovery run, goal-line clearance, offside
  trap sprung, and **the foul as a tactical choice**.
- **Goalkeeping:** routine save, diving, tipped over, parried (and the rebound
  that follows), claimed cross, punched, sweeper action, distribution.

And the decision layer that chooses between them: **the man on the ball must
score several different options against his role, his instructions, the
positions of everyone around him and his own traits — and pick among them, not
maximise one number.** The single-maximiser is what produced one pass a match.

---

## 5. Acceptance — what "the match looks right" means

Per match, both sides combined. Verify these against a real dataset (Opta/FBref
style) before locking them in; they are the shape of the target, not gospel.

| | target |
|---|---|
| goals | 2.6–2.9 |
| shots | 22–27 |
| shots on target | 8–9 |
| shots blocked | 8–10 |
| headers at goal | 8–12 |
| crosses | 28–34 |
| corners | 9–11 |
| corners producing a shot | 20–25% |
| passes attempted | 800–950 |
| pass completion | 78–85% |
| interceptions | 16–22 |
| tackles | 30–36 |
| take-ons attempted | 30–40 |
| fouls | 20–24 |
| offsides | 4–6 |
| defenders goal-side of a ball in their own box | 8–9 of 10 |
| defensive block depth | 30–40 m |

**A number outside its band is a bug, not a preference.** These are the tests
that would have caught every defect in the predecessor on day one.

---

## 6. Build order

1. **Harness, shim and the fingerprint gate — before the first match is
   simulated.** Not after. The gate is what makes everything below cheap.
2. Pitch, ball flight, eleven men who move to targets. No decisions yet.
3. The event vocabulary from §4, with a census harness. Every event countable
   before any of them is drawn.
4. The decision layer: several options, scored, chosen among. Prove variety with
   the census before touching the renderer.
5. Roles and duties (§2.2), each with its harness and its two frames.
6. Phases and phase instructions (§2.3).
7. Triggers and the situational rules engine (§2.4).
8. The opposition manager, running the same system.
9. Set-piece routines.
10. Presentation: captions, commentary, replays — last, on top of football that
    is already varied.

**Presentation is step ten because it was step three last time.**

---

## 7. Traps, paid for already

- **Do not tune a lever that is dead.** Before changing any behaviour, prove the
  line executes in the shipped configuration by printing a counter from it.
- **Do not let two engines drift.** If an old path is kept as a control, a
  harness must print both fingerprints on the same seeds, every run.
- **Do not put a caption on an event that happens 75 times a match.** Use the
  census rate to decide: a caption is for something under ~4 a match; anything
  more frequent belongs to the commentary voice.
- **Do not let a pose freeze a man silently.** A pose that stops movement
  changes who reaches the next ball, and that is a result.
- **Do not write a classifier to invent variety that the football does not
  have.** If three different rules all say ⅓ of passes are through balls, the
  finding is about the passing model, not the rule.
- **Do not trust a comment.** Trust the harness over the comment, and the
  rendered frame over the harness.

---

## 8. First deliverable

Do not write engine code yet. Reply with:

1. The data model for §2.1–2.3 — how a formation, a role, a duty and a phase
   instruction are represented, and how the engine reads them each tick.
2. Your proposal for the decision layer of §4: what options a man on the ball
   scores, what inputs each score takes, and how one is chosen among them
   without collapsing to a maximiser.
3. The list of harnesses you would build alongside step 4 of the build order,
   and what each one prints.
4. Anything in this brief you think is wrong, or too expensive for what it buys.
