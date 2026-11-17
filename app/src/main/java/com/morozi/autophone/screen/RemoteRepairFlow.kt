package com.morozi.autophone.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

private const val TAG = "RemoteRepair"

/**
 * Pairs the Bluetooth remote again the way it works by hand: Settings ->
 * "Controles remotos e acessórios" -> the remote -> "Esquecer", then "Parear
 * acessório" -> the remote. Android cannot forget a pairing for an app, so the
 * accessibility service goes through the system screens (they take touch and
 * clicks). Each step waits for its screen; the whole flow gives up after
 * [TIMEOUT_MS] and goes back to the home screen.
 *
 * Words are matched in Portuguese and English (the Pi's settings language).
 */
internal class RemoteRepairFlow(
    private val service: AccessibilityService,
    private val remoteName: String,
    private val isConnected: () -> Boolean,
    private val onEnd: (paired: Boolean) -> Unit
) {
    private enum class Step { OPEN_SETTINGS, ACCESSORIES, REMOTE, FORGET, CONFIRM_FORGET, PAIR_NEW, PICK_REMOTE, WAIT_PAIRED }

    private val main = Handler(Looper.getMainLooper())
    private var step = Step.OPEN_SETTINGS
    private var startedAt = 0L
    private var stepSince = 0L
    private var forgotten = 0

    fun start() {
        startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "re-pairing \"$remoteName\"")
        go(Step.OPEN_SETTINGS)
        main.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (isConnected()) return finish(true)
            if (now - startedAt > TIMEOUT_MS) return finish(false)
            runCatching { advance() }.onFailure { Log.w(TAG, "step $step failed: $it") }
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun go(next: Step) {
        step = next
        stepSince = SystemClock.elapsedRealtime()
        Log.i(TAG, "step $next")
        if (next == Step.OPEN_SETTINGS) {
            // Straight to the pairing screen: Android TV's main settings list is
            // not readable by accessibility on this build, so "Esquecer" cannot
            // be reached from here. Pairing the remote's new address is tried first.
            service.startActivity(
                Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            go(Step.PICK_REMOTE)
        }
    }

    private fun stepAge() = SystemClock.elapsedRealtime() - stepSince

    private fun advance() {
        when (step) {
            Step.OPEN_SETTINGS -> Unit
            Step.ACCESSORIES -> {
                if (click(ACCESSORIES)) go(Step.REMOTE) else scrollForward()
            }
            Step.REMOTE -> {
                // Old pairings of this remote: forget them all (one per round).
                when {
                    click(listOf(remoteName)) -> go(Step.FORGET)
                    stepAge() > 2_500 -> go(Step.PAIR_NEW)     // none left
                }
            }
            Step.FORGET -> if (click(FORGET)) go(Step.CONFIRM_FORGET)
            Step.CONFIRM_FORGET -> {
                // Some versions ask again; others go straight back to the list.
                if (click(CONFIRM) || stepAge() > 1_500) {
                    forgotten++
                    Log.i(TAG, "forgot an old pairing ($forgotten)")
                    go(Step.REMOTE)
                }
            }
            Step.PAIR_NEW -> if (click(PAIR_ACCESSORY)) go(Step.PICK_REMOTE)
            Step.PICK_REMOTE -> if (click(listOf(remoteName))) go(Step.WAIT_PAIRED)
            Step.WAIT_PAIRED -> {
                click(CONFIRM_PAIR)
                if (stepAge() > 20_000) go(Step.PICK_REMOTE)    // try once more
            }
        }
    }

    private fun finish(paired: Boolean) {
        main.removeCallbacks(tick)
        Log.i(TAG, if (paired) "remote paired again" else "gave up (remote did not show up)")
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        onEnd(paired)
    }

    /** Clicks the first node whose text is exactly one of [words]; true if it did. */
    private fun click(words: List<String>): Boolean {
        for (window in service.windows) {
            val root = window.root ?: continue
            for (word in words) {
                val node = root.findAccessibilityNodeInfosByText(word)
                    .firstOrNull { it.text?.toString()?.trim().equals(word, ignoreCase = true) && it.isVisibleToUser }
                    ?: continue
                val target = clickable(node) ?: continue
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "clicked \"$word\"")
                    return true
                }
            }
        }
        return false
    }

    private fun clickable(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var node: AccessibilityNodeInfo? = start
        while (node != null) {
            if (node.isClickable) return node
            node = node.parent
        }
        return null
    }

    private fun scrollForward() {
        val root = service.rootInActiveWindow ?: return
        findScrollable(root)?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> findScrollable(child)?.let { return it } }
        }
        return null
    }

    private companion object {
        const val POLL_MS = 700L
        const val TIMEOUT_MS = 75_000L
        val ACCESSORIES = listOf("Controles remotos e acessórios", "Remotes & Accessories", "Remotes and accessories")
        val FORGET = listOf("Esquecer", "Forget", "Desparear", "Unpair")
        val CONFIRM = listOf("OK", "Esquecer", "Forget", "Sim", "Yes")
        val PAIR_ACCESSORY = listOf("Parear acessório", "Pair accessory", "Parear controle remoto ou acessório", "Pair remote or accessory")
        val CONFIRM_PAIR = listOf("Parear", "Pair", "Emparelhar")
    }
}
