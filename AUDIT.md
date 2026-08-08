# Career Dugout — external audit

Reviewed at the state shipped in `careerdugout181.zip`. Everything below was
measured on this machine, not read off `PROJECT_STATE.md`. The whole app —
engine, renderer and `MainActivity` — was compiled against the Java2D shim and
run headless, so every number here is reproducible with the harnesses named
against it.

**Read `PROJECT_STATE.md` §0 first.** This audit accepts its framing without
argument: it is a management game, the player only watches, and the only test
that matters is whether the picture is right. Nothing below asks for an emergent
simulation, and nothing below is a realism target the player cannot perceive.

---

## 0. State of the build, as found

| | measured here | `PROJECT_STATE.md` |
|---|---|---|
| `BalanceBig`, 9,120 matches | 2.52 ± 0.03 goals | 2.52 ± 0.03 ✔ |
| home / draw / away | 42.3 / 26.1 / 31.6% | same ✔ |
| cards per game | 1.88 | 1.88 ✔ |
| `ResultFingerprint` | `2bb89d51608933db` | `a2775c4ee5913035` ✘ |

The balance figures reproduce exactly. **The fingerprint does not**, so this zip
is ahead of the commit the handoff document was verified at (`1d98d07`) and the
"current verified state" section is describing a build that no longer exists.
That is worth fixing in the document itself: a fingerprint that does not match
is indistinguishable from a fingerprint that was broken, which is the one thing
it exists to tell you.

Compiling the full app off-device takes ~31 s here and `BalanceBig` ~9 min. The
harness is genuinely good and it is the reason this audit could say anything
specific at all. The rest of this document is written in its idiom.

---

## 1. P1 — the corner that does not stage. Solved, and it is not what the document thinks

`PROJECT_STATE.md` §6 lists two untested candidates: that `stageCorner` receives
`possessionSide` after it has flipped, or that the queued `sceneAfter` path
behaves differently from the direct `OUT_CORNER` path.

**Both are wrong.** `StageGate` reads the private `stagedUntil` by reflection on
every corner in 20 matches:

```
corner captions shown               151
  gate OPEN when caption appears    151   100.0%
  mean stagedUntil then            9.32 s
  attackers in box then            0.83
  EMPTY box when caption appears    102   67.5%
  gate shuts before the caption      1/150  0.7%
```

The staging gate is open, on time, on every single corner, with 9.32 of its 9.6
seconds still to run. Nothing is expiring early and nothing is overwriting the
targets. The arrangement works exactly as designed.

### What is actually wrong: three time bases that do not agree

The engine runs on three different clocks and they were never reconciled.

| clock | what runs on it | scales with the 1×/2×/4× button? |
|---|---|---|
| match minutes | `clock`, phase duration, **the pending set-piece shot** | yes |
| `dtSeconds * speed` | player movement, ball flight | yes |
| raw `dtSeconds` | `sceneTime`, **`stagedUntil`**, `refTime`, banner, woodwork, camera danger, replay | **no** |

The corner's arrangement (`stagedUntil = CORNER_SECONDS + 1.6` = 9.6) was in raw
real seconds. The corner's *delivery* was booked by `shootAfterScene(..., 0.60)`
in **match minutes** — 2.4 real seconds at 1×. So the ball was struck at 2.4 s
into a 9.6 s walk-in, and the number that was supposed to control the whole
moment controlled nothing.

`CornerStrike` watches the ball leave the flag, which is exact:

| | delivery lag | attackers in box **at the ball** | at the caption ending |
|---|---|---|---|
| 1× | 2.38 s | **2.23** | 3.40 (at 4.52 s) |
| 2× | 1.19 s | 2.73 | 3.55 |
| 4× | **0.59 s** | 3.79 | 3.74 |

At 4× — which is how anyone actually watches a 408-second match — **the corner
is struck six tenths of a second after it is awarded.**

### Why every previous measurement missed it

`StagingCheck` samples on the tick the CORNER *caption* expires, and says so in
its own loop:

```kotlin
// the tick the corner scene ends is the tick it gets delivered
```

It is not. It is five to six seconds after. The corner-hold sweep in
`PROJECT_STATE.md` §4 — `1.3 s → 1.04`, `8.0 s → 3.43`, `10.0 s → 3.80` — was
measured at an instant long after the ball had gone, and the comment above
`CORNER_SECONDS` in the source describes that table as "measured at the moment
the set piece is TAKEN". This is the project's own §8 note about a confidently
wrong comment, happening again, on the exact bug it was written about.

The rendered `out/scenes/corner.png` is real evidence, correctly captured — but
it is the **first frame** of the sequence. `SceneFilm` photographs the corner
when `scene == Scene.CORNER` first becomes true, and the caption goes up on the
same tick the staging starts, when by construction nobody has moved yet. The box
is empty in that frame because the frame is at t = 0.

### And a third fault at the other end

`stagedUntil` is a fixed hold set when the corner is *awarded*, and
`updateTargets` returns early for the whole of it. The delivery happens partway
through. Measured: **after the ball leaves the flag the gate stays open a
further 7.20 seconds** — all twenty-two men frozen in their corner positions
while the goal kick is taken around them. Nobody had looked at the end of the
moment, only the beginning.

---

## 2. What was changed

Each result-affecting change is behind its own `@JvmField var` so it has a
control row and can be reverted alone, which is the harness README's rule 3.

### 2.1 One time base *(`MatchEngine.update`)*

Every presentation timer now advances on `vis = dtSeconds * speed`, the same
step the players already used. At 1× this is arithmetically identical, so it
cannot move a result; above 1× the captions, the arrangement, the camera and the
replay speed up together with the football, which is what the button claims to
do. **This is the fix that matters most** — it is not a corner fix, it is the
reason 2× and 4× were a different game.

### 2.2 The delivery is derived from the hold *(`SET_PIECE_TIMING`)*

```kotlin
private fun takeDelay(holdSeconds: Float, legacy: Double): Double =
    if (SET_PIECE_TIMING) holdSeconds * SET_PIECE_TAKE / SECONDS_PER_MINUTE else legacy
```

A set piece is struck at 0.85 of its arrangement instead of at a magic 0.60 that
had no relation to it. Corners and all three free-kick routines use it, so the
two numbers cannot drift apart again.

### 2.3 The arrangement ends when the ball is struck *(`RELEASE_STAGING`)*

`stagedUntil = 0f` at the top of `attemptShot`. The arrangement exists to get men
into position for the delivery; the delivery has happened. Kills the 7.2 s
frozen pitch.

### 2.4 The keeper stays on his line *(`KEEPER_ON_LINE`)*

`stageCorner`'s attacking loop skips the goalkeeper. **The defending loop did
not**, so on every corner the defending keeper was sent to a random point up to
nine units off his line and anywhere across forty units of the six-yard box, and
held there. Measured: **8.7 m off his own goal**, rising to 11.6 m at 4×. He is
the one man on the pitch whose position at a corner is not a matter of opinion.

Both `rng.f()` draws are still taken for him. Skipping them would shift every
later roll in the match, which is the trap the slide-tackle rolls document.

### 2.5 `CORNER_SECONDS` left alone at 8.0

Shortening it to 5.0 looked reasonable — the hold is dead time the viewer now
genuinely waits through — and measured worse on both counts: 1.87 attackers in
the box at the delivery against 3.09 at 8.0, and goals per game fell well below
the band. The number was right all along. It was the delivery that was not
listening to it.

### 2.6 Two lines of dead code

`ball.holder = null` written twice in a row, and `if (kind == "header")
corners.let { }`, a statement that reads an array and discards it. Neither draws
a random number, so neither can move a result.

---

## 3. What the changes measure

### 3.1 The picture at every speed — `CornerStrike`, 20 matches per speed

The point of the time-base fix is that the three speeds should now show the same
football. They do.

| | attackers in box **at the delivery** | | defenders | | keeper off his line | |
|---|---|---|---|---|---|---|
| | before | after | before | after | before | after |
| 1× | 2.23 | **3.34** | 3.15 | 4.24 | 8.7 m | **6.9 m** |
| 2× | 2.73 | **3.20** | 3.80 | 4.40 | 9.1 m | **7.3 m** |
| 4× | 3.79 | **3.45** | 4.12 | 4.72 | 10.7 m | **7.2 m** |

Before, the box occupancy swung by 70% across the speed control — 2.23 at 1× to
3.79 at 4× — because the men were speed-scaled and the arrangement was not. After,
it is flat at 3.2–3.5 whatever speed you watch at, which is the whole claim.

The delivery lag in *real* seconds still shortens with speed (4.93 / 2.52 / 1.32).
That is correct and is what the button is for: at 4× the whole moment plays four
times faster, arrangement included. What must not change with speed — and no
longer does — is what the corner is delivered *into*.

Against real football's five or six attackers and eight defenders, 3.3 and 4.2
is still short. Per §0's rule, that is a judgement about the picture and not a
target to chase: it now reads as a corner, which it did not before.

### 3.2 Each change against a control — `SetPieceSweep`, 120 matches, speed 1×

```
                           deliver   att box   def box     frozen   GK off    goals     match
                             lag s   AT BALL   AT BALL    after s   line m   per gm    real s
control (all off, hold 8)     2.38      2.33      3.46       7.19      8.7     2.68       406
+ timing only                 5.27      3.41      4.49       4.31      8.9     2.67       427
+ release only                2.37      1.82      2.99       3.37      9.2     2.78       408
+ keeper only                 2.37      2.08      3.17       7.20      8.2     2.68       406
ALL ON, hold 5.0              3.64      2.04      3.18       1.49      7.6     2.42       421
ALL ON, hold 6.5              4.42      2.64      3.88       2.11      7.1     2.61       425
ALL ON, hold 8.0              5.16      3.43      4.61       2.79      6.9     2.68       431
```

SE on goals at 120 matches is 0.147, so nothing under 0.29 is a trend.

Reading the rows:

* **Timing is the change that fills the box.** On its own it takes the delivery
  from 2.38 s to 5.27 s and the attackers in it from 2.33 to 3.41, at a cost of
  0.01 goals a game — nothing.
* **Release on its own makes the picture slightly worse** (1.82 in the box) and
  that is expected: it lets men leave the arrangement at a delivery that is
  still far too early. It is the right change and it needs the timing change
  under it, which is what the combined rows show.
* **The keeper change is free**, as it should be: identical goals, identical
  delivery, 8.7 m to 8.2 m off his line.
* **Shortening the hold is a mistake** — 5.0 gives 2.04 in the box and 2.42
  goals. The 8.0 that was already there is the best row on every column.
* The match runs 406 s to 431 s at 1×, a 6% increase, all of it set-piece dead
  time that is now actually used for something. There is a 4× button.


### 3.3 Balance — `BalanceBig`, 9,120 matches, the authoritative check

```
                        before          after       target
goals per game       2.52 +/- 0.03   2.48 +/- 0.03   2.5 - 2.7
home wins               42.3%           42.9%        ~45%
draws                   26.1%           25.2%        ~25%
away wins               31.6%           31.8%        ~30%
cards per game           1.88            1.70
```

**Goals: unchanged.** 2.52 to 2.48 is a difference of 0.04 against a combined
standard error of 0.024, so 1.7 SE — not significant, and chasing it with
`CHANCE_QUALITY` would be tuning the engine against the sampling error of its
own test, which is the mistake the harness README already documents once.
`CHANCE_QUALITY` is deliberately left at 0.1038.

**Cards: a real change, and understood.** 1.88 to 1.70 is 13 SE, so this one is
not noise. The mechanism is that set pieces now hold the ball for longer, and
the compensation is not quite exact — see 4.4.


---

## 4. Findings outside the match engine

These are not visible on the pitch, which is why they have not come up. They are
the kind of thing that ends a career save rather than spoiling a frame.

### 4.1 The autosave blocks the UI thread for a tenth of a second — on a desktop

`MainActivity.autosave()` calls `Save.write` directly, including from
`onPause()`. Measured by `SaveCost`:

```
world build            362 ms   (1236 clubs, 32136 players)
Save.write             254 / 130 / 116 / 121 / 110 ms   (5 runs)
save file              7.84 MB
Save.read              804 ms
```

That is a desktop JVM. A mid-range phone is three to ten times slower, which
puts the autosave at half a second to two and a half seconds **on the main
thread**, at the exact moment Android is trying to background the activity and
is least patient about it. `onPause` has a much tighter budget than the 5 s ANR
watchdog.

Not changed here, because moving it off-thread has to be done carefully — a
write that outlives the process is worse than a slow one — but it is the single
largest non-visual risk in the project. The shape that works: keep `onPause`
synchronous because it must complete, and make the routine week-advance
autosaves asynchronous with a "save in progress" guard.

### 4.2 A corrupt save silently became a new career

`Save.write` deleted the target and then renamed the temp over it. Between those
two calls there is no `career.sav` at all, and `Save.exists()` is what the front
screen uses to choose between "Continue" and "New career" — so a process death
in that window shows a returning player a fresh game. Worse, it returned `true`
whatever `renameTo` said, so a failed write (out of space, which is exactly when
a 7.8 MB write fails) was reported as a successful save.

**Fixed.** The previous save is retired to `career.sav.bak` rather than deleted,
the return value is now the truth, a failed rename puts the old file back, and
`read()` falls back to the backup. Losing one week of a career beats losing all
of it. `delete()` clears the backup too, so "start again" still means it.

### 4.3 A backgrounded match kept simulating

```kotlin
// a match left playing to itself in the background is both a battery
// drain and, with sound, extremely rude
matchAudio?.stop()
```

The comment describes stopping the match. The code stopped the mixer. `startLoop`
re-posts itself every 28 ms and nothing cancelled it, so a backgrounded match
kept running the engine and calling `invalidate()` on an activity the user had
left — the battery drain the comment is about, minus the noise. **Fixed:**
`onPause` calls `stopLoop()`, `onResume` restarts it for a match that was
actually running.

### 4.4 Added time under-compensates for stoppage

`shootAfterScene` adds its delay to `stoppage`, and `stoppage` is added on as
added time, so holding the ball for a set piece is meant to cost nothing:

```kotlin
if (half == 2 && clock > 89.5 && addedTime == 0.0) addedTime = 1.0 + rng.f() * 4.0 + stoppage
```

But `addedTime` is computed **once**, at 89.5 minutes, from the stoppage
accumulated up to that point. Every set piece taken during added time itself
adds to `stoppage` and is never given back. The longer set pieces are held, the
more playing time leaks away — which is why cards fell 1.88 to 1.70 when the
deliveries were slowed, and why goals drifted down by a statistically
insignificant but directionally consistent 0.04.

Pre-existing, small, and not fixed here because it is a balance change and
deserves its own measured commit. The fix is to keep topping `addedTime` up
while it is being played, rather than freezing it at the moment it is first set.

### 4.6 The save-version comment is false

```kotlin
// Version 1 files still load and default to home.
```

They do not, and never did. `read()` rejects any header whose version is not
exactly 2, so a v1 save is discarded in silence. Adding `managerNation` shifted
every index in the header row and there is no v1 reader to fall back on. The
comment is corrected rather than the behaviour, because there is no correct
behaviour left to restore — but a wrong comment about save compatibility is
precisely how a career gets eaten.

---

## 5. Presentation, from the rendered frames

Judged from `out/film/*.png` and `out/scenes/*.png` as shipped, plus the frames
rendered here.

**P3 (ground beyond the goal line renders dark) is understated in the document.**
In `out/film/05.png` the near-black wedge is roughly the right-hand sixth of the
frame, and there is a second one at the left edge. At 40° with the crowd band
lifted out of shot, a frame framed near a goal line reads as a pitch floating in
a void. The reference never shows a boundary without *something* beyond it. This
is the most damaging remaining art gap and it is cheap: the surround already
exists (`ox = 12f` of grass), it just needs to keep going to the horizon.

**The HUD gap in §7 is real and is worse in motion.** `out/scenes/corner.png`
shows the league ticker running *behind* the radar, with `DUGOUT` and `TRIVOLT`
half-occluded by it — two elements the reference does not have at all, fighting
each other for the same corner. The reference has a thin top bar, one nameplate
and no ticker. Dropping the ticker and one nameplate is subtraction, which is
the cheapest kind of art direction there is.

**P2 (the ball is invisible at 320×240) is confirmed and should be P1 now that
the corner is diagnosed.** The chevron over the carrier does exist in
`out/film/05.png`. The *ball* is a two-pixel dot. In a game whose entire input is
"watch", losing the ball is the only unrecoverable failure.

---

## 6. Two notes on method

**The harness is excellent and it hid this bug for months.** Not through any
weakness in the measurements — through the *sampling instant*. `StagingCheck`
counted the right thing at the wrong tick, and because the number it produced
was plausible and moved in the expected direction when the hold was swept, it
read as a working measurement. The project's rule 1 says render frames and look
at them; the corollary this bug argues for is **state when a frame was taken
relative to the event, not just what produced it.** The film harnesses already
stamp the commit and the constants into every frame. Stamping the time since the
moment began would have caught this in one glance.

**`ResultFingerprint` should be part of the definition of done, not a habit.**
It caught nothing here because it was never run against a mismatched value — the
recorded value in the handoff document is stale, so a real regression and an
out-of-date document look identical. Re-record it whenever it legitimately
moves, in the same commit.

---

## 7. Suggested order of work from here

1. **P3, the dark ground beyond the goal line.** Largest visible defect, and it
   is a fill, not a system.
2. **P2, the ball marker.** Small, high value, already scoped in the document.
3. **The autosave threading (4.1).** The only thing here that can lose a
   player's career rather than spoil a frame.
4. **HUD subtraction (§5).** Drop the ticker and the second nameplate.
5. **Re-run `SetPieceSweep` at 2× and 4×.** The time-base fix should make the
   three speeds agree; that is now a testable claim and nobody has tested it.

Explicitly not recommended: anything in `PROJECT_STATE.md` §5 or the "explicitly
dropped" list. Those decisions are correct and well evidenced, and the reasoning
behind them is better than most shipped games manage.

---

## 8. Second pass: the scenes, audited one at a time

Prompted by "the scenes are completely broken", which was right. The corner fix
in §1 was one bug in one moment; it did not touch the other six. `SceneAudit`
samples every scene occurrence **at the instant play restarts** — the same
lesson as §1, applied deliberately this time — and asks the one question that
decides whether each moment reads.

### 8.1 What was wrong

| scene | reads correctly | the fault |
|---|---|---|
| THROW-IN | **12%** | staging wiped one tick later; scene shorter than the ball's own journey |
| GOAL KICK | **44%** | staging wiped one tick later; keeper 11.0 m from his own goal kick |
| FREE KICK | — | **no caption at all**, on a false comment |
| PENALTY | 100% **stacked** | PENALTY caption, GOAL banner and celebration in one frame |

**Throw-ins and goal kicks were never given a staging hold.** `stageCorner` and
`stageFreeKick` both set `stagedUntil`, which is the whole mechanism that stops
`updateTargets` overwriting an arrangement on the next tick. `stageThrowIn`
never did, and the goal kick had no stage function at all — four lines inline.
So both set their targets and had them wiped one frame later. This is precisely
the fault `PROJECT_STATE.md` §4 records as *fixed* for the free-kick wall; it
was fixed there and never applied to the other two restarts.

**The throw-in scene was shorter than the ball's journey to the touchline.**
`ThrowTrace`, tick by tick, is unambiguous: the ball is rolled from wherever the
move ended to the touchline — 36 units in one traced case — taking about 1.3 s,
arriving on the exact tick the 1.4 s caption expires, with the nearest man still
8 m away and play restarting on top of him. The viewer was shown the word
THROW-IN over a ball rolling across open grass, and never the throw-in.

**The free kick had no caption because of a wrong comment.** `MatchView` excludes
CORNER, PENALTY and FREEKICK from the generic caption, on a comment asserting
that all three "already draw their OWN titles in drawCornerScene,
drawPenaltyScene and drawSpray". `drawSpray` draws no text — it draws the
referee's vanishing spray and nothing else. So 3.9 free kicks a match played out
completely unnamed, with a scatter of white dots on the grass as the only thing
marking the moment. Same fault as `Scene.LABEL` going unreferenced for months,
found the same way: by looking.

**A goal never cleared the scene.** `scoreGoal` sets the banner and the
celebration but leaves `scene` alone, so a penalty scored inside its own
two-second caption drew PENALTY, GOAL and the celebration starburst in one
frame. Measured on 100% of penalties.

### 8.2 After — `SceneAudit`, 20 matches

Sampled at **0.18 of the scene remaining**: the last moment before the ball is
played. That instant is chosen, not assumed — see 8.5, where getting it wrong
cost me a false finding in the first draft of this section.

```
scene           seen   spread   nearest   ball to        READS    STACKED     extra
                            m    mate m     man m    CORRECTLY on another
CORNER           134     12.2       6.7       3.0          66%         0%      3.93
FREE KICK         92     18.4      10.2       1.6          89%         0%      3.17
THROW-IN         141     18.4      10.8       2.4         100%         0%      1.04
GOAL KICK        416     17.4       9.8       2.0          82%         0%      3.34
PENALTY            1     10.1       4.7       2.5         100%         0%      1.00
BOOKING           27     19.9      10.4       0.9          89%         0%      0.00
KICK OFF          20     23.4      13.4       4.6          45%         0%      0.00
SUB               76     16.5       9.3       5.6          62%         3%      0.00
```

Throw-in 12% → **100%**. Goal kick 44% → **82%**. Free kick, once it had a
caption, **89%** with a 3.2-man wall and the taker 1.6 m from the ball. Penalty
stacking 100% → **0%**.

For the corner the authoritative number is not in this table — `CornerStrike`
detects the delivery exactly, by watching the ball leave the flag:

| | attackers in box at the delivery | | defenders | keeper off his line |
|---|---|---|---|---|
| | before | after | after | after |
| 1× | 2.23 | **3.18** | 5.08 | 7.3 m |
| 2× | 2.73 | **3.15** | 4.63 | 7.1 m |
| 4× | 3.79 | **2.87** | 4.63 | 6.7 m |

Flat across the speed control, which is the point of §2.1.

### 8.2b Nobody was taking the corner

The table above shows the ball **17.7 m from the nearest player** at the moment
before it is played, and that was a real bug, not a sampling artefact:
`stageCorner` sent *every* outfielder to the penalty area and nominated nobody
to stand over the ball. The corner was being swung in by an invisible man.

The nearest attacker is now placed at the flag, the way `stageFreeKick` has
always placed its taker, and excluded from the box scatter — with his two `rng`
draws still taken so the stream is untouched. Ball to nearest man **17.7 m →
3.0 m**. It costs about 0.2 of a body in the box, because the taker used to be
counted among them while walking through.

### 8.3 Balance: the added-time leak, found by breaking it

Holding the restarts long enough to be seen pushed goals per game to **2.41**,
outside the target band — 4.6 standard errors below the 2.52 baseline, so not
noise. The cause is §4.4, which the first pass reported and left alone:
`addedTime` was computed **once** from the stoppage accrued so far, and every
set piece taken during added time was never given back. The longer set pieces
hold the ball, the more playing time leaks away.

Fixed properly rather than papered over with `CHANCE_QUALITY`: the referee's
allowance is still rolled once, and stoppage is now added on continuously, which
is what a fourth official actually does.

```
                     baseline      2.41 build     added-time fixed    final
goals per game    2.52 +/- 0.03   2.41 +/- 0.03    2.49 +/- 0.03   2.51 +/- 0.03
cards per game         1.88            1.68             1.73            1.72
home / draw / away  42.3/26.1/31.6  42.6/26.2/31.2  42.6/25.8/31.6  43.0/25.6/31.4
```

**2.51 against a 2.52 baseline**, inside the target band and inside the combined
standard error. The whole scene programme is balance-neutral. `UiFlow` 33/33,
0 crashes.

### 8.4 A correction to my own first pass

I described the players as clumping into a swarm, from the throw-in frame. That
was one unlucky frame. Measured spread is 18–19 m from a side's own centroid and
does not move across the change set. There is no clumping problem; the real
defects were the ones the numbers found, not the one I thought I saw. Included
here because it is exactly the failure mode §6 is about, and it caught me too.

### 8.5 A second correction to my own work

The first draft of this section reported that "the free-kick scene count drops
from 91 to 10 between the midpoint and the restart sample, meaning most
free-kick captions do not survive their own scene. Something is overwriting
them. Unexplained." That was wrong, and it was my harness, not the engine.

A set piece is struck at `SET_PIECE_TAKE` = 0.85 of its hold, which leaves 15%
of the scene, and the next moment — a goal kick, a save — replaces the caption
at that instant, correctly. My sampler fired at 12% remaining, i.e. *after* the
free kick had already been taken and replaced, so it caught only the residue
where no new scene followed. Sweeping the sampling point settles it:

```
sample at   FREE KICK seen   reads correctly
   0.12                 9              67%
   0.18                59              95%
   0.30                59              97%
```

The free kick was fine once it had a caption. I published a defect that was an
artefact of where I put the shutter — which is the same mistake as
`StagingCheck`, made by me, two sections after I wrote it up. It is a genuinely
easy one to make and the only defence is the one this project already knows:
state the instant, and sweep it before believing the number.

### 8.6 Still open

- **KICK OFF reads 45%** — the ball is a mean 4.6 m from the nearest man when
  the scene ends. Not investigated.
- **SUBSTITUTION reads 62%**, ball 5.6 m away. Not investigated.
- **The corner is 66% on the `SceneAudit` criterion** (3+ attackers in the box
  at the sampling instant) because that instant is just *before* the delivery,
  while men are still arriving. At the delivery itself it is 3.2 attackers and
  5.1 defenders. Whether that is enough is a judgement about the picture, and
  the rendered frame now reads as a corner; §0's rule says stop there.
- **`ResultFingerprint` needs re-recording.** Every result-affecting change here
  legitimately moves it, and the value in `PROJECT_STATE.md` was already stale
  before I started.

---

## 9. The presentation pass: sound, the goal, the restart, the squad screen

Seven things, in the owner's words, and what happened to each.

### 9.1 "7iyed ga3 sawt mn lgame" — every sound is gone

`ui/MatchAudio.kt` deleted, the mixer and the AudioTrack with it, and
`core/Synth.kt` reduced from 388 lines of run-time synthesis — noise sources,
biquads, a crowd bed that never repeated — to the eleven constants that NAME
the moments. The Sound/Muted button is gone from the controls row.

`MatchEngine.sound` stays. A cue is a record of what happened and where, not a
noise; the engine, the replay and about thirty harnesses read or drain that
list, and deleting it would have meant changing the simulation to satisfy a
change to the presentation. If sound is ever wanted back, only the mixer has to
return.

### 9.2 The ball now goes IN THE NET

A scoring shot was aimed at x = 99 or 1 — the goal LINE — so it stopped dead on
it, and the replay, which is captured on the frame the goal is given, ended with
the ball sitting on the paint. Worse, the aim was `50 ± 8` units while the goal
is 5.4 units either side of centre, so a third of all goals were drawn entering
the net outside the upright.

A shot that is going in is now re-aimed to 1.9 units past the line (2.0 m, and
the renderer's net runs 2.3 m deep, so it finishes inside the netting rather
than behind it) and clamped between the posts. Misses are untouched, and the
conversion roll has already happened when this runs, so it moves the picture and
not one result.

`GoalNet`, 60 matches, 203 goals:

| | before | after |
|---|---|---|
| ball past the line when the goal is given | 0% | **100%** |
| ball between the posts | ~65% | **100%** |

### 9.3 There is a kick-off after a goal

`Scene.KICK_OFF` fired once a match, at the opening whistle. Every goal after
that just flipped possession after 1.2 match minutes, wherever the celebration
had left everybody standing.

Now: the conceding side restarts from the centre spot. The ball is placed, every
man is sent into his own half, two takers stand over it, and play is held until
the arrangement has had its time. The whole dead period beyond the old 1.2
minutes is returned as stoppage, the same bargain the throw-in makes.

And the camera CUTS to it. Staged on the centre spot with the camera still down
at the net, the pan -- rate limited on purpose -- could not get there: `GoalFilm`
measured the ball at x = -46 and then -53 in a 400-wide buffer, off the left edge,
so the whole restart happened where nobody could see it. Broadcast cuts to the
centre circle at a kick-off rather than swinging the length of the pitch, and so
does this, on the one frame the scene begins. The ball now lands at x = 202 of
400 -- dead centre -- and the rendered frame reads as a kick-off: the circle, the
caption, the ball on the spot with two men over it.

`GoalNet`: kick-offs went from 1.0 a match to **4.33**, against 1 + 3.28 goals
= 4.38 expected, and 200 of 203 goals were followed by one (the three are goals
on the stroke of half or full time). One man in twenty two is in the opponents'
half at the restart, which is the taker, which is the law.

`BalanceBig`, 9,120 matches: **2.59 ± 0.03 goals, 43.8% home** — inside the
band, and exactly the figure v2 read before any of this.

One trap found while writing it. A goal parks `pendingSide` at `clock + 999` so
that nothing resumes until the kick-off has been staged, and every engine tick is
gated on `pendingSide < 0`. A goal in the last two minutes of added time reaches
half time before the restart is taken, and the interval used to carry that park
into the second half: the flip would come due at minute 1044 and the second half
would contain no football at all. Cleared at the interval now.

### 9.4 Players head the ball again

`AliveCensus` read **0.0 headers a match** under v2, against a real forty. The
pose was correct and the code that set it was correct; it lived on the v1 path,
where a lofted pass named its receiver before it was struck. v2 has no named
receiver — the ball is a loose object and whoever reaches it takes it — so that
line had not run since v2 was switched on.

A ball collected above 1.25 m is now headed, read off the height the ball is
actually at. **0.0 → 17.1 a match.** `ResultFingerprint` byte identical before
and after: `1a4dd0489a53eb20`.

### 9.5 The squad screen shows the squad

The dressing room and the filter row were pinned above a `ScrollView` holding
the list, so on a landscape phone the list got whatever was left — about four
rows of a twenty-five man squad, in a window that never grew however far you
scrolled. The whole screen scrolls now, so the header travels up and off and the
list ends up with the entire display. Tactics was already one scroll and needed
nothing.

### 9.6 The "how do you want to start" screen

Two one-sentence panels stacked down a landscape phone, with the bottom three
fifths of the screen empty, on the one screen where the player decides how his
career begins. Rebuilt as two cards side by side, each filling the height, each
saying enough about the route to choose it: what starting out of work actually
means, and what taking a club now actually means.

### 9.7 What was tried and reverted

Rule 7, four times over.

- **The carrier's infield drift.** Every carrier is pulled 12% toward y = 50 on
  every decision and nothing pushes the other way, which looked like the reason
  play lives in the middle. Restricting it to the final third moved the ball's
  share of the middle fifth from 60.3% to 61.4% and throw-ins from 0.50 to 1.00.
- **The pass scorer's distance-to-goal term**, taken to the goal line rather
  than the goal centre so width was not penalised twice: 62.9% and 0.58. Worse.
- **A man pinned on the touchline playing it out**, which is how most real
  throw-ins happen: 1.75 deliveries a match aimed off the field against 1.67,
  throw-ins 0.58. The condition almost never holds.
- **The ball's run-on expressed per second instead of per frame.** This one is a
  real defect and the finding is worth more than the change. `driftX` is applied
  once a tick with bare constants, so how far a ball runs on depends on the frame
  rate the phone manages and on the speed button — at 4× it is a quarter of what
  it should be. Correcting it took `BalanceBig` from 2.56 to **2.82 goals**,
  outside the band. The reason is that `BalanceBig` runs at `speed = 6` and
  dt = 1/30, where one tick is worth seven reference frames: **the harness and
  the game have disagreed about the ball since v2 was tuned.** Fixing it properly
  means earning balance a fourth time with a full `CHANCE_QUALITY` sweep, which
  is a job of its own.
- **The camera's framing of a goal.** `GoalFilm` prints where the ball projects
  on screen: at the instant a goal is given it is at x = 400 in a 400-wide
  buffer, hard against the edge, and the pan is still catching up forty frames
  later. Leading the camera at the ball's destination, winding the pan easing
  from 0.16 to 0.55, and opening the behind-the-line clamp so it may centre on
  the net moved that from 400 to 391 between them. The clamp was never the
  constraint: the pan travels about five buffer pixels a frame at its ceiling
  while a shot travels twenty. The fix is `PAN_MAX` and the 0.085 gain, which
  govern every frame of every match, so it belongs to a pass with rendered
  frames of ordinary play in front of it.

### 9.8 The width finding, which is the real one

`WidthCheck` measures where play happens across the pitch, counting live play
only — a third of a match is dead ball and almost all of that has the ball on or
near the centre line, so counting every frame measures the stoppages.

```
                          ball     men   man on the ball
  y  0-20 (left touch)     7.9%   10.2%       4.5%
  y 20-40                  8.2%   14.8%       8.6%
  y 40-60 (middle)        60.3%   48.1%      69.0%
  y 60-80                 13.3%   15.9%      12.3%
  y 80-100 (right touch)  10.3%   10.9%       5.7%

  deliveries aimed off the field   1.67 a match
  ...of which crossed              0.50 a match
```

The band split has no reference figure, and the first draft of this harness
printed "a real match is roughly 20/20/20/20/20" beside it, which I asserted and
did not measure — real football does play more through the middle than down
either wing. It has been taken out. What the split IS good for is the
comparison between its three columns: the man on the ball is markedly more
central than the men are, so play concentrates the ball beyond where the shape
puts the players.

The last pair is the whole of the throw-in gap — 0.5 a match against a real
forty — and it is not a retrieval problem, a clamp or a scoring weight. Almost
nothing is ever aimed out, because the man on the ball is inside the outer two
fifths of the pitch for a tenth of live play. Three separate attempts on it are
listed above and all three moved nothing.

**v2 does not play down the flanks, so no rule about the touchline can fire.**
Wide play needs a model of where the space IS. That is the entire reason engine
v3 exists, and it is the argument for finishing v3 rather than tuning v2 again.

---

## 10. The second list: the UI, the shouts, and why the match looks empty

### 10.1 The appointment screen was a black rectangle — one line

Reported with two screenshots: the panel reads "YOU HAVE THE <CLUB> JOB" and
everything under it is empty down to "WHAT THEY EXPECT".

```kotlin
top.addView(crest(c, 56), lp(WRAP, WRAP).also { it.rightMargin = dp(12) })
```

`crest()` returns a `CrestView` already carrying its own 56 dp layout params.
Passing a second set replaced them with `wrap_content` — and **a custom View
that does not override `onMeasure` does not honour wrap_content**:
`View.getDefaultSize` returns the whole `AT_MOST` size. So the crest measured to
the full width of the panel, and the info column beside it, which takes what is
left through a weight, got zero. The club name, division, stadium and squad
rating were all being drawn into a column no pixels wide.

Eleven other crests in the app are added without layout params and are fine;
this was the only one. `CrestView` now measures itself properly as well, so
neither half can recur.

### 10.2 The camera and the resolution are not choices

Two buttons cycled the framing (Wide/Close/Tight) and the pixel buffer
(256/320/384/512), both relabelling themselves as they went. Both gone: the
camera is fixed at `WIDE_ZOOM = 1.9` and `PIXEL_WIDTH` is 1280. At 1280 columns
the nearest-neighbour blit is one to one, so the chunkiness goes and the
palette, flat shading, shallow camera and figure proportions stay — the same
drawing, drawn sharp.

### 10.3 The tactics screen no longer jumps to the top

Every control there ends in `renderScreen()`, which rebuilds the tree, and a new
`ScrollView` starts at the top. So picking a style, a role, an instruction or a
taker threw you back to the top of a screen five pages long. The scroll position
is remembered per screen and restored — but only when the rebuild is of the
*same* screen, so navigating somewhere new still starts at the top.

### 10.4 Shouts DO reshape the side — and two of them lie

`ShoutCheck` plays to the half hour, makes one change, and measures the twenty
minutes after it against a control that changed nothing on the same seeds.
`line` is the mean x of the ten outfield men in their own attacking direction.

```
  shout                    line      width    press m   territory
  SAY NOTHING              46.6      19.3        5.7       46.4%
  Push them forward        59.8      19.2        6.3       60.9%
  Throw everyone up        67.1      18.3        5.7       63.5%
  See it out               29.8      18.7        6.4       32.5%
  Through the middle       49.4      14.8        6.4       53.6%
  Get it wide              39.7      21.5        6.2       35.2%
  Tighten up               37.4      18.3        6.9       43.6%
  Press them               43.1      18.0        6.5       41.3%
```

Four of them are emphatic and correct. Two are not:

- **"Press them" does the opposite of pressing.** The nearest opponent to the
  man on the ball goes from 5.7 m to **6.5 m** — further away — and the line
  drops four units. In v2, `tactics.press` reaches only `updateTargets`, where
  it slides the whole shape toward the ball wherever the ball happens to be,
  including deep in your own half. It never reaches the code that actually
  presses: `V2_TACKLE_M`, the chaser's urgency, or the size of `v2Support`.
- **"Get it wide" retreats.** Width does rise (19.3 → 21.5) but the line falls
  seven units and territory falls eleven points, which is not what the button
  says.

**No shout improves the press.** Every one of the eight leaves the carrier with
more space than saying nothing. Not fixed here — it is a result-affecting
change to v2's defending and needs its own `BalanceBig`.

### 10.5 Why the match looks like nothing is happening

`AliveCensus` counts 26.8 take-ons, 11.0 dives and 4.1 slides a match, and the
owner reports seeing none of them. Both are true: **a count is not a duration**,
and nobody had ever measured the duration. `PoseTime`, 15 matches, seconds of a
407-second match:

```
                              before   after
  somebody taking a man on      13.8    22.0
  a shot being struck           19.4    24.9
  a header                       8.6    12.9
  the keeper diving             12.5    12.5
  a man sliding in               2.1     2.1   0.5%
  a man on the floor             2.0     2.0   0.5%
  the referee showing a card     2.8     2.8   0.7%
  ANY named pose at all         57.4    72.4   14.1% -> 17.8%
```

A slide is on screen for **two seconds in seven minutes**. So is a foul. The
referee is on screen for under three. Nobody will ever catch those, however well
they are drawn — and that is the answer to "there are no scenes."

`TAKE_ON` and `SHOT` captions read **0.0 seconds**: those two scenes exist and
are never once set under v2. `PENALTY` also reads 0.0.

Lengthened here: `TAKE_ON_POSE`, `HEADER_POSE`, `SHOOT_POSE`. I expected these
to be free — none of the three is among the actions that stop a man moving —
and `ResultFingerprint` moved, so that expectation was wrong and they were
earned on `BalanceBig` instead: **2.59 ± 0.03 goals, 43.8% home over 9,120
matches**, which is the same figure to two decimal places as before the change.
The fingerprint moving without the balance moving is what a chaotic-but-neutral
change looks like, and it is the reason the 200-match fingerprint is a detector
and the 9,120-match run is the verdict.

`TACKLE` and `DOWN` are left alone deliberately: they freeze a man, so
lengthening the slide and the foul changes who is available to play the next
ball. That is the next measured change, and it is the one that matters most for
what the owner is asking to see.

### 10.6 Still open from this list

- **Player distribution across the pitch.** `WidthCheck` §9.8 is the measurement:
  the men are 48% inside the middle fifth and the man on the ball 69%.
- **AI layers for all 22.** This is engine v3 — `Pitch.kt`, `RoleTree.kt` and the
  utility scorer are written and quarantined behind `ENGINE_V3`; what is missing
  is a v3 match that plays to full time (docs/ENGINE_V3.md §6).
- **The dugout.** The tactics written there are the same objects `updateTargets`
  reads every tick, and §10.4 shows those changes do reach the pitch. If a
  change still reads as no change, the likely candidate is the two shouts above
  that move the side the wrong way.

---

## 11. Engine v4, step one: a corner that the attackers reach

The owner's instruction: take from every version what was right, put it in a new
engine, rewrite what still has a problem, and try ideas that have not been tried
here. The split and the plan are in `docs/ENGINE_V4.md`. This is the first step
of it, and the first genuinely new idea.

### 11.1 Look at a corner. It is worse than the aggregates said.

`CornerFilm` films a whole corner instead of sampling an instant, which is all
any previous harness did. On the first corner of a match, with the delivery at
frame 32:

```
                       before   after
  attackers in the box      5       8
  defenders in the box     10      10
  mean gap to target      7.6 m   3.6 m
  taker from the ball     4.5     3.5
```

The attacking side was told to fill the box and was still 7.6 m short of where
it had been sent when the ball was swung in. Earlier in the same corner it is
worse: two attackers against eight defenders while the men jog across.

They were not ignoring the instruction. The effort table gave them 0.80 of their
pace, and 0.80 of their pace does not cross half a pitch in the time a corner
lasts. **No value of that multiplier fixes it, because the multiplier does not
know the deadline.**

### 11.2 The new idea: ARRIVE BY, not RUN HARD

Every engine here has answered "how fast should this man run?" with a table of
distance-to-ball bands times a hand-tuned urgency. v4 inverts it. A target can
carry a **time** — *be there in N seconds* — and the speed follows:

```
  needed = distance / secondsLeft,  capped by what he can physically do
```

The table's pace stays as the floor, so a deadline can only ever make a man
quicker, never lazier. A man 40 m from the six yard box with 8 seconds runs at
5 m/s and arrives; the same man with 30 seconds walks. `stageCorner` now hands
out the deadline the delivery actually has — `CORNER_SECONDS * SET_PIECE_TAKE`
less a moment to be set — and the defending side gets a slightly earlier one, so
it is set before the attackers arrive.

Behind `ARRIVE_BY`, default on, with the old table one boolean away.

### 11.3 And the slide, the foul and the referee

Continued from §10.5, and earned on `BalanceBig` this time rather than assumed
free. `TACKLE_POSE` 0.6 → 0.95 s, the share of won challenges shown as a slide
0.34 → 0.62, `DOWN_POSE` 0.5 → 1.1 s, `REF_HOLD` 2.6 → 3.8 s. `PoseTime`,
seconds of a 407-second match:

```
                              was    now
  a man sliding in            2.1    7.4
  a man on the floor          2.0    4.2
  the referee showing a card  2.8    4.1
  a header                    8.6   14.1
  somebody taking a man on   13.8   22.3
```

`act()` already caps the pitch at two men on the floor at once, so the longer
poses cannot turn a match into a hospital scene.

### 11.4 Kept honest

`BalanceBig` on the slide and foul alone: **2.60 ± 0.03 goals, 44.1% home**.
`BalanceBig` with `ARRIVE_BY` on top: **2.56 ± 0.03 goals, 43.9% home**. Both
inside the band, and both within one standard error of the 2.59 v2 has read all
along — so eight attackers arriving in the box instead of five costs the game
nothing. `UiFlow` 33/33, `PoseTime` as above.

### 11.5 Next

`docs/ENGINE_V4.md` §5 has the order. The next new idea is **the corner as a
play with parts and deadlines** rather than nine men scattered into a box — a
taker, a near-post and a far-post runner on staggered deadlines so they attack
the ball rather than stand in it, a short option, an edge man and two holding.
With 11.2 underneath it, the corner routine settings finally mean something.

---

## 12. Engine v4, idea 2: the corner is a play, and the manager can see it

`stageCorner` sent nine men to random points in a rectangle over the penalty
area. Two things followed, both measured.

**The picture was the same for all four routines.** The routine chose who the
ball was aimed at and what the chance was worth, and moved nobody — so "near
post", "far post", "work it short" and "edge of the box" were one arrangement
with four sets of odds. **And a scatter has no near post and no far post**, so
there was nothing for a delivery to be aimed at, which is why the corner has
been the hardest scene in this project to make look like football.

A corner is now a set of PARTS, each a place and a time: somebody on the ball, a
short option who must be there early or the short ball is not a real choice, a
near-post and a far-post runner on late deadlines so they attack the ball rather
than stand waiting for it, a man at the penalty spot, one on the edge for the
cut-back, and men held back against the counter. Parts go to whoever is nearest
them, not in shirt order — a corner where the left back sprints past two team
mates to reach the far post is not a corner.

`CornerPlay`, where the attacking side stands at the instant the ball is struck:

```
  routine               box   near    far  short   edge   back
  near post             4.3    1.6    1.4    1.8    1.1    2.1
  far post              4.4    0.9    0.7    1.8    1.1    2.3
  work it short         3.8    0.9    0.6    1.9    1.0    2.0
  edge of the box       4.0    0.9    0.8    1.9    1.9    2.1

  men up                box   near    far  short   edge   back
  keep men back         3.9    1.0    0.7    1.9    0.1    4.0
  normal                4.4    0.9    0.7    1.8    1.1    2.3
  everyone up           6.3    1.0    0.6    1.9    1.8    0.0
```

Four identical rows would mean the manager cannot see his own instruction. They
are not identical: a near-post corner puts a second body on the near post, an
edge corner doubles up outside the area, everyone-up puts **6.3** men in the box
with nobody left behind, and keep-men-back leaves **4.0** in their own half.

`CornerFilm` on the same corner as §11: at the delivery every man is a mean
**2.1 m** from where he was sent, against 7.6 m before any of this — the whole
routine is in place when the ball comes in.

### 12.1 Two bugs found by measuring rather than by looking

- **"Everyone up" was identical to "normal".** There were only seven parts and
  the normal setting already used all seven, so the extra men had nowhere to go.
  Two more places to be, and the setting means something.
- **The men "held back" were held back in the OPPOSITION half.** The sign was
  the wrong way round: `inward` already points from the corner flag into the
  pitch, so going back is plus, not minus. They were standing at x 56–70 for a
  side attacking x = 100, twenty metres in front of where they belonged, and the
  `back` column read 0.0 for every setting.

Neither would have been visible without a harness that asks where the men are
per setting. Both were introduced in this same rewrite and caught before it
shipped, which is the argument for writing the gate at the same time as the code.

### 12.2 Kept honest

`BalanceBig`, 9,120 matches: **2.54 ± 0.03 goals, 44.3% home** — inside the band.
`UiFlow` 33/33. So a corner that is actually defended, with two men on the posts
and everybody goal-side of somebody, costs the game nothing.
