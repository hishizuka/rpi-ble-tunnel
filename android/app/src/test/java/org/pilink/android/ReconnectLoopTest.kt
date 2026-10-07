package org.pilink.android

import org.junit.Assert.*
import org.junit.Test

class ReconnectLoopTest {
    private class Fixture {
        val attempts = mutableListOf<Long>()
        var alarm: Pair<Long, Long>? = null
        val loop = ReconnectLoop({ token, delay -> alarm = token to delay }, { alarm = null }, { attempts.add(it) })
    }

    @Test fun lostLinkWaitsTwoMinutesAndRetriesUntilAvailable() {
        val f = Fixture()
        f.loop.start()
        val first = f.attempts.single()
        assertTrue(f.loop.failed(first))
        assertEquals(first to 120_000L, f.alarm)
        assertEquals(1, f.attempts.size)
        f.loop.retry(first)
        val second = f.attempts.last()
        assertNotEquals(first, second)
        assertTrue(f.loop.failed(second))
        f.loop.retry(second)
        assertEquals(3, f.attempts.size)
        assertTrue(f.loop.isCurrent(f.attempts.last()))
        assertNull(f.alarm)
    }

    @Test fun manualStopCancelsRetryEvenIfAlarmWasAlreadyDelivered() {
        val f = Fixture()
        f.loop.start()
        val token = f.attempts.single()
        f.loop.failed(token)
        f.loop.stop()
        f.loop.retry(token)
        assertFalse(f.loop.running)
        assertNull(f.alarm)
        assertEquals(1, f.attempts.size)
    }

    @Test fun duplicateTimersAndOldFailuresCannotInterruptNewConnection() {
        val f = Fixture()
        f.loop.start()
        val old = f.attempts.single()
        f.loop.failed(old)
        f.loop.retry(old)
        f.loop.retry(old)
        assertFalse(f.loop.failed(old))
        assertEquals(2, f.attempts.size)
        assertNull(f.alarm)
        assertTrue(f.loop.isCurrent(f.attempts.last()))
    }

    @Test fun newSelectionInvalidatesPreviousRetry() {
        val f = Fixture()
        f.loop.start()
        val old = f.attempts.single()
        f.loop.failed(old)
        f.loop.start()
        f.loop.retry(old)
        assertEquals(2, f.attempts.size)
        assertTrue(f.loop.isCurrent(f.attempts.last()))
    }
}
