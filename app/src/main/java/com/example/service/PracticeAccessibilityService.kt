package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Optional Android accessibility bridge for permitted practice pages.
 * It is opt-in in Android Settings and auto-submit is separately disabled by default.
 * For multiple-choice answers it clicks the matching options, for written answers it types
 * the answer into the text field, and then presses a clearly labelled Submit/Check button.
 * It stays silent when there is nothing to select, type or submit.
 */
class PracticeAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "AskScreenA11y"
        @Volatile private var active: PracticeAccessibilityService? = null

        /** answer may hold several lines like "A. Oxygen" (one per question). */
        fun selectAndSubmit(answer: String): Boolean = active?.selectAndSubmitAnswers(answer) ?: false

        /** Types a written answer into the text field on screen, then presses Submit. */
        fun typeAndSubmit(answer: String): Boolean = active?.typeAnswerAndSubmit(answer) ?: false

        fun isEnabled(): Boolean = active != null

        private val OPTION_LINE = Regex("^[A-Za-z]\\s*[.)\\-]\\s*\\S")
        private val SUBMIT_LABELS = setOf(
            "submit", "submit answer", "submit quiz", "submit test", "submit exam",
            "check", "check answer", "confirm", "confirm answer",
            "send", "send answer", "done", "finish", "save answer"
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 250
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ---------- Multiple choice ----------

    private fun selectAndSubmitAnswers(rawAnswer: String): Boolean {
        val lines = rawAnswer.lines().map { it.trim() }.filter { OPTION_LINE.containsMatchIn(it) }
        if (lines.isEmpty()) return false

        // First answer is clicked right away so we know whether this screen can be automated.
        if (!clickOption(lines[0])) return false

        var delay = 400L
        for (i in 1 until lines.size) {
            val line = lines[i]
            handler.postDelayed({ clickOption(line) }, delay)
            delay += 400L
        }
        handler.postDelayed({ pressSubmit() }, delay + 100L)
        return true
    }

    private fun clickOption(line: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val normalized = line.lowercase(Locale.ROOT)
        val answerLetter = Regex("^([a-z])\\s*[.)\\-]").find(normalized)?.groupValues?.get(1)
        val answerText = Regex("^[a-z]\\s*[.)\\-]\\s*(.+)$").find(normalized)?.groupValues?.get(1)?.trim()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)

        // Prefer matching the option text; fall back to the option letter.
        val byText = nodes.firstOrNull { node ->
            val text = labelOf(node)
            text.isNotEmpty() && answerText != null && (text == answerText || text.contains(answerText))
        }
        val byLetter = nodes.firstOrNull { node ->
            val text = labelOf(node)
            text.isNotEmpty() && answerLetter != null &&
                (text == answerLetter || Regex("^${Regex.escape(answerLetter)}[.)\\s:-]").containsMatchIn(text))
        }
        val option = byText ?: byLetter ?: return false
        return clickSelfOrClickableParent(option)
    }

    // ---------- Written (theory) answers ----------

    private fun typeAnswerAndSubmit(rawAnswer: String): Boolean {
        val answer = rawAnswer.trim()
        if (answer.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)

        val field = nodes.firstOrNull { it.isEditable && it.isFocused }
            ?: nodes.firstOrNull { it.isEditable && it.isEnabled }
            ?: return false

        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, answer)
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false

        handler.postDelayed({ pressSubmit() }, 600L)
        return true
    }

    // ---------- Submit ----------

    private fun pressSubmit() {
        val freshRoot = rootInActiveWindow ?: return
        val freshNodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(freshRoot, freshNodes)
        val submit = freshNodes.firstOrNull { node ->
            val label = labelOf(node)
            label in SUBMIT_LABELS || label.startsWith("submit")
        }
        if (submit == null) {
            Log.i(TAG, "No clearly labelled Submit/Check button found")
            return
        }
        if (clickSelfOrClickableParent(submit)) {
            Toast.makeText(this, "Answer submitted", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Helpers ----------

    private fun labelOf(node: AccessibilityNodeInfo): String =
        (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim().lowercase(Locale.ROOT)

    private fun collectNodes(node: AccessibilityNodeInfo, output: MutableList<AccessibilityNodeInfo>) {
        output += node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> collectNodes(child, output) }
        }
    }

    private fun clickSelfOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val candidate = current ?: return false
            if (candidate.isClickable && candidate.isEnabled) {
                return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = candidate.parent
        }
        return false
    }

    override fun onDestroy() {
        if (active === this) active = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
