package com.astra.vpn

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.rgb(15, 18, 30))
        }

        val title = TextView(this).apply {
            text = "ASTRA VPN"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = "Отключено"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        val button = Button(this).apply {
            text = "ПОДКЛЮЧИТЬСЯ"
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "VPN-движок будет подключён следующим этапом",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(button)

        setContentView(layout)
    }
}
