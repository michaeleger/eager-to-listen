package com.eagertolisten.app.data.v4v

import com.eagertolisten.app.data.model.ValueRecipient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V4VSplitMathTest {

    @Test
    fun testSingleRecipient_receivesFullAmount() {
        val recipient = ValueRecipient(name = "Host", address = "03abc...", split = 100)
        val splits = V4VSplitMath.calculateSplits(1000L, listOf(recipient))

        assertEquals(1, splits.size)
        assertEquals(1000L, splits[0].amountSats)
        assertEquals(1_000_000L, splits[0].amountMsats)
        assertEquals(recipient, splits[0].recipient)
    }

    @Test
    fun testEvenSplits_dividedEqually() {
        val r1 = ValueRecipient(name = "Host A", address = "03a...", split = 50)
        val r2 = ValueRecipient(name = "Host B", address = "03b...", split = 50)
        val splits = V4VSplitMath.calculateSplits(1000L, listOf(r1, r2))

        assertEquals(2, splits.size)
        assertEquals(500L, splits[0].amountSats)
        assertEquals(500L, splits[1].amountSats)
        assertEquals(1000L, splits.sumOf { it.amountSats })
    }

    @Test
    fun testRemainderAssignedToPrimaryNonFeeRecipient() {
        // 101 sats split 90% Host, 10% Fee
        val host = ValueRecipient(name = "Host", address = "03host...", split = 90, fee = false)
        val hostingFee = ValueRecipient(name = "Hosting", address = "03fee...", split = 10, fee = true)

        val splits = V4VSplitMath.calculateSplits(101L, listOf(host, hostingFee))

        assertEquals(2, splits.size)
        // 101 * 90 / 100 = 90, 101 * 10 / 100 = 10. Remainder = 1 -> assigned to host.
        assertEquals(91L, splits[0].amountSats)
        assertEquals(10L, splits[1].amountSats)
        assertEquals(101L, splits.sumOf { it.amountSats })
    }

    @Test
    fun testThreeWaySplitWithRounding() {
        // 100 sats split 1:1:1 across 3 hosts
        val r1 = ValueRecipient(name = "H1", address = "03h1...", split = 1)
        val r2 = ValueRecipient(name = "H2", address = "03h2...", split = 1)
        val r3 = ValueRecipient(name = "H3", address = "03h3...", split = 1)

        val splits = V4VSplitMath.calculateSplits(100L, listOf(r1, r2, r3))

        assertEquals(3, splits.size)
        // 33 each = 99, primary gets +1 = 34
        assertEquals(34L, splits[0].amountSats)
        assertEquals(33L, splits[1].amountSats)
        assertEquals(33L, splits[2].amountSats)
        assertEquals(100L, splits.sumOf { it.amountSats })
    }

    @Test
    fun testInvariant_sumAlwaysEqualsTotalAcrossVariousAmounts() {
        val recipients = listOf(
            ValueRecipient(name = "Host", address = "03h...", split = 70),
            ValueRecipient(name = "Producer", address = "03p...", split = 20),
            ValueRecipient(name = "Music", address = "03m...", split = 10)
        )

        for (amount in listOf(1L, 2L, 3L, 7L, 11L, 99L, 100L, 543L, 1000L, 21000L)) {
            val splits = V4VSplitMath.calculateSplits(amount, recipients)
            val sum = splits.sumOf { it.amountSats }
            assertEquals("Invariant failed for amount $amount", amount, sum)
        }
    }

    @Test
    fun testEmptyOrInvalidRecipients_returnsEmpty() {
        assertTrue(V4VSplitMath.calculateSplits(1000L, emptyList()).isEmpty())
        assertTrue(V4VSplitMath.calculateSplits(0L, listOf(ValueRecipient(name = "H", address = "03...", split = 100))).isEmpty())
        assertTrue(V4VSplitMath.calculateSplits(-50L, listOf(ValueRecipient(name = "H", address = "03...", split = 100))).isEmpty())
    }
}
