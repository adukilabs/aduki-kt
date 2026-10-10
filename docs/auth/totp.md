# Two-factor TOTP (deprecated)

> **Deprecated.** Sign-in moved to Aduki ID, which owns second factors: set up
> an authenticator in the Account Center and pass its code to
> `Aduki.login(code = ...)`. `Aduki.totp` calls mail's `/v1/user/totp`, which
> is going away.

```kotlin
@Deprecated("Second factors are managed in Aduki ID's Account Center.")
suspend fun Aduki.totp(code: String): Boolean
```

| Parameter | Type | Description |
| :--- | :--- | :--- |
| `code` | `String` | exactly 6 digits; anything else throws `IllegalArgumentException` before any request |

Sends `PATCH /v1/user/totp` with the code as a JSON string (`"123456"`) and
returns whether the response was successful. A `401` throws
`AdukiException.Unauthorized`.
