package com.eagertolisten.app

object FeatureFlags {
    /**
     * Value-for-Value Nostr Wallet Connect (NIP-47) wallet setup in Settings.
     * Off for everyone (testers included) until the NWC client signs and encrypts requests
     * properly; see REVIEW.md R2.3. The current client can't complete a payment.
     */
    const val V4V_NWC: Boolean = false

    /**
     * Lightning "Boost" buttons (Now Playing, show pages, episode rows). Off until boosts can
     * actually be sent; Support Show (plain donate link) stays visible.
     */
    const val V4V_BOOST: Boolean = false
}
