package com.eagertolisten.app.data.v4v

import com.eagertolisten.app.data.model.ValueRecipient

data class RecipientSplit(
    val recipient: ValueRecipient,
    val amountSats: Long,
    val amountMsats: Long
)

object V4VSplitMath {

    /**
     * Calculates proportional split amounts across recipients for a given total satoshi amount.
     * Guaranteed invariant: The sum of all allocated sats exactly equals [totalSats].
     * Any integer division remainder is assigned to the primary recipient (first non-fee recipient).
     */
    fun calculateSplits(totalSats: Long, recipients: List<ValueRecipient>): List<RecipientSplit> {
        if (totalSats <= 0L || recipients.isEmpty()) {
            return emptyList()
        }

        val validRecipients = recipients.filter { it.split > 0 }
        if (validRecipients.isEmpty()) {
            return emptyList()
        }

        val totalSplit = validRecipients.sumOf { it.split.toLong() }
        if (totalSplit <= 0L) {
            return emptyList()
        }

        // Initial proportional allocation (floor division)
        val splits = validRecipients.map { r ->
            val satShare = (totalSats * r.split.toLong()) / totalSplit
            RecipientSplit(
                recipient = r,
                amountSats = satShare,
                amountMsats = satShare * 1000L
            )
        }.toMutableList()

        val allocatedSum = splits.sumOf { it.amountSats }
        val remainder = totalSats - allocatedSum

        if (remainder > 0) {
            // Assign remainder to the primary recipient (first non-fee, or first overall)
            val primaryIndex = splits.indexOfFirst { !it.recipient.fee }.let { if (it >= 0) it else 0 }
            val primary = splits[primaryIndex]
            val newAmount = primary.amountSats + remainder
            splits[primaryIndex] = primary.copy(
                amountSats = newAmount,
                amountMsats = newAmount * 1000L
            )
        }

        return splits
    }
}
