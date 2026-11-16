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

        // Couleur de la barre du bas d'Instagram en mode sombre.
        // Ajuste si tu vois un rectangle de teinte différente (ex: 0xFF000000.toInt()).
        private const val COLOR_DARK = 0xFF0B1014.toInt()
        private const val COLOR_LIGHT = 0xFFFFFFFF.toInt()
    }

    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var overlay: View? = null
    private var lastBounds: Rect? = null

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName) return

        if (pkg != IG) {
            // On a quitté Instagram (hors barre de notifs / clavier) : on retire le cache.
            if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !pkg.startsWith("com.android.systemui") &&
                !pkg.contains("inputmethod")
            ) removeOverlay()
            return
        }

        val root = rootInActiveWindow ?: return
        val tab = findReelsTab(root)
        if (tab == null) {
            removeOverlay()
            return
        }
        val r = Rect()
        tab.getBoundsInScreen(r)
        showOverlay(r)
    }

    private fun findReelsTab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1) id connu de l'onglet Reels
        root.findAccessibilityNodeInfosByViewId("$IG:id/clips_tab").firstOrNull()?.let { return it }

        // 2) Secours : un noeud "Reels" situé dans le bas de l'écran
        val screenH = resources.displayMetrics.heightPixels
        return root.findAccessibilityNodeInfosByText("Reels").firstOrNull {
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
