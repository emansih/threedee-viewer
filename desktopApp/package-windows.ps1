# Packages the desktop app for Windows with jpackage (JDK 21+; SceneView's desktop renderer needs 21).
#   .\desktopApp\package-windows.ps1              # MSI installer (needs the WiX Toolset)
#   .\desktopApp\package-windows.ps1 -Type exe    # EXE installer (needs the WiX Toolset)
#   .\desktopApp\package-windows.ps1 -Type app-image   # portable folder with "ThreeDee Viewer.exe", no WiX needed
# Output: build\jpackage
param(
    [ValidateSet("msi", "exe", "app-image")] [string] $Type = "msi",
    [string] $Jpackage = "jpackage",
    # Raise it for every installer you hand out: Windows Installer only upgrades to a higher version
    [string] $Version = "1.0.2",
    # WiX 3 (candle.exe, light.exe); found on the PATH when not given. JDK 23's jpackage needs WiX 3.
    [string] $WixDir = "$env:LOCALAPPDATA\Programs\wix314"
)
$ErrorActionPreference = "Stop"
if (Test-Path (Join-Path $WixDir "candle.exe")) { $env:PATH = "$WixDir;$env:PATH" }
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

& .\kotlin.bat package -m desktopApp
if ($LASTEXITCODE -ne 0) { throw "kotlin package failed" }

$jar = Get-Item build\tasks\_desktopApp_executableJarJvm\desktopApp-jvm-executable.jar
$staging = "build\jpackage\input"
Remove-Item -Recurse -Force $staging -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $staging | Out-Null

# The executable jar keeps its libraries by file name only, so two artifacts with the same
# name lose one: org.jetbrains.compose.runtime:runtime-desktop (a stub) hides
# androidx.compose.runtime:runtime-desktop (the real runtime). Ship the libraries as plain
# jars instead, with every same-named artifact from the toolchain's cache.
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    foreach ($entry in $zip.Entries | Where-Object { $_.FullName -like "BOOT-INF/lib/*.jar" }) {
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $staging $entry.Name))
    }
} finally { $zip.Dispose() }
$cache = Join-Path $env:LOCALAPPDATA "JetBrains\Kotlin\.m2.cache"
$cached = Get-ChildItem $cache -Recurse -Filter *.jar | Group-Object Name -AsHashTable
foreach ($lib in Get-ChildItem $staging) {
    $sameName = $cached[$lib.Name]
    if ($sameName.Count -gt 1) {
        Remove-Item $lib.FullName
        foreach ($artifact in $sameName) {
            $group = $artifact.Directory.Parent.Parent.FullName.Substring($cache.Length + 1).Replace("\", ".")
            Copy-Item $artifact.FullName (Join-Path $staging "$group-$($artifact.Name)")
        }
    }
}

$arguments = @(
    "--type", $Type,
    # The Start menu shortcut and the install folder take this name, so it's what Windows search finds
    "--name", "ThreeDee Viewer",
    "--app-version", $Version,
    "--vendor", "ThreeDee Viewer",
    "--input", $staging,
    "--main-jar", "desktopApp-jvm.jar",
    "--main-class", "com.hisname.productrender.MainKt",
    "--dest", "build\jpackage",
    # Skiko and Filament load native libraries
    "--java-options", "--enable-native-access=ALL-UNNAMED"
)
if ($Type -ne "app-image") {
    $arguments += @("--win-menu", "--win-menu-group", "ThreeDee Viewer", "--win-shortcut", "--win-dir-chooser", "--win-upgrade-uuid", "6f7c3d1e-2b8a-4c5e-9f1d-3a7b5e9c2d40")
}
& $Jpackage @arguments
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }
Get-ChildItem build\jpackage -Exclude input
