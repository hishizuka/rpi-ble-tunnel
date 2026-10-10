package org.rpibletunnel.android

class ReconnectLoop(
    private val schedule: (Long, Long) -> Unit,
    private val cancel: () -> Unit,
    private val attempt: (Long) -> Unit
) {
    companion object { const val DELAY_MS = 120_000L }
    var running = false
        private set
    private var waiting = false
    private var generation = 0L

    fun start() {
        stop()
        running = true
        nextAttempt()
    }

    private fun nextAttempt() {
        waiting = false
        attempt(++generation)
    }

    fun isCurrent(token: Long) = running && !waiting && token == generation

    fun failed(token: Long): Boolean {
        if (!isCurrent(token)) return false
        waiting = true
        schedule(token, DELAY_MS)
        return true
    }

    fun retry(token: Long) {
        if (!running || !waiting || token != generation) return
        cancel()
        nextAttempt()
    }

    fun stop() {
        running = false
        waiting = false
        generation++
        cancel()
    }
}
