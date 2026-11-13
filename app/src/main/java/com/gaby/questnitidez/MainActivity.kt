package com.gaby.questnitidez

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.content.Intent
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
    private lateinit var googleState: TextView
    private lateinit var downloadsBox: LinearLayout
    private lateinit var tabNitidez: View
    private lateinit var tabGoogle: View
    private lateinit var tabButtons: List<Button>
    private val store by lazy { GoogleStore(this) { setStatus(it) } }

    // Colores
    private val bg = Color.parseColor("#12141C")
    private val card = Color.parseColor("#1D2130")
    private val accent = Color.parseColor("#4FC3F7")
    private val fg = Color.parseColor("#E8EAF0")
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

        root.addView(label("Quest Nitidez", 28f, fg, bold = true))
        root.addView(label("Nitidez de los juegos y tienda de Google en tus Quest", 15f, muted).apply {
            setPadding(0, dp(4), 0, dp(12))
        })

        // Pestañas
        val tabs = row()
        val b1 = bigButton("Nitidez") { showTab(0) }
        val b2 = bigButton("Google / Play Store") { showTab(1) }
        tabButtons = listOf(b1, b2)
        tabs.addView(b1, weighted(64))
        tabs.addView(b2, weighted(64))
        root.addView(tabs)

        status = label("", 15f, fg).apply { setPadding(0, dp(12), 0, dp(8)) }
        root.addView(status)

        tabNitidez = buildNitidezTab()
        tabGoogle = buildGoogleTab()
        root.addView(tabNitidez)
        root.addView(tabGoogle)
        showTab(0)

        return ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        }
    }

    private fun showTab(i: Int) {
        tabNitidez.visibility = if (i == 0) View.VISIBLE else View.GONE
        tabGoogle.visibility = if (i == 1) View.VISIBLE else View.GONE
        tabButtons.forEachIndexed { idx, b ->
            (b.background as GradientDrawable).setColor(if (idx == i) Color.parseColor("#2B3550") else card)
        }
        if (i == 1) refreshGoogle()
    }

    private fun buildNitidezTab(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        current = label("Resolución actual: …", 17f, accent, bold = true)
        root.addView(current)

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

        return root
    }

    private fun buildGoogleTab(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        googleState = label("Estado: …", 16f, accent, bold = true)
        root.addView(googleState)

        // Paso 1: tienda
        root.addView(section("1 · Tienda de Google Play (Aurora Store)"))
        root.addView(note(
            "Aurora Store muestra el catálogo completo de Google Play. Entras con tu cuenta de Google " +
            "(o en modo anónimo), buscas cualquier app y la instalas. No necesita servicios de Google."
        ))
        val r1 = row()
        r1.addView(bigButton("Instalar / actualizar\nAurora Store") { installAurora() }, weighted())
        r1.addView(bigButton("Abrir Aurora Store") { launch(Pkg.AURORA) }, weighted())
        root.addView(r1)

        // Paso 2: Shizuku
        root.addView(section("2 · Instalación sin bloqueos (Shizuku)"))
        root.addView(note(
            "Las Quest no dejan que una tienda instale apps por su cuenta. Shizuku le presta a Aurora " +
            "los permisos de ADB. En Aurora: Ajustes → Instalación → Instalador → Shizuku, y acepta el permiso.\n" +
            "Tras reiniciar las gafas: adb tcpip 5555 y pulsa \"Arrancar Shizuku\"."
        ))
        val r2 = row()
        r2.addView(bigButton("Instalar Shizuku") { installShizuku() }, weighted())
        r2.addView(bigButton("Arrancar Shizuku") { startShizuku() }, weighted())
        root.addView(r2)

        // Paso 3: APKs de Descargas
        root.addView(section("3 · Instalar APKs de la carpeta Descargas"))
        root.addView(note(
            "Descarga un .apk, .apks, .apkm o .xapk con el navegador del visor y luego instálalo aquí. " +
            "Si están las 3 piezas de Google se instalan en el orden correcto."
        ))
        val r3 = row()
        r3.addView(bigButton("Buscar en Descargas") { scanDownloads() }, weighted())
        r3.addView(bigButton("Instalar todo lo encontrado") { installAllDownloads() }, weighted())
        root.addView(r3)
        downloadsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(downloadsBox)

        // Paso 4: Google Play oficial
        root.addView(section("4 · Google Play oficial (experimental)"))
        root.addView(note(
            "Solo si una app exige \"Servicios de Google Play\". Desde el navegador del visor descarga " +
            "a Descargas estas 3 apps (versión arm64-v8a, Android 12 / nodpi):\n" +
            "  • Google Services Framework\n" +
            "  • Google Play Services\n" +
            "  • Google Play Store\n" +
            "Luego pulsa \"Instalar todo lo encontrado\" y abre Play Store para entrar con tu cuenta.\n" +
            "Aviso: las Quest no están certificadas por Google; algunas apps (bancos, Netflix, " +
            "notificaciones push) pueden no funcionar, y Meta puede romperlo en una actualización."
        ))
        val r4 = row()
        r4.addView(bigButton("Abrir Play Store") { launch(Pkg.VENDING) }, weighted())
        r4.addView(bigButton("Quitar Google Play") { removeGooglePlay() }, weighted())
        root.addView(r4)

        return root
    }

    private fun note(t: String) = label(t, 14f, muted).apply { setPadding(0, 0, 0, dp(6)) }

    private fun label(t: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun section(t: String) = label(t, 18f, fg, bold = true).apply {
        setPadding(0, dp(18), 0, dp(8))
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun weighted(height: Int = 84) = LinearLayout.LayoutParams(0, dp(height), 1f).apply {
        setMargins(dp(6), dp(6), dp(6), dp(6))
    }

    private fun bigButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 16f
        setTextColor(fg)
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
        status.setTextColor(if (ok) fg else Color.parseColor("#FF8A80"))
    }

    // ---------------- ADB ----------------

    private fun keyPair(): AdbKeyPair {
        val priv = File(filesDir, "adbkey")
        val pub = File(filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    @Volatile private var busy = false

    /**
     * Abre una conexion con el ADB de las propias gafas y ejecuta [block] en segundo plano.
     * El resultado se entrega en el hilo principal con [onDone].
     */
    private fun <T> withAdb(block: (Dadb) -> T, onDone: (T) -> Unit) {
        if (busy) { setStatus("Espera, hay una operación en curso…"); return }
        busy = true
        setStatus("Conectando…")
        worker.execute {
            var deliver: (() -> Unit)? = null
            try {
                Dadb.create("127.0.0.1", 5555, keyPair()).use { adb ->
                    try {
                        adb.shell("echo ok")
                    } catch (e: Exception) {
                        setStatus(
                            "No pude conectar con el ADB de las gafas.\n" +
                            "• Ejecuta  adb tcpip 5555  desde la PC (otra vez si reiniciaste).\n" +
                            "• Acepta \"Permitir depuración\" en el visor y vuelve a intentar.\n" +
                            "(${e.javaClass.simpleName}: ${e.message ?: "sin detalle"})",
                            ok = false
                        )
                        return@use
                    }
                    val r = block(adb)
                    deliver = { onDone(r) }
                }
            } catch (e: OpError) {
                setStatus(e.message ?: "Error", ok = false)
            } catch (e: Exception) {
                setStatus("Error: ${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}", ok = false)
            } finally {
                busy = false
            }
            // Se entrega despues de liberar "busy" para que onDone pueda encadenar otra operacion
            deliver?.let { d -> main.post { d() } }
        }
    }

    /** Ejecuta comandos en el shell de ADB de las propias gafas. */
    private fun runShell(vararg cmds: String, onDone: (List<String>) -> Unit) =
        withAdb({ adb -> cmds.map { adb.shell(it).allOutput.trim() } }, onDone)

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

    // ---------------- Google / Play Store ----------------

    private fun refreshGoogle() {
        withAdb({ adb -> store.installedPackages(adb) to adb.shell("pidof shizuku_server").output.isNotBlank() }) { (pkgs, shizukuOn) ->
            fun mark(p: String) = if (p in pkgs) "✔" else "✘"
            googleState.text =
                "Aurora Store ${mark(Pkg.AURORA)}   ·   Shizuku ${mark(Pkg.SHIZUKU)}" +
                (if (Pkg.SHIZUKU in pkgs) (if (shizukuOn) " (activo)" else " (detenido)") else "") + "\n" +
                "Google Play: Framework ${mark(Pkg.GSF)}  Services ${mark(Pkg.GMS)}  Store ${mark(Pkg.VENDING)}"
            setStatus("Conectado ✔")
        }
    }

    private fun installAurora() = withAdb({ store.installAurora(it) }) {
        setStatus("Aurora Store instalada ✔ Ábrela, elige \"Cuenta de Google\" o \"Anónima\" y busca tus apps.\n" +
            "Recomendado: instala también Shizuku (paso 2).")
        refreshGoogle()
    }

    private fun installShizuku() = withAdb({ store.installShizuku(it) }) {
        setStatus("Shizuku instalado y activo ✔\nEn Aurora: Ajustes → Instalación → Instalador → Shizuku.")
        refreshGoogle()
    }

    private fun startShizuku() = withAdb({ store.startShizuku(it) }) {
        setStatus("Shizuku activo ✔ (se detiene al reiniciar las gafas).")
        refreshGoogle()
    }

    private var found: List<RemoteApk> = emptyList()

    private fun scanDownloads() = withAdb({ store.listDownloads(it) }) { list ->
        found = list
        downloadsBox.removeAllViews()
        if (list.isEmpty()) {
            setStatus("No hay .apk / .apks / .apkm / .xapk en la carpeta Descargas.", ok = false)
            return@withAdb
        }
        list.forEach { apk ->
            downloadsBox.addView(bigButton("Instalar  ${apk.name}") { installDownloads(listOf(apk)) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60)).apply {
                    setMargins(dp(6), dp(4), dp(6), dp(4))
                })
        }
        setStatus("Encontré ${list.size} archivo(s). Toca uno para instalarlo o \"Instalar todo\".")
    }

    private fun installAllDownloads() {
        if (found.isEmpty()) {
            withAdb({ store.listDownloads(it) }) { list ->
                found = list
                if (list.isEmpty()) setStatus("No hay APKs en la carpeta Descargas.", ok = false)
                else installDownloads(list)
            }
        } else installDownloads(found)
    }

    private fun installDownloads(items: List<RemoteApk>) = withAdb({ store.installFromDownloads(it, items) }) { done ->
        setStatus("Instalado ✔\n" + done.joinToString("\n") +
            "\nLas apps aparecen en Biblioteca → Fuentes desconocidas.")
        refreshGoogle()
    }

    private fun removeGooglePlay() = withAdb({ adb ->
        listOf(Pkg.VENDING, Pkg.GMS, Pkg.GSF).map { p -> adb.shell("pm uninstall $p").allOutput.trim() }
    }) {
        setStatus("Google Play quitado. Aurora Store sigue funcionando.")
        refreshGoogle()
    }

    /** Abre una app instalada (ventana 2D dentro del visor). */
    private fun launch(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            // Respaldo: lanzarla desde el shell de ADB
            withAdb({ adb -> adb.shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1").allOutput }) { out ->
                if ("No activities found" in out || "not found" in out)
                    setStatus("Esa app no está instalada todavía.", ok = false)
                else setStatus("Abriendo…")
            }
        }
    }
}
