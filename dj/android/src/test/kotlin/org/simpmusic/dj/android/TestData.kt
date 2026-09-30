package org.simpmusic.dj.android

import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.DeckPlan
import org.simpmusic.dj.model.Ease
import org.simpmusic.dj.model.Keyframe
import org.simpmusic.dj.model.ParamCurve
import org.simpmusic.dj.model.PlanKind
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TransitionPlan

fun fakeAnalysis(
    id: String,
    analyzerId: String = "fake-1",
    bpm: Float = 120f,
    durationMs: Long = 240_000,
): TrackAnalysis {
    val beatMs = (60_000f / bpm).toInt()
    val beats = (0 until (durationMs / beatMs).toInt()).map { it * beatMs }
    return TrackAnalysis(
        videoId = id,
        analyzerId = analyzerId,
        analyzedAtEpochMs = 1L,
        durationMs = durationMs,
        bpm = Confident(bpm, 0.9f),
        beatTimesMs = Confident(beats, 0.9f),
        downbeatBeatIndices = Confident(beats.indices.filter { it % 4 == 0 }, 0.8f),
        beatsPerBar = 4,
        key = null,
        energyHopMs = 100,
        energy = List(20) { 0.5f },
        lowBandEnergy = List(20) { 0.5f },
        sections = null,
        vocals = null,
        loudnessDb = -14f,
    )
}

/**
 * A representative BEAT_MATCHED plan: outgoing exits at [exit] (T0), the overlap is [overlapMs] long,
 * the outgoing rate glides to [outRate] while the incoming starts at [inRate] and ramps back to 1.0
 * by [rampBackMs]. Lanes have a [preRollMs] head start.
 */
fun fakePlan(
    from: String = "A",
    to: String = "B",
    exit: Long = 180_000,
    entry: Long = 16_000,
    overlapMs: Long = 16_000,
    preRollMs: Long = 4_000,
    outRate: Float = 1.04f,
    inRate: Float = 1.0f,
    rampBackMs: Long = 20_000,
    incomingEndVolume: Float = 1f,
): TransitionPlan =
    TransitionPlan(
        fromId = from,
        toId = to,
        kind = PlanKind.BEAT_MATCHED,
        exitPointMs = exit,
        entryPointMs = entry,
        overlapMs = overlapMs,
        outgoing =
            DeckPlan(
                rate = ParamCurve(listOf(Keyframe(-preRollMs, 1f), Keyframe(0, outRate, Ease.SMOOTHSTEP))),
                volume = ParamCurve(listOf(Keyframe(0, 1f), Keyframe(overlapMs, 0f, Ease.SMOOTHSTEP))),
                lowCutHz = ParamCurve(listOf(Keyframe(0, 20f), Keyframe(overlapMs / 2, 250f, Ease.EXPONENTIAL))),
            ),
        incoming =
            DeckPlan(
                rate = ParamCurve(listOf(Keyframe(0, inRate), Keyframe(rampBackMs, 1f, Ease.SMOOTHSTEP))),
                volume = ParamCurve(listOf(Keyframe(0, 0f), Keyframe(overlapMs, incomingEndVolume, Ease.SMOOTHSTEP))),
            ),
        confidence = 0.9f,
        reason = "test plan",
        mixBpm = 124f,
    )
