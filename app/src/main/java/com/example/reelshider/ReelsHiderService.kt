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
    private var tabOverlay: View? = null
    private var lastTabBounds: Rect? = null
    private var lastSkipTimestamp = 0L

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg != IG) {
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !pkg.startsWith("com.android.systemui") &&
                !pkg.contains("inputmethod")
            ) {
                removeTabOverlay()
            }
            return
        }

        val root = rootInActiveWindow ?: return
        val screenW = resources.displayMetrics.widthPixels

        // 1. Repérer la barre d'onglets du bas et l'onglet Reels
        val reelsTab = findReelsTab(root, screenW)
        if (reelsTab == null || !reelsTab.isVisibleToUser) {
            removeTabOverlay()
            return
        }

        val tabRect = Rect()
        reelsTab.getBoundsInScreen(tabRect)

        // Si l'onglet Reels est hors écran (ex: tu es dans tes DM), on retire le petit cache
        if (tabRect.width() <= 0 || tabRect.height() <= 0 || tabRect.left < 0 || tabRect.right > screenW) {
            removeTabOverlay()
            return
        }

        // 2. Afficher uniquement le petit cache sur l'icône Reels dans la barre du bas
        showTabOverlay(tabRect)

        // 3. SI L'ONGLET REELS EST SÉLECTIONNÉ (via slide) -> SKIP AUTOMATIQUE !
        if (reelsTab.isSelected) {
            val now = System.currentTimeMillis()
            // Anti-rebond de 350ms pour éviter une boucle de clics pendant l'animation
            if (now - lastSkipTimestamp > 350L) {
                lastSkipTimestamp = now
                skipToNextTab(root, tabRect)
            }
        }
    }

    private fun skipToNextTab(root: AccessibilityNodeInfo, reelsTabRect: Rect) {
        val tabBar = root.findAccessibilityNodeInfosByViewId("$IG:id/tab_bar").firstOrNull()
        if (tabBar != null) {
            val tabs = mutableListOf<AccessibilityNodeInfo>()
            for (i in 0 until tabBar.childCount) {
                tabBar.getChild(i)?.let { if (it.isClickable) tabs.add(it) }
            }
            // Trier les onglets de gauche à droite selon leur position X
            tabs.sortBy {
                val r = Rect()
                it.getBoundsInScreen(r)
                r.left
            }
            // Chercher l'onglet situé juste après l'onglet Reels (ex: Profil ou Messages selon ta barre)
            val nextTab = tabs.firstOrNull {
                val r = Rect()
                it.getBoundsInScreen(r)
                r.left >= reelsTabRect.right - 10
            }
            if (nextTab != null) {
                nextTab.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }

        // Secours si la barre d'onglets n'a pas pu être parcourue : retour direct à l'onglet Accueil
        val homeTab = root.findAccessibilityNodeInfosByViewId("$IG:id/feed_tab").firstOrNull()
        homeTab?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun findReelsTab(root: AccessibilityNodeInfo, screenW: Int): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$IG:id/clips_tab")
            .firstOrNull()
            ?.let { return it }

        val screenH = resources.displayMetrics.heightPixels
        return root.findAccessibilityNodeInfosByText("Reels").firstOrNull {
            val b = Rect()
            it.getBoundsInScreen(b)
            b.top > screenH * 0.85 && b.width() < screenW / 3
        }
    }

    private fun showTabOverlay(r: Rect) {
        if (r == lastTabBounds && tabOverlay != null) return
        removeTabOverlay()

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
        tabOverlay = v
        lastTabBounds = Rect(r)
    }

    private fun removeTabOverlay() {
        tabOverlay?.let { runCatching { wm.removeView(it) } }
        tabOverlay = null
        lastTabBounds = null
    }

    override fun onInterrupt() = removeTabOverlay()

    override fun onDestroy() {
        removeTabOverlay()
        super.onDestroy()
    }
}
