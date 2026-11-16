package pro.aduki.sdk

import pro.aduki.core.models.AddOn
import pro.aduki.core.models.AddOnPurchase
import pro.aduki.core.models.BillingPage
import pro.aduki.core.models.Invoice
import pro.aduki.core.models.Payment
import pro.aduki.core.models.Subscription
import pro.aduki.core.models.Usage
import pro.aduki.net.http.Billing as NetBilling

/**
 * The signed-in personal account's billing at Aduki ID, read only
 * (`Aduki.billing`): its package, add-ons, the month's usage, and its own
 * payments and invoices. Buying, paying and changing the package happen on
 * the web. A workspace member has no package of their own: those reads
 * fail with `AdukiException.Api` 404.
 *
 * Every call runs on `Dispatchers.IO` and fails with `AdukiException`.
 */
class Billing internal constructor(private val net: () -> NetBilling) {

    /** The HTTP client of this API. */
    val api: NetBilling get() = net()

    /** The package, its effective limits and what applies now. */
    suspend fun subscription(): Subscription = guard { api.subscription() }

    /** The add-on purchases. */
    suspend fun addons(): List<AddOnPurchase> = guard { api.addons() }

    /** The add-ons the package takes now, with prices. */
    suspend fun addonCatalogue(): List<AddOn> = guard { api.addonCatalogue() }

    /** The month against the plan ([period] `YYYY-MM`, this month by default). */
    suspend fun usage(period: String? = null): Usage = guard { api.usage(period) }

    /** The account's payments, newest first. */
    suspend fun payments(limit: Int? = null, after: String? = null): BillingPage<Payment> = guard { api.payments(limit, after) }

    /** The account's invoices, newest first. */
    suspend fun invoices(limit: Int? = null, after: String? = null): BillingPage<Invoice> = guard { api.invoices(limit, after) }
}
