# CALIBRATION — why the engine takes twice as many shots

No mechanics were added for this. It is a diagnosis, a scoreboard, and a
prediction for each row that a later commit can prove wrong.

## The claim I made, and it was wrong

The last engine commit said: *"far too many bad shots are taken… a real side
takes 25 and scores from one in eight; this one takes 47 and scores from one in
twenty-three."* The natural reading is that the decision layer shoots from
stupid places.

`ShotAudit` bucket-for-bucket against 375 real non-penalty shots says otherwise.

```
 distance    offered     TAKEN  take rate    our xG  REAL taken   REAL xG
     0-6 m       0.4      0.40     100.0%     0.814        1.43     0.360
    6-11 m       3.9      2.75      70.5%     0.303        6.07     0.184
   11-16 m      25.7     15.55      60.5%     0.141        7.36     0.116
   16-22 m      84.0     20.80      24.8%     0.073        7.14     0.048
   22-30 m     178.9      6.80       3.8%     0.032        4.07     0.026
   30-40 m     242.1      0.55       0.2%     0.009        0.50     0.011
     40+ m      93.6      0.00       0.0%     0.000        0.21     0.004
    TOTAL                46.85               0.108       26.80     0.110
```

**Mean xG per shot: 0.108 ours, 0.110 real.** The chooser's appetite for a shot
is, on average, exactly right. It is offered a shot 630 times a match from
beyond 22 m and takes seven; the generator is loose and the decision layer holds.

## What is actually wrong — two things, neither of them shot selection

**1. The engine cannot get close to goal.** The excess is entirely 11-22 m:
36.4 shots a match where football takes 14.5. And inside 11 m it is SHORT — 3.15
against 7.5. Football's shots come from close because its attacks arrive there;
ours stall at the edge of the box and shoot. The mean xG matches only because
too many mid-range and too few close-range cancel out.

**2. Every shot is aimed at the centre of the goal, which is where the
goalkeeper stands.** Conversion is 3.6% against an expected 10.8% — the shots
underperform their own xG threefold. `Decide.generate` targets
`Pitch.WIDTH * 0.5`, and the keeper's resting position is `WIDTH/2 + (ball.y -
WIDTH/2) * 0.22`, i.e. near the middle. Every shot is struck at him.

Aiming away from the keeper was tried once, early, and reverted: blocks fell
16.5 → 4.75 but goals fell 1.35 → 1.00. That test ran against a keeper whose
reach was a CONSTANT 3.2 m regardless of distance or pace, so aiming wide could
not beat him and only cost accuracy. The keeper is now a reaction time and a
dive speed, and the earlier null result was measured under that confound — it
does not carry.

## Expected direction, written down before the change

| row | now | target | expected | why |
|---|---|---|---|---|
| shots | 46.9 | 22-27 | **down** | not by shooting better — by arriving closer, so the 11-22 m band shrinks |
| goals | 1.70 | 2.6-2.9 | **up** | shots underperform their own xG threefold; they are struck at the keeper |
| pass completion | 56.8% | 78-85 | **up** | the cap on everything structural |
| interceptions | 64.5 | 16-22 | **down** | consequence of completion, not a separate fix |
| corners | 3.65 | 9-11 | **up** | needs the deflection; a defender can only put it behind with his head |
| offsides | 0.30 | 4-6 | **up**, toward the real 2.83 | the §5 band is itself high |

A direction is a falsifiable claim. If a commit moves a row the other way, the
model behind it was wrong, and that is worth more than the row.

## The baseline is NOT re-recorded

The fingerprint stays red against the baseline from `f48f866`. Re-recording
would freeze an engine that takes 47 shots and converts 3.6%, and it should not
be done until the debug frames and the metric changes above have been looked at
by the owner. `--record` still refuses; the gate writes a proposal and installs
nothing.

## What is in CI now

`Scoreboard` prints the six rows with targets and expected directions.
`ShotAudit` prints the table above. Both print; neither gates. A diagnosis that
gates is a diagnosis people tune at.
