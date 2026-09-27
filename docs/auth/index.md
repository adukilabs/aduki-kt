# Authentication & Session Architecture

The Hermes Android SDK provides a defense-in-depth authentication layer supporting interactive multi-factor sessions and headless machine API keys.

---

## 1. Authentication Schemes Matrix

The SDK strictly enforces separation between user identity sessions and service credentials:

| Property | Interactive User Session | Static Service API Key |
| :--- | :--- | :--- |
| **Primary Use Case** | Mobile Android applications with human users | Embedded kiosks, testing suites, CI automation |
| **Credentials** | Address + password + second factor, at Aduki ID | Cryptographic API key (`hm_live_...` / `hm_test_...`) |
| **HTTP Authorization** | `Authorization: Bearer <jwt>` | `Authorization: Key <apiKey>` |
| **gRPC Metadata** | `authorization: Bearer <jwt>` | `authorization: Key <apiKey>` |
| **Token Lifetime** | 10-minute access token (EdDSA JWT, audience `mail`), 30-day single-use refresh token | Indefinite until server revocation |
| **Rotation Strategy** | Automatic on `401` via Aduki ID `POST /v1/tokens` | Manual key replacement via client re-instantiation |
| **Local Persistence** | Android KeyStore envelope cipher (`AES-256-GCM`) | StrongBox / TEE sealed storage |
| **Revocation** | Aduki ID `DELETE /v1/sessions/{hex}` | Hermes Admin Console key deletion |

---

## 2. Session Lifecycle & State Machine

```mermaid
stateDiagram-v2
    [*] --> Unauthenticated
    Unauthenticated --> LoggingIn: HermesClient.login(handle, pass, code)
    LoggingIn --> ActiveSession: 200 OK (Tokens received)
    LoggingIn --> Unauthenticated: 401 Unauthorized / Error
    ActiveSession --> Refreshing: 401 / HermesClient.refresh()
    Refreshing --> ActiveSession: 200 OK (Tokens rotated)
    Refreshing --> Unauthenticated: 401 Unauthorized (Refresh expired)
    ActiveSession --> Unauthenticated: HermesClient.logout() (DELETE /v1/sessions/{hex})
```

---

## 3. Core Data Types

### `Tokens`
Container for active access and refresh credentials returned from authentication endpoints:

```kotlin
package pro.aduki.hermes.core.models

data class Tokens(
    val token: String = "",
    val refresh: String = "",
    val expires: String = "",
    val session: String = ""
)
```

- `token: String`: Short-lived JSON Web Token (JWT) passed in `Authorization: Bearer <jwt>`.
- `refresh: String`: Single-use refresh token for Aduki ID `POST /v1/tokens`.
- `expires: String`: Access-token lifetime in seconds.
- `session: String`: Aduki ID session hex, used to sign out.

### `Identity`
Resolved account retrieved via `GET /v1/user` (`scopes` and `tier` stay empty):

```kotlin
package pro.aduki.hermes.state.repository

data class Identity(
    val user: String = "",
    val tenant: String = "",
    val owner: Boolean = false,
    val scopes: List<String> = emptyList(),
    val tier: String = ""
)
```

- `user: String`: Unique user identifier hex string.
- `tenant: String`: Organization / tenant isolation hex string.
- `owner: Boolean`: True if the user possesses administrative privileges within the tenant.
- `scopes: List<String>`: List of authorized capability strings (e.g., `mail:read`, `mail:write`, `contacts:sync`).
- `tier: String`: Account service tier (`free`, `pro`, `enterprise`).

---

## 4. Hardware Security Guarantees

All authentication tokens and credentials adhere to the following zero-exposure runtime rules:

1. **Envelope Encryption**: Tokens stored locally are encrypted with an AES-256-GCM data encryption key sealed by the Android KeyStore hardware root of trust (StrongBox or TEE).
2. **In-Memory Zeroization**: Plaintext passwords, TOTP codes, and sensitive buffers are allocated in guarded memory segments and wiped immediately after transmission (`wipe(ByteArray)`).
3. **Automatic Cache Clear**: Invoking `logout()` revokes the Aduki ID session, wipes local KeyStore entries, and transitions reactive `Session` state flows to `null`.

