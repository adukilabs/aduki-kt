package pro.aduki.core.models

import java.time.Instant

/**
 * A personal account's package at Aduki ID (`GET /v1/subscription`). An
 * account without a subscription is on `free` with [hex] null. [limits] are
 * the effective limits (the package applied plus add-ons; null is
 * unlimited; a switch is 1 or 0); [applies] is the package in force now,
 * which differs from [plan] while a chosen package waits for its first
 * payment.
 */
data class Subscription(
    val hex: String?,
    val plan: String,
    val state: String,
    val applies: String? = null,
    val seats: Int? = null,
    val renews: Instant? = null,
    val limits: Map<String, Long?> = emptyMap(),
    /** The limits of [applies] alone. */
    val packageLimits: Map<String, Long?> = emptyMap(),
    /** A smaller package chosen for the next period, or null. */
    val next: String? = null,
    val nextFrom: Instant? = null
)

/** An add-on purchase (`GET /v1/addons`): [state] `active` or `cancelled` (its units count until [ends]). */
data class AddOnPurchase(
    val purchase: String,
    val addon: String,
    /** The limit key it raises (`ai.audio.minutes`, `storage.bytes`, ...). */
    val key: String,
    val unit: Long,
    val quantity: Int,
    val state: String,
    val ends: Instant? = null,
    val created: Instant? = null,
    /** A monthly allowance (true) or a one-off pack. */
    val recurring: Boolean = true,
    /** The price in minor units and its currency; null when billed with the package. */
    val amount: Long? = null,
    val currency: String? = null
)

/** An add-on the account's package takes now (`GET /v1/addons/catalogue`), with prices in minor units per currency. */
data class AddOn(
    val addon: String,
    val key: String,
    val unit: Long,
    val recurring: Boolean,
    /** A pack's validity after payment in days; null for a monthly add-on. */
    val days: Int?,
    val label: String,
    val description: String,
    val prices: Map<String, Long>
)

/** The account's month against its plan (`GET /v1/usage`): [period] `YYYY-MM`, the effective limits and what was used. */
data class Usage(
    val period: String,
    val plan: String?,
    val state: String?,
    val limits: Map<String, Long?>,
    /** What the services measured this month, by key. */
    val used: Map<String, Long>,
    /** One-off minutes still valid, by key. */
    val credits: Map<String, Long>
)

/** A payment of the account's own (`GET /v1/portal/payments`): amounts in minor units. */
data class Payment(
    val id: String,
    val invoice: String,
    val provider: String,
    /** `card`, `mpesa`, `bank`, `ussd`, `wallet`. */
    val channel: String,
    val amount: Long,
    val currency: String,
    /** `pending`, `success`, `failed`, `refunded`. */
    val status: String,
    val initiated: Instant?,
    val settled: Instant?
)

/** An invoice of the account's own (`GET /v1/portal/invoices`): amounts in minor units. */
data class Invoice(
    val id: String,
    val number: String,
    /** `pending`, `overdue`, `paid`, `void`, ... */
    val status: String,
    val currency: String,
    val total: Long,
    val owing: Long,
    val due: Instant?,
    val issued: Instant?,
    val paid: Instant?
)

/** One page and the cursor of the next, null on the last. */
data class BillingPage<T>(val items: List<T>, val next: String?)
