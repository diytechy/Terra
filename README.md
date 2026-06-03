# ⚠️ This is NOT Terra. This is a Vibe-Coded Abomination. ⚠️

Let's get the important part out of the way first, in bold, so nobody can say they
weren't warned:

> **This repository is not Terra.** It is a personal, vibe-coded fork that has
> wandered off from the real [Terra](https://github.com/PolyhedralDev/Terra)
> project, eaten some things it shouldn't have, and is now wearing Terra's
> skin. The wonderful people at Polyhedral Development did not write this, did
> not review this, and are not responsible for whatever this does to your world
> save.

If you came here looking for the actual, supported, community-loved world
generator, **turn around now** and go to the real thing.

## What is this, then?

This is my own project, for my own Minecraft world, left out in the open in case
anyone else wants to poke it with a stick. It exists to do exactly two things:

1. Run on **Minecraft 26.1 (Paper 26.1)**, and
2. Generate a world pack that mashes together the major community-driven Terra
   packs into one combined experience:
   **[CHIMERA](https://github.com/diytechy/CHIMERA)**.

That's the whole mission. Everything in here bends toward making *my* world boot
and look the way I want it to. It was built by vibes, prompts, and stubbornness —
**not** by formal review, careful benchmarking, or anything resembling
engineering discipline.

## The Rules of Engagement (please read)

* **Found a bug? Raise it HERE.** Open an issue on *this* repo. Do **not** go
  file it on the real Terra project. They didn't do this. You will only confuse
  and sadden them.
* **Unless** you have actually reproduced the issue against *stock, upstream
  Terra* and proven it lives in their scope — then, sure, that's a real Terra
  bug and belongs with Terra. The bar is "I proved it exists upstream," not "it
  broke and Terra is the closest name on the box."
* **My support is best-effort, and "best" may be very small.** This is a hobby
  fork for one world. I may fix your issue tomorrow, in six months, or never. No
  promises, no SLAs, no hard feelings.
* **This is not official, not community-supported, and not blessed by anyone.**
  It's a thing I made. You're welcome to try it. Caveat emptor, and bring a
  backup of your save.

## What's been done to poor Terra

This fork has diverged substantially from upstream Terra's 1.21.11 line. The
full, gory accounting lives in
[investigations/Branch-vs-Upstream-1.21.11.md](investigations/Branch-vs-Upstream-1.21.11.md).
The highlights, derived from that document and the code itself:

* **Multi-platform, sort of.** Targets MC 26.1 / Paper 26.1 on **JDK 25**. The
  live build graph covers **Bukkit/Paper, CLI, Allay, and Minestom**. **Fabric**
  and **NeoForge** have build
  systems staged but kept disabled — WIP, untested, with architectury dropped
  because it hasn't ported to 26.1. **Forge, Quilt, and Sponge** remain dormant.
  All the disabled platforms' sources still sit in the tree, quietly rotting.
* **A custom pack-publishing pipeline.** Every merge to `main` cuts a
  `VIBE-`tagged GitHub release with built platform jars, and the build pulls and
  bundles the CHIMERA pack (treated as *required* — a missing CHIMERA fails the
  build).
* **Heavily reworked terrain engine.** The 3D chunk generator
  (`ChunkInterpolator`, `Sampler3D`, `NoiseChunkGenerator3D`), a from-scratch
  rewrite of the noise `CacheSampler` (literally committed with the message
  *"Claude's cache rewrite - UNTESTED"*), a new chunk-scoped caching layer in the
  biome pipeline plus a `StructureSearchBiomeProvider`, a new max-Y biome
  extrusion type, new surface-locator addons, and DendryTerra / BubblesOnChunkGen
  integrations.
* **NMS post-processors** that fill in vanilla-like behaviour after generation:
  chest and BrushableBlock loot, beehive population, End gateway teleport
  targets, and bedrock suppression around End crystals.
* **Custom forks of upstream libraries** (Tectonic and Seismic, see below),
  resolved from a public `diytechy` Repsy repo.

**Enormous flashing caveat:** none of these changes have been formally reviewed,
properly benchmarked, or validated against upstream. They could be subtly wrong.
They could **reduce performance**. They could generate beautiful terrain on
Tuesday and cursed terrain on Wednesday. They exist because they made my world
work, not because they're correct.

## The other abominations it depends on

This fork doesn't stop at Terra — it drags two of Terra's own dependencies down
with it. Both are similarly vibe-augmented forks:

### Vibe-augmented Seismic — https://github.com/diytechy/Seismic

Fork of [PolyhedralDev/Seismic](https://github.com/PolyhedralDev/Seismic),
pinned as `2.5.7-PATCHED`. Notable changes:

* **Fixed two real bugs in `OpenSimplex2SSampler` 2D `getNoiseRaw`** — an `a1`
  attenuation term missing its `+ a0` (which was exposing the raw simplex
  lattice as visible seams across the whole output), and a `y2` coordinate using
  the wrong unskew constant. Both found by diffing against the already-correct
  derivative variant and verifying algebraically.
* Added local / Repsy publishing (no GPG) and a `publishToMavenLocal` finalizer
  for patch testing.

### Vibe-augmented Tectonic — https://github.com/diytechy/Tectonic

Fork of [PolyhedralDev/Tectonic](https://github.com/PolyhedralDev/Tectonic),
pinned as `4.3.2-diytechy`. Notable changes:

* **A session-scoped type-load cache** (`beginSession()` / `endSession()` on
  `ConfigLoader`) that deduplicates type-load results across configs sharing
  inherited raw YAML from a common parent — thread-safe because Terra loads
  configs in a parallel stream. **This API does not exist upstream**, and this
  fork's Terra calls it directly, so you cannot simply drop back to stock
  Tectonic.
* **Java 25 retargeting**, including a `ScopedValue` (`CURRENT_DEPTH`) for the
  `DepthTracker` instead of threading it through every call frame, plus assorted
  reflective-loader changes. Gradle bumped to 9.2.1. A broader roadmap of
  Java-25-era ideas lives in
  [Tectonic's Java25Improvements.md](https://github.com/diytechy/Tectonic/blob/master/Java25Improvements.md).

Same disclaimer applies to both: unreviewed, vibe-driven, here because they made
my world work.

## Building

If you really want to build this thing: `./gradlew build` (`gradlew.bat build`
on Windows). You will need a JDK 25 toolchain reachable by Gradle, and network
access to the `diytechy` Repsy artifacts (should be public) for the custom Tectonic/Seismic/pack
dependencies — a clean offline checkout will not build.

## Licensing

This is a fork of Terra and inherits Terra's licensing. Nothing here changes
that — all credit for the underlying platform belongs to Polyhedral Development.

Parts of Terra are licensed under either the MIT License or the GNU General
Public License, version 3.0.

* The API is licensed under the [MIT License](LICENSE).
* The core addons are also licensed under the [MIT License](LICENSE).
* The platform-agnostic implementations and platform implementations are
  licensed under the
  [GNU General Public License, version 3.0](common/implementation/LICENSE).

If you're not sure which license a particular file is under, check the file's
header or the LICENSE file in the closest parent folder.

## Credit where it's due

The real Terra is the work of [Polyhedral Development](https://github.com/PolyhedralDev)
and its contributors. They built something genuinely excellent. I vibe-coded on
top of it for my own amusement. Please direct your admiration to them — and your
bug reports to me.