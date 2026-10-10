# Skill: run the live tier against a real server

The live tests send and delete real mail on a test account. Use a dedicated account that has an authenticator (second factor is mandatory at Aduki ID). Never paste credentials into files or logs: export them in the shell only.

```bash
export ADUKI_LIVE_URL=https://mail.example.com/v1     # mail REST base
export ADUKI_LIVE_ID=https://id.example.com/v1        # Aduki ID base (default https://id.aduki.pro/v1)
export ADUKI_LIVE_EMAIL=...        # test account address
export ADUKI_LIVE_PASSWORD=...     # set from your secret store, do not echo
export ADUKI_LIVE_CODE=...         # a current authenticator code (or ADUKI_LIVE_BACKUP=<unused backup code>)
./gradlew --no-daemon :net:liveTest
```

What `net/.../live.test.kt` does: signs in at Aduki ID, sends a message to itself, follows `/user/mail/changes` until it arrives, flags it, deletes it, checks that an idempotent retry does not send twice.

Gotchas:

- Codes expire in 30 s and backup codes are single use: a failed run may have spent them.
- A server with a self-signed certificate: import the certificate into the JVM truststore.
- The `sdk` module has a separate `LiveTest` (API-key based): it runs only with `ADUKI_LIVE=true` and `ADUKI_KEY` set; otherwise it uses a placeholder key and fails.

Verify: the test reports show the live tests as passed, not skipped.
