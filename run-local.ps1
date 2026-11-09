$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (!(Test-Path -LiteralPath 'server.properties')) { & ./setup-local.ps1 }
    if (!(Test-Path -LiteralPath 'dist/client/inventory-client.jar')) { & ./build.ps1 }
    & docker compose up -d db
    if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop and try again.' }
    $inventoryReady = $false
    for ($inventoryAttempt = 0; $inventoryAttempt -lt 60; $inventoryAttempt++) {
        & docker compose exec -T db healthcheck.sh --connect --innodb_initialized 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $inventoryReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$inventoryReady) { throw 'MariaDB startup timed out.' }
    $inventoryServerRunning = $false
    try { $inventoryServerRunning = (Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 2).status -eq 'ok' } catch {}
    if (!$inventoryServerRunning) {
        $inventoryJava = (Get-Command java).Source
        $inventoryServerArgs = '-cp "{0};{1}" inventory.Server "{2}"' -f (Join-Path $PSScriptRoot 'dist/server/inventory-server.jar'), (Join-Path $PSScriptRoot 'dist/server/lib/*'), (Join-Path $PSScriptRoot 'server.properties')
        Start-Process -FilePath $inventoryJava -ArgumentList $inventoryServerArgs -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $PSScriptRoot 'server-output.log') -RedirectStandardError (Join-Path $PSScriptRoot 'server-error.log') | Out-Null
        for ($inventoryAttempt = 0; $inventoryAttempt -lt 30; $inventoryAttempt++) {
            Start-Sleep -Milliseconds 500
            try { if ((Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/health' -TimeoutSec 2).status -eq 'ok') { $inventoryServerRunning = $true; break } } catch {}
        }
    }
    if (!$inventoryServerRunning) { throw 'Server did not start. See server-error.log. Port 8080 may already be in use.' }
    Write-Output 'Server ready at http://localhost:8080. Copy api.token from server.properties into the client connection form.'
    $inventoryJavaWindowless = (Get-Command javaw).Source
    $inventoryClientArgs = '-cp "{0};{1}" inventory.DesktopApp' -f (Join-Path $PSScriptRoot 'dist/client/inventory-client.jar'), (Join-Path $PSScriptRoot 'dist/client/lib/*')
    Start-Process -FilePath $inventoryJavaWindowless -ArgumentList $inventoryClientArgs -WindowStyle Hidden | Out-Null
} finally { Pop-Location }
