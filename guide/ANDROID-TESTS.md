# Android tests on a real phone

For the owner, after Aduki ID (and Space) are hosted. The lead folds this file into `REQ/tests.md`. What the build machine could and could not check is in section 6; everything marked "phone" below is unverified until you run it.

## 1. What to install

Needs: a phone with Android 8 or later (API 26+), Google Play services (for Credential Manager on Android 13 and earlier; Android 14 has it in the OS), a screen lock (the Keystore and passkeys need one), USB debugging on, a PC with JDK 17, the Android SDK platform 34 and `adb`.

1. On the PC, in the repository root:

   ```
   export JAVA_HOME=/path/to/jdk17 ANDROID_HOME=/path/to/android-sdk
   ./gradlew :sample:assembleDebug -Paduki.android=true \
       -PadukiIssuer=https://id.aduki.pro -PadukiClient=<OIDC client id> -PadukiScheme=pro.aduki.sample
   adb install -r sample/build/outputs/apk/debug/sample-debug.apk
   ```

   `-Paduki.android=true` (or `ADUKI_ANDROID=1`) adds the Android modules to the build; without it only the JVM modules build. The first build downloads Gradle 9.5.1, AGP 8.13.2 and the Android build tools.

2. Register the OIDC client at Aduki ID before step 3 of section 3: public client, redirect URI exactly `pro.aduki.sample:/callback` (the scheme you passed as `-PadukiScheme`), PKCE S256, scopes `openid profile offline_access`. The Android package `pro.aduki.sample` needs no App Link for the custom scheme. Passkeys need a Digital Asset Links file at `https://id.aduki.pro/.well-known/assetlinks.json` that lists package `pro.aduki.sample` and the SHA-256 of the debug signing certificate (`keytool -list -v -keystore ~/.android/debug.keystore -storepass android`, the public fingerprint, not a secret).

3. Instrumented tests (Keystore behaviour on the real chip), phone attached:

   ```
   ./gradlew :android:connectedDebugAndroidTest -Paduki.android=true
   ```

## 2. The screen

The sample has eight buttons (their numbers are used below) and a log. The log prints handles, hex ids, HTTP statuses and durations, never tokens or keys. Select and copy the log to paste it back.

## 3. Manual checks

| # | Step | Expected | Paste back |
|---|---|---|---|
| 1 | Button 1 "Key info" | `alg=ES256`, `extractable=false`, `level=trusted_environment` or `strongbox` on a phone with secure hardware (`software` means the phone has none: report the model), a 43-character `jkt` | the log line |
| 1b | Close the app (swipe away), force stop in Settings, reopen, button 1 | the same `jkt` as before | both `jkt` values |
| 2 | Button 2 "Sign in with Aduki" | A Custom Tab (Chrome) opens `id.aduki.pro/oauth/authorize`; sign in with password and authenticator code (or a passkey); the tab closes and the app returns; the log says `signed in as <handle>` with `refresh=true` | the log; a screenshot of the tab address bar showing `id.aduki.pro` |
| 2b | Start sign-in, then in the tab press back | the app stays usable, no crash, no account added | what happened |
| 2c | Start sign-in, copy the redirect from `adb logcat` is not needed: instead open `adb shell am start -a android.intent.action.VIEW -d "pro.aduki.sample:/callback?code=x&state=y"` | the log reports `FAILED Auth: No sign-in in flight for this redirect`; nothing is added | the log line |
| 3 | Button 3 "Accounts and switcher" | one row per linked account, personal first, `[active]` on the signed-in one, `[lock]` on accounts that need their own credential, `[sign in again]` when the session ended | the log |
| 3b | Settings, Passwords and accounts: an "Aduki" entry per account is listed | the same handles | a screenshot |
| 4 | Link a second account in the Aduki web Account Center (a member account of a tenant with `links = allow`), then button 4 "Refresh center" | `added=[<hex>]`, and button 3 lists it | the log |
| 5 | In the tenant's admin, set `links = deny` and note the time; press button 4 | `removed=[<hex of the member>]`; button 3 no longer lists it; Settings no longer shows the account. Report the time between the admin action and the account disappearing in the app. The target is 1 s end to end when the app is told by the bus event; the sample has no push channel yet, so press button 4 at once and report the duration printed in the log (`center: ... (N ms)`) | the log lines and the seconds from the admin click |
| 6 | Button 5 "Passkey: register" (needs a step-up first: sign in with a fresh authenticator code, or run button 6 after registering by an earlier passkey) | the system passkey sheet appears, you confirm with your screen lock or fingerprint; the log says `registered passkey=<hex>` | the log; which credential provider showed (Google Password Manager, other) |
| 7 | Button 6 "Passkey: step-up" | the sheet appears, then `stepped=<unix seconds>` | the log |
| 8 | Button 7 "Passkey: sign in" | the sheet lists the passkey, then `signed in, session=<hex>` | the log |
| 8b | In every sheet press cancel | the log says `FAILED Cancelled` and no request reaches the server beyond the begin call | the log |
| 9 | Button 8 "DPoP call to Space" (needs a Space tenant with the `space` app and a DPoP-capable test account) | `HTTP 200, bound jkt=<same jkt as button 1>` | the log; on the Space side the audit entry |
| 10 | Switch the phone to airplane mode, button 8 | a network failure is reported, not a crash | the log |

Not applicable on a phone: the device authorization grant (RFC 8628) is for devices without a browser; its client is in `Oidc` and is covered by the JVM tests.

### ES256 center unlock (steps 11 to 13; needs Aduki ID with PR 35, the `id` ES256 device credentials)

The sample code for buttons 9 and 10 and `Accounts.registerDevice` / `unlockWithDevice` is UNVERIFIED: it was written without an Android SDK and has never been compiled. If `./gradlew :sample:installDebug -Paduki.android=true` fails to compile, paste the compiler errors back first; that is the first result.

| # | Step | Expected | Paste back |
|---|---|---|---|
| 11 | Signed in (step 2), the account in an Account Center. Type a fresh authenticator code in the field, press button 9 "register ES256 credential" | `device register: credential=<hex> alg=ES256 point=65 bytes level=...` (`level` as in step 1; `software` means no secure hardware) | the log |
| 11b | Press button 9 again with the same (already used) code | a failure line, not a crash (a TOTP code is single-use or the server answers 401 `auth.invalid`) | the log |
| 12 | Press button 10 "unlock with it" | `device unlock: unlocked=[<hex>...]`, one hex per account whose `unlock` is `center`; button 3 then lists them without `[sign in again]`; `adb logcat` has no token | the log, a screenshot of button 3 |
| 13 | In the Aduki web Account Center revoke this device's credential, press button 10 | `FAILED Network: ... 404`; pressing 10 again says `No device credential registered` (the id was forgotten) | both log lines |

If step 12 gives `FAILED Unauthorized: auth.invalid`, report it with the phone model and Android version: the cause is a signature form or key encoding problem and the server answers the same 401 for all causes, so check the Aduki ID audit log for the credential (`credential.registered` with `alg: ES256`) and tell the lead. Device and Keystore keys here are created without per-use user authentication; a biometric-bound key needs a BiometricPrompt that is not wired yet.

## 4. Keystore survival (two phases, automated, phone attached)

```
./gradlew :android:installDebug :android:installDebugAndroidTest -Paduki.android=true
adb shell am instrument -w -e class pro.aduki.android.KeystoreInstrumentedTest#survivalPhase1CreateKeysAndData \
    pro.aduki.android.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop pro.aduki.android.test
adb shell am instrument -w -e class pro.aduki.android.KeystoreInstrumentedTest#survivalPhase2AfterForceStopKeysStillWork \
    pro.aduki.android.test/androidx.test.runner.AndroidJUnitRunner
```

Expected: both print `OK (1 test)`. Phase 2 skips (reported as OK with an assumption failure) when phase 1 did not run. Also reboot the phone between the phases once and repeat phase 2 (needs the phone unlocked once after the reboot).

## 5. What to paste back

The log of each numbered check (including 11 to 13 and any compiler error), the `adb shell getprop ro.product.model` and `ro.build.version.release`, the instrumented test result (`android/build/reports/androidTests/connected/debug/index.html` summary), and any crash from `adb logcat -d | grep -E "AndroidRuntime|AdukiKeystore|AdukiAccounts"` (no tokens are logged by the SDK; check before pasting).

## 6. What was and was not verified on the build machine

Verified with Robolectric (JVM, `./gradlew test -Paduki.android=true`): account storage and reconcile logic, sealing of stored tokens, the switcher state, removal of a `links = deny` account (logic, under 1 s with a fake server), the authenticator's token hand-out and refresh, the Custom Tabs launch intent, redirect matching and replay/expiry/stray-redirect refusal, the manifest wiring (authenticator service, redirect activity), Credential Manager request shaping and failure mapping, and the passkey flow against a fake Aduki ID.

Verified on an Android 14 (API 34) x86_64 emulator, software CPU (no KVM), AOSP image without Google services: the real `AndroidKeyStore` provider (key creation, `getEncoded()` null, `KeyInfo`, P-256 signatures verifying with the public JWK, DPoP proofs verifying, IV generated by the Keystore and never repeating over 300 encryptions, caller IV refused, sealed columns and blind-index seed file, key rotation, AccountManager registration and storage, redirect resolution). The emulator's Keystore is software-backed (`level=software`).

NOT verified: hardware-backed Keystore and StrongBox, user-authentication-bound keys (biometrics), real Credential Manager and Google Play services, a passkey ceremony, Custom Tabs in a real browser, the real Aduki ID and Space servers, process death in the middle of a sign-in, the end-to-end 1 s removal time (device plus bus), App Links (the custom scheme was verified for intent resolution only), R8/ProGuard release builds, Android 8 to 13 behaviour (only API 34 ran; Robolectric covers API 34 only).
