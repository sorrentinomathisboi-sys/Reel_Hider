package com.example.reelshider

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            text = "Active « ReelsHider » dans Accessibilité > Applications installées.\n" +
                    "L'onglet Reels d'Instagram sera alors masqué."
            textSize = 16f
            setPadding(48, 48, 48, 48)
        }
        val button = Button(this).apply {
            this.text = "Ouvrir les réglages d'accessibilité"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(text)
            addView(button)
        })
    }
}
