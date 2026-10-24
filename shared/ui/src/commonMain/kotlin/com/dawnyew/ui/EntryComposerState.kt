package com.dawnyew.ui

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import com.dawnyew.core.DimensionKind
import com.dawnyew.core.DomainException
import com.dawnyew.core.Money
import com.dawnyew.presentation.AmountCalculator
import com.dawnyew.presentation.EditorChange
import com.dawnyew.presentation.EditorDraft

internal enum class EntryInputMode { NONE, CALCULATOR, NOTE, SYSTEM_AMOUNT }

internal sealed interface EntryComposerDialog {
    data object Ledger : EntryComposerDialog
    data object Category : EntryComposerDialog
    data object Account : EntryComposerDialog
    data object Tags : EntryComposerDialog
    data object Date : EntryComposerDialog
    data object Time : EntryComposerDialog
    data object More : EntryComposerDialog
    data class DimensionPicker(val kind: DimensionKind) : EntryComposerDialog
    data class NewDimension(val kind: DimensionKind) : EntryComposerDialog
}

/** Only transient editing UI lives here. Text and financial selections remain in EditorDraft. */
@Stable
internal class EntryComposerState(initialMode: EntryInputMode, amountLength: Int, noteLength: Int) {
    var inputMode by mutableStateOf(initialMode)
        private set
    var inputRequest by mutableIntStateOf(0)
        private set
    var amountSelection by mutableStateOf(TextRange(amountLength))
    var noteSelection by mutableStateOf(TextRange(noteLength))
    var noteComposition by mutableStateOf<TextRange?>(null)
    var dialog by mutableStateOf<EntryComposerDialog?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var dialogError by mutableStateOf<String?>(null)

    fun requestInput(mode: EntryInputMode) {
        inputMode = mode
        inputRequest++ // A focused field must also be able to reopen an IME hidden by system Back.
    }

    fun openDialog(target: EntryComposerDialog) {
        dialogError = null
        dialog = target
        requestInput(EntryInputMode.NONE)
    }

    fun closeDialog() { dialog = null; dialogError = null }
}

/** A current read-only binding, never an independently editable copy of the financial draft. */
internal data class EntryComposerBindings(
    val draft: EditorDraft,
    val busy: Boolean,
    val onChange: (EditorChange) -> Unit,
    val onSave: (Boolean) -> Unit,
    val onTransfer: (String) -> Unit,
)

internal data class AmountEditResult(val text: String, val selection: TextRange, val error: String? = null)

internal fun TextRange.within(length: Int) = TextRange(start.coerceIn(0, length), end.coerceIn(0, length))

/** Selection-aware expression editing; a rejected/no-op edit leaves the financial text unchanged. */
internal fun editEntryAmount(text: String, selection: TextRange, key: String): AmountEditResult {
    val range = selection.within(text.length)
    val start = minOf(range.start, range.end)
    val end = maxOf(range.start, range.end)
    fun replace(value: String, from: Int = start, to: Int = end): AmountEditResult {
        val next = text.take(from) + value + text.drop(to)
        return if (next.length > 96) AmountEditResult(text, range, "算式最多 96 个字符")
        else AmountEditResult(next, TextRange(from + value.length))
    }
    return when (key) {
        "C" -> AmountEditResult("", TextRange.Zero)
        "⌫" -> if (start != end) replace("") else if (start > 0) replace("", start - 1, end) else AmountEditResult(text, range)
        "=" -> try {
            val result = Money.format(AmountCalculator.evaluate(text))
            AmountEditResult(result, TextRange(result.length))
        } catch (error: DomainException) { AmountEditResult(text, range, error.message) }
        "." -> replace(if (start == 0 || text[start - 1] in "+-*/(") "0." else ".")
        in listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "+", "-", "*", "/", "(", ")", "−", "×", "÷") ->
            replace(when (key) { "−" -> "-"; "×" -> "*"; "÷" -> "/"; else -> key })
        else -> AmountEditResult(text, range, "不支持的金额按键")
    }
}

internal fun entryKeyboardRowHeight(fontScale: Float) = maxOf(40f, 24f * fontScale + 8f)

internal fun entryCalculatorFits(bodyHeightDp: Float, fontScale: Float): Boolean {
    val keyboardAndSave = entryKeyboardRowHeight(fontScale) * 4 + 4 * 3 + 4 + 64
    return bodyHeightDp >= keyboardAndSave + 200 * fontScale
}
