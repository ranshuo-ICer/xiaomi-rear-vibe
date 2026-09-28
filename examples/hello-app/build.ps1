# examples/hello-app/build.ps1 - same no-Gradle pipeline as app/build.ps1.
# Requires JAVA_HOME (JDK 17+) and ANDROID_HOME (platforms;android-35,
# build-tools;35.0.0). Produces build\hello.apk.
param(
    [string]$JavaHome  = $env:JAVA_HOME,
    [string]$SdkRoot   = $(if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }),
    [string]$Platform  = "android-35",
    [string]$BuildTools = "35.0.0"
)
$ErrorActionPreference = "Stop"

if (-not $JavaHome) { throw "JAVA_HOME is not set (need JDK 17+)" }
if (-not $SdkRoot)  { throw "ANDROID_HOME / ANDROID_SDK_ROOT is not set" }

$root   = $PSScriptRoot
$src    = Join-Path $root "src\main"
$build  = Join-Path $root "build"
$ks     = Join-Path $root "debug.jks"
$bt     = Join-Path $SdkRoot "build-tools\$BuildTools"
$andJar = Join-Path $SdkRoot "platforms\$Platform\android.jar"

$env:JAVA_HOME = $JavaHome
$env:Path = "$JavaHome\bin;$bt;" + $env:Path
foreach ($p in @($andJar, "$bt\aapt2.exe", "$bt\d8.bat", "$bt\apksigner.bat")) {
    if (-not (Test-Path $p)) { throw "missing build dependency: $p" }
}

Remove-Item $build -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$build\classes", "$build\dex" | Out-Null

& "$bt\aapt2.exe" link -o "$build\hello-unsigned.apk" -I $andJar `
    --manifest "$src\AndroidManifest.xml" `
    --min-sdk-version 26 --target-sdk-version 35 --version-code 1 --version-name 1.0
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

& "$JavaHome\bin\javac.exe" -classpath $andJar -encoding UTF-8 -d "$build\classes" `
    "$src\java\com\dsh\hello\MainActivity.java"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

$classes = Get-ChildItem "$build\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --release --lib $andJar --output "$build\dex" @classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Push-Location "$build\dex"
& "$JavaHome\bin\jar.exe" uf "$build\hello-unsigned.apk" classes.dex
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

if (-not (Test-Path $ks)) {
    & "$JavaHome\bin\keytool.exe" -genkeypair -keystore $ks -storepass android `
        -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US"
}
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$build\hello.apk" "$build\hello-unsigned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Write-Host "OK -> $build\hello.apk"
