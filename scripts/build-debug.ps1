$originalBuildDirectory = $env:ORG_GRADLE_PROJECT_moreadBuildDir
$env:ORG_GRADLE_PROJECT_moreadBuildDir = "../.isolated-build/app"
$exitCode = 1

try {
    & (Join-Path $PSScriptRoot "gradle.ps1") assembleDebug @args
    $exitCode = $LASTEXITCODE
} finally {
    $env:ORG_GRADLE_PROJECT_moreadBuildDir = $originalBuildDirectory
}

exit $exitCode
