#Requires -Version 5.1
<#
  Quest Modo Shooter
  ------------------
  Prepara tu PC con Windows para jugar shooters de PC en las Meta Quest
  (Virtual Desktop, Air Link / Quest Link o Steam Link) con el menor
  retraso posible en el mouse.

  Qué hace:
    1. Activar modo shooter (cambios en Windows, se pueden deshacer):
       - Quita la aceleración del mouse ("Mejorar precisión del puntero").
       - Pone el plan de energía de máximo rendimiento.
       - Activa el Modo de juego de Windows.
       - Apaga la grabación en segundo plano de Xbox Game Bar (Game DVR).
    2. Modo partida: déjalo abierto mientras juegas. Sube la prioridad del
       programa que transmite a las gafas para que el juego no lo frene.
    3. Diagnóstico: revisa tu red, tu monitor y tu tarjeta de video, y mide
       el ping real hasta las gafas.
    4. Restaurar: deja todo como estaba antes.

  Ningún programa puede dar latencia CERO por Wi-Fi: la imagen hay que
  comprimirla, enviarla y descomprimirla. Lo que sí se puede es bajarla
  al mínimo (unos 20-35 ms en total), que en la práctica se siente como
  jugar en un monitor normal.
#>

# 'Continue' para que los avisos de powercfg no corten el programa;
# donde hace falta se usa -ErrorAction Stop explícitamente.
$ErrorActionPreference = 'Continue'
try { $Host.UI.RawUI.WindowTitle = 'Quest Modo Shooter' } catch {}

# ------------------------------------------------------------------
# Comprobaciones iniciales
# ------------------------------------------------------------------

$principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Host ''
    Write-Host 'Abre este programa con "QuestModoShooter.bat" (pide permisos de administrador).' -ForegroundColor Red
    Write-Host ''
    Read-Host 'Pulsa Enter para salir'
    exit 1
}

$BackupDir  = Join-Path $env:LOCALAPPDATA 'QuestModoShooter'
$BackupFile = Join-Path $BackupDir 'respaldo.json'

# Plan de energía "Máximo rendimiento" (Ultimate Performance) y "Alto rendimiento".
$UltimateTemplate = 'e9a42b02-d5df-448d-aa00-03f14749eb61'
$HighPerfScheme   = '8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c'

# Programas que transmiten la imagen a las gafas.
$StreamerProcesses = @(
    'VirtualDesktop.Streamer',   # Virtual Desktop
    'OVRServer_x64',             # Air Link / Quest Link (app Meta Quest Link)
    'vrserver', 'vrcompositor',  # SteamVR / Steam Link / ALVR
    'ALVR Dashboard', 'alvr_dashboard'
)

if (-not ('QMS.Native' -as [type])) {
    Add-Type -Namespace QMS -Name Native -MemberDefinition @'
[DllImport("user32.dll", SetLastError = true)]
public static extern bool SystemParametersInfo(uint uiAction, uint uiParam, [In, Out] int[] pvParam, uint fWinIni);
'@
}

$SPI_GETMOUSE = 0x0003
$SPI_SETMOUSE = 0x0004
$SPIF_UPDATE_AND_SEND = 0x0003   # SPIF_UPDATEINIFILE | SPIF_SENDCHANGE

# ------------------------------------------------------------------
# Utilidades
# ------------------------------------------------------------------

function Write-Ok($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  [!]  $msg" -ForegroundColor Yellow }
function Write-Bad($msg)  { Write-Host "  [X]  $msg" -ForegroundColor Red }
function Write-Info($msg) { Write-Host "       $msg" -ForegroundColor Gray }

function Write-Title($msg) {
    Write-Host ''
    Write-Host "== $msg ==" -ForegroundColor Cyan
}

function Get-RegValue($Path, $Name) {
    try { return (Get-ItemProperty -Path $Path -Name $Name -ErrorAction Stop).$Name }
    catch { return $null }
}

function Set-RegDword($Path, $Name, [int]$Value) {
    if (-not (Test-Path $Path)) { New-Item -Path $Path -Force -ErrorAction Stop | Out-Null }
    New-ItemProperty -Path $Path -Name $Name -Value $Value -PropertyType DWord -Force -ErrorAction Stop | Out-Null
}

function Get-MouseParams {
    $p = New-Object int[] 3
    [void][QMS.Native]::SystemParametersInfo($SPI_GETMOUSE, 0, $p, 0)
    return ,$p
}

function Set-MouseParams([int[]]$p) {
    $ok = [QMS.Native]::SystemParametersInfo($SPI_SETMOUSE, 0, $p, $SPIF_UPDATE_AND_SEND)
    if (-not $ok) { throw 'Windows no aceptó el cambio del mouse.' }
}

function Get-ActiveScheme {
    $out = (powercfg /getactivescheme) -join ' '
    if ($out -match '([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})') { return $Matches[1] }
    return $null
}

function Test-SchemeExists($guid) {
    if (-not $guid) { return $false }
    powercfg /query $guid *> $null
    return ($LASTEXITCODE -eq 0)
}

function Read-Backup {
    if (Test-Path $BackupFile) { return (Get-Content $BackupFile -Raw | ConvertFrom-Json) }
    return $null
}

# Valores del registro que este programa cambia (para poder deshacerlos).
$RegTweaks = @(
    @{ Path = 'HKCU:\Software\Microsoft\GameBar';                           Name = 'AutoGameModeEnabled'; Value = 1; What = 'Modo de juego de Windows activado' },
    @{ Path = 'HKCU:\Software\Microsoft\GameBar';                           Name = 'AllowAutoGameMode';   Value = 1; What = $null },
    @{ Path = 'HKCU:\System\GameConfigStore';                               Name = 'GameDVR_Enabled';     Value = 0; What = 'Grabación en segundo plano de Xbox Game Bar apagada' },
    @{ Path = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\GameDVR';    Name = 'AppCaptureEnabled';   Value = 0; What = $null }
)

# ------------------------------------------------------------------
# 1. Activar modo shooter
# ------------------------------------------------------------------

function Enable-ShooterMode {
    Write-Title 'Activando modo shooter'

    # Respaldo: solo se guarda la PRIMERA vez, para no perder los valores originales.
    $backup = Read-Backup
    if (-not $backup) {
        $regBackup = @()
        foreach ($t in $RegTweaks) {
            $regBackup += [pscustomobject]@{ Path = $t.Path; Name = $t.Name; Value = (Get-RegValue $t.Path $t.Name) }
        }
        $backup = [pscustomobject]@{
            Mouse         = @(Get-MouseParams)
            PowerScheme   = (Get-ActiveScheme)
            CreatedScheme = $null
            Registry      = $regBackup
        }
        New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
        $backup | ConvertTo-Json -Depth 5 | Set-Content -Path $BackupFile -Encoding UTF8 -ErrorAction Stop
        Write-Ok "Respaldo de tu configuración guardado en $BackupFile"
    } else {
        Write-Info 'Ya existía un respaldo de tu configuración original: se conserva.'
    }

    # Mouse: sin aceleración. Los umbrales 6/10 son los de Windows; MouseSpeed=0 la apaga.
    try {
        Set-MouseParams @(6, 10, 0)
        Write-Ok 'Aceleración del mouse desactivada (movimiento 1:1, mejor puntería).'
    } catch { Write-Bad "No pude cambiar el mouse: $($_.Exception.Message)" }

    # Plan de energía.
    $scheme = $null
    if ($backup.CreatedScheme -and (Test-SchemeExists $backup.CreatedScheme)) {
        $scheme = $backup.CreatedScheme
    } else {
        $dup = (powercfg /duplicatescheme $UltimateTemplate 2>$null) -join ' '
        if ($LASTEXITCODE -eq 0 -and $dup -match '([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})') {
            $scheme = $Matches[1]
            powercfg /changename $scheme 'Quest Modo Shooter' 'Creado por Quest Modo Shooter' *> $null
            $backup.CreatedScheme = $scheme
            $backup | ConvertTo-Json -Depth 5 | Set-Content -Path $BackupFile -Encoding UTF8
        }
    }
    $applied = $false
    foreach ($candidate in @($scheme, $HighPerfScheme)) {
        if (-not $candidate) { continue }
        powercfg /setactive $candidate *> $null
        if ($LASTEXITCODE -eq 0) { $applied = $true; break }
    }
    if ($applied) { Write-Ok 'Plan de energía de máximo rendimiento activado (la CPU no baja de velocidad en pleno tiroteo).' }
    else          { Write-Warn 'Tu equipo no permite el plan de máximo rendimiento. Si es laptop, conéctala al cargador.' }

    # Registro: Modo de juego y Game DVR.
    foreach ($t in $RegTweaks) {
        try {
            Set-RegDword $t.Path $t.Name $t.Value
            if ($t.What) { Write-Ok $t.What }
        } catch { Write-Bad "No pude cambiar $($t.Name): $($_.Exception.Message)" }
    }

    Write-Host ''
    Write-Host '  Listo. Ahora abre la opción 3 (Diagnóstico) y luego juega con la opción 2 (Modo partida).' -ForegroundColor Cyan
}

# ------------------------------------------------------------------
# 2. Modo partida
# ------------------------------------------------------------------

function Start-GameSession {
    Write-Title 'Modo partida'
    Write-Info 'Deja esta ventana abierta mientras juegas. Pulsa Q para volver al menú.'
    Write-Info 'Cada pocos segundos sube a prioridad "Alta" el programa que manda la imagen a las gafas'
    Write-Info '(Virtual Desktop, Quest Link / Air Link o SteamVR), para que el juego no lo frene.'
    Write-Host ''

    $seen = @{}
    $waiting = $false
    while ($true) {
        foreach ($name in $StreamerProcesses) {
            foreach ($proc in @(Get-Process -Name $name -ErrorAction SilentlyContinue)) {
                try {
                    if ($proc.PriorityClass -ne [System.Diagnostics.ProcessPriorityClass]::High) {
                        $proc.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::High
                    }
                    if (-not $seen.ContainsKey($proc.Id)) {
                        $seen[$proc.Id] = $true
                        Write-Ok "$($proc.ProcessName) (PID $($proc.Id)) en prioridad Alta"
                    }
                } catch {
                    if (-not $seen.ContainsKey($proc.Id)) {
                        $seen[$proc.Id] = $true
                        Write-Warn "No pude cambiar la prioridad de $($proc.ProcessName): $($_.Exception.Message)"
                    }
                }
            }
        }
        if ($seen.Count -eq 0 -and -not $waiting) {
            $waiting = $true
            Write-Info 'Esperando a que conectes las gafas (Virtual Desktop / Link / SteamVR)...'
        }
        # Espera 3 s, pero revisando si se pulsó Q.
        for ($i = 0; $i -lt 30; $i++) {
            try {
                if ([Console]::KeyAvailable) {
                    $key = [Console]::ReadKey($true)
                    if ($key.Key -eq [ConsoleKey]::Q) { Write-Info 'Saliendo del modo partida.'; return }
                }
            } catch {}
            Start-Sleep -Milliseconds 100
        }
    }
}

# ------------------------------------------------------------------
# 3. Diagnóstico
# ------------------------------------------------------------------

function Invoke-Diagnostics {
    Write-Title 'Diagnóstico'

    # --- Conexión de la PC ---
    Write-Host ''
    Write-Host '  Conexión de la PC al router' -ForegroundColor White
    try {
        $route = Get-NetRoute -DestinationPrefix '0.0.0.0/0' -ErrorAction Stop |
                 Sort-Object { $_.RouteMetric + $_.InterfaceMetric } | Select-Object -First 1
        $adapter = Get-NetAdapter -InterfaceIndex $route.ifIndex -ErrorAction Stop
        $isWifi = ($adapter.PhysicalMediaType -match '802\.11') -or ($adapter.InterfaceDescription -match 'Wi-?Fi|Wireless|WLAN|802\.11')
        if ($isWifi) {
            Write-Bad "La PC está conectada por Wi-Fi ($($adapter.InterfaceDescription))."
            Write-Info 'Esto es la causa nº 1 de retraso y tirones: la imagen viaja DOS veces por el aire.'
            Write-Info 'Conecta la PC al router con un cable de red (Ethernet).'
            $wlan = netsh wlan show interfaces 2>$null | Where-Object { $_ -match 'Banda|Band|Canal|Channel|Señal|Signal|Radio|recepci|Receive|transmisi|Transmit' }
            foreach ($l in $wlan) { Write-Info $l.Trim() }
        } else {
            Write-Ok "PC conectada por cable ($($adapter.InterfaceDescription), $($adapter.LinkSpeed))."
            if ($adapter.LinkSpeed -match '^(10|100) Mbps') {
                Write-Warn 'El cable va a 100 Mbps o menos. Usa un cable Cat5e/Cat6 y un puerto Gigabit del router.'
            }
        }
    } catch {
        Write-Warn "No pude identificar tu conexión de red: $($_.Exception.Message)"
    }

    # --- Monitor y tarjeta de video ---
    Write-Host ''
    Write-Host '  Tarjeta de video y monitor' -ForegroundColor White
    $gpus = @(Get-CimInstance Win32_VideoController -ErrorAction SilentlyContinue)
    $vendor = ''
    foreach ($g in $gpus) {
        $hz = $g.CurrentRefreshRate
        Write-Info "$($g.Name)$(if ($hz) { " - monitor a $hz Hz" })"
        if ($g.Name -match 'NVIDIA') { $vendor = 'NVIDIA' }
        elseif ($g.Name -match 'AMD|Radeon' -and -not $vendor) { $vendor = 'AMD' }
        elseif ($g.Name -match 'Intel' -and -not $vendor) { $vendor = 'Intel' }
        if ($hz -and $hz -gt 1 -and $hz -le 60 -and $g.Name -notmatch 'Virtual|Basic|Parsec|Meta|Oculus') {
            Write-Warn 'Tu monitor está a 60 Hz. Si transmites el ESCRITORIO, la imagen sale a 60 fps (más retraso).'
            Write-Info 'Súbelo a 120/144 Hz en Configuración > Pantalla > Pantalla avanzada,'
            Write-Info 'o usa el monitor virtual de Virtual Desktop a 120 Hz.'
        }
    }
    switch ($vendor) {
        'NVIDIA' { Write-Info 'NVIDIA: en el juego activa "NVIDIA Reflex: Activado + Boost". Si el juego no tiene Reflex,'
                   Write-Info 'en el Panel de control de NVIDIA pon "Modo de baja latencia: Ultra".' }
        'AMD'    { Write-Info 'AMD: en AMD Software activa "Radeon Anti-Lag" (y Anti-Lag 2 si el juego lo trae).' }
        'Intel'  { Write-Info 'Intel: en Intel Graphics Software activa "Low Latency" si tu tarjeta lo ofrece.' }
    }

    # --- Mouse ---
    Write-Host ''
    Write-Host '  Mouse' -ForegroundColor White
    $m = Get-MouseParams
    if ($m[2] -eq 0) { Write-Ok 'Aceleración del mouse desactivada.' }
    else             { Write-Warn 'La aceleración del mouse está activada. Usa la opción 1 para quitarla.' }
    Write-Info 'El mouse va conectado a la PC (no a las gafas): así lo lee el juego directamente.'

    # --- Ping a las gafas ---
    Write-Host ''
    Write-Host '  Ping a las gafas' -ForegroundColor White
    Write-Info 'Mira la IP de las Quest en: Configuración > Wi-Fi > tu red > Avanzado (ej: 192.168.1.50).'
    Write-Info 'Haz la prueba con las gafas puestas y Virtual Desktop/Link conectado (si no, las gafas'
    Write-Info 'ahorran batería en el Wi-Fi y el ping sale más alto de lo real).'
    $ip = Read-Host '  IP de las gafas (Enter para saltar)'
    if ($ip) {
        $ip = $ip.Trim()
        $pinger = New-Object System.Net.NetworkInformation.Ping
        $times = New-Object System.Collections.Generic.List[int]
        $lost = 0
        $total = 100
        Write-Host '  Midiendo' -NoNewline
        for ($i = 0; $i -lt $total; $i++) {
            try {
                $r = $pinger.Send($ip, 1000)
                if ($r.Status -eq [System.Net.NetworkInformation.IPStatus]::Success) { $times.Add([int]$r.RoundtripTime) } else { $lost++ }
            } catch { $lost++ }
            if ($i % 10 -eq 0) { Write-Host '.' -NoNewline }
            Start-Sleep -Milliseconds 30
        }
        Write-Host ''
        if ($times.Count -eq 0) {
            Write-Bad 'Las gafas no responden. Revisa la IP y que estén en la misma red que la PC.'
        } else {
            $avg = ($times | Measure-Object -Average).Average
            $max = ($times | Measure-Object -Maximum).Maximum
            $jit = 0
            for ($i = 1; $i -lt $times.Count; $i++) { $jit += [math]::Abs($times[$i] - $times[$i - 1]) }
            if ($times.Count -gt 1) { $jit = $jit / ($times.Count - 1) }
            $lossPct = [math]::Round(100 * $lost / $total, 1)
            Write-Info ("Promedio: {0:N1} ms   Máximo: {1} ms   Variación (jitter): {2:N1} ms   Pérdida: {3}%" -f $avg, $max, $jit, $lossPct)
            if ($avg -le 5 -and $max -le 25 -and $lossPct -eq 0) {
                Write-Ok 'Red excelente para shooters.'
            } elseif ($avg -le 10 -and $max -le 60 -and $lossPct -le 1) {
                Write-Warn 'Red aceptable. Acerca el router a donde juegas (misma habitación, sin paredes).'
            } else {
                Write-Bad 'Red mala para shooters: vas a notar retraso y tirones.'
                Write-Info '- Router en la misma habitación, a la vista de las gafas.'
                Write-Info '- Gafas conectadas a la red de 5 GHz (o 6 GHz si tu router es Wi-Fi 6E/7), canal de 80 MHz.'
                Write-Info '- Que nadie más use mucho esa red mientras juegas (descargas, streaming).'
                Write-Info '- O usa un cable USB-C (Quest Link por cable): es lo más estable.'
            }
        }
    }

    Show-Recommendations
}

# ------------------------------------------------------------------
# 4. Ajustes recomendados (texto)
# ------------------------------------------------------------------

function Show-Recommendations {
    Write-Title 'Ajustes recomendados para shooters'
    Write-Host @'

  EN EL JUEGO
   - Pantalla completa sin bordes (Borderless) o Pantalla completa.
   - V-Sync APAGADO. Triple buffering APAGADO.
   - NVIDIA Reflex "Activado + Boost" / AMD Anti-Lag si existe.
   - Baja gráficos hasta que el juego vaya SIEMPRE por encima de 120 FPS.
   - Si el juego tiene "Raw input" / "Entrada directa del mouse", actívalo.

  VIRTUAL DESKTOP (la mejor opción por Wi-Fi)
   - Frecuencia de las gafas: 120 Hz (y que el juego dé 120 FPS o más).
   - Códec: H.264+ (es el que menos tarda en descomprimir). AV1 solo si
     tienes RTX 40/50 o RX 7000/9000 y ves bloques con H.264+.
   - Bitrate: 100-150 Mbps. Más bitrate = más nitidez, pero si la red
     no da, sube el retraso. Baja el bitrate si ves la latencia saltar.
   - "Video buffering" APAGADO.
   - Activa la superposición de rendimiento (Performance overlay) y busca
     que la latencia total ("Total latency") quede entre 20 y 35 ms.

  QUEST LINK POR CABLE / AIR LINK
   - App Meta Quest Link > Dispositivos > Gráficos: 120 Hz.
   - Cable USB 3 (lo más estable). Air Link: misma regla de red que arriba.

  STEAM LINK
   - En las gafas: 120 Hz. Usa el modo escritorio para juegos planos.

  RED
   - PC al router por CABLE. Gafas por Wi-Fi 5 GHz / 6 GHz, router en la
     misma habitación. Mejor aún: un router solo para las gafas.
'@ -ForegroundColor Gray
}

# ------------------------------------------------------------------
# 5. Restaurar
# ------------------------------------------------------------------

function Restore-Settings {
    Write-Title 'Restaurando tu configuración original'
    $backup = Read-Backup
    if (-not $backup) {
        Write-Warn 'No hay nada que restaurar (no se activó el modo shooter, o ya se restauró).'
        return
    }

    try {
        Set-MouseParams @([int[]]$backup.Mouse)
        Write-Ok 'Mouse como estaba.'
    } catch { Write-Bad "Mouse: $($_.Exception.Message)" }

    if ($backup.PowerScheme) {
        powercfg /setactive $backup.PowerScheme *> $null
        if ($LASTEXITCODE -eq 0) { Write-Ok 'Plan de energía original activado.' }
        else { Write-Warn 'No pude volver al plan de energía original.' }
    }
    if ($backup.CreatedScheme -and $backup.CreatedScheme -ne $backup.PowerScheme -and (Test-SchemeExists $backup.CreatedScheme)) {
        powercfg /delete $backup.CreatedScheme *> $null
    }

    foreach ($r in @($backup.Registry)) {
        try {
            if ($null -eq $r.Value) {
                Remove-ItemProperty -Path $r.Path -Name $r.Name -ErrorAction SilentlyContinue
            } else {
                Set-RegDword $r.Path $r.Name ([int]$r.Value)
            }
        } catch { Write-Bad "$($r.Name): $($_.Exception.Message)" }
    }
    Write-Ok 'Modo de juego y Game Bar como estaban.'

    Remove-Item $BackupFile -Force -ErrorAction SilentlyContinue
    Write-Ok 'Todo restaurado.'
}

# ------------------------------------------------------------------
# Menú
# ------------------------------------------------------------------

while ($true) {
    Write-Host ''
    Write-Host '  ===========================================' -ForegroundColor Cyan
    Write-Host '        QUEST MODO SHOOTER  -  PC -> Quest' -ForegroundColor Cyan
    Write-Host '  ===========================================' -ForegroundColor Cyan
    $state = if (Read-Backup) { 'ACTIVADO' } else { 'desactivado' }
    Write-Host "  Modo shooter: $state" -ForegroundColor $(if ($state -eq 'ACTIVADO') { 'Green' } else { 'DarkGray' })
    Write-Host ''
    Write-Host '   1) Activar modo shooter (optimiza Windows)'
    Write-Host '   2) Modo partida (déjalo abierto mientras juegas)'
    Write-Host '   3) Diagnóstico de red, monitor y mouse'
    Write-Host '   4) Ver ajustes recomendados'
    Write-Host '   5) Restaurar todo como estaba'
    Write-Host '   0) Salir'
    Write-Host ''
    $opt = Read-Host '  Elige una opción'
    try {
        switch ($opt) {
            '1' { Enable-ShooterMode }
            '2' { Start-GameSession }
            '3' { Invoke-Diagnostics }
            '4' { Show-Recommendations }
            '5' { Restore-Settings }
            '0' { exit 0 }
            default { Write-Warn 'Opción no válida.' }
        }
    } catch {
        Write-Bad "Error: $($_.Exception.Message)"
    }
}
