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
