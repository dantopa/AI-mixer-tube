package org.simpmusic.dj.android.ai

/**
 * The upcoming tracks Claude put in order ("Order with AI", `DjEngine.orderWithAi`). The queue look-ahead leaves a next
 * track that is one of them where it is: the owner asked Claude for the order, the tempo look-ahead must not undo it.
 */
object ClaudeOrder {
    @Volatile var ids: Set<String> = emptySet()
}
