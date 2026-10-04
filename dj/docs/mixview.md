# Mix view: seeing the DJ mix

A DJ-software style picture of the transition that is playing (or about to): two lanes (outgoing on top, incoming
below) on ONE time axis, beat ticks of both decks facing each other in the gap, the overlap zone, both faders, the EQ
cut bands (bass swap) and a playhead. Not wired into the engine or Now Playing yet; this page says exactly how.

## Pieces

| Piece | Where | Notes |
|---|---|---|
| `DjMixViewData`, `DjMixDeck`, `DjMixKind`, `DjMixPhase` | `dj/brain/.../org/simpmusic/dj/mixview/DjMixViewData.kt` | Plain immutable data, no Android/Compose. Lives in `:brain` (not `:djAndroid`) because `composeApp/commonMain` also compiles for Desktop and cannot see the Android-only `:djAndroid`. |
| `DjMixViewBuilder.build(plan, fromAnalysis, toAnalysis, titles, nowPlanMs)` | `dj/android/.../android/mixview/DjMixViewBuilder.kt` | Pure. Uses `WindowTimeline` to map both decks through their tempo lanes. Tests: `DjMixViewBuilderTest` (10 tests). |
| `DjMixCanvas`, `DjMixPanel`, `DjMixCardContent`, `DjMixStrings` | `composeApp/.../ui/component/dj/` | Pure Compose, no resources: renderable off-screen. Colours only from `MaterialTheme.colorScheme`. |
| `DjMixCard(data?)`, `DjMixSheet(data?, onDismiss)`, `rememberDjMixStrings()` | same package | Resource-backed wrappers (13 strings appended to base `strings.xml`, `dj_mix_*`). |
| Off-screen renderer | `dj/mixview-preview` (standalone Gradle build) | See below. |

## Data contract

Everything is on the plan's wall-clock axis, T0 = the instant the incoming deck starts (0), negative = before it.

* `startMs..endMs`: `min(window start, -6 s)` (window start = `preRoll - LEAD_IN_MS`) .. `max(settle + 4 s, 8 s)`, both
  snapped to `stepMs` (100). `overlapEndMs` = plan overlap (0 for a CUT), `settleMs` = when every lane is constant.
* Per deck (`outgoing`, `incoming`): series of `sampleCount` values at `startMs + i * stepMs`:
  `energy`, `lowEnergy` (read THROUGH the deck's rate lane: mean over the source range each step covers, 0 where the
  deck is not playing / past the track end), `volume`, `lowCutHz`, `highCutHz`, `rate`, `pitchSemitones` (the sampled
  automation lanes). Ticks: `beatsMs` and `downbeatsMs` in plan ms (already tempo-mapped, so the two decks' ticks
  coincide when the grids line up). Labels: `title`, `bpm`, `key` (Camelot). `activeFromMs`: outgoing = `startMs`,
  incoming = 0.
* Top level: `kind` (null while analysing), `mixBpm`, `confidence`, `reason`, `nowMs` (nullable), `phase`.
* Missing analysis parts degrade, never throw: no energy -> zero series and `hasEnergy = false`, no beats -> empty ticks.
* `data.withNow(planMs)` is a cheap copy (shares every series) that moves the playhead and re-derives `phase`
  (`READY` when null, `LEAD_IN` before T0 with `msUntilT0`, `MIXING` with `mixProgress`, `SETTLING`).
* `DjMixViewData.analysing(fromTitle, toTitle)` is the empty state (`phase = ANALYSING`).

## Wiring (about 20 lines)

1. **Build setup (one line).** The data type is in `:brain`, so `composeApp/build.gradle.kts` `commonMain.dependencies`
   needs `implementation("org.simpmusic.dj:brain")` (the root build already substitutes it via `includeBuild("dj")`; the
   Android side gets it through `:djAndroid`'s `api`). Nothing else in composeApp changes.

2. **Engine exposes a flow** (in `DjEngine`, Android):

   ```kotlin
   private val _mixView = MutableStateFlow<DjMixViewData?>(null)
   val mixView: StateFlow<DjMixViewData?> = _mixView.asStateFlow()

   // once, when a plan for the next transition is ready (both analyses known, or null for a degraded one):
   _mixView.value = DjMixViewBuilder.build(plan, fromAnalysis, toAnalysis, fromTitle to toTitle)

   // ~10 Hz while the window plays (the controller already polls the window player for the phase lock):
   val nowPlanMs = timeline.planTimeOfWindow(windowPositionMs)        // WindowTimeline
   _mixView.update { it?.withNow(nowPlanMs) }

   // window finished / aborted / plan dropped:
   _mixView.value = null                                              // or it?.withNow(null) to keep the picture as READY
   ```

   Building is a few ms (a 10 ms tabulation of two tempo lanes plus the beat lists), do it on the same background
   dispatcher that plans. While the plan is being computed emit `DjMixViewData.analysing(fromTitle, toTitle)` to show the
   empty state.

3. **UI** (Now Playing, wherever the DJ chip goes; the engine flow reaches commonMain as a plain parameter):

   ```kotlin
   val mix by djEngine.mixView.collectAsState()          // Android side
   var showMix by remember { mutableStateOf(false) }
   if (mix != null) AssistChip(onClick = { showMix = true }, label = { Text(rememberDjMixStrings().sheetTitle) })
   if (showMix) DjMixSheet(data = mix, onDismiss = { showMix = false })
   ```

   or inline: `DjMixCard(data = mix, modifier = Modifier.padding(16.dp))` (renders nothing for null).

The playhead is interpolated on the frame clock between updates and never moves backwards, so 10 Hz is plenty; a jump
of more than 400 ms snaps. Set `animatePlayhead = false` for static renders.

## Look and reading guide

* Lane A (outgoing, `colorScheme.primary`) on top, lane B (incoming, `tertiary`) below; badges show the tempo lane at T0
  (`x1.069`) and the pitch shift when there is one.
* Waveform: outer body = total energy, bright core = low band. Bars dim with the deck's fader, so the waveform "lights up"
  as the fader opens. The bass core also fades as the deck's low-cut closes on it: the bass swap reads as the core
  changing hands.
* Dashed bands at the bottom of a lane = the high-pass (bass cut) reaching 300 Hz; the band from the top = a low-pass.
* Ticks hang into the gap between the lanes (downbeats taller); aligned grids mirror each other. Faint vertical lines
  through each lane mark that deck's downbeats.
* Shaded zone = overlap; a dashed line alone = a CUT. Everything left of the playhead is dimmed.

## Off-screen renderer and tests

`dj/mixview-preview` is a standalone build (Compose Desktop, same versions as `gradle/libs.versions.toml`) that compiles
the sources from where they live, so no Android, KSP or `core` submodule is needed:

```bash
cd dj/mixview-preview
MIXVIEW_OUT=/some/dir DJ_CACHE=/path/to/anacache ../../gradlew test --console=plain
```

It runs the 10 builder unit tests and, when the two env vars are set, `RenderMixViews`: real cached analyses, real
`DjTransitionPlanner`, PNGs at 390x260 and 800x400 (canvas) and a 390-wide card, dark and light, for a beat-matched mix
(mid-overlap and lead-in), a simple crossfade, a cut and the analysing state. `DjMixCard.kt` / `DjMixSheet.kt` are
compiled there against compile-only stubs (`src/stubs`) but not rendered.

## Known limits

* The sheet has not been run on a device or with real resources; only type-checked against stubs. On force-dark screens
  the canvas still follows `MaterialTheme` (not `rememberSurfaceDarkColors`), so a light theme opening the sheet from a
  force-dark screen gets light-theme lane colours on a dark card.
* Energy is normalised per track (1 = its loudest hop) and not loudness-matched, so two lanes are comparable in shape,
  not in level. The fader curve is drawn separately.
* Long titles are ellipsized in the card; the canvas measures its few labels with a small per-instance cache.
