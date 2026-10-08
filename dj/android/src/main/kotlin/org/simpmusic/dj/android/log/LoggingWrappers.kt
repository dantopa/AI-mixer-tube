package org.simpmusic.dj.android.log

import org.simpmusic.dj.android.render.RenderRequest
import org.simpmusic.dj.android.render.RenderedWindow
import org.simpmusic.dj.android.render.TransitionWindowRenderer
import org.simpmusic.dj.model.Confident
import org.simpmusic.dj.model.DeckPlan
import org.simpmusic.dj.model.DjSettings
import org.simpmusic.dj.model.TrackAnalysis
import org.simpmusic.dj.model.TransitionPlan
import org.simpmusic.dj.model.PlanConstraints
import org.simpmusic.dj.model.TransitionPlanner

private fun conf(c: Confident<*>?): String = if (c == null) "none" else "c%.2f".format(c.confidence)

/** One line of what the planner is given about a track. */
internal fun describe(a: TrackAnalysis?): String =
    if (a == null) {
        "null"
    } else {
        "${a.videoId}[bpm=${a.bpm?.let { "%.1f(%s)".format(it.value, conf(it)) }} key=${a.key?.let { "${it.value.camelot()}(${conf(it)})" }} " +
            "beats=${a.beatTimesMs?.let { "${it.value.size}(${conf(it)})" }} downbeats=${a.downbeatBeatIndices?.let { "${it.value.size}(${conf(it)})" }} " +
            "bpb=${a.beatsPerBar} sections=${conf(a.sections)} dur=${a.durationMs}ms analyzer=${a.analyzerId}]"
    }

private fun rates(d: DeckPlan): String = "rate ${"%.3f".format(d.rate.keys.first().value)}->${"%.3f".format(d.rate.keys.last().value)} pitch ${d.pitchSemitones.keys.last().value}st vol->${"%.2f".format(d.volume.keys.last().value)}"

/** Logs every planning call with its inputs and its result (tag `planner`). */
class LoggingPlanner(private val delegate: TransitionPlanner) : TransitionPlanner {
    override fun plan(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings): TransitionPlan = logged(from, to, settings, null)

    override fun plan(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings, constraints: PlanConstraints): TransitionPlan =
        logged(from, to, settings, constraints)

    private fun logged(from: TrackAnalysis?, to: TrackAnalysis?, settings: DjSettings, constraints: PlanConstraints?): TransitionPlan {
        val t0 = System.nanoTime()
        DjLog.d(TAG, "plan inputs: from=${describe(from)} to=${describe(to)} settings[bars=${settings.overlapBars} bend=${settings.maxTempoBend} keyShift=${settings.allowKeyShift}/${settings.maxPitchShift} bassSwap=${settings.bassSwap} minConf=${settings.minConfidence} mixPoint=${settings.mixPoint} earliestExit=${constraints?.earliestExitMs} perfectFirst=${constraints?.preferPerfect}]")
        try {
            val p = if (constraints == null) delegate.plan(from, to, settings) else delegate.plan(from, to, settings, constraints)
            DjLog.i(
                TAG,
                "plan ${p.fromId}->${p.toId} = ${p.kind} conf=${"%.2f".format(p.confidence)} in ${(System.nanoTime() - t0) / 1_000_000} ms: exit=${p.exitPointMs} entry=${p.entryPointMs} " +
                    "overlap=${p.overlapMs} preRoll=${p.preRollMs} mixBpm=${p.mixBpm?.let { "%.1f".format(it) }} echo=${p.echoOut?.let { "${it.delayMs.toInt()}ms fb=${it.feedback} tail=${it.tailMs}" }} | out ${rates(p.outgoing)} | in ${rates(p.incoming)} | reason: ${p.reason}",
            )
            return p
        } catch (e: Throwable) {
            DjLog.e(TAG, "plan FAILED after ${(System.nanoTime() - t0) / 1_000_000} ms", e)
            throw e
        }
    }

    private companion object {
        const val TAG = "planner"
    }
}

/** Logs every render with its inputs and the size of what it produced (tag `render`). */
class LoggingRenderer(private val delegate: TransitionWindowRenderer) : TransitionWindowRenderer {
    override fun render(request: RenderRequest): RenderedWindow {
        val t0 = System.nanoTime()
        DjLog.d(
            TAG,
            "render ${request.plan.fromId}->${request.plan.toId} ${request.plan.kind}: window [${request.startRelMs},${request.endRelMs}] ms plan time, " +
                "outgoing tail src [${request.outgoingTail.startMs},${request.outgoingTail.endMs}] (${request.outgoingTail.frames} frames), " +
                "incoming head src [${request.incomingHead.startMs},${request.incomingHead.endMs}] (${request.incomingHead.frames} frames), ${request.sampleRate} Hz",
        )
        try {
            val w = delegate.render(request)
            DjLog.i(TAG, "rendered ${request.plan.fromId}->${request.plan.toId} in ${(System.nanoTime() - t0) / 1_000_000} ms: ${w.frames} frames (${w.durationMs} ms) ${w.file.length()} B ${w.file.name}")
            return w
        } catch (e: Throwable) {
            DjLog.e(TAG, "render FAILED after ${(System.nanoTime() - t0) / 1_000_000} ms", e)
            throw e
        }
    }

    private companion object {
        const val TAG = "render"
    }
}
