#!/usr/bin/env python3
"""
REAL FOOTBALL, MEASURED — the anchors this engine is judged against.

GAME_BRIEF.md §4 says the value surface is anchored OUTSIDE the engine and is
never re-derived from the engine's own play. Until now those anchors were my
recollection of published figures, stated to two significant figures and
sourced to nothing. This replaces them with numbers taken from event data.

SOURCE: StatsBomb Open Data, https://github.com/statsbomb/open-data
        CC BY-NC-SA 4.0, 3,961 matches with a full event stream per match.
        Not scraped from a stats site — an openly licensed dataset, used as
        licensed.

WHAT THE DATA IS ALLOWED TO DO, and this matters more than the numbers:

  it MAY set the acceptance targets and the anchors
  it MAY NOT set a mechanism, and it may NEVER be replayed

The engine must produce 7.96 strikes per possession BECAUSE players can hold
the ball, not because a distribution was fitted to make it so. Matching an
output distribution without simulating the football that causes it is the
predecessor's disease in a more sophisticated form: every number correct and a
tactic still invisible on the pitch. §1 is the test — a tactic must be visible
and measurable, or it is not implemented.

  python3 tools/data/anchors.py [competition_id] [season_id] [matches]
"""
import json
import statistics
import sys
import urllib.request
from collections import Counter

BASE = "https://raw.githubusercontent.com/statsbomb/open-data/master/data"

# What counts as a man playing the ball, chosen to mean the same thing the
# engine's own counter means: he struck it. A StatsBomb "Carry" is deliberately
# counted separately, because the engine has no such act at all and lumping it
# in would hide exactly the hole this measurement exposes.
STRIKE = {"Pass", "Shot", "Clearance"}


def get(url):
    return json.load(urllib.request.urlopen(url, timeout=90))


def main():
    comp = int(sys.argv[1]) if len(sys.argv) > 1 else 9
    season = int(sys.argv[2]) if len(sys.argv) > 2 else 281
    limit = int(sys.argv[3]) if len(sys.argv) > 3 else 25

    matches = sorted(get(f"{BASE}/matches/{comp}/{season}.json"),
                     key=lambda m: m["match_id"])[:limit]

    strikes = carries = possessions = one_strike = 0
    durations, per_poss = [], []
    passes = completed = shots = goals = fouls = cards = tackles = 0
    offside_events = offside_passes = 0
    n = 0

    for match in matches:
        try:
            events = get(f"{BASE}/events/{match['match_id']}.json")
        except Exception as exc:                       # a missing file is not fatal
            print(f"  skipped {match['match_id']}: {exc}", file=sys.stderr)
            continue
        n += 1
        current, count, start, last = None, 0, None, None

        for e in events:
            kind = e["type"]["name"]
            if kind == "Pass":
                passes += 1
                if "outcome" not in e.get("pass", {}):
                    completed += 1
                # Offside is recorded as a PASS OUTCOME here, not as its own
                # event. Counting only the event type reads 0.28 a match, which
                # is not football and was a bug in the first version of this
                # script rather than a finding about the game.
                if e.get("pass", {}).get("outcome", {}).get("name") == "Pass Offside":
                    offside_passes += 1
            elif kind == "Shot":
                shots += 1
                if e["shot"]["outcome"]["name"] == "Goal":
                    goals += 1
            elif kind == "Foul Committed":
                fouls += 1
                if e.get("foul_committed", {}).get("card"):
                    cards += 1
            elif kind == "Duel":
                if e.get("duel", {}).get("type", {}).get("name", "").startswith("Tackle"):
                    tackles += 1
            elif kind == "Offside":
                offside_events += 1
            elif kind == "Carry":
                carries += 1

            if e.get("possession") != current:
                if current is not None and count > 0:
                    possessions += 1
                    per_poss.append(count)
                    strikes += count
                    if count <= 1:
                        one_strike += 1
                    durations.append(max(0.0, (last or 0) - (start or 0)))
                current, count = e.get("possession"), 0
                start = e["minute"] * 60 + e["second"]
            if kind in STRIKE:
                count += 1
            last = e["minute"] * 60 + e["second"]

    offsides = offside_events + offside_passes
    print(f"SOURCE  StatsBomb Open Data, competition {comp} season {season}")
    print(f"SAMPLE  {n} matches, {strikes:,} strikes, {possessions:,} possessions")
    print()
    rows = [
        ("strikes per possession", strikes / possessions),
        ("seconds per possession", statistics.mean(durations)),
        ("one-strike possessions %", 100 * one_strike / possessions),
        ("possessions a match", possessions / n),
        ("passes a match", passes / n),
        ("pass completion %", 100 * completed / passes),
        ("carries a match", carries / n),
        ("shots a match", shots / n),
        ("goals a match", goals / n),
        ("fouls a match", fouls / n),
        ("cards a match", cards / n),
        ("tackles a match", tackles / n),
        ("offsides a match", offsides / n),
    ]
    for label, value in rows:
        print(f"  {label:<26} {value:8.2f}")


if __name__ == "__main__":
    main()
