param([switch]$Ui)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
$inventoryContainer = 'stockroom-test-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$inventoryTestPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
try {
    & ./build.ps1 -Test
    & docker run --rm -d --name $inventoryContainer -e MARIADB_DATABASE=inventory -e MARIADB_USER=inventory -e "MARIADB_PASSWORD=$inventoryTestPassword" -e "MARIADB_ROOT_PASSWORD=$inventoryTestPassword" -p 127.0.0.1::3306 mariadb:11.4
    if ($LASTEXITCODE -ne 0) { throw 'Could not start disposable MariaDB container.' }
    $inventoryReady = $false
    for ($inventoryAttempt = 0; $inventoryAttempt -lt 60; $inventoryAttempt++) {
        & docker exec $inventoryContainer healthcheck.sh --connect --innodb_initialized 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { $inventoryReady = $true; break }
        Start-Sleep -Seconds 1
    }
    if (!$inventoryReady) { throw 'MariaDB startup timed out.' }
    $inventoryBinding = (& docker port $inventoryContainer 3306).Trim()
    $inventoryPort = $inventoryBinding.Split(':')[-1]
    $env:TEST_DB_URL = "jdbc:mariadb://127.0.0.1:$inventoryPort/inventory?connectTimeout=5000&socketTimeout=10000&timezone=UTC"
    $env:TEST_DB_USER = 'inventory'
    $env:TEST_DB_PASSWORD = $inventoryTestPassword
    $inventoryTestArgs = @('-ea','-cp','build/classes;build/test-classes;lib/*','inventory.InventoryTest')
    if ($Ui) { $inventoryTestArgs += '--ui' }
    & java @inventoryTestArgs
    if ($LASTEXITCODE -ne 0) { throw 'Inventory integration tests failed.' }
} finally {
    & docker rm -f $inventoryContainer 2>$null | Out-Null
    Remove-Item Env:TEST_DB_URL,Env:TEST_DB_USER,Env:TEST_DB_PASSWORD -ErrorAction SilentlyContinue
    Pop-Location
}
