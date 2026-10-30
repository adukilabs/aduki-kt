# Android Module (`pro.aduki:android`)

The Android library module for the Aduki SDK (AAR, `minSdk` 26, `compileSdk` 34).

Provides Android-specific capabilities:
- **AndroidKey**: Hardware-backed or StrongBox P-256 DPoP keys in Android Keystore (`ES256`).
- **Accounts**: Account Center multi-account management with Android `AccountManager` (`pro.aduki.account`).
- **SignIn**: Custom Tab browser sign-in flow and redirect handling (`RedirectActivity`).
- **Passkeys & Credential Manager**: Passkey ceremonies and saved credential federation.

## Dependencies

In your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("pro.aduki:android:0.5.4")
}
```

`pro.aduki:android` exports `androidx.credentials:credentials` and `androidx.credentials:credentials-play-services-auth` as transitive `api` dependencies. Consumer apps on Android 13 and below inherit Play Services credential fallback automatically.

## Digital Asset Links & Credential Manager

Android Credential Manager verifies ownership between apps and web origins using Digital Asset Links (`assetlinks.json`).

### Default Federation

The library manifest automatically declares:

```xml
<meta-data
    android:name="asset_statements"
    android:resource="@string/asset_statements" />
```

By default, `@string/asset_statements` includes the hosted statement:

```json
[{"include": "https://mail.aduki.pro/.well-known/assetlinks.json"}]
```

This federates saved credentials between `mail.aduki.pro` and authorized Android packages (`pro.aduki.mail`, `pro.aduki.contacts`, `pro.aduki.calendar`).

### Custom Domains

When connecting to a self-hosted Aduki instance, override `@string/asset_statements` in your app's `res/values/strings.xml`:

```xml
<resources>
    <string name="asset_statements" translatable="false">
        [
          {\"include\": \"https://mail.aduki.pro/.well-known/assetlinks.json\"},
          {\"include\": \"https://mail.example.org/.well-known/assetlinks.json\"}
        ]
    </string>
</resources>
```

### Keystore Fingerprint Retrieval

To register your app in `assetlinks.json` on the server, extract the SHA-256 certificate fingerprint:

```bash
keytool -list -v -keystore your-keystore.jks -alias your-alias
```

Format the SHA-256 fingerprint with uppercase hex bytes separated by colons:
`52:0B:57:FE:4B:8F:9C:04:3A:45:57:C9:FC:41:23:DD:0F:53:C0:E3:02:73:FF:ED:B2:A8:27:8D:24:41:DB:BC`
