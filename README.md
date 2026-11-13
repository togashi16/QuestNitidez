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

---

# Quest Modo Shooter (programa para la PC)

Para jugar **juegos de PC (shooters) en las Quest con el mouse de la PC y el menor retraso posible**.

La imagen del juego llega a las gafas por streaming (Virtual Desktop, Air Link / Quest Link o Steam Link). El mouse va conectado a la PC, así que el retraso que notas al apuntar es el tiempo que tarda la imagen en llegar a tus ojos. **Latencia cero es físicamente imposible** (hay que comprimir, enviar y descomprimir cada imagen), pero bien configurado queda en **20–35 ms en total**, que se siente como un monitor normal. Este programa deja la PC lista para eso y te dice qué falla.

## Descargar
Pestaña **Actions** → último "Compilar APK" → descarga **QuestModoShooter-PC** (zip con `QuestModoShooter.bat` y `QuestModoShooter.ps1`). Descomprime ambos en la misma carpeta.

## Usar
Doble clic en **`QuestModoShooter.bat`** (pide permiso de administrador) y:

| Opción | Qué hace |
|---|---|
| **1 · Activar modo shooter** | Quita la aceleración del mouse, pone el plan de energía de máximo rendimiento, activa el Modo de juego y apaga la grabación en segundo plano de Xbox Game Bar. Guarda un respaldo antes. |
| **2 · Modo partida** | Déjalo abierto mientras juegas: pone en prioridad Alta el programa que transmite a las gafas (Virtual Desktop, Link, SteamVR) para que el juego no lo frene. `Q` para salir. |
| **3 · Diagnóstico** | Revisa si la PC va por cable o Wi-Fi, los Hz del monitor, la tarjeta de video y mide el ping y la variación hasta las gafas. |
| **4 · Ajustes recomendados** | Lista de ajustes para el juego, Virtual Desktop, Link y Steam Link. |
| **5 · Restaurar** | Deja Windows exactamente como estaba. |

## Lo que más reduce el retraso (en orden)
1. **PC al router por cable**; gafas por Wi-Fi 5 GHz/6 GHz con el router en la misma habitación. O cable USB-C con Quest Link.
2. **120 Hz** en las gafas y el juego por encima de 120 FPS.
3. En el juego: **V-Sync apagado** y **NVIDIA Reflex (Activado + Boost)** o AMD Anti-Lag.
4. Virtual Desktop: códec **H.264+**, 100–150 Mbps, *Video buffering* apagado. Mira la "Total latency" en la superposición de rendimiento.
5. Si transmites el escritorio, el monitor de la PC a 120/144 Hz (a 60 Hz la imagen sale a 60 fps).
