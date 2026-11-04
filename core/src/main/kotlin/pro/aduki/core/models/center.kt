package pro.aduki.core.models

/** A session a device unlock opened for one account. */
data class Unlocked(val account: String, val tokens: Tokens)
