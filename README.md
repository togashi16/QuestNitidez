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
