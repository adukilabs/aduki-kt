# Billing

`Aduki.billing` reads the signed-in personal account's billing at Aduki ID
(payments moved there from Space): its package, add-ons, this month's usage, and
its own payments and invoices. It only reads; buying an add-on, changing the
package and paying happen on the web.

```kotlin
val sub = client.billing.subscription()     // plan, applies, state, renews, limits
val usage = client.billing.usage()          // this month: limits, used, credits
val receipts = client.billing.payments(limit = 20).items
```

| Call | Route |
| --- | --- |
| `subscription()` | `GET /v1/subscription`: the package chosen (`plan`), the one in force (`applies`), effective `limits` (null: unlimited; a switch is 1 or 0) |
| `addons()` | `GET /v1/addons`: the purchases |
| `addonCatalogue()` | `GET /v1/addons/catalogue`: what the package takes, prices per currency in minor units |
| `usage(period)` | `GET /v1/usage?period=YYYY-MM` |
| `payments(limit, after)`, `invoices(limit, after)` | `GET /v1/portal/payments`, `/invoices` with a portal session (`POST /v1/billing/session`) opened on first use and again when it runs out |

The tokens are of audience `id`, from the client's own session (renewed under the
session lock, the rotated refresh token saved). A member of a workspace has no
package of their own: those reads fail with `AdukiException.Api` 404. Amounts are
in minor units (cents).
