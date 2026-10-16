package org.simpmusic.dj.android.log

/**
 * Lets a line that says the same as last time through at most once per [everyMs]: the DJ log was mostly the scheduler's
 * "already analysed" and the adapter's 5 s poll repeating unchanged (a 15 MB export for a few mixes). A line whose [key]
 * changed goes through at once, so every change of state is still logged when it happens.
 */
class RepeatFilter(private val everyMs: Long, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val last = LinkedHashMap<String, Pair<String, Long>>()

    /** [slot] says what kind of line it is (one per track and request, say); [key] what it says. */
    fun shouldLog(slot: String, key: String): Boolean = synchronized(last) {
        val now = clock()
        val prev = last[slot]
        if (prev != null && prev.first == key && now - prev.second < everyMs) return false
        last[slot] = key to now
        if (last.size > MAX_SLOTS) last.remove(last.keys.first())
        true
    }

    companion object {
        private const val MAX_SLOTS = 256

        /** [message] with its numbers taken out: a position or a countdown changes every poll without changing the state. */
        fun withoutNumbers(message: String): String = message.replace(Regex("-?\\d+"), "#")
    }
}
