# ATTEMPTS

One line per attempt at a broken row. **Read this before starting another one.**

It exists because the same row was attacked four times in one session by four
different mechanisms, three were reverted, and the reason all four failed was
written in a code comment after the first — buried in a thousand-line file
where nobody, including its author, ever read it again.

The notes themselves stay next to the code they explain. This is the index.

| row | attempt | mechanism | result | commit |
|---|---|---|---|---|
| pass completion | 1 | 50-50 contest for a dying ball | reverted — 69.5% → 69.2%, goals 1.35 → 0.80 | pre-`2b1684f` |
| pass completion | 2 | loose ball: a contested touch can break | **shipped** — 63.2% → 59.1%, but LOOSE_BALL/RECOVERY_RUN alive | `166ab29` |
| pass completion | 3 | a man may take a MOVING ball | reverted — every reach radius made completion and interceptions worse | `a3472c8` |
| pass completion | 4 | solve the strike against the physics | **shipped** — 59.1% → 67.9% | `a3472c8` |
| pass completion | 5 | fix the ball's bounce; scan instead of bisect | **shipped** — ball-vs-aim 10.21 m → 1.50 m, but completion 68.3% → 54.2% | this |
| pass completion | 6 | lead the pass to his declared target | reverted — 5.93 m → 6.02 m, no change | this |
| delivery miss | 1 | cut the delivery error scale 26 → 9 | reverted — 8.57 m → 8.23 m | early |
| delivery miss | 2 | lead the pass by his velocity | **shipped**, effect 0.14 m | early |
| delivery miss | 3 | solve strike speed for distance | **shipped** — found a 5 m systematic overshoot | early |
| interceptions | 1 | split cut-out from dispossession | **shipped** — 401.9 → 218.7 | early |
| interceptions | 2 | an interception needs a man in the LINE at the strike | **shipped** — 224 → 109 | `166ab29` |
| goals | 1 | aim shots away from the keeper | reverted — blocks 16.5 → 4.75 but goals 1.35 → 1.00 | early |
| goals | 2 | two shot options, placed and driven | reverted — shots 27 → 50, a softmax vote on kinds | early |
| block rate | 3 | perception radius narrower than the block | reverted — blocks 16.5 → 7.15 but shots 27 → 17 | early |
| defending | 1-4 | clamp / line-from-slide / no-leash / phases | all reverted | early |
| defending | 5 | a line between the ball and the goal | **shipped** — goal-side 0.00 → 0.99 | `2b1684f` |
| option prices | 1 | fit every price to what MindCheck measured | reverted — four rows worse; the ACTS are misimplemented | `2f44c6d` |

## The rule these keep re-teaching

**A check that cannot disagree with what it is checking measures nothing**, and
its cousin: *a null result measured under a confound is not a null result.*

Four of the reverts above were measured while something else was also broken.
Two of the "bugs" found in this session were bugs in the instrument, not in the
engine — including one where a stale field made it look like a man was kicking
a ball fourteen metres away, and the fix was written before the instrument was
checked.
