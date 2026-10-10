@file:Suppress("DEPRECATION")

package pro.aduki.hermes

import pro.aduki.sdk.Aduki
import pro.aduki.core.errors.AdukiException

/**
 * Source-compatibility aliases for the pre-`pro.aduki` names. They exist for
 * one minor release and are then removed (ADK-KT-003, K1). A typealias does not
 * expose nested classes: use `AdukiException.Network` etc. directly.
 */
@Deprecated("Renamed to pro.aduki.Aduki", ReplaceWith("Aduki", "pro.aduki.Aduki"))
typealias HermesClient = Aduki

@Deprecated("Renamed to pro.aduki.core.errors.AdukiException", ReplaceWith("AdukiException", "pro.aduki.core.errors.AdukiException"))
typealias HermesException = AdukiException
