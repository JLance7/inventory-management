param([switch]$Test)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (!(Get-Command javac -ErrorAction SilentlyContinue)) { throw 'Install a Java JDK 11 or newer and add its bin directory to PATH.' }
    New-Item -ItemType Directory -Force -Path lib,build/classes,build/test-classes,dist/client/lib,dist/server/lib,dist/server/sources | Out-Null
    $inventoryDependencies = Get-Content -LiteralPath 'dependencies.json' -Raw | ConvertFrom-Json
    foreach ($inventoryDependency in $inventoryDependencies) {
        $inventoryJar = Join-Path 'lib' $inventoryDependency.name
        if (!(Test-Path -LiteralPath $inventoryJar)) { Invoke-WebRequest -Uri $inventoryDependency.url -OutFile $inventoryJar }
        if ((Get-FileHash -LiteralPath $inventoryJar -Algorithm SHA256).Hash -ne $inventoryDependency.sha256) { throw "Dependency checksum mismatch: $inventoryJar" }
    }
    $inventorySources = @(Get-ChildItem -Path 'src/main/java' -Recurse -Filter '*.java' | ForEach-Object FullName)
    & javac --release 11 -encoding UTF-8 -cp 'lib/*' -d build/classes $inventorySources
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }
    # Package shared classes and each component into their own artifacts.
    # The desktop JAR excludes Database/Server; no database driver ships to clients.
    $inventoryClientClasses = @(Get-ChildItem -Path 'build/classes/inventory' -Filter '*.class' | Where-Object { $_.Name -match '^(Models|ApiClient|DesktopApp)(\$|\.)' })
    $inventoryClientArgs = @('--create','--file','dist/client/inventory-client.jar','--main-class','inventory.DesktopApp')
    foreach ($inventoryClass in $inventoryClientClasses) { $inventoryClientArgs += @('-C','build/classes',('inventory/' + $inventoryClass.Name)) }
    & jar @inventoryClientArgs
    if ($LASTEXITCODE -ne 0) { throw 'Client packaging failed.' }
    $inventoryServerClasses = @(Get-ChildItem -Path 'build/classes/inventory' -Filter '*.class' | Where-Object { $_.Name -match '^(Models|Database|Server)(\$|\.)' })
    $inventoryServerArgs = @('--create','--file','dist/server/inventory-server.jar','--main-class','inventory.Server')
    foreach ($inventoryClass in $inventoryServerClasses) { $inventoryServerArgs += @('-C','build/classes',('inventory/' + $inventoryClass.Name)) }
    & jar @inventoryServerArgs
    if ($LASTEXITCODE -ne 0) { throw 'Server packaging failed.' }
    Copy-Item -LiteralPath 'lib/gson-2.13.2.jar' -Destination 'dist/client/lib'
    foreach ($inventoryDependency in $inventoryDependencies) {
        Copy-Item -LiteralPath (Join-Path 'lib' $inventoryDependency.name) -Destination 'dist/server/lib'
    }
    Copy-Item -LiteralPath 'scripts/Start-Client.bat' -Destination 'dist/client/Start-Client.bat'
    Copy-Item -LiteralPath 'scripts/Start-Server.bat' -Destination 'dist/server/Start-Server.bat'
    Copy-Item -LiteralPath 'server.properties.example' -Destination 'dist/server/server.properties.example'
    foreach ($inventoryPackage in @('dist/client', 'dist/server')) {
        Copy-Item -LiteralPath 'README.md','THIRD_PARTY.md' -Destination $inventoryPackage
        Copy-Item -LiteralPath 'licenses' -Destination $inventoryPackage -Recurse -Force
    }
    Copy-Item -LiteralPath 'sources/mariadb-java-client-3.5.11-sources.jar' -Destination 'dist/server/sources'
    Compress-Archive -Path 'dist/client' -DestinationPath 'dist/Stockroom-Desktop.zip' -Force
    Compress-Archive -Path 'dist/server' -DestinationPath 'dist/Stockroom-Server.zip' -Force
    if ($Test) {
        $inventoryTests = @(Get-ChildItem -Path 'src/test/java' -Recurse -Filter '*.java' | ForEach-Object FullName)
        & javac --release 11 -encoding UTF-8 -cp 'build/classes;lib/*' -d build/test-classes $inventoryTests
        if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
        & java -ea -cp 'build/classes;build/test-classes;lib/*' inventory.InventoryTest
        if ($LASTEXITCODE -ne 0) { throw 'Tests failed.' }
    }
    Write-Output 'Build complete: dist/Stockroom-Desktop.zip and dist/Stockroom-Server.zip'
} finally { Pop-Location }
