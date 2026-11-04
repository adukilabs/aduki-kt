package pro.aduki.core.errors

/**
 * Why a sign-in with a password failed, for the one message an app shows.
 *
 * Aduki ID answers a wrong password and a wrong authenticator or backup code
 * the same way (`401 auth.invalid`), so that a guesser cannot learn which
 * one was right: [Credentials] covers both. Read it with [of].
 */
sealed interface SignInFailure {
    /** The address, the password or the code is wrong ("The password or the code is wrong"). */
    object Credentials : SignInFailure

    /** The account needs a second factor and none was sent: ask for the code. */
    object NeedsCode : SignInFailure

    /**
     * Too many attempts (`429`): try again after [retryAfter] seconds, when
     * the server said. Five wrong codes in a row hold the account for 15 minutes.
     */
    data class Limited(val retryAfter: Long?) : SignInFailure

    /** Aduki ID could not be reached. */
    object Offline : SignInFailure

    /** The device key's proof was refused (a clock far off, a key problem): not the person's fault. */
    object Proof : SignInFailure

    /** Anything else; the exception's message says what. */
    object Other : SignInFailure

    companion object {
        /** The failure [error] (thrown by `Aduki.signIn`, `Aduki.login` or `Login.signIn`) stands for. */
        fun of(error: Throwable): SignInFailure = when (error) {
            is AdukiException.Unauthorized -> if (error.kind == "auth.factor") NeedsCode else Credentials
            is AdukiException.Proof -> Proof
            is AdukiException.Network -> when (error.code) {
                429 -> Limited(error.retryAfter)
                null -> Offline
                else -> Other
            }
            else -> Other
        }
    }
}
