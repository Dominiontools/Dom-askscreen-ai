package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
 * It only clicks a matching answer and an explicitly labelled Submit/Check button.
 */
class PracticeAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "AskScreenA11y"
        @Volatile private var active: PracticeAccessibilityService? = null

        fun selectAndSubmit(answer: String): Boolean = active?.selectAndSubmitAnswer(answer) ?: false
        fun isEnabled(): Boolean = active != null
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

    private fun selectAndSubmitAnswer(rawAnswer: String): Boolean {
        val answer = rawAnswer.trim()
        if (answer.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val normalized = answer.lowercase(Locale.ROOT)
        if (!Regex("^[a-z]\\s*[.)-]").containsMatchIn(normalized)) return false
        val answerLetter = Regex("^([a-z])\\s*[.)-]").find(normalized)?.groupValues?.get(1)
        val answerText = Regex("^[a-z]\\s*[.)-]\\s*(.+)$").find(normalized)?.groupValues?.get(1)?.trim()
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, nodes)

        val option = nodes.firstOrNull { node ->
            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim().lowercase(Locale.ROOT)
            if (text.isEmpty()) false else {
                (answerText != null && (text == answerText || text.contains(answerText))) ||
                    (answerLetter != null && (text == answerLetter || Regex("^${Regex.escape(answerLetter)}[.)\\s:-]").containsMatchIn(text)))
            }
        } ?: return false

        val clicked = clickSelfOrClickableParent(option)
        if (!clicked) return false
        handler.postDelayed({
            val freshRoot = rootInActiveWindow ?: return@postDelayed
            val freshNodes = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(freshRoot, freshNodes)
            val answerStillVisible = freshNodes.any { node ->
                val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
                    .trim().lowercase(Locale.ROOT)
                (answerText != null && (label == answerText || label.contains(answerText))) ||
                    (answerLetter != null && (label == answerLetter || Regex("^${Regex.escape(answerLetter)}[.)\\s:-]").containsMatchIn(label)))
            }
            if (!answerStillVisible) {
                Toast.makeText(this, "The page changed after selection; submit was not tapped", Toast.LENGTH_SHORT).show()
                return@postDelayed
            }
            val submit = freshNodes.firstOrNull { node ->
                val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
                    .trim().lowercase(Locale.ROOT)
                label in setOf("submit", "submit answer", "check", "check answer", "confirm answer")
            }
            if (submit != null) {
                val submitted = clickSelfOrClickableParent(submit)
                Toast.makeText(this, if (submitted) "Practice answer submitted" else "Answer selected; submit action was blocked", Toast.LENGTH_SHORT).show()
            } else {
                Log.i(TAG, "Answer selected; no clearly labelled Submit/Check button found")
                Toast.makeText(this, "Answer selected; no accessible Submit/Check button found", Toast.LENGTH_SHORT).show()
            }
        }, 450L)
        return true
    }

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
