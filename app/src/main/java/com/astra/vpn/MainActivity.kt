package com.astra.vpn

import android.app.Activity
import android.app.AlertDialog
import android.net.VpnService
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var profileStore: ProfileStore
    private lateinit var profileList: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileStore = ProfileStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(28, 48, 28, 28)
            setBackgroundColor(Color.rgb(13, 17, 28))
        }

        val title = TextView(this).apply {
            text = "ASTRA VPN"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val subtitle = TextView(this).apply {
            text = "Защищённое подключение"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            text = "Движок подключения готовится"
            textSize = 14f
            setTextColor(Color.rgb(255, 196, 92))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 20)
        }

        val importButton = Button(this).apply {
            text = "ДОБАВИТЬ СЕРВЕР"
            setOnClickListener { showImportDialog() }
        }

        val connectButton = Button(this).apply {
            text = "ПОДКЛЮЧЕНИЕ ПОКА НЕДОСТУПНО"
            isEnabled = false
        }

        val listTitle = TextView(this).apply {
            text = "Мои серверы"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, 28, 0, 10)
        }
        profileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(importButton, matchWidth())
        root.addView(connectButton, matchWidth())
        root.addView(listTitle)
        root.addView(profileList)

        setContentView(root)
        renderProfiles()
    }

    private fun showImportDialog() {
        val input = EditText(this).apply {
            hint = "vless://… или hysteria2://…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setPadding(24, 18, 24, 18)
        }
        AlertDialog.Builder(this)
            .setTitle("Импорт сервера")
            .setMessage("Вставь ссылку подключения. Она будет сохранена в зашифрованном виде на этом устройстве.")
            .setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Добавить", null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val link = input.text.toString().trim()
                        if (!isSupportedLink(link)) {
                            input.error = "Проверь ссылку и её протокол"
                            return@setOnClickListener
                        }
                        profileStore.add(link)
                        dialog.dismiss()
                        renderProfiles()
                        Toast.makeText(this, "Сервер сохранён", Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.show()
            }
    }

    private fun isSupportedLink(link: String): Boolean {
        val scheme = link.substringBefore("://", "").lowercase()
        return link.length in 12..16_384 &&
            scheme in setOf("vless", "vmess", "trojan", "ss", "ssr", "hysteria2", "hy2", "tuic")
    }

    private fun renderProfiles() {
        profileList.removeAllViews()
        val profiles = profileStore.all()
        if (profiles.isEmpty()) {
            profileList.addView(TextView(this).apply {
                text = "Пока нет добавленных серверов"
                setTextColor(Color.LTGRAY)
                textSize = 14f
            })
            return
        }

        profiles.forEach { link ->
            val label = link.substringAfter('#', "").ifBlank {
                link.substringBefore("://").uppercase()
            }.take(48)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }
            val name = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val delete = Button(this).apply {
                text = "Удалить"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("Удалить этот сервер?")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить") { _, _ ->
                            profileStore.remove(link)
                            renderProfiles()
                        }
                        .show()
                }
            }
            row.addView(name)
            row.addView(delete)
            profileList.addView(row)
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
