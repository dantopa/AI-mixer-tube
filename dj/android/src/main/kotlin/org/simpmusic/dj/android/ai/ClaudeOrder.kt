package org.simpmusic.dj.android.ai

/**
 * The upcoming tracks Claude put in order ("Order list", by hand or automatically: `DjEngine.orderWithAi`,
 * `maybeAutoOrder`). The queue look-ahead leaves a next track that is one of them where it is: Claude chose the order,
 * the tempo look-ahead must not undo it. The automatic order counts the tracks NOT in here to know when the radio brought
 * enough new ones to order again.
 */
object ClaudeOrder {
    private const val MEMORY = 60

    @Volatile var ids: Set<String> = emptySet()
        private set

    @Synchronized
    fun add(order: List<String>) {
        val s = LinkedHashSet(ids)
        order.forEach { s.remove(it); s.add(it) }
        while (s.size > MEMORY) s.remove(s.first())
        ids = s
    }
}
