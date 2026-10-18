package com.gaby.questnitidez

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.util.concurrent.Executors

/**
 * Quest Nitidez
 * Cambia la resolucion de render de las Meta Quest 3S desde dentro de las gafas.
 *
 * Como funciona: las apps normales no pueden cambiar "debug.oculus.*".
 * Solo el shell de ADB puede. Por eso esta app se conecta al ADB inalambrico
 * de las propias gafas (127.0.0.1:5555) y ejecuta los comandos ahi.
 *
 * Requisito: activar una vez desde la PC con "adb tcpip 5555"
 * (hay que repetirlo despues de cada reinicio de las gafas).
 */
class MainActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var current: TextView

    // Colores
    private val bg = Color.parseColor("#12141C")
    private val card = Color.parseColor("#1D2130")
    private val accent = Color.parseColor("#4FC3F7")
    private val text = Color.parseColor("#E8EAF0")
    private val muted = Color.parseColor("#9AA0B4")

    data class Preset(val name: String, val detail: String, val w: Int, val h: Int)

    private val presets = listOf(
        Preset("Fábrica", "1680 × 1760", 1680, 1760),
        Preset("Nitidez media", "2016 × 2112 · recomendado", 2016, 2112),
        Preset("Nitidez alta", "2352 × 2464 · puede bajar FPS", 2352, 2464),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshCurrent()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(28), dp(32), dp(28))
            setBackgroundColor(bg)
        }

        root.addView(label("Quest Nitidez", 28f, text, bold = true))
        root.addView(label("Sube la resolución de render para ver los juegos más nítidos", 15f, muted).apply {
            setPadding(0, dp(4), 0, dp(16))
        })

        current = label("Resolución actual: …", 17f, accent, bold = true)
        root.addView(current)

        status = label("", 15f, text).apply { setPadding(0, dp(8), 0, dp(16)) }
        root.addView(status)

        // Resolucion
        root.addView(section("Resolución por ojo"))
        val presetRow = row()
        presets.forEach { p ->
            presetRow.addView(bigButton("${p.name}\n${p.detail}") { applyResolution(p) }, weighted())
        }
        root.addView(presetRow)

        // Frecuencia
        root.addView(section("Frecuencia de refresco (Hz)"))
        val hzRow = row()
        listOf(72, 90, 120).forEach { hz ->
            hzRow.addView(bigButton("$hz Hz") { applyRefresh(hz) }, weighted())
        }
        root.addView(hzRow)

        // Utilidades
        root.addView(section("Herramientas"))
        val toolsRow = row()
        toolsRow.addView(bigButton("Probar conexión") { refreshCurrent() }, weighted())
        toolsRow.addView(bigButton("Restaurar todo") { restoreAll() }, weighted())
        root.addView(toolsRow)

        root.addView(label(
            "Cómo usar:\n" +
            "1. Una vez por reinicio: conecta las gafas a la PC y ejecuta  adb tcpip 5555\n" +
            "2. Desconecta el cable y abre esta app.\n" +
            "3. La primera vez, acepta \"Permitir depuración\" dentro del visor.\n" +
            "4. Elige una resolución y vuelve a abrir el juego para notar el cambio.\n\n" +
            "Los cambios se borran al reiniciar las gafas.",
            14f, muted
        ).apply { setPadding(0, dp(20), 0, 0) })

        return ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
    }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun section(t: String) = label(t, 18f, text, bold = true).apply {
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun weighted() = LinearLayout.LayoutParams(0, dp(84), 1f).apply {
        setMargins(dp(6), dp(6), dp(6), dp(6))
    }

    private fun bigButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 16f
        setTextColor(text)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(card)
            setStroke(dp(2), accent)
        }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setStatus(msg: String, ok: Boolean = true) = main.post {
        status.text = msg
        status.setTextColor(if (ok) text else Color.parseColor("#FF8A80"))
    }

    // ---------------- ADB ----------------

    private fun keyPair(): AdbKeyPair {
        val priv = File(filesDir, "adbkey")
        val pub = File(filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Ejecuta comandos en el shell de ADB de las propias gafas. */
    private fun runShell(vararg cmds: String, onDone: (List<String>) -> Unit) {
        setStatus("Conectando…")
        worker.execute {
            try {
                val out = mutableListOf<String>()
                Dadb.create("127.0.0.1", 5555, keyPair()).use { adb ->
                    cmds.forEach { out += adb.shell(it).allOutput.trim() }
                }
                main.post { onDone(out) }
            } catch (e: Exception) {
                setStatus(
                    "No pude conectar con el ADB de las gafas.\n" +
                    "• Ejecuta  adb tcpip 5555  desde la PC (otra vez si reiniciaste).\n" +
                    "• Acepta \"Permitir depuración\" en el visor y vuelve a intentar.\n" +
                    "(${e.javaClass.simpleName}: ${e.message ?: "sin detalle"})",
                    ok = false
                )
            }
        }
    }

    private fun refreshCurrent() {
        runShell(
            "getprop debug.oculus.textureWidth",
            "getprop debug.oculus.textureHeight",
            "getprop debug.oculus.refreshRate"
        ) { r ->
            val w = r[0].ifEmpty { "1680" }
            val h = r[1].ifEmpty { "1760" }
            val hz = r[2].ifEmpty { "predeterminada" }
            current.text = "Resolución actual: $w × $h por ojo · Frecuencia: $hz"
            setStatus("Conectado ✔")
        }
    }

    private fun applyResolution(p: Preset) {
        runShell(
            "setprop debug.oculus.textureWidth ${p.w}",
            "setprop debug.oculus.textureHeight ${p.h}"
        ) {
            setStatus("Listo: ${p.name} (${p.w} × ${p.h}). Cierra y vuelve a abrir el juego.")
            refreshCurrent()
        }
    }

    private fun applyRefresh(hz: Int) {
        runShell("setprop debug.oculus.refreshRate $hz") {
            setStatus("Frecuencia puesta en $hz Hz. Vuelve a abrir el juego.")
            refreshCurrent()
        }
    }

    private fun restoreAll() {
        runShell(
            "setprop debug.oculus.textureWidth 1680",
            "setprop debug.oculus.textureHeight 1760"
        ) {
            setStatus("Resolución de fábrica restaurada. (La frecuencia vuelve a la normal al reiniciar.)")
            refreshCurrent()
        }
    }
}
