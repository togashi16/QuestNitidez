# Quest Nitidez

App para Meta Quest 3S que cambia la resolución de render (y la frecuencia en Hz) desde dentro de las gafas, al estilo de Quest Games Optimizer.

## Cómo obtener el APK
GitHub lo compila solo: pestaña **Actions** → último "Compilar APK" → descarga **QuestNitidez-apk** (viene en un .zip; dentro está `app-debug.apk`).

## Instalar en las gafas
1. Modo desarrollador activado (app Meta Horizon del celular).
2. Instala el APK con SideQuest o con `adb install app-debug.apk`.
3. Aparece en la Biblioteca → **Fuentes desconocidas**.

## Usar
1. Con las gafas conectadas a la PC: `adb tcpip 5555` (repetir tras cada reinicio de las gafas).
2. Desconecta el cable y abre **Quest Nitidez**.
3. La primera vez acepta "Permitir depuración" en el visor.
4. Elige una resolución y vuelve a abrir el juego.

Los cambios se borran al reiniciar las gafas. Si un juego va a tirones, usa "Nitidez media" o "Fábrica".

## Google / Play Store en las Quest

Pestaña **Google / Play Store** dentro de la app (usa la misma conexión `adb tcpip 5555`):

1. **Aurora Store**: botón *Instalar / actualizar Aurora Store*. Descarga la última versión desde F-Droid y la instala. Es un cliente del catálogo de Google Play: entras con tu cuenta de Google (o en modo anónimo), buscas cualquier app y la instalas. No necesita servicios de Google.
2. **Shizuku**: botón *Instalar Shizuku*. Le da a Aurora permisos de ADB para instalar y actualizar apps sin bloqueos. En Aurora: *Ajustes → Instalación → Instalador → Shizuku*. Tras reiniciar las gafas: `adb tcpip 5555` y *Arrancar Shizuku*.
3. **APKs de Descargas**: baja un `.apk`, `.apks`, `.apkm` o `.xapk` con el navegador del visor y pulsa *Buscar en Descargas* (incluye OBB de los `.xapk`).
4. **Google Play oficial (experimental)**: si una app exige "Servicios de Google Play", descarga a Descargas *Google Services Framework*, *Google Play Services* y *Google Play Store* (arm64-v8a, Android 12, nodpi) y pulsa *Instalar todo lo encontrado*: se instalan en orden y con permisos. Las Quest no están certificadas por Google, así que algunas apps (bancos, Netflix, notificaciones push) pueden fallar. *Quitar Google Play* lo desinstala.

Las apps instaladas aparecen en **Biblioteca → Fuentes desconocidas**.
