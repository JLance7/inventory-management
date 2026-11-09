$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if ((Test-Path -LiteralPath '.env') -or (Test-Path -LiteralPath 'server.properties')) { throw 'Local configuration already exists. Edit it rather than replacing credentials.' }
    function New-InventorySecret {
        $inventoryBytes = New-Object byte[] 32
        $inventoryRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $inventoryRandom.GetBytes($inventoryBytes) } finally { $inventoryRandom.Dispose() }
        return -join ($inventoryBytes | ForEach-Object { $_.ToString('x2') })
    }
    $inventoryDatabasePassword = New-InventorySecret
    $inventoryRootPassword = New-InventorySecret
    $inventoryApiToken = New-InventorySecret
    @("INVENTORY_DB_PASSWORD=$inventoryDatabasePassword", "MARIADB_ROOT_PASSWORD=$inventoryRootPassword", "INVENTORY_API_TOKEN=$inventoryApiToken") | Set-Content -LiteralPath '.env' -Encoding ASCII
    $inventoryConfig = Get-Content -LiteralPath 'server.properties.example' -Raw
    $inventoryConfig = $inventoryConfig.Replace('REPLACE_WITH_DATABASE_PASSWORD', $inventoryDatabasePassword).Replace('REPLACE_WITH_RANDOM_TOKEN_AT_LEAST_32_CHARACTERS', $inventoryApiToken)
    Set-Content -LiteralPath 'server.properties' -Value $inventoryConfig -Encoding ASCII
    Write-Output 'Generated .env and server.properties. Copy api.token from server.properties into the desktop connection form.'
    Write-Output 'Next: docker compose up -d db, then ./build.ps1, then ./run-server.bat and ./run-client.bat.'
} finally { Pop-Location }
