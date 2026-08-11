# ANCHORS — real football, measured

Every band in GAME_BRIEF.md §5 was written from recollection. This file
replaces recollection with measurement, and it changes some of the bands.

**Source.** StatsBomb Open Data — https://github.com/statsbomb/open-data —
CC BY-NC-SA 4.0. 3,961 matches carry a full event stream, every touch with a
type, a location and a possession index. This is an openly licensed dataset
used as licensed, not a stats site scraped against its terms.

**Sample below.** Bundesliga 2023/24, 12–25 matches, ~15,000 strikes. Small,
and deliberately labelled as such: the pipeline is `tools/data/anchors.py` and
anyone can widen it to the whole corpus.

## What the data is allowed to do

| | |
|---|---|
| MAY | set the acceptance targets, and set the anchors §4 requires |
| MAY NOT | set a mechanism |
| NEVER | be replayed, or have engine output fitted to its distributions |

The engine must produce eight strikes per possession **because players can hold
the ball**, not because a distribution was matched. Reproducing statistics
without simulating the football underneath is the predecessor's disease wearing
better clothes: every number right and the tactic still invisible on the grass.
§1 remains the test.

## Measured, against the engine and against §5

| row | REAL | engine | §5 band | verdict on the BAND |
|---|---|---|---|---|
| passes a match | **1183** | 642 | 800–950 | **band is wrong — too low** |
| pass completion | **85.4%** | 61.4% | 78–85% | ok, real sits at the top |
| shots a match | **25.5** | ~19 | 22–27 | ok |
| goals a match | **3.67** | 3.28 | 2.6–2.9 | band low for this league |
| fouls a match | **22.0** | 4.4 | 20–24 | ok |
| tackles a match | **30.8** | 30.4 | 30–36 | ok |
| offsides a match | **2.83** | 9.03 | 4–6 | band slightly high |
| cards a match | **2.67** | 0.50 | 3.0–4.5 | band slightly high |

## Rows §5 does not have, and needs

These describe the SHAPE of a possession, and their absence is why the engine
could be badly wrong about it for months without a single row complaining.

| row | REAL | engine |
|---|---|---|
| strikes per possession | **8.18** | 1.98 |
| seconds per possession | **25.30** | 11.74 |
| one-strike possessions | **9.4%** | 48.8% |
| possessions a match | **151** | 459 |
| **carries a match** | **976** | **0** |

## The finding

A carry is very nearly as common as a pass — 976 against 1,183 — and this
engine has none at all. That single absence was diagnosed from the inside two
commits ago, from possession structure alone; the data confirms it with a
number an order of magnitude larger than expected.

And the guess that came with the diagnosis was itself too generous. I estimated
real football at about 3.5 strikes per possession. It is 8.18. The engine's 1.98
is not half of football, it is a quarter of it.

## Reproducing

```
python3 tools/data/anchors.py [competition_id] [season_id] [matches]
```

A bug worth keeping in view: the first version read offsides at 0.28 a match,
which is not football. StatsBomb records most offsides as a PASS OUTCOME rather
than as an event of their own. The instrument was wrong, not the game — the
third time in this project that has happened, and the reason every number above
carries its extraction code next to it.
