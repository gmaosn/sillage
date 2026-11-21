package sillage.ui

/**
 * The editing of a line of text, without drawing it: the text, the caret, the selection (from [anchor] to
 * the caret), and what the keys do. Moves never split a character made of two UTF-16 units. Kept apart
 * from the widget so that tests can type into it directly.
 */
internal class TextEditing {
    private val b = StringBuilder()
    var text: String = ""; private set
    /** The caret's place, in UTF-16 units from the start. */
    var caret = 0; private set
    /** The other end of the selection (equal to [caret] when nothing is selected). */
    var anchor = 0; private set
    var maxLength = Int.MAX_VALUE

    val selectionStart get() = minOf(caret, anchor)
    val selectionEnd get() = maxOf(caret, anchor)
    val hasSelection get() = caret != anchor
    val selected: String get() = text.substring(selectionStart, selectionEnd)

    private fun commit() { text = b.toString() }

    fun set(value: String) { b.setLength(0); b.append(value.take(maxLength)); commit(); caret = text.length; anchor = caret }

    private fun deleteSelection(): Boolean {
        if (!hasSelection) return false
        b.delete(selectionStart, selectionEnd); caret = selectionStart; anchor = caret; commit()
        return true
    }

    /** Types [s] at the caret, replacing the selection; what goes beyond [maxLength] is dropped. True if the text changed. */
    fun insert(s: String): Boolean {
        val clean = s.filter { it >= ' ' && it != '\u007f' }
        val had = deleteSelection()
        if (clean.isEmpty()) return had
        val room = maxLength - b.length
        if (room <= 0) return had
        var cut = clean
        if (cut.length > room) { cut = cut.substring(0, room); if (Character.isHighSurrogate(cut.last())) cut = cut.dropLast(1) }
        b.insert(caret, cut); caret += cut.length; anchor = caret; commit()
        return true
    }

    fun backspace(word: Boolean = false): Boolean {
        if (deleteSelection()) return true
        if (caret == 0) return false
        val to = if (word) wordLeft(caret) else previous(caret)
        b.delete(to, caret); caret = to; anchor = caret; commit()
        return true
    }

    fun delete(word: Boolean = false): Boolean {
        if (deleteSelection()) return true
        if (caret >= b.length) return false
        val to = if (word) wordRight(caret) else next(caret)
        b.delete(caret, to); commit()
        return true
    }

    /** Moves the caret left (by a word with [word]); with [select] the selection follows. */
    fun left(select: Boolean = false, word: Boolean = false) {
        if (!select && hasSelection && !word) { caret = selectionStart; anchor = caret; return }
        caret = if (word) wordLeft(caret) else previous(caret)
        if (!select) anchor = caret
    }

    fun right(select: Boolean = false, word: Boolean = false) {
        if (!select && hasSelection && !word) { caret = selectionEnd; anchor = caret; return }
        caret = if (word) wordRight(caret) else next(caret)
        if (!select) anchor = caret
    }

    fun home(select: Boolean = false) { caret = 0; if (!select) anchor = caret }
    fun end(select: Boolean = false) { caret = b.length; if (!select) anchor = caret }
    fun selectAll() { anchor = 0; caret = b.length }

    /** Puts the caret at [index] (a click), extending the selection with [select] (a drag). */
    fun place(index: Int, select: Boolean = false) {
        var i = index.coerceIn(0, b.length)
        if (i in 1 until b.length && Character.isLowSurrogate(b[i])) i--
        caret = i; if (!select) anchor = caret
    }

    /** Removes the selection and gives it (Ctrl+X). */
    fun cut(): String { val s = selected; deleteSelection(); return s }

    private fun previous(i: Int): Int = if (i <= 0) 0 else if (i >= 2 && Character.isLowSurrogate(b[i - 1]) && Character.isHighSurrogate(b[i - 2])) i - 2 else i - 1
    private fun next(i: Int): Int = if (i >= b.length) b.length else if (i + 1 < b.length && Character.isHighSurrogate(b[i]) && Character.isLowSurrogate(b[i + 1])) i + 2 else i + 1
    private fun wordLeft(i0: Int): Int { var i = i0; while (i > 0 && !b[i - 1].isLetterOrDigit()) i--; while (i > 0 && b[i - 1].isLetterOrDigit()) i--; return i }
    private fun wordRight(i0: Int): Int { var i = i0; while (i < b.length && !b[i].isLetterOrDigit()) i++; while (i < b.length && b[i].isLetterOrDigit()) i++; return i }
}
