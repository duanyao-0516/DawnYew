package com.dawnyew.core

/** Both supported platforms use Unicode NFC for identity-bearing names. */
expect fun normalizeTextNfc(value: String): String
