# tools — the shim, the gate, and how to run them

Nothing in here ships. It is the machinery that makes a claim about the game
measurable, and by `GAME_BRIEF.md` §6 it exists before the first match is
simulated.

## What is here

```
tools/shim/AndroidGraphics.kt   Java2D stand-in for Canvas, Paint, Path, Bitmap
tools/gate/Digest.kt            the two fingerprints, and the readable breakdown
tools/gate/Engine.kt            the contract an engine must satisfy to be gated
tools/gate/SimGate.kt           the adapter between the football and the gate
tools/gate/Gate.kt              the gate itself
tools/gate/baseline.properties  DOES NOT EXIST YET — the owner records it
tools/harness/ShapeCheck.kt     does a setting move the block? with a control row
tools/harness/Film.kt           debug frames as PNGs
```

The shim is carried over intact from the predecessor, which is the point of
carrying it over. It is the reason the renderer can be compiled and run on a
JVM with no device, so a claim about what appears on screen is a thing you can
render to a PNG and look at.

## Running it

No Gradle, no plugins, no dependencies. Kotlin compiler and a JVM.

```sh
export PATH="$PATH:$HOME/kotlinc/bin"

SRC=app/src/main/java/com/dugout/career
kotlinc -nowarn $SRC/sim/*.kt $SRC/ui/*.kt \
    tools/shim/*.kt tools/gate/*.kt tools/harness/*.kt \
    -include-runtime -d build/gate.jar

java -Dstdout.encoding=UTF-8 -Djava.awt.headless=true -cp build/gate.jar gate.GateKt --selftest
java -Dstdout.encoding=UTF-8 -Djava.awt.headless=true -cp build/gate.jar harness.ShapeCheckKt 8
java -Dstdout.encoding=UTF-8 -Djava.awt.headless=true -cp build/gate.jar harness.FilmKt build/film 1000
java -Dstdout.encoding=UTF-8 -Djava.awt.headless=true -cp build/gate.jar gate.GateKt
```

The compile line is the one CI uses, so a thing that builds here builds there.
The gate is 200 matches and takes about 35 seconds.

`-Dstdout.encoding=UTF-8` is not decoration: on a container with a POSIX locale
the breakdown arrives as question marks, which is the readable-breakdown
requirement of §1.2 failed on a technicality.

## Exit codes

| code | meaning |
|---|---|
| 0 | both fingerprints unchanged |
| 1 | a fingerprint moved — the breakdown says which statistic and by how much |
| 2 | **no engine to fingerprint.** Expected until step 2. CI is red on purpose |
| 3 | no baseline recorded. The owner records it |
| 4 | `--record` refused |

## Why the gate is red right now

The engine exists and both fingerprints are computed, but **no baseline has
been recorded**, so there is nothing to compare them against and the gate exits
3. That is still the correct colour.

Running the gate writes `build/baseline.proposed.properties` — 1,155 lines, the
two hashes and every quantised component. It does **not** write
`tools/gate/baseline.properties`. When the football behind those numbers looks
right:

```sh
cp build/baseline.proposed.properties tools/gate/baseline.properties
```

That is the owner's call and nobody else's (§1.2), and `--record` refuses for
the same reason.

## The one thing already proved

`--selftest` exercises the shape breakdown against synthetic digests: that an
unchanged digest reports nothing, that a moved statistic is named with its
delta in quanta, that a change smaller than its quantum is not a change, that
the list is sorted worst first, and that the hash depends on the statistics
rather than on the order they were added. It prints a real breakdown at the end
so the format can be read before anyone needs it in anger.
