package com.gaby.questnitidez

import android.content.Context
import dadb.Dadb
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/** Error con un mensaje listo para mostrar al usuario. */
class OpError(msg: String) : Exception(msg)

object Pkg {
    const val AURORA = "com.aurora.store"
    const val SHIZUKU = "moe.shizuku.privileged.api"
    const val GSF = "com.google.android.gsf"
    const val GMS = "com.google.android.gms"
    const val VENDING = "com.android.vending"
}

/** Un APK (o paquete .apks/.apkm/.xapk) encontrado en la carpeta Descargas de las gafas. */
data class RemoteApk(val path: String) {
    val name get() = path.substringAfterLast('/')
}

/**
 * Todo lo necesario para tener la tienda de Google en las Quest.
 *
 * Las Quest no traen Google Play ni dejan que una app normal instale otras apps.
 * Pero esta app ya habla con el ADB de las propias gafas, y el shell de ADB SI puede
 * instalar APKs y dar permisos. Con eso montamos:
 *
 *  1. Aurora Store: cliente libre del catálogo de Google Play (entras con tu cuenta
 *     de Google o en modo anónimo) que funciona sin servicios de Google.
 *  2. Shizuku: le presta los permisos del shell de ADB a Aurora para que instale
 *     y actualice apps sin pedir confirmación ni chocar con los bloqueos de Meta.
 *  3. Un instalador de APKs / bundles desde la carpeta Descargas (por si bajas
 *     algo desde el navegador del visor).
 *  4. Google Play oficial (experimental): instala en orden Google Services Framework,
 *     Google Play Services y Play Store si los descargas a la carpeta Descargas.
 */
class GoogleStore(private val ctx: Context, private val progress: (String) -> Unit) {

    private val cache = File(ctx.cacheDir, "apks").apply { mkdirs() }

    // ---------------- Estado ----------------

    fun installedPackages(adb: Dadb): Set<String> =
        adb.shell("pm list packages").output.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    // ---------------- Aurora Store ----------------

    fun installAurora(adb: Dadb) {
        progress("Buscando la última versión de Aurora Store…")
        val info = JSONObject(httpGet("https://f-droid.org/api/v1/packages/${Pkg.AURORA}"))
        val code = info.optLong("suggestedVersionCode", -1).takeIf { it > 0 }
            ?: info.getJSONArray("packages").getJSONObject(0).getLong("versionCode")
        val apk = File(cache, "aurora.apk")
        download("https://f-droid.org/repo/${Pkg.AURORA}_$code.apk", apk, "Aurora Store")
        installFiles(adb, listOf(apk), "Aurora Store")
        apk.delete()
        // Permitir que Aurora instale apps y que no la mate el ahorro de batería
        adb.shell("appops set ${Pkg.AURORA} REQUEST_INSTALL_PACKAGES allow")
        adb.shell("dumpsys deviceidle whitelist +${Pkg.AURORA}")
    }

    // ---------------- Shizuku ----------------

    fun installShizuku(adb: Dadb) {
        progress("Buscando la última versión de Shizuku…")
        val rel = JSONObject(httpGet("https://api.github.com/repos/RikkaApps/Shizuku/releases/latest"))
        val assets = rel.getJSONArray("assets")
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); break }
        }
        url ?: throw OpError("No encontré el APK de Shizuku en GitHub. Inténtalo más tarde.")
        val apk = File(cache, "shizuku.apk")
        download(url, apk, "Shizuku")
        installFiles(adb, listOf(apk), "Shizuku")
        apk.delete()
        startShizuku(adb)
    }

    /** Arranca el servicio de Shizuku con permisos de shell. Hay que repetirlo tras reiniciar. */
    fun startShizuku(adb: Dadb): String {
        progress("Arrancando Shizuku…")
        val apkPath = adb.shell("pm path ${Pkg.SHIZUKU}").output.lineSequence()
            .firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim()
            ?: throw OpError("Shizuku no está instalado.")
        val libDir = apkPath.substringBeforeLast('/') + "/lib"
        // Versiones nuevas: el arrancador es libshizuku.so. Versiones viejas: start.sh
        val cmd = "for f in $libDir/*/libshizuku.so; do [ -x \"\$f\" ] && exec \"\$f\"; done; " +
            "sh /storage/emulated/0/Android/data/${Pkg.SHIZUKU}/start.sh"
        val out = adb.shell(cmd).allOutput
        Thread.sleep(1500)
        val running = adb.shell("pidof shizuku_server").output.isNotBlank()
        if (!running && !out.contains("exit with 0")) {
            throw OpError(
                "Shizuku no arrancó. Abre la app Shizuku una vez y vuelve a pulsar \"Arrancar Shizuku\".\n" +
                out.lines().takeLast(3).joinToString("\n")
            )
        }
        return out
    }

    // ---------------- APKs de Descargas ----------------

    fun listDownloads(adb: Dadb): List<RemoteApk> {
        val out = adb.shell("ls -1t /sdcard/Download/ 2>/dev/null").output
        return out.lineSequence()
            .map { it.trim() }
            .filter { n -> EXTS.any { n.lowercase().endsWith(it) } }
            .map { RemoteApk("/sdcard/Download/$it") }
            .toList()
    }

    /**
     * Instala varios APKs/bundles de Descargas. Si entre ellos están las piezas de Google
     * (Services Framework, Play Services, Play Store) se instalan en el orden correcto
     * y se les dan los permisos que necesitan.
     */
    fun installFromDownloads(adb: Dadb, items: List<RemoteApk>): List<String> {
        data class Prepared(val pkg: String, val label: String, val apks: List<File>, val obbs: List<Pair<File, String>>, val dir: File)

        val prepared = items.mapIndexed { i, item ->
            progress("Copiando ${item.name} (${i + 1}/${items.size})…")
            val dir = File(cache, "dl$i").apply { deleteRecursively(); mkdirs() }
            val local = File(dir, item.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            adb.pull(local, item.path)
            val (apks, obbs) = if (local.name.lowercase().endsWith(".apk")) {
                listOf(local) to emptyList()
            } else {
                progress("Descomprimiendo ${item.name}…")
                unpackBundle(local, dir).also { local.delete() }
            }
            if (apks.isEmpty()) throw OpError("${item.name} no contiene ningún APK.")
            val pkg = apks.firstNotNullOfOrNull { packageOf(it) }
                ?: throw OpError("${item.name} no parece un APK válido (¿descarga incompleta?).")
            Prepared(pkg, item.name, apks, obbs, dir)
        }.sortedBy { ORDER[it.pkg] ?: 99 }

        val done = mutableListOf<String>()
        try {
            prepared.forEach { p ->
                installFiles(adb, p.apks, p.label)
                p.obbs.forEach { (file, remote) ->
                    progress("Copiando datos OBB de ${p.label}…")
                    adb.shell("mkdir -p \"${remote.substringBeforeLast('/')}\"")
                    adb.push(file, remote)
                }
                if (p.pkg in ORDER) grantGoogle(adb, p.pkg)
                done += "${p.label}  →  ${p.pkg}"
            }
        } finally {
            prepared.forEach { it.dir.deleteRecursively() }
        }
        return done
    }

    /** Permisos y ajustes que las piezas de Google necesitan al no ser apps de sistema. */
    private fun grantGoogle(adb: Dadb, pkg: String) {
        progress("Dando permisos a $pkg…")
        val perms = when (pkg) {
            Pkg.GMS -> listOf(
                "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.READ_PHONE_STATE",
                "android.permission.GET_ACCOUNTS", "android.permission.READ_CONTACTS",
                "android.permission.POST_NOTIFICATIONS", "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT"
            )
            Pkg.VENDING -> listOf(
                "android.permission.READ_PHONE_STATE", "android.permission.GET_ACCOUNTS",
                "android.permission.POST_NOTIFICATIONS"
            )
            else -> listOf("android.permission.READ_PHONE_STATE", "android.permission.GET_ACCOUNTS")
        }
        // Un permiso que no existe en esta versión simplemente falla; no pasa nada.
        adb.shell(perms.joinToString("; ") { "pm grant $pkg $it 2>/dev/null" } + "; true")
        adb.shell("dumpsys deviceidle whitelist +$pkg")
        if (pkg == Pkg.VENDING) {
            adb.shell("appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        }
    }

    // ---------------- Utilidades ----------------

    private fun installFiles(adb: Dadb, apks: List<File>, label: String) {
        progress("Instalando $label…")
        try {
            // -r reemplaza si ya existe, -g concede todos los permisos de ejecución
            if (apks.size == 1) adb.install(apks[0], "-r", "-g")
            else adb.installMultiple(apks, "-r", "-g")
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            throw OpError(
                "No se pudo instalar $label.\n" + when {
                    "INSUFFICIENT_STORAGE" in msg -> "No queda espacio en las gafas."
                    "UPDATE_INCOMPATIBLE" in msg || "SIGNATURES" in msg ->
                        "Ya hay otra versión firmada distinta. Desinstálala primero."
                    "NO_MATCHING_ABIS" in msg -> "Ese APK no es para este procesador (busca la versión arm64-v8a)."
                    "OLDER_SDK" in msg -> "Ese APK necesita una versión de Android más nueva que la de las Quest."
                    "VERSION_DOWNGRADE" in msg -> "Ya tienes una versión más nueva instalada."
                    else -> msg.take(300)
                }
            )
        }
    }

    /** Extrae los APK (y los OBB de un .xapk) de un bundle .apks / .apkm / .xapk. */
    private fun unpackBundle(bundle: File, dir: File): Pair<List<File>, List<Pair<File, String>>> {
        val apks = mutableListOf<File>()
        val obbs = mutableListOf<Pair<File, String>>()
        try {
            ZipFile(bundle).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                    val lower = e.name.lowercase()
                    val isApk = lower.endsWith(".apk")
                    val isObb = lower.endsWith(".obb") && lower.startsWith("android/obb/")
                    if (!isApk && !isObb) return@forEach
                    val out = File(dir, "${apks.size + obbs.size}_" + e.name.substringAfterLast('/'))
                    zip.getInputStream(e).use { inp -> out.outputStream().use { inp.copyTo(it) } }
                    if (isApk) apks += out else obbs += out to "/sdcard/${e.name}"
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw OpError("${bundle.name} está dañado o cifrado. Si es .apkm de APKMirror protegido, descarga el .apk normal.")
        }
        return apks to obbs
    }

    private fun packageOf(apk: File): String? =
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName

    private fun httpGet(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, dest: File, label: String) {
        val c = open(url)
        try {
            val total = c.contentLengthLong
            var read = 0L
            var lastShown = -1L
            c.inputStream.use { inp ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) read * 100 / total else read / (1024 * 1024)
                        if (pct != lastShown) {
                            lastShown = pct
                            progress(
                                if (total > 0) "Descargando $label… $pct%"
                                else "Descargando $label… $pct MB"
                            )
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val c = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "QuestNitidez")
                setRequestProperty("Accept", "application/json, application/octet-stream, */*")
            }
            val code = try {
                c.responseCode
            } catch (e: Exception) {
                throw OpError("Sin conexión a Internet en las gafas (${e.javaClass.simpleName}).")
            }
            if (code in 300..399) {
                current = c.getHeaderField("Location") ?: throw OpError("Redirección inválida en $current")
                c.disconnect()
                return@repeat
            }
            if (code != 200) {
                c.disconnect()
                throw OpError("El servidor respondió $code al descargar $current")
            }
            return c
        }
        throw OpError("Demasiadas redirecciones en $url")
    }

    companion object {
        val EXTS = listOf(".apk", ".apks", ".apkm", ".xapk")
        /** Orden obligatorio de instalación de las piezas de Google. */
        val ORDER = mapOf(Pkg.GSF to 0, Pkg.GMS to 1, Pkg.VENDING to 2)
    }
}
