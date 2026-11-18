package com.example.reelshider

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ReelsHiderService : AccessibilityService() {

    companion object {
        private const val IG = "com.instagram.android"
        private const val COLOR_DARK = 0xFF0B1014.toInt()
        private const val COLOR_LIGHT = 0xFFFFFFFF.toInt()
    }

    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val handler = Handler(Looper.getMainLooper())

    private var overlay: View? = null
    private var lastBounds: Rect? = null
    private var openedFromDm = false
    private var isMutedByUs = false
    private var burstChecksRemaining = 0

    private val burstRunnable = object : Runnable {
        override fun run() {
            evaluateScreen()
            if (burstChecksRemaining > 0) {
                burstChecksRemaining--
                handler.postDelayed(this, 80L)
            }
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg != IG) {
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !pkg.startsWith("com.android.systemui") &&
                !pkg.contains("inputmethod")
            ) {
                stopBurst()
                removeOverlay()
                unmuteMediaIfNeeded()
                openedFromDm = false
            }
            return
        }

        // À chaque interaction ou changement d'état, on lance une rafale de vérifications
        // toutes les 80ms pendant ~1.2s pour suivre les animations de glissement en temps réel.
        triggerBurst()
        evaluateScreen()
    }

    private fun triggerBurst() {
        burstChecksRemaining = 15
        handler.removeCallbacks(burstRunnable)
        handler.postDelayed(burstRunnable, 80L)
    }

    private fun stopBurst() {
        burstChecksRemaining = 0
        handler.removeCallbacks(burstRunnable)
    }

    private fun evaluateScreen() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != IG) return

        val screenW = resources.displayMetrics.widthPixels

        // 1. PRIORITÉ ABSOLUE : Est-on dans la messagerie (liste des DM ou conversation) ?
        if (isInDirectMessages(root, screenW)) {
            openedFromDm = true
            removeOverlay()
            unmuteMediaIfNeeded()
            return
        }

        // 2. Détecter si le flux Reels plein écran est réellement centré à l'écran
        val reelsBounds = findCenteredReelsFeedBounds(root, screenW)
        if (reelsBounds != null) {
            if (openedFromDm) {
                removeOverlay()
                unmuteMediaIfNeeded()
                return
            }

            muteMedia()
            val tabBarTop = findBottomBarTop(root, screenW)
            if (tabBarTop > 0 && reelsBounds.bottom > tabBarTop) {
                reelsBounds.bottom = tabBarTop
            }
            showOverlay(reelsBounds)
            return
        }

        // 3. On n'est ni dans les DM ni sur le flux Reels : rétablir le son
        unmuteMediaIfNeeded()

        // 4. Masquer uniquement l'icône de l'onglet Reels si la barre du bas est visible à l'écran
        val tabBounds = findVisibleReelsTabBounds(root, screenW)
        if (tabBounds != null) {
            openedFromDm = false
            showOverlay(tabBounds)
            return
        }

        // 5. Transition ou écran intermédiaire : tout nettoyer immédiatement
        removeOverlay()
    }

    private fun isInDirectMessages(root: AccessibilityNodeInfo, screenW: Int): Boolean {
        val dmIds = listOf(
            "$IG:id/row_thread_composer_edittext",
            "$IG:id/direct_inbox_null_state",
            "$IG:id/inbox_refreshable_thread_list_recyclerview",
            "$IG:id/direct_thread_header_title",
            "$IG:id/direct_inbox_search_row"
        )
        for (id in dmIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (!node.isVisibleToUser) continue
                val r = Rect()
                node.getBoundsInScreen(r)
                // Vérifie que l'élément DM est bien entré dans l'écran visible
                if (r.width() > 0 && r.left < screenW - 20 && r.right > 20) {
                    return true
                }
            }
        }
        return false
    }

    private fun findCenteredReelsFeedBounds(root: AccessibilityNodeInfo, screenW: Int): Rect? {
        val viewerIds = listOf(
            "$IG:id/clips_viewer_view_pager",
            "$IG:id/clips_video_container"
        )
        for (id in viewerIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (node in nodes) {
                if (!node.isVisibleToUser) continue
                val r = Rect()
                node.getBoundsInScreen(r)
                // Le lecteur Reels doit occuper la majeure partie de la largeur de l'écran
                // Dès qu'on glisse vers les DM, r.left devient négatif et r.right diminue : on libère aussitôt.
                val visibleWidth = minOf(r.right, screenW) - maxOf(r.left, 0)
                if (visibleWidth > screenW * 0.65 && r.height() > 200) {
                    return Rect(maxOf(r.left, 0), maxOf(r.top, 0), minOf(r.right, screenW), r.bottom)
                }
            }
        }
        return null
    }

    private fun findBottomBarTop(root: AccessibilityNodeInfo, screenW: Int): Int {
        val tabBar = root.findAccessibilityNodeInfosByViewId("$IG:id/tab_bar").firstOrNull() ?: return -1
        if (!tabBar.isVisibleToUser) return -1
        val r = Rect()
        tabBar.getBoundsInScreen(r)
        if (r.right <= 0 || r.left >= screenW) return -1
        return r.top
    }

    private fun findVisibleReelsTabBounds(root: AccessibilityNodeInfo, screenW: Int): Rect? {
        val screenH = resources.displayMetrics.heightPixels

        val byId = root.findAccessibilityNodeInfosByViewId("$IG:id/clips_tab")
            .firstOrNull { it.isVisibleToUser }
        if (byId != null) {
            val r = Rect()
            byId.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0 && r.left >= 0 && r.right <= screenW) {
                return r
            }
        }

        val byText = root.findAccessibilityNodeInfosByText("Reels").firstOrNull {
            if (!it.isVisibleToUser) return@firstOrNull false
            val b = Rect()
            it.getBoundsInScreen(b)
            b.top > screenH * 0.85 &&
                    b.width() < screenW / 3 &&
                    b.left >= 0 &&
                    b.right <= screenW
        }
        if (byText != null) {
            val r = Rect()
            byText.getBoundsInScreen(r)
            return r
        }

        return null
    }

    private fun muteMedia() {
        if (isMutedByUs) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, true)
            }
            isMutedByUs = true
        }
    }

    private fun unmuteMediaIfNeeded() {
        if (!isMutedByUs) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            } else {
                @Suppress("DEPRECATION")
                audioManager.setStreamMute(AudioManager.STREAM_MUSIC, false)
            }
            isMutedByUs = false
        }
    }

    private fun showOverlay(r: Rect) {
        if (r.width() <= 0 || r.height() <= 0) {
            removeOverlay()
            return
        }
        if (r == lastBounds && overlay != null) return
        removeOverlay()

        val night = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        val v = View(this).apply { setBackgroundColor(if (night) COLOR_DARK else COLOR_LIGHT) }
        val lp = WindowManager.LayoutParams(
            r.width(), r.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = r.left
            y = r.top
        }
        wm.addView(v, lp)
        overlay = v
        lastBounds = Rect(r)
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        lastBounds = null
    }

    override fun onInterrupt() {
        stopBurst()
        removeOverlay()
        unmuteMediaIfNeeded()
    }

    override fun onDestroy() {
        stopBurst()
        removeOverlay()
        unmuteMediaIfNeeded()
        super.onDestroy()
    }
}
