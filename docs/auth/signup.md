# Sign-up

Since 1.0.0 the SDK has no sign-up calls. Accounts are made on Aduki ID's
sign-up page, `https://id.aduki.pro/signup`, in a browser: the page asks for
the address, the name, the phone when the server needs one, the password and
the authenticator, and shows the backup codes. Apps keep only sign-in
([Sign-in](login.md), and the [suite sign-in](suite.md) between the apps).

## Opening the page

On Android, `pro.aduki.android.signin.SignUpTab` opens the page in a Custom
Tab over the app. The app's sign-in screen stays underneath, so closing the
tab returns to it.

```kotlin
val signUp = SignUpTab(back = "pro.aduki.mail:/signup")

// The "Create an account" button of the sign-in screen:
signUp.open(activity)

// onCreate and onNewIntent of the activity that shows the sign-in:
signUp.handle(intent)?.let { handle -> viewModel.prefill(handle) }
```

`back` is optional. It is a URI only this app handles (its own scheme, or an
https App Link), declared with an intent filter on the sign-in activity. Once
the account is made the page sends the person there with `?handle=` (the new
account's address). `handle(intent)` returns it only for that exact URI
(scheme, authority and path); it is a hint for the form, never a credential:
the person still signs in with the password and an authenticator code.

Without Android, build the address and open it however the platform does:

```kotlin
val url = Aduki.signUpUrl(handle = "ada", back = "pro.aduki.mail:/signup")
// https://id.aduki.pro/signup?handle=ada&return=pro.aduki.mail%3A%2Fsignup
val handle = Aduki.signedUp(returnedUri, back = "pro.aduki.mail:/signup")
```

`Signup.url(identity, handle, back)` and `Signup.handle(uri, back)` in the
`net` module do the same for another Aduki ID (`identity` is its API base,
with or without `/v1`).

The page honours `return` once the Aduki ID site supports it; until then it
ends on the site's own sign-in and the person closes the tab.

## The personal domain

`Aduki.requirements()` (`GET /v1/accounts` at Aduki ID) stays: its `domain`
(`aduki.me`) completes a handle typed without a domain on the sign-in form.
