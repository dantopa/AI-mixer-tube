package org.simpmusic.dj.planner

/** Markers the planner writes into `TransitionPlan.reason` that the app reads back (the look-ahead ranks pairs by them). */
object PlanTags {
    /** A structure hand-off: an instrumental of the outgoing laid over the incoming's (see `StructureHandoff`). */
    const val STRUCTURE = "STRUCTURE: "

    /** The structure hand-off's second tier: the outgoing's instrumental ends under the incoming's phrase 1, which sings. */
    const val SECOND_TIER = "second tier"
}
