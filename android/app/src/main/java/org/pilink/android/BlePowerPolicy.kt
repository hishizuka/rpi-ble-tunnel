package org.pilink.android

// Keep priority requests separate from wake-lock ownership and Bluetooth I/O.
class BlePowerPolicy(private val request: (lowPower: Boolean) -> Boolean) {
    private var ready = false
    private var enabled = false
    private var busy = true
    private var applied: Boolean? = null

    fun update(enabled: Boolean, busy: Boolean) {
        this.enabled = enabled
        this.busy = busy
        apply()
    }

    fun connected() {
        ready = true
        applied = null
        apply()
    }

    fun disconnected() {
        ready = false
        applied = null
    }

    private fun apply() {
        val lowPower = enabled && !busy
        if (!ready || applied == lowPower) return
        // A rejected request must not prevent a later retry or stop the tunnel.
        applied = if (request(lowPower)) lowPower else null
    }
}
