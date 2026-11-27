package com.galiais.agent.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.galiais.agent.engine.ssh.StickyModifierManager
import com.galiais.agent.engine.terminal.TerminalKey

/**
 * 生产级统一 Android 终端输入桥接视图（全分屏共享唯一实例，根治分屏焦点互抢与切屏断连）：
 * - 采用真实 Editable `BaseInputConnection(this, true)` 承载中文拼音/五笔/搜狗/百度/Gboard 组合缓冲，
 *   拼音退格仅删组合区不误删远端命令，确认上屏一次性 UTF-8 串行直发且绝不误包括号粘贴转义；
 * - 严格修正 `deleteSurroundingText(0, x)` 误发退格、`\r\n` 双回车、Shift 粘滞键忽略与 `ACTION_UP` 泄漏；
 * - 显式拦截硬件 `KEYCODE_ESCAPE` 送入终端而非触发 Android 返回。
 */
internal class TerminalImeInputView(context: Context) : View(context) {
    var modifiers: StickyModifierManager? = null
    var inputEnabled: Boolean = true
    var onDispatchKey: ((TerminalKey, Boolean, Boolean, Boolean) -> Unit)? = null
    var onDispatchChar: ((Int, Boolean, Boolean) -> Unit)? = null
    var onDispatchText: ((String) -> Unit)? = null
    var onComposingChanged: ((String) -> Unit)? = null
    var onInputSent: (() -> Unit)? = null
    var onShortcut: ((String) -> Unit)? = null

    private val composingBuffer = SpannableStringBuilder()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun focusAndShowIme() {
        if (!inputEnabled) return
        if (!hasFocus()) requestFocus()
        post {
            context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideIme() {
        clearComposingBuffer(restartIme = false)
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(windowToken, 0)
    }

    fun clearComposingBuffer(restartIme: Boolean = true) {
        if (composingBuffer.isNotEmpty()) {
            composingBuffer.clear()
            onComposingChanged?.invoke("")
            if (restartIme) {
                context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
            }
        }
    }

    private fun flushComposingIfAny() {
        if (composingBuffer.isEmpty()) return
        val text = composingBuffer.toString()
        composingBuffer.clear()
        onComposingChanged?.invoke("")
        commitCleanText(text)
    }

    private fun commitCleanText(raw: String) {
        if (raw.isEmpty() || !inputEnabled) return
        val str = raw.replace("\r\n", "\n")
        val mods = modifiers
        val ctrl = mods?.isCtrlActive() == true
        val alt = mods?.isAltActive() == true
        val shift = mods?.isShiftActive() == true

        if (!ctrl && !alt && !shift) {
            // 拆分换行，纯文本段一次性串行发送（保留 FIFO 顺序，不拆碎多字节/词组）
            val sb = StringBuilder(str.length)
            var i = 0
            while (i < str.length) {
                val ch = str[i]
                if (ch == '\n' || ch == '\r') {
                    if (sb.isNotEmpty()) {
                        onDispatchText?.invoke(sb.toString())
                        sb.clear()
                    }
                    onDispatchKey?.invoke(TerminalKey.ENTER, false, false, false)
                } else {
                    sb.append(ch)
                }
                i++
            }
            if (sb.isNotEmpty()) {
                onDispatchText?.invoke(sb.toString())
            }
        } else {
            val cp0 = str.codePointAt(0)
            val effectiveCp = if (shift && !ctrl && !alt && cp0 in 'a'.code..'z'.code) cp0 - 32 else cp0
            if (effectiveCp == '\n'.code || effectiveCp == '\r'.code) {
                onDispatchKey?.invoke(TerminalKey.ENTER, ctrl, alt, shift)
            } else {
                onDispatchChar?.invoke(effectiveCp, ctrl, alt)
            }
            val restStart = Character.charCount(cp0)
            if (restStart < str.length) {
                onDispatchText?.invoke(str.substring(restStart))
            }
            mods.clearTransients()
        }
        onInputSent?.invoke()
    }

    private fun syncImeSelection() {
        val len = composingBuffer.length
        val compStart = if (len > 0) 0 else -1
        val compEnd = if (len > 0) len else -1
        context.getSystemService(InputMethodManager::class.java)
            ?.updateSelection(this, len, len, compStart, compEnd)
    }

    private fun deleteLastCodePointFromComposing(): Boolean {
        if (composingBuffer.isEmpty()) return false
        val end = composingBuffer.length
        val cp = Character.codePointBefore(composingBuffer, end)
        val count = Character.charCount(cp)
        composingBuffer.delete((end - count).coerceAtLeast(0), end)
        onComposingChanged?.invoke(composingBuffer.toString())
        syncImeSelection()
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = inputEnabled

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!inputEnabled) return null
        // 使用 TYPE_CLASS_TEXT + NO_SUGGESTIONS：关闭英文自动更正同时确保搜狗/百度/Gboard 中文拼音正常候选上屏
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun getEditable(): Editable = composingBuffer

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!inputEnabled) return true
                val ok = super.setComposingText(text, newCursorPosition)
                onComposingChanged?.invoke(composingBuffer.toString())
                syncImeSelection()
                return ok
            }

            override fun finishComposingText(): Boolean {
                val ok = super.finishComposingText()
                flushComposingIfAny()
                syncImeSelection()
                return ok
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString().orEmpty()
                composingBuffer.clear()
                onComposingChanged?.invoke("")
                syncImeSelection()
                if (str.isNotEmpty()) commitCleanText(str)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (!inputEnabled) return true
                var remBefore = beforeLength.coerceIn(0, 64)
                val remAfter = afterLength.coerceIn(0, 64)
                if (composingBuffer.isNotEmpty() && remBefore > 0) {
                    val take = remBefore.coerceAtMost(composingBuffer.length)
                    var start = composingBuffer.length - take
                    // 防止 UTF-16 代理对被腰斩
                    if (start > 0 && Character.isLowSurrogate(composingBuffer[start]) && Character.isHighSurrogate(composingBuffer[start - 1])) {
                        start -= 1
                    }
                    composingBuffer.delete(start, composingBuffer.length)
                    onComposingChanged?.invoke(composingBuffer.toString())
                    syncImeSelection()
                    remBefore -= take
                }
                if (remBefore > 0) {
                    repeat(remBefore) { onDispatchKey?.invoke(TerminalKey.BACKSPACE, false, false, false) }
                    onInputSent?.invoke()
                }
                if (remAfter > 0) {
                    repeat(remAfter) { onDispatchKey?.invoke(TerminalKey.DELETE, false, false, false) }
                    onInputSent?.invoke()
                }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (!inputEnabled) return true
                var remBefore = beforeLength.coerceIn(0, 64)
                val remAfter = afterLength.coerceIn(0, 64)
                while (remBefore > 0 && deleteLastCodePointFromComposing()) {
                    remBefore--
                }
                if (remBefore > 0) {
                    repeat(remBefore) { onDispatchKey?.invoke(TerminalKey.BACKSPACE, false, false, false) }
                    onInputSent?.invoke()
                }
                if (remAfter > 0) {
                    repeat(remAfter) { onDispatchKey?.invoke(TerminalKey.DELETE, false, false, false) }
                    onInputSent?.invoke()
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (handleKeyEvent(event)) return true
                return super.sendKeyEvent(event)
            }
        }
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && inputEnabled) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                clearComposingBuffer(restartIme = false)
                onDispatchKey?.invoke(TerminalKey.ESCAPE, false, false, false)
                modifiers?.clearTransients()
                onInputSent?.invoke()
            }
            return true
        }
        return super.onKeyPreIme(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKeyEvent(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (inputEnabled && isHandledKeyCode(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean {
        val chars = event.characters
        if (inputEnabled && !chars.isNullOrEmpty()) {
            flushComposingIfAny()
            commitCleanText(chars)
            return true
        }
        return super.onKeyMultiple(keyCode, repeatCount, event)
    }

    private fun isHandledKeyCode(keyCode: Int): Boolean = mapSpecialKey(keyCode) != null

    private fun mapSpecialKey(keyCode: Int): TerminalKey? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKey.ENTER
        KeyEvent.KEYCODE_DEL -> TerminalKey.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKey.DELETE
        KeyEvent.KEYCODE_TAB -> TerminalKey.TAB
        KeyEvent.KEYCODE_ESCAPE -> TerminalKey.ESCAPE
        KeyEvent.KEYCODE_DPAD_UP -> TerminalKey.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKey.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKey.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKey.RIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> TerminalKey.HOME
        KeyEvent.KEYCODE_MOVE_END -> TerminalKey.END
        KeyEvent.KEYCODE_PAGE_UP -> TerminalKey.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKey.PAGE_DOWN
        KeyEvent.KEYCODE_INSERT -> TerminalKey.INSERT
        KeyEvent.KEYCODE_F1 -> TerminalKey.F1
        KeyEvent.KEYCODE_F2 -> TerminalKey.F2
        KeyEvent.KEYCODE_F3 -> TerminalKey.F3
        KeyEvent.KEYCODE_F4 -> TerminalKey.F4
        KeyEvent.KEYCODE_F5 -> TerminalKey.F5
        KeyEvent.KEYCODE_F6 -> TerminalKey.F6
        KeyEvent.KEYCODE_F7 -> TerminalKey.F7
        KeyEvent.KEYCODE_F8 -> TerminalKey.F8
        KeyEvent.KEYCODE_F9 -> TerminalKey.F9
        KeyEvent.KEYCODE_F10 -> TerminalKey.F10
        KeyEvent.KEYCODE_F11 -> TerminalKey.F11
        KeyEvent.KEYCODE_F12 -> TerminalKey.F12
        else -> null
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!inputEnabled) return false
        val special = mapSpecialKey(event.keyCode)
        if (event.action == KeyEvent.ACTION_UP) {
            return special != null || event.unicodeChar > 0
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // 拼音组合中按退格优先删除本地组合缓冲（按完整 Unicode 码点删除），按回车先上屏组合文字再发送回车
        if (special == TerminalKey.BACKSPACE && composingBuffer.isNotEmpty()) {
            deleteLastCodePointFromComposing()
            return true
        }
        if (special == TerminalKey.ENTER && composingBuffer.isNotEmpty()) {
            flushComposingIfAny()
        }

        val mods = modifiers
        val ctrl = event.isCtrlPressed || mods?.isCtrlActive() == true
        val alt = event.isAltPressed || mods?.isAltActive() == true
        val shift = event.isShiftPressed || mods?.isShiftActive() == true

        // 硬件键盘全局快捷操作（Ctrl+Shift+C/V/F/R 与 Ctrl+=/- /0 缩放字号）
        if (ctrl && !alt && onShortcut != null) {
            val shortcut = when {
                shift && event.keyCode == KeyEvent.KEYCODE_C -> "copy"
                shift && event.keyCode == KeyEvent.KEYCODE_V -> "paste"
                shift && event.keyCode == KeyEvent.KEYCODE_F -> "search"
                shift && event.keyCode == KeyEvent.KEYCODE_R -> "history"
                event.keyCode == KeyEvent.KEYCODE_EQUALS || event.keyCode == KeyEvent.KEYCODE_PLUS -> "zoom_in"
                event.keyCode == KeyEvent.KEYCODE_MINUS -> "zoom_out"
                event.keyCode == KeyEvent.KEYCODE_0 -> "zoom_reset"
                else -> null
            }
            if (shortcut != null) {
                onShortcut?.invoke(shortcut)
                mods?.clearTransients()
                return true
            }
        }

        if (special != null) {
            onDispatchKey?.invoke(special, ctrl, alt, shift)
            mods?.clearTransients()
            onInputSent?.invoke()
            return true
        }
        val metaNoCtrlAlt = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val ch = event.getUnicodeChar(if (mods?.isShiftActive() == true) (metaNoCtrlAlt or KeyEvent.META_SHIFT_ON) else metaNoCtrlAlt)
        if (ch > 0) {
            flushComposingIfAny()
            onDispatchChar?.invoke(ch, ctrl, alt)
            mods?.clearTransients()
            onInputSent?.invoke()
            return true
        }
        return false
    }
}
