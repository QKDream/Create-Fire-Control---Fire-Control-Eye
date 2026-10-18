$ErrorActionPreference = "Stop"
$ws = "C:/Users/1/Documents/DSH/fire-control-eye/firecontrolcompat"
$mcLib = "D:/.minecraft/libraries"
# The fire control and mianbao jars are swapped by hand between game sessions, so the
# build follows whatever version is currently enabled in the mods folder instead of
# pinning file names that stop matching the moment the player changes version.
function Get-VersionDirs ([string]$filter) {
    Get-ChildItem "D:/.minecraft/versions" -Directory |
        Where-Object { @(Get-ChildItem (Join-Path $_.FullName "mods") -Filter $filter -File -ErrorAction SilentlyContinue).Count -gt 0 }
}
# Prefer the pack this mod is installed in, so other modpacks that happen to ship
# a fire control jar are not mistaken for the test instance. Several packs may
# carry a copy, so the pack that also has an enabled fire control jar and whose
# copy of this mod was written last wins: that is the instance being tested.
function Get-PackStamp {
    param([string]$root)
    $jar = Get-ChildItem (Join-Path $root "mods") -Filter "firecontrolcompat-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*.jar" } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($jar) { return $jar.LastWriteTime } else { return [datetime]::MinValue }
}
function Test-PackHasFireControl {
    param([string]$root)
    $mods = Join-Path $root "mods"
    $ours = @(Get-ChildItem $mods -Filter "firecontrolcompat-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*.jar" }).Count -gt 0
    $fireControl = @(Get-ChildItem $mods -Filter "create-fire-control-*.jar" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*.jar" }).Count -gt 0
    return $ours -and $fireControl
}
# Several packs ship both jars, so the pack that is being tested is picked by the
# fire control version it carries first (the newest one is what this build targets),
# with the copy of this mod that was written last as the tie breaker. Otherwise a
# pack left on an old fire control jar wins on timestamps and the build compiles
# against a version the game does not run.
function Get-FireControlRank {
    param([string]$root)
    $jar = Get-ChildItem (Join-Path $root "mods") -Filter "create-fire-control-*.jar*" -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*.jar" } | Select-Object -First 1
    if (-not $jar) { return "0000.0000.0000" }
    $match = [regex]::Match($jar.Name, '(\d+)\.(\d+)(?:\.(\d+))?')
    if (-not $match.Success) { return "0000.0000.0000" }
    $patch = if ($match.Groups[3].Success) { [int]$match.Groups[3].Value } else { 0 }
    return ("{0:D4}.{1:D4}.{2:D4}" -f [int]$match.Groups[1].Value, [int]$match.Groups[2].Value, $patch)
}
$versionRoot = $null
$candidates = @(Get-VersionDirs "firecontrolcompat-*.jar*") | Where-Object { Test-PackHasFireControl $_.FullName }
if ($candidates.Count -gt 0) {
    $versionRoot = $candidates |
        Sort-Object @{ Expression = { Get-FireControlRank $_.FullName }; Descending = $true },
                    @{ Expression = { Get-PackStamp $_.FullName }; Descending = $true } |
        Select-Object -First 1
}
if (-not $versionRoot) { $versionRoot = @(Get-VersionDirs "create-fire-control-*.jar*") | Select-Object -First 1 }
if (-not $versionRoot) { $versionRoot = @(Get-VersionDirs "firecontrolcompat-*.jar*") | Select-Object -First 1 }
if (-not $versionRoot) { Write-Host "No version dir with create-fire-control found"; exit 1 }
$modsDir = Join-Path $versionRoot.FullName "mods"
Write-Host "Using mods dir: $modsDir (fire control rank $(Get-FireControlRank $versionRoot.FullName))"
$javac = "D:/.minecraft/runtime/java-runtime-delta/bin/javac.exe"
$jarExe = "D:/.minecraft/runtime/java-runtime-delta/bin/jar.exe"

# The classpath jars are kept between builds: a freshly written copy is what makes
# javac's zip filesystem fail to close on Windows (AccessDeniedException) at exit.
$cpDir = "$ws/build/modscp"
New-Item -ItemType Directory -Force $cpDir | Out-Null
function Resolve-ActiveJar([string]$family) {
    $all = @(Get-ChildItem -LiteralPath $modsDir -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "$family*" -and ($_.Name -like "*.jar" -or $_.Name -like "*.jar.disabled") })
    if ($all.Count -eq 0) { return $null }
    $enabled = @($all | Where-Object { $_.Name -like "*.jar" })
    if ($enabled.Count -gt 0) { $all = $enabled }
    return ($all | Sort-Object Name -Descending | Select-Object -First 1)
}

# classpath name (inside build/modscp) -> jar that is copied there
$modSources = @{}
foreach ($name in @(
        "sable-neoforge-1.21.1-2.0.5.jar")) {
    $modSources[$name] = (Join-Path $modsDir $name)
}
# Versioned families are resolved from the pack instead of being pinned by file
# name: the player swaps these jars whenever a new build comes out, and a name
# that stops matching turns into a hard "MISSING" build failure.
foreach ($family in @("create-fire-control-", "mianbaos_modernwarfare-", "shaolib-",
        "shaolib_munitions-", "taov_core-", "taov_weapons-", "synaxis-", "geckolib-neoforge-")) {
    $jar = Resolve-ActiveJar $family
    if (-not $jar) { Write-Host "No $family jar found in $modsDir"; exit 1 }
    $target = $jar.Name -replace '\.disabled$', ''
    Write-Host "Using $($jar.Name) as $target"
    $modSources[$target] = $jar.FullName
}
# Touhou Little Maid is an optional integration. The maid sources are only part
# of the compile when its jar is present, so a pack without the maid mod still
# builds: nothing outside src/main/java/com/qkdream/firecontrolcompat/compat/maid
# touches the maid API, and that package is skipped when the jar is missing.
# The terminal ballistics mod is optional: its two spall mixins are the only
# sources that name its classes, so they are left out while it is absent and
# the game then runs on the fragments the pack itself hands out.
$terminalBallisticsJar = Get-ChildItem -LiteralPath $modsDir -Filter "cbc_terminal_ballistics-*.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
if ($terminalBallisticsJar) {
    $modSources[$terminalBallisticsJar.Name] = $terminalBallisticsJar.FullName
    Write-Host "Using $($terminalBallisticsJar.Name)"
} else {
    Write-Host "No cbc_terminal_ballistics jar found: building without the terminal ballistics mixins"
}
# Yuushya Modelling is an optional integration as well: only the modelling block
# sources name its classes, so a pack without it still builds and runs.
$yuushyaJar = Get-ChildItem -LiteralPath $modsDir -Filter "*yuushya_modelling-*.jar*" -File -ErrorAction SilentlyContinue | Select-Object -First 1
if ($yuushyaJar) {
    $modSources["yuushya_modelling.jar"] = $yuushyaJar.FullName
    Write-Host "Using $($yuushyaJar.Name) as yuushya_modelling.jar"
} else {
    Write-Host "No yuushya modelling jar found: building without the modelling block sources"
}
$maidJar = Get-ChildItem -LiteralPath $modsDir -Filter "*touhoulittlemaid-*.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
if ($maidJar) {
    $modSources["touhoulittlemaid.jar"] = $maidJar.FullName
    Write-Host "Using $($maidJar.Name) as touhoulittlemaid.jar"
} else {
    Write-Host "No touhou little maid jar found: building without the maid integration sources"
}
# vestalihy is an optional integration too. Its three mixins are the only files
# that name vestalihy classes, so they are left out of the compile while the jar
# is absent and the game then runs with the bridge finding nothing.
$vestalihyJar = Get-ChildItem -LiteralPath $modsDir -Filter "vestalihy-*.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
if ($vestalihyJar) {
    $modSources[$vestalihyJar.Name] = $vestalihyJar.FullName
    Write-Host "Using $($vestalihyJar.Name)"
} else {
    Write-Host "No vestalihy jar found: building without the vestalihy integration sources"
}
# Copycats is an optional integration: the silencer tiles are cut from its
# camouflage blocks, so those sources are only compiled while it is in the pack
# and a pack without it still builds - the game then simply has no tiles.
$copycatsJar = Get-ChildItem -LiteralPath $modsDir -Filter "*copycats-*.jar" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike "*extra*" } | Select-Object -First 1
if ($copycatsJar) {
    $modSources["copycats.jar"] = $copycatsJar.FullName
    Write-Host "Using $($copycatsJar.Name) for the silencer tiles"
} else {
    Write-Host "No copycats jar found: building without the silencer tiles"
}
# The vibration hooks listen to three more optional mods: the diesel generators
# (engines and burners), gearwork (wheels and track mounts) and tracks+ (its own
# track mount). Each hook is the only source naming that mod's classes, so a pack
# without one of them still builds and runs.
$dieselJar = Get-ChildItem -LiteralPath $modsDir -Filter "*createdieselgenerators-*.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
if ($dieselJar) {
    $modSources["createdieselgenerators.jar"] = $dieselJar.FullName
    Write-Host "Using $($dieselJar.Name) for the diesel vibration hook"
} else {
    Write-Host "No createdieselgenerators jar found: building without the diesel vibration hook"
}
$gearworkJar = Get-ChildItem -LiteralPath $modsDir -Filter "gearwork-*.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
if ($gearworkJar) {
    $modSources["gearwork.jar"] = $gearworkJar.FullName
    Write-Host "Using $($gearworkJar.Name) for the wheel and track vibration hook"
} else {
    Write-Host "No gearwork jar found: building without the gearwork vibration hook"
}
$tracksJar = Get-ChildItem -LiteralPath $modsDir -Filter "*tracks_plus-*.jar" -File -ErrorAction SilentlyContinue | Select-Object -First 1
if ($tracksJar) {
    $modSources["tracks_plus.jar"] = $tracksJar.FullName
    Write-Host "Using $($tracksJar.Name) for the track vibration hook"
} else {
    Write-Host "No tracks+ jar found: building without the tracks+ vibration hook"
}
$wantedMods = @($modSources.Keys)
# Any older jar of the same mod left in the classpath dir wins over the wanted one
# (javac keeps the first match), which silently compiles against a version the game
# does not run. Drop every stale version of the versioned families before copying.
$versionedFamilies = @(
    "mianbaos_modernwarfare-",
    "create-fire-control-",
    "taov_core-",
    "taov_weapons-",
    "synaxis-",
    "geckolib-neoforge-",
    "shaolib-",
    "shaolib_munitions-",
    "vestalihy-",
    "cbc_terminal_ballistics-"
)
foreach ($family in $versionedFamilies) {
    Get-ChildItem -LiteralPath $cpDir -Filter "$family*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $wantedMods -notcontains $_.Name } |
        ForEach-Object { Write-Host "Dropping stale classpath jar: $($_.Name)"; Remove-Item -LiteralPath $_.FullName -Force }
}
foreach ($name in $wantedMods) {
    $src = $modSources[$name]
    $dst = Join-Path $cpDir $name
    if (-not (Test-Path -LiteralPath $src)) { continue }
    $needsCopy = $true
    if (Test-Path -LiteralPath $dst) {
        $needsCopy = (Get-Item -LiteralPath $src).Length -ne (Get-Item -LiteralPath $dst).Length
    }
    if ($needsCopy) { Copy-Item -LiteralPath $src -Destination $dst -Force }
}

$cbcmsJar = Get-ChildItem -LiteralPath $modsDir -Filter "*CBC-Military-Supplement*.jar" | Select-Object -First 1
if ($cbcmsJar) { Copy-Item -LiteralPath $cbcmsJar.FullName -Destination (Join-Path $cpDir "cbcms.jar") }
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
$registrateName = "Registrate-MC1.21-1.3.0+67.jar"
Push-Location $cpDir
& $jarExe xf "create-1.21.1-6.0.10.jar" "META-INF/jarjar/$registrateName"
Pop-Location
$registratePath = Join-Path $cpDir "META-INF/jarjar/$registrateName"
if (Test-Path -LiteralPath $registratePath) {
    Copy-Item -LiteralPath $registratePath -Destination (Join-Path $cpDir $registrateName)
}
# Create also ships the catnip library as a jar-in-jar. Create Big Cannons reads
# its own server config through catnip, so the one place this mod asks that config
# how much of the world a burst is allowed to break - the torpedo's warhead - needs
# the library on the compile path as well.
$createJarPath = Join-Path $cpDir "create-1.21.1-6.0.10.jar"
$catnipEntry = (& $jarExe tf $createJarPath) | Where-Object { $_ -like "META-INF/jarjar/catnip-*.jar" } | Select-Object -First 1
if (-not $catnipEntry) {
    $catnipEntry = (& $jarExe tf $createJarPath) | Where-Object { $_ -like "META-INF/jarjar/ponder-*.jar" } | Select-Object -First 1
}
if ($catnipEntry) {
    Push-Location $cpDir
    & $jarExe xf $createJarPath $catnipEntry
    Pop-Location
    $catnipPath = Join-Path $cpDir $catnipEntry
    if (Test-Path -LiteralPath $catnipPath) {
        $catnipName = Split-Path $catnipEntry -Leaf
        Copy-Item -LiteralPath $catnipPath -Destination (Join-Path $cpDir $catnipName)
        Write-Host "Using $catnipName for the catnip config classes"
    }
} else {
    Write-Host "No catnip library inside the create jar: the torpedo burst will use the plain blast"
}
$createBigCannons = Get-ChildItem -LiteralPath $modsDir -Filter "*createbigcannons-*.jar" | Select-Object -First 1
if ($createBigCannons) { Copy-Item -LiteralPath $createBigCannons.FullName -Destination (Join-Path $cpDir "createbigcannons.jar") }
$cbcModernWarfare = Get-ChildItem -LiteralPath $modsDir -Filter "*cbcmodernwarfare-*.jar" | Select-Object -First 1
if (-not $cbcModernWarfare) { Write-Host "No cbcmodernwarfare jar found in $modsDir"; exit 1 }
Copy-Item -LiteralPath $cbcModernWarfare.FullName -Destination (Join-Path $cpDir "cbcmodernwarfare.jar")
$ritchiesProjectileLib = Get-ChildItem -LiteralPath $modsDir -Filter "*ritchiesprojectilelib-*.jar" | Select-Object -First 1
if (-not $ritchiesProjectileLib) { Write-Host "No ritchiesprojectilelib jar found in $modsDir"; exit 1 }
Copy-Item -LiteralPath $ritchiesProjectileLib.FullName -Destination (Join-Path $cpDir "ritchiesprojectilelib.jar")

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
Get-ChildItem -Path "$ws/src/main/java" -Recurse -Filter "*.java" |
    Where-Object { ($yuushyaJar -or -not ($_.FullName.Replace('\','/') -like "*/yuushya/*")) -and ($maidJar -or -not ($_.FullName.Replace('\','/') -like "*/compat/maid/*")) -and ($vestalihyJar -or ((-not ($_.FullName.Replace('\','/') -like "*/mixin/Vestalihy*")) -and (-not ($_.FullName.Replace('\','/') -like "*/client/Vestalihy*")))) -and ($copycatsJar -or -not ($_.FullName.Replace('\','/') -like "*/compat/copycats/*")) -and ($terminalBallisticsJar -or -not ($_.FullName.Replace('\','/') -like "*/mixin/CtbSpall*")) -and ($dieselJar -or -not ($_.FullName.Replace('\','/') -like "*/mixin/DieselEngineSonicMixin.java")) -and ($gearworkJar -or -not ($_.FullName.Replace('\','/') -like "*/mixin/GearworkRunningGearMixin.java")) -and ($tracksJar -or -not ($_.FullName.Replace('\','/') -like "*/mixin/TracksRunningGearMixin.java")) } |
    ForEach-Object { $lines.Add($_.FullName.Replace('\','/')) }
[System.IO.File]::WriteAllLines($argFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Compiling $($lines.Count - 7) java files..."
$javacAttempts = 0
while ($true) {
    $javacAttempts++
    & $javac "@$argFile"
    if ($LASTEXITCODE -eq 0) { break }
    if ($javacAttempts -ge 2) {
        Write-Host "COMPILATION FAILED (exit $LASTEXITCODE)"
        exit 1
    }
    Write-Host "javac exit $LASTEXITCODE on attempt $javacAttempts, retrying..."
}
Write-Host "Compilation OK! ($((Get-ChildItem -Path $outDir -Recurse -Filter *.class | Measure-Object).Count) classes)"

$stage = "$ws/build/jar_tmp"
if (Test-Path $stage) { Remove-Item -Recurse -Force $stage }
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item -Recurse -Force "$outDir/*" $stage
Copy-Item -Recurse -Force "$ws/src/main/resources/*" $stage

$jarOut = "$ws/firecontrolcompat-1.60.2.jar"
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






