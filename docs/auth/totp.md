# Two-Factor Authentication (TOTP) Reference

> **Deprecated.** Sign-in moved to Aduki ID, which owns second factors: set up
> an authenticator in the Account Center and pass its code to
> `Aduki.login(code = …)`. `Aduki.totp` calls mail's
> `/v1/user/totp`, which is going away.

Aduki implements RFC 6238 Time-based One-Time Passwords (TOTP) (SHA-1 / SHA-256 with 30-second time drift windows).

---

## 1. Method Specification

```kotlin
package pro.aduki.sdk

suspend fun Aduki.totp(code: String): Boolean
```

### Parameters

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `code` | `String` | Yes | Exactly 6 numeric ASCII digits (`0-9`). |

### Return Value

- **Type**: `Boolean`
- **Description**: Returns `true` if the TOTP secret was verified and confirmed by the server; `false` if HTTP returned a non-success status.

### Throws & Validation

| Exception | Condition |
| :--- | :--- |
| `IllegalArgumentException` | Thrown immediately before network dispatch if `code.length != 6` or if any character is non-numeric (`code.any { !it.isDigit() }`). |
| `AdukiException.Unauthorized` | HTTP `401 Unauthorized` — Active JWT session has expired or been revoked. |
| `AdukiException.Network` | Network connectivity error or server timeout. |

---

## 2. Network Protocol Specification

### HTTP Request

```http
PATCH /v1/user/totp HTTP/1.1
Host: mail.aduki.pro
Authorization: Bearer eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9...
Content-Type: application/json; charset=utf-8
Accept: application/json

"123456"
```

> [!NOTE]
> The request body is a formatted JSON string literal (`"123456"`), not a JSON object, adhering to Aduki REST API specifications.

### HTTP Response (200 OK / 204 No Content)

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{
  "status": "enabled",
  "confirmed": true
}
```

---

## 3. Two-Step Verification Flow

```mermaid
sequenceDiagram
    autonumber
    participant App as Android UI
    participant Client as Aduki
    participant Server as Aduki REST

    Note over App,Server: Step 1: Interactive Login with 2FA Challenge
    App->>Client: Aduki.login(email, pass, totp = null)
    Client->>Server: POST /v1/auth/login { email, password }
    Server-->>Client: 401 Unauthorized ("TOTP required")
    Client-->>App: throws AdukiException.Unauthorized

    Note over App,Server: Step 2: User Inputs 6-digit Code
    App->>Client: Aduki.login(email, pass, totp = "492810")
    Client->>Server: POST /v1/auth/login { email, password, totp: "492810" }
    Server-->>Client: 200 OK { token, refresh, expires }
    Client-->>App: returns active Aduki

    Note over App,Server: Step 3: Account Security Management (In-App)
    App->>Client: client.totp("849201")
    Client->>Server: PATCH /v1/user/totp "849201"
    Server-->>Client: 200 OK
    Client-->>App: returns true
```

---

## 4. UI Implementation Example

```kotlin
@Composable
fun TotpConfirmationDialog(
    client: Aduki,
    onConfirmed: () -> Unit,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirm 2FA") },
        text = {
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) code = it },
                    label = { Text("6-Digit Code") },
                    isError = error != null,
                    singleLine = true
                )
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (code.length != 6) {
                        error = "Code must be 6 digits"
                        return@Button
                    }
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            val ok = client.totp(code)
                            if (ok) onConfirmed() else error = "Verification failed"
                        } catch (e: Exception) {
                            error = e.message ?: "Network error"
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading && code.length == 6
            ) {
                Text(if (loading) "Verifying..." else "Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) {
                Text("Cancel")
            }
        }
    )
}
```

