package com.eagertolisten.app

object FeatureFlags {
    /**
     * Value-for-Value Nostr Wallet Connect (NIP-47).
     * Enabled in debug builds for testing; disabled in release builds
     * pending security audits and Play Store compliance review.
     */
    val V4V_NWC: Boolean = BuildConfig.DEBUG
}
