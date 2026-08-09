# Response to the brief — §8, first deliverable

No engine code, as instructed. This is the data model, the decision layer, the
harnesses, and what I think is wrong in the brief.

It is written against the predecessor in `careerdugout20.zip`, which I read
before answering: `CLAUDE.md`, `AUDIT.md`, `docs/ENGINE_V4.md`, the ~120
programs in `tools/harness/`, the Java2D shim in `tools/harness/shim/` and
`tools/gate/Gate.kt`. Where a proposal below replaces something that was
already measured broken, the measurement is named.

---

## 0. The one structural idea

Everything below hangs off a single decision, so it goes first.

**Every tactical setting is a row in a declared schema, not a field on a data
class.** A setting has a key, a type, a range, a default, the code sites that
read it, and the harness that proves it. The schema is one file, and the UI, the
save format, the AI manager's action space and the acceptance harnesses are all
generated from it.

This is not architecture for its own sake. It is what makes §3 of the brief
affordable. §3 says every setting ships with a harness that runs the fixture
twice and prints the difference, plus two rendered frames. With ~60 settings
that is 60 bespoke harnesses that will not get written, and the rule quietly
dies — the same way "every scene must exist" died three times in the
predecessor's own audit. With a schema, §3 is one generic differ invoked by key:

```
$ ABSweep final_third.cross_timing=EARLY --film
```

and the two frames fall out of the same run. The rule stops depending on
discipline.

It also mechanises trap 7.1, *do not tune a lever that is dead*. Each schema
row declares its consumers, and a read-counter reports reads-per-match per
setting. `CHANCE_QUALITY` was swept for four rows of `BalanceBig` while dead
under v2; `v2Defend` was written, correct, and never called. Both are a zero in
a column, found in one run, if the column exists.

---

## 1. The data model — §2.1 to §2.3

### 1.1 Frame of reference

Metres, and **attack-normalised per side**. Each team computes in its own frame:
`x` 0 at its own goal line to 105 at theirs, `y` 0 to 68 left-to-right from its
own perspective. One mirror at the boundary. Every role, every instruction and
every trigger is then written once and works for both sides, and the AI manager
cannot accidentally be a special case because there is no second coordinate
system for it to live in.

The 5×6 grid is a *view* of that space, not the space itself:

- lanes split on the box lines — `0, 13.85, 27.25, 40.75, 54.15, 68` m, so the
  wings are the corridor outside the box and the half-spaces are real
  half-spaces rather than fifths of a rectangle;
- bands — own box, own third, middle-defensive, middle-attacking, final third,
  their box.

### 1.2 Formation

```kotlin
data class Slot(
    val id: Int,                    // 0 = GK, 1..10 outfield
    val lane: Float,                // 0..4, continuous
    val band: Float,                // 0..5, continuous
    val role: RoleId,
    val duty: Duty,                 // DEFEND / SUPPORT / ATTACK
    val opp: OppInstructions        // §2.5, per man
)

data class Formation(val slots: List<Slot>)   // exactly 11
```

`lane` and `band` are floats, not grid indices. The grid is a **snap, not a
cage** — "4-3-3" is a preset that writes eleven anchors, and dragging a man puts
him at 3.4/2.7 if that is where he was dropped. Lane occupancy queries round.

Asymmetry is therefore free and needs no feature: left-back at band 3.6 and
right-back at band 1.1 is two numbers, and nothing downstream knows or cares
that they differ. The brief asks for asymmetry to be first-class; the cheapest
way to make something first-class is to make symmetry the special case that is
never written down.

```kotlin
data class Block(
    val widthInPossM: Float, val widthOutPossM: Float,
    val depthInPossM: Float, val depthOutPossM: Float,
    val lineHeightM: Float          // last line, metres from own goal line
)
```

Four numbers plus the line height, as specified. Width and depth are *targets
for the shape*, applied as a scale about the shape's centroid when anchors are
compiled to metres — not a clamp applied per player per tick, which would fight
the role movement and produce the rubber-banding the predecessor had in
`updateTargets`.

### 1.3 What the engine reads instead of the formation name

```kotlin
class ShapeReading(                 // derived; recomputed on tactical change
    val perLane: IntArray,          // 5
    val perBand: IntArray,          // 6
    val halfSpaceCount: Int,
    val fullBacksHigh: Int,         // 0, 1 or 2
    val firstLineCount: Int,        // build-up
    val restDefenceCount: Int,      // §2.3
    val centreOverload: Int         // ours minus theirs, central lanes
)
```

This is the only thing the opposition manager is allowed to look at, and both
sides compute it with the same function. §2.5 says he must run the same system;
the way to guarantee that is not discipline, it is to give him no other input.

### 1.4 Role and duty

A role is three things, and each maps to one of the two signatures §3 demands:

```kotlin
data class Role(
    val id: RoleId,
    val posBias: Array<Bias>,       // one per phase: Δlane, Δband, leash radius m
    val movement: FloatArray,       // weight per RunKind — the off-ball signature
    val intent: FloatArray          // prior per OptionKind — the on-ball signature
)
```

- `posBias` produces the **heat-map** difference.
- `movement` — weights over a fixed vocabulary of runs: `OVERLAP`, `UNDERLAP`,
  `IN_BEHIND`, `DROP_BETWEEN_LINES`, `HOLD_WIDTH`, `COME_SHORT`, `NEAR_POST`,
  `FAR_POST`, `THIRD_MAN`, `STAY_GOAL_SIDE`, `ARRIVE_LATE_IN_BOX`,
  `SUPPORT_BEHIND_BALL` — produces the **event-rate** difference, because a run
  is what creates a passing option (see §2.5 below).
- `intent` — priors over on-ball option kinds.

An inverted full-back and an overlapping full-back are the same eleven fields
with different numbers. There is no `if (role == INVERTED)` anywhere, and that
is the test of whether the role system is real.

**Duty is a transform, not a fourth table.** Fourteen roles times three duties is
forty-two hand-tuned bundles that will drift apart within a month. Duty is four
scalars applied to the role:

```kotlin
data class Duty(
    val bandPush: Float,            // ± anchor band
    val leashScale: Float,          // how far he leaves his anchor
    val riskAppetite: Float,        // feeds the decision layer's risk weight
    val restDefenceDuty: Float      // obligation to stay behind the ball
)
```

### 1.5 Phase

```kotlin
enum class Phase {
    BUILD_UP, PROGRESSION, FINAL_THIRD,
    TRANSITION_TO_ATTACK,           // proposed — see §4.1
    TRANSITION_TO_DEFENCE, DEFENDING
}
```

Phase is **derived, never set**, and each team is in exactly one:

```
have the ball:  msSinceGain < counterWindow && theirShapeDisorder > k
                    -> TRANSITION_TO_ATTACK
                otherwise by ball band: 0–1 BUILD_UP, 2–3 PROGRESSION, 4–5 FINAL_THIRD
not the ball:   msSinceLoss < counterPressWindow -> TRANSITION_TO_DEFENCE
                otherwise -> DEFENDING
```

with hysteresis — a band boundary must be crossed by 1.5 m, or held for 0.4 s,
before the phase flips. Without it the phase chatters on the third line, every
player's target jumps every tick, and you get men vibrating on a boundary. That
is a cheap bug to prevent and an expensive one to find in a renderer.

A phase instruction is a set of typed schema rows. Types are drawn from a small
closed set — `ENUM`, `METRES`, `COUNT`, `FLOAT01`, `PLAYER_REF`, `LANE`,
`ZONE` — because the AI manager's action space, the trigger action vocabulary,
the UI widget and the save codec are all switch statements over that set.

```kotlin
data class PhaseInstructions(
    val buildUp:    Settings,   // gkDistribution, firstLineCount, dropperSlot, baitPress
    val progression:Settings,   // route, tempo, passLengthMix, freeManSlot, riskCeiling
    val finalThird: Settings,   // crossTiming, boxMen, boxRoles, runMix, patience
    val transDef:   Settings,   // counterPressSeconds, restDefenceCount, cynicalFoul
    val transAtk:   Settings,   // directness, breakMen, holdOrGo
    val defending:  Settings    // lineHeightM, blockWidthM, triggers, marking[], offsideTrap
)
```

### 1.6 How it is read each tick

Not by re-walking the config. Three cadences:

**On change** — kick-off, sub, shout, a trigger firing, a role edit:
`Tactics` (immutable) is compiled to `CompiledTactics` — flat float arrays of
per-slot anchors in metres, leash radii, per-phase deltas, per-slot intent
priors, marking assignments. Allocation-free thereafter.

The compile step is also where §2.7's *tactical changes take time to bed in*
lives, and it is one line rather than a system: the live compiled struct is
**interpolated toward** the new one over N seconds, and the interpolation
parameter is exposed as a disorganisation term that widens the leash and slows
decisions. A settled side converges in ~20 s; a side that has just changed
shape takes minutes and can be watched doing it. Familiarity (§2.8) scales N.

**Per team, 6 Hz** — phase resolution, `ShapeReading`, the pitch-control grid
(kept from v3, it was the layer that worked), marking assignment, rest-defence
set.

**Per player, 6 Hz** — read the compiled arrays, take the phase, produce a
target *with a deadline*: `arrive at (x, y) by t`. Speed is derived,
`distance / secondsLeft`, clamped to his maximum. This is `ENGINE_V4` §3.1,
already built and already measured in the predecessor: `CornerFilm` went from 5
to 8 attackers in the box at the delivery, mean gap to target 7.6 m → 3.6 m. It
replaces the urgency table outright and it is the reason corners can work at
all. Carry it over unchanged.

Per player per tick that is about twenty float reads and no allocation, at 22
players × 6 Hz.

---

## 2. The decision layer — §4

The predecessor played one pass a match because `v2Decide` was a hand-weighted
sum of distances and it maximised it. Re-weighting the sum was tried twice and
moved nothing (`WidthCheck`: 69% of the carrier's play central). So the fix is
not a better sum. There are four changes, and each removes one cause.

### 2.1 An option is a concrete act, not a category

Not "pass". **"Driven pass, to Smith, into the space at (72,14), arriving in
1.1 s."** Options carry a target, a delivery type and a time. That single change
is what makes §4's event vocabulary reachable: a cut-back and a byline cross
differ by two numbers, not by a classifier bolted on afterwards.

Generation, capped at ~24 candidates per decision:

- per reachable team-mate, up to 2 variants — to feet, and into the space his
  current run is making;
- carries in 5 directions, each with a distance;
- a shot, if a lane exists;
- `SHIELD`, `TURN`, `TAKE_ON`, `ONE_TWO`, `BACKHEEL`, `FLICK_ON`, `CROSS`
  variants when their preconditions hold.

Decisions happen on events — receipt, a pressure threshold crossed, the chosen
option expiring — not every tick, with a per-player reaction latency. That is
what gives "time on the ball" a meaning and stops flip-flopping.

### 2.2 Four axes, scored separately

**Reward.** Change in possession value from here to the destination, plus xG
if it is a shot. The possession-value grid must **not** be a hand-weighted sum
of distances — that is the exact thing that was condemned. Derive it from the
engine's own play: run 5,000 matches, count how often the ball at each grid cell
ends in a goal within the possession, feed the result back as the grid, repeat
two or three times until it stops moving. The grid then contains the engine's
real geography instead of my prejudice about it, and `ValueGridDump` renders it
as a heat map you can look at and immediately see when it is wrong.

**Risk.** `pFail × opponentValueAtTurnover(location, ourRestDefenceCount)`.
Cost of failure is not a constant: losing it in your own half with three men
behind the ball is a goal against, losing it in their corner is a throw. This
is what makes rest defence (§2.3) matter through the model rather than through a
special case, and it is why a side told to commit five men forward will
genuinely get countered.

**Feasibility — the gate, and the most important line here.** §2.8 says a
low-vision midfielder does not *see* the switch, so he plays the simple ball
instead. That must be implemented as **the option is never generated**, not as a
penalty on its score. Perception is a filter on the option set: what he can see,
given vision, body orientation, pressure and how long he has had it. He is not
worse at the switch. He is doing something else, which is what the brief asks
for and what makes the same tactic look different with different men.

**Conformance.** Role intent, duty, phase instruction and armed triggers, as a
**multiplicative prior** on utility. This is the only channel by which tactics
enter, and it is the enforceable version of the brief's design principle.

### 2.3 Choosing among them, not maximising

`u = reward − riskWeight·risk`, then **softmax with a decisiveness
temperature**: `P(i) ∝ exp(u_i / T)`.

`T` comes from the player (decisions, composure), the situation (pressure, time
on the ball) and the tempo instruction. A maximiser is `T → 0`, and `T` is never
0. A man under pressure with poor composure is genuinely erratic; a good one
under no pressure is close to optimal. That is one mechanism doing work three
systems used to do badly.

But softmax alone will **not** save a degenerate model, and this is worth being
blunt about: if one option dominates on every possession, a temperature just
adds noise around the same choice. Variety in what is chosen is downstream of
variety in the option set, and the option set is generated from where everyone
else is standing. **So the off-ball layer is the real anti-degeneracy device,
and it must land before the decision layer is tuned at all.** The brief's own
lesson, stated mechanically: build the several different things men are trying
to do, and the choices follow.

### 2.4 Traits operate on the option set

A trait is a transform applied after generation, before scoring:

- *shoots from distance* — injects a shot candidate that perception would not
  have surfaced, and raises its prior;
- *tries killer balls* — lowers the risk weight for through balls only;
- *hugs the line* — deletes the inside-carry options;
- *dives into tackles* — off the ball, raises the tackle option's prior and its
  foul probability.

The chosen option carries a flag saying a trait sourced or promoted it, so
commentary can notice a man disobeying without a second system existing to
detect disobedience.

### 2.5 Execution is separate from choice, and that is where the events come from

Once chosen, nothing is resolved by a success roll. The pass is *struck*, with
weight and direction error from technique, pressure and fatigue; then the ball
is a physical object under `Physics.kt`, and it goes to whoever's claim time is
lowest (`ENGINE_V4` §3.2, and the fix for v3's 92%-loose-ball degeneration).

Almost the whole of the brief's §4 vocabulary falls out of that separation
rather than being authored:

- **misplaced pass** — delivery error large enough that the intended man's
  claim is no longer lowest;
- **interception** — a defender's claim was lowest;
- **block, deflection** — a defender's body volume intersects the ball path
  inside its flight, resolved geometrically;
- **rebound, parry, woodwork** — the ball keeps existing after the save;
- **offside** — a receiver's position at the moment of the strike.

None of those needs a probability table. The predecessor read
`SHOT_BLOCKED = 0.00`, `SHOT_DEFLECTED = 0.00`, `PASS_INTERCEPTED = 0.00`
because it had no block, no deflection and no misplaced-pass model — it resolved
passes as outcomes rather than striking them. Fix the resolution and three
event families appear at once.

### 2.6 The design principle as a lint rule

Tactics fields may be read **only** by: option-generation preconditions,
conformance priors, target positions and deadlines, and temperature. A CI check
greps for tactics identifiers in the outcome-resolution files and fails if one
appears. `if (pressingHigh) chance += 0.1` then cannot be committed, rather than
merely being discouraged in a document.

---

## 3. Harnesses to build alongside step 4

All with a control row, all printing a 95% interval, all at 400 matches unless
noted — goals-per-match is roughly Poisson at 2.7, so 400 carries ±0.16 and a
sweep that moves less has measured nothing.

| harness | what it prints |
|---|---|
| `EventCensus` | rate per match for every event type, both sides, with CI, next to its §5 target band and a PASS / FAIL / — column. The one gate that catches the predecessor's whole defect list on day one. |
| `OptionCensus` | options generated per decision (mean, distribution); **how often the chosen option was the argmax** — if that is 99% the softmax is decoration; entropy of chosen-kind; and % of decisions with ≤2 options, which is the real failure mode. |
| `PassMix` | share and completion by kind — short / long / switch / through / cross / cut-back / one-two — plus a length histogram and forward-sideways-back split. The predecessor's "one pass a match" is a single row here. |
| `ChannelCheck` | touches and final-third entries by lane, per team. Gives the 69%-central number a permanent home. |
| `FailureCensus` | for every failed action, **why** — misplaced, intercepted, blocked, tackled, dispossessed, heavy touch, offside, out. The brief's lesson is that men must fail in several ways; a single dominant failure mode is a defect, and this is the only harness that can see it. |
| `ValueGridDump` | the possession-value grid as a PNG heat map, plus its extreme cells. Wrong grid, wrong decisions, and you can see it in one image. |
| `LeverCheck` | reads-per-match for every schema row. Zeros are dead levers. Trap 7.1, mechanised. |
| `ABSweep <key>=<value> [--film]` | the generic §3 differ: same 400 fixtures, one setting changed, every census row side by side with the delta and whether it clears its noise floor — and with `--film`, the two rendered frames at the same clock second. Every setting's acceptance test is one line. |
| `DecisionTrace` | one match, one player, every decision: the option set, each axis's score, the temperature, the choice. The debugger. Without it a bad census number is unattributable and you tune blind. |
| `Gate` / `ResultFingerprint` | carried over unchanged. 200 fixed-seed matches, FNV-1a over scores, shots, cards, corners. Plus the shape digest in §4.6 below. |
| `PerfCheck` | decisions/sec, ms per match-minute, allocations per tick. The option generator is the hot path and it must be sized now, not after the renderer lands. |

---

## 4. What I think is wrong in the brief

### 4.1 Five phases is four and a half — the counter is missing

The brief has transition to defence but not transition to attack. That is the
phase most modern goals come from, and §2.3 strands its own setting inside
Defending: *"what we do when we win it: hold it / go long / go direct"* is an
instruction about the six seconds **after** the ball is won, which is a moment
Defending no longer governs. Without the sixth phase there is nowhere to put
directness, break-men count, or "the counter is on" — and a manager game whose
tactics cannot express counter-attacking is missing a whole style. Cheap now,
structural later. I have included it in §1.5 above.

### 4.2 The acceptance table's shot decomposition does not close

The table is good and most rows check out against public per-team averages —
crosses ~31 combined, corners ~10, tackles ~33, fouls ~21, offsides ~4,
take-ons ~38, completion ~81%, all inside their bands. Two rows do not:

- **Shots 22–27, on target 8–9, blocked 8–10** leaves 6–9 off target. Real
  splits run closer to on target ~8.6, blocked ~7, **off target ~9.6** — off
  target is normally the *largest* category, and the table has it as the
  smallest. Some off-target volume has been booked as blocked.
- **Headers at goal 8–12** looks high by roughly a factor of two. Headers are
  about 15% of shots, so ~4 combined, not 10. An engine tuned to hit 10 will be
  the wrong game — men will be told to head balls they would really control.

Recommendation, since the brief already says to verify before locking: state
shots as a **decomposition that sums to 1** — on target / blocked / off target /
woodwork — and have the harness check the sum as well as the parts. That makes
this class of error impossible to reintroduce.

Also: *"defenders goal-side of a ball in their own box, 8–9 of 10"* has no
public dataset behind it — it is the engine's own definition. It needs writing
down precisely (which ball, at which instant, which ten men) before it is a
target, or it will be met by redefining it.

### 4.3 §2.4's rules engine will become a scripting language if it is allowed to

The examples are right and the feature is the best thing in the brief, but "a
small rules engine the manager edits" plus arbitrary conditions is a parser, a
validator, a save format and a UI, and it can eat a month. Constrain it: a fixed
vocabulary of ~12 conditions and ~15 actions, both typed from the same schema as
everything else, `AND` of at most two clauses, no free text, no nesting. That is
most of the value at a fifth of the cost, and — the point of the feature — it
stays inspectable.

### 4.4 "The opposition manager is a real agent" has an unbudgeted cost

Right in principle, and the frame-of-reference decision in §1.1 makes it nearly
free for the watched match. The unwritten problem is the rest of the league: a
season is ~380 fixtures, and if off-screen matches run a cheaper path, results
diverge from watched matches and the table stops meaning anything. That is trap
7.2 arriving through a door the brief leaves open. Decide it explicitly and
early: one engine, one tick rate, measured against a season's wall-clock budget.
If it does not fit, the honest fix is fewer simulated divisions — not a second
engine.

### 4.5 Fourteen roles is too many to ship at step 5

Each needs a harness, two frames and a *measured* separation from its
neighbours, and separation gets harder as the set crowds — cover and holding
midfielder will be hard to tell apart on a heat map, and if they cannot be told
apart they are decoration by the brief's own test. Ship six that are maximally
distinct — ball-playing defender, inverted full-back, holding midfielder,
advanced playmaker, touchline winger, poacher — prove clean separation on the
differ, then add the rest against the same bar. Same destination, risk
front-loaded.

### 4.6 The fingerprint has a blind spot, and it is exactly this project's subject

`Gate` hashes scores, shots, cards and corners. Off-ball position — the entire
subject of this rebuild — is not in it, so a change that ruins the shape while
leaving the scoreline intact passes clean. Add a second hash over a positional
digest: mean lane occupancy per band, per team, per five-minute bucket,
quantised. Cheap, and it makes a "cosmetic" claim honest about shape rather than
only about results.

### 4.7 "Presentation last" is right and will still blind you at step 3

§6 puts presentation at step 10, while §1.4 and trap 7.6 say render frames and
look at them, and the predecessor's two largest bugs were invisible to every
aggregate and obvious in one PNG. Both are correct, about different things.
Resolve it in writing: a deliberately ugly **debug** renderer — top-down, 22
dots, the ball, lane lines, target markers and an arrow per man to where he is
trying to be — at **step 2**. It costs a day and it is the tool that finds
everything. What is deferred to step 10 is the broadcast renderer, captions,
faces and commentary.

### 4.8 One thing to sharpen rather than change

§1.5's arithmetic is right — Poisson at 2.7 over 400 matches gives ±0.16 at 95%.
Real goals-per-match is mildly over-dispersed against Poisson, so use ±0.17 and
round against yourself. It changes nothing structural; it stops a marginal sweep
being read as a win.

### 4.9 One gap in §2.8

Familiarity is the right model, but a new career starts at match 1 and the
player has not drilled anything. Without a defined starting familiarity — by
squad continuity, by how far the shape sits from what the squad played last
season — every new save opens with a side that stands in the wrong places, and
the player will read that as the game being broken rather than as his squad
being new. Needs one line of design before it is built, not after.

---

## 5. What I would build first

Unchanged from §6 of the brief, with 4.7 folded in: harness, shim and gate
(most of which already exists and works and should be lifted intact); pitch,
ball flight and men moving to targets with deadlines; the debug renderer; the
event vocabulary with `EventCensus`; then off-ball movement; and only then the
decision layer, because §2.3 says its variety is downstream of the option set
and the option set is downstream of where everybody else is standing.
