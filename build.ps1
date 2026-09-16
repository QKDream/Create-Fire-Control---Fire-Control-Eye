$ErrorActionPreference = "Stop"
$ws = "C:/Users/1/Documents/Codex/2026-08-07/zhe/cbc-addon/firecontrolcompat"
$mcLib = "D:/.minecraft/libraries"
$versionRoot = Get-ChildItem "D:/.minecraft/versions" -Directory | Where-Object { Test-Path (Join-Path $_.FullName "mods/create-fire-control-0.7.1.jar") } | Select-Object -First 1
if (-not $versionRoot) { Write-Host "No version dir with create-fire-control found"; exit 1 }
$modsDir = Join-Path $versionRoot.FullName "mods"
Write-Host "Using mods dir: $modsDir"
$javac = "D:/.minecraft/runtime/java-runtime-delta/bin/javac.exe"
$jarExe = "D:/.minecraft/runtime/java-runtime-delta/bin/jar.exe"

$cpDir = "$ws/build/modscp"
try { if (Test-Path $cpDir) { Remove-Item -Recurse -Force $cpDir -ErrorAction SilentlyContinue } } catch { Write-Host 'modscp cleanup deferred' }
New-Item -ItemType Directory -Force $cpDir | Out-Null
$wantedMods = @(
    "create-fire-control-0.7.1.jar",
    "sable-neoforge-1.21.1-2.0.5.jar",
    "shaolib-0.1.0.jar",
    "shaolib_munitions-0.1.0.jar",
    "synaxis-1.5.0.jar",
    "taov_core-0.1.0.jar",
    "taov_weapons-0.1.1.jar",
    "mianbaos_modernwarfare-2.5.1-neoforge.jar"
)
foreach ($name in $wantedMods) {
    $src = Join-Path $modsDir $name
    if (Test-Path -LiteralPath $src) { Copy-Item -LiteralPath $src -Destination $cpDir }
}
if (-not (Test-Path -LiteralPath (Join-Path $cpDir "mianbaos_modernwarfare-2.5.1-neoforge.jar"))) {
    $mianbaoJar = Get-ChildItem -LiteralPath $modsDir -Filter "mianbaos_modernwarfare-*.jar" | Sort-Object Name | Select-Object -First 1
    if ($mianbaoJar) { Copy-Item -LiteralPath $mianbaoJar.FullName -Destination (Join-Path $cpDir $mianbaoJar.Name) }
}
$sableJar = Join-Path $cpDir "sable-neoforge-1.21.1-2.0.5.jar"
if (Test-Path -LiteralPath $sableJar) {
    $companionName = "sable-companion-common-1.21.1-1.6.0.jar"
    Push-Location $cpDir
    & $jarExe xf $sableJar "META-INF/jarjar/$companionName"
    Pop-Location
    $companionPath = Join-Path $cpDir "META-INF/jarjar/$companionName"
    if (Test-Path -LiteralPath $companionPath) {
        Copy-Item -LiteralPath $companionPath -Destination (Join-Path $cpDir $companionName)
    }
}
$createJar = Get-ChildItem -LiteralPath $modsDir -Filter "*create-1.21.1-6.0.10.jar" | Select-Object -First 1
if ($createJar) { Copy-Item -LiteralPath $createJar.FullName -Destination (Join-Path $cpDir "create-1.21.1-6.0.10.jar") }
$createBigCannons = Get-ChildItem -LiteralPath $modsDir -Filter "*createbigcannons-*.jar" | Select-Object -First 1
if ($createBigCannons) { Copy-Item -LiteralPath $createBigCannons.FullName -Destination (Join-Path $cpDir "createbigcannons.jar") }

$cpParts = New-Object System.Collections.Generic.List[string]
$cpParts.Add("$mcLib/net/neoforged/neoforge/21.1.228/neoforge-21.1.228-client.jar")
$cpParts.Add("$mcLib/net/neoforged/neoforge/21.1.228/neoforge-21.1.228-universal.jar")
$cpParts.Add("$mcLib/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar")
$cpParts.Add("$mcLib/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-extra.jar")
$cpParts.Add("$mcLib/net/neoforged/fancymodloader/loader/4.0.42/loader-4.0.42.jar")
$cpParts.Add("$mcLib/net/neoforged/bus/8.0.5/bus-8.0.5.jar")
$cpParts.Add("$mcLib/net/neoforged/coremods/7.0.3/coremods-7.0.3.jar")
$cpParts.Add("$mcLib/net/neoforged/mergetool/2.0.3/mergetool-2.0.3-api.jar")
$cpParts.Add("$mcLib/org/spongepowered/mixin/0.8.7/mixin-0.8.7.jar")
$cpParts.Add("$mcLib/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar")
$cpParts.Add("$mcLib/org/ow2/asm/asm-tree/9.10.1/asm-tree-9.10.1.jar")
$cpParts.Add("$mcLib/org/ow2/asm/asm-analysis/9.10.1/asm-analysis-9.10.1.jar")
$cpParts.Add("$mcLib/org/ow2/asm/asm-commons/9.10.1/asm-commons-9.10.1.jar")
$cpParts.Add("$mcLib/org/ow2/asm/asm-util/9.10.1/asm-util-9.10.1.jar")
$cpParts.Add("$mcLib/com/mojang/datafixerupper/6.0.6/datafixerupper-6.0.6.jar")
$cpParts.Add("$mcLib/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar")
$cpParts.Add("$mcLib/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar")
$cpParts.Add("$mcLib/io/netty/netty-buffer/4.1.97.Final/netty-buffer-4.1.97.Final.jar")
$cpParts.Add("$mcLib/io/netty/netty-common/4.1.97.Final/netty-common-4.1.97.Final.jar")
$cpParts.Add("$mcLib/com/google/guava/guava/33.5.0-jre/guava-33.5.0-jre.jar")
$cpParts.Add("$mcLib/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar")
$cpParts.Add("$mcLib/org/joml/joml/1.10.5/joml-1.10.5.jar")
Get-ChildItem -LiteralPath $cpDir -Filter "*.jar" | ForEach-Object { $cpParts.Add($_.FullName.Replace('\','/')) }
$stubOut = "$ws/build/stub_classes"
try { if (Test-Path $stubOut) { Remove-Item -Recurse -Force $stubOut -ErrorAction SilentlyContinue } } catch { Write-Host "stub cleanup deferred" }
New-Item -ItemType Directory -Force $stubOut | Out-Null
& $javac -d $stubOut -proc:none -encoding UTF-8 "$ws/stubs/net/createmod/ponder/api/VirtualBlockEntity.java"
if ($LASTEXITCODE -ne 0) { Write-Host "STUB COMPILATION FAILED (exit $LASTEXITCODE)"; exit 1 }
& $jarExe cf "$stubOut/stub.jar" -C $stubOut net
if ($LASTEXITCODE -ne 0) { Write-Host "STUB JAR FAILED (exit $LASTEXITCODE)"; exit 1 }
$cpParts.Add(("$stubOut/stub.jar").Replace("\","/"))
$missing = $cpParts | Where-Object { -not (Test-Path -LiteralPath $_) }
if ($missing) { Write-Host "MISSING:"; $missing | ForEach-Object { Write-Host "  $_" }; exit 1 }
$classpath = $cpParts -join ";"
Write-Host "Classpath entries: $($cpParts.Count)"

$outDir = "$ws/build/classes"
try { if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir -ErrorAction SilentlyContinue } } catch { Write-Host 'classes cleanup deferred' }
New-Item -ItemType Directory -Force $outDir | Out-Null

$argFile = "$ws/build/javac_argfile.txt"
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("-d"); $lines.Add($outDir)
$lines.Add("-cp"); $lines.Add($classpath)
$lines.Add("-encoding"); $lines.Add("UTF-8")
$lines.Add("-proc:none")
Get-ChildItem -Path "$ws/src/main/java" -Recurse -Filter "*.java" | ForEach-Object { $lines.Add($_.FullName.Replace('\','/')) }
[System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Compiling $($lines.Count - 7) java files..."
& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) {
    Write-Host "COMPILATION FAILED (exit $LASTEXITCODE)"
    exit 1
}
Write-Host "Compilation OK!"

$stage = "$ws/build/jar_tmp"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item -Recurse -Force "$outDir/*" $stage
Copy-Item -Recurse -Force "$ws/src/main/resources/*" $stage

$jarOut = "$ws/firecontrolcompat-1.30fix.jar"
Push-Location $stage
& $jarExe cf $jarOut *
$jarExit = $LASTEXITCODE
Pop-Location
if (Test-Path $jarOut) {
    Write-Host "BUILD SUCCESS: $jarOut"
} else {
    Write-Host "JAR FAILED (exit $jarExit)"
    exit 1
}







