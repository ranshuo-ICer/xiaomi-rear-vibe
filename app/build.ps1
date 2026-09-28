# app/build.ps1 - hand-rolled Android build: aapt2 + javac + d8 + apksigner.
# No Gradle, no Android Studio. Produces build\rearvibe.apk.
#
# Requirements (env vars):
#   JAVA_HOME          JDK 17+          e.g. Temurin 21
#   ANDROID_HOME       Android SDK root with:
#     - platforms;android-35            (or pass -Platform)
#     - build-tools;35.0.0              (or pass -BuildTools)
#
# Usage:  pwsh -File build.ps1 [-Platform android-35] [-BuildTools 35.0.0]
param(
    [string]$JavaHome  = $env:JAVA_HOME,
    [string]$SdkRoot   = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }),
    [string]$Platform  = "android-35",
    [string]$BuildTools = "35.0.0"
)
$ErrorActionPreference = "Stop"

if (-not $JavaHome) { throw "JAVA_HOME is not set (need JDK 17+)" }
if (-not $SdkRoot)  { throw "ANDROID_HOME / ANDROID_SDK_ROOT is not set" }

$root    = $PSScriptRoot
$src     = Join-Path $root "src\main"
$build   = Join-Path $root "build"
$ks      = Join-Path $root "debug.jks"
$bt      = Join-Path $SdkRoot "build-tools\$BuildTools"
$andJar  = Join-Path $SdkRoot "platforms\$Platform\android.jar"

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$bt;" + $env:Path

foreach ($p in @($andJar, "$bt\aapt2.exe", "$bt\d8.bat", "$bt\apksigner.bat")) {
    if (-not (Test-Path $p)) { throw "missing build dependency: $p" }
}

Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$build\classes", "$build\dex" | Out-Null

Write-Host "[1/6] aapt2 link"
& "$bt\aapt2.exe" link -o "$build\rearvibe-unsigned.apk" -I $andJar `
    --manifest "$src\AndroidManifest.xml" `
    --min-sdk-version 26 --target-sdk-version 35 `
    --version-code 1 --version-name 1.0
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[2/6] javac"
$sources = Get-ChildItem "$src\java" -Recurse -Filter *.java | ForEach-Object FullName
& "$JavaHome\bin\javac.exe" -classpath $andJar -encoding UTF-8 -d "$build\classes" @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[3/6] d8"
$classes = Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --release --lib $andJar --output "$build\dex" @classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[4/6] add classes.dex"
Push-Location "$build\dex"
& "$JavaHome\bin\jar.exe" uf "$build\rearvibe-unsigned.apk" classes.dex
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "[5/6] keystore"
if (-not (Test-Path $ks)) {
    & "$JavaHome\bin\keytool.exe" -genkeypair -keystore $ks -storepass android `
        -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "[6/6] apksigner"
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$build\rearvibe.apk" "$build\rearvibe-unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "OK -> $build\rearvibe.apk ($([math]::Round((Get-Item "$build\rearvibe.apk").Length/1KB,1)) KB)"
Write-Host "install: adb install -r `"$build\rearvibe.apk`""
