package com.timewarpscan.nativecamera.core.iap

/**
 * In-app purchase product IDs.
 *
 * Placeholder values — replace with your actual Google Play Console product IDs.
 * These IDs must match exactly what's configured in the Play Console.
 */
object IAPConfig {

    // --- One-time purchases (in-app products) ---
    const val PRODUCT_PREMIUM = "premium_upgrade"
    const val PRODUCT_REMOVE_ADS = "remove_ads"

    // --- Subscriptions ---
    const val SUB_MONTHLY = "sub_monthly_premium"
    const val SUB_QUARTERLY = "sub_quarterly_premium"
    const val SUB_YEARLY = "sub_yearly_premium"

    /** All one-time product IDs to query. */
    val IN_APP_PRODUCTS = listOf(PRODUCT_PREMIUM, PRODUCT_REMOVE_ADS)

    /** All subscription IDs to query. */
    val SUBSCRIPTION_PRODUCTS = listOf(SUB_MONTHLY, SUB_QUARTERLY, SUB_YEARLY)

    /** Product IDs that grant premium status (removes ads, unlocks features). */
    val PREMIUM_PRODUCT_IDS = setOf(PRODUCT_PREMIUM, PRODUCT_REMOVE_ADS, SUB_MONTHLY, SUB_QUARTERLY, SUB_YEARLY)
}
