package com.example.reelshider

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
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
    private var overlay: View? = null
    private var lastBounds: Rect? = null
    private var openedFromDm = false

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg != IG) {
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !pkg.startsWith("com.android.systemui") &&
                !pkg.contains("inputmethod")
            ) {
                removeOverlay()
                openedFromDm = false
            }
            return
        }

        val root = rootInActiveWindow ?: return

        // 1. Détecter si l'utilisateur est actuellement dans la messagerie (DM)
        if (isInDirectMessages(root)) {
            openedFromDm = true
            removeOverlay()
            return
        }

        // 2. Détecter si le flux Reels plein écran est affiché (ex: via swipe vers la gauche)
        val reelsFeed = findReelsFeedContainer(root)
        if (reelsFeed != null) {
            // Si on vient des DM, on laisse voir le Reel envoyé par l'ami
            if (openedFromDm) {
                removeOverlay()
                return
            }
            // Sinon, on recouvre le flux Reels d'un rectangle noir (en laissant la barre du bas accessible)
            val feedRect = Rect()
            reelsFeed.getBoundsInScreen(feedRect)
            val tabBarTop = findBottomBarTop(root)
            if (tabBarTop > 0 && feedRect.bottom > tabBarTop) {
                feedRect.bottom = tabBarTop
            }
            showOverlay(feedRect)
            return
        }

        // 3. On est revenu sur l'accueil / recherche / profil : réinitialiser le flag DM
        val tab = findReelsTab(root)
        if (tab != null && tab.isVisibleToUser) {
            openedFromDm = false
            val r = Rect()
            tab.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                showOverlay(r)
                return
            }
        }

        // 4. Dans tous les autres cas (ex: transition vers les messages), retirer immédiatement
        removeOverlay()
    }

    private fun isInDirectMessages(root: AccessibilityNodeInfo): Boolean {
        // Présence de la zone de texte de message ou de la liste des conversations
        val dmIds = listOf(
            "$IG:id/row_thread_composer_edittext",
            "$IG:id/direct_inbox_null_state",
            "$IG:id/inbox_refreshable_thread_list_recyclerview"
        )
        return dmIds.any { id ->
            root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser }
        }
    }

    private fun findReelsFeedContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val viewerIds = listOf(
            "$IG:id/clips_viewer_view_pager",
            "$IG:id/clips_video_container"
        )
        for (id in viewerIds) {
            val node = root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
            if (node != null) return node
        }
        return null
    }

    private fun findBottomBarTop(root: AccessibilityNodeInfo): Int {
        val tabBar = root.findAccessibilityNodeInfosByViewId("$IG:id/tab_bar").firstOrNull()
        if (tabBar != null) {
            val r = Rect()
            tabBar.getBoundsInScreen(r)
            return r.top
        }
        return -1
    }

    private fun findReelsTab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$IG:id/clips_tab")
            .firstOrNull { it.isVisibleToUser }
            ?.let { return it }

        val screenH = resources.displayMetrics.heightPixels
        return root.findAccessibilityNodeInfosByText("Reels").firstOrNull {
            if (!it.isVisibleToUser) return@firstOrNull false
            val b = Rect()
            it.getBoundsInScreen(b)
            b.top > screenH * 0.85 && b.width() < resources.displayMetrics.widthPixels / 3
        }
    }

    private fun showOverlay(r: Rect) {
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

    override fun onInterrupt() = removeOverlay()

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }
}
