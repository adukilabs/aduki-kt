package pro.aduki.core.errors

import org.junit.Assert.assertEquals
import org.junit.Test

class SignInFailureTest {
    @Test
    fun aWrongPasswordAndAWrongCodeAreOneFailure() {
        // Aduki ID answers both with `401 auth.invalid` (checked live on 2026-11-04).
        assertEquals(SignInFailure.Credentials, SignInFailure.of(AdukiException.Unauthorized("auth.invalid: invalid credentials", null, "auth.invalid")))
        assertEquals(SignInFailure.Credentials, SignInFailure.of(AdukiException.Unauthorized("HTTP 401")))
    }

    @Test
    fun eachOtherAnswerHasItsOwnFailure() {
        assertEquals(SignInFailure.NeedsCode, SignInFailure.of(AdukiException.Unauthorized("auth.factor: a second factor is required", null, "auth.factor")))
        assertEquals(SignInFailure.Limited(60), SignInFailure.of(AdukiException.Network("Sign-in failed: rate.limited", code = 429, retryAfter = 60)))
        assertEquals(SignInFailure.Offline, SignInFailure.of(AdukiException.Network("Network request failed", java.io.IOException("no route"))))
        assertEquals(SignInFailure.Proof, SignInFailure.of(AdukiException.Proof("auth.dpop", "auth.dpop", null)))
        assertEquals(SignInFailure.Other, SignInFailure.of(AdukiException.Network("Sign-in failed", code = 500)))
        assertEquals(SignInFailure.Other, SignInFailure.of(IllegalStateException("x")))
    }
}
