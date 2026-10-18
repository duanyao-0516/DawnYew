package com.dawnyew.core

actual fun normalizeTextNfc(value: String): String = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC)
