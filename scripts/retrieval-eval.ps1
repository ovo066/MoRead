param(
    [Parameter(Mandatory = $true)][string]$ManifestPath,
    [ValidateSet("export", "evaluate")][string]$Action = "evaluate"
)
$ErrorActionPreference = "Stop"
$manifest = (Resolve-Path -LiteralPath $ManifestPath).Path
$oldManifest = $env:MOREAD_RETRIEVAL_EVAL_MANIFEST
$oldAction = $env:MOREAD_RETRIEVAL_EVAL_ACTION
try {
    $env:MOREAD_RETRIEVAL_EVAL_MANIFEST = $manifest
    $env:MOREAD_RETRIEVAL_EVAL_ACTION = $Action
    # Re-run just the test task, not every compilation task. Never cache private corpus reports remotely.
    & (Join-Path $PSScriptRoot "gradle.ps1") :app:testDebugUnitTest --tests "*LocalRetrievalEvaluationTest" --rerun --no-build-cache --console=plain
    $code = $LASTEXITCODE
} finally {
    $env:MOREAD_RETRIEVAL_EVAL_MANIFEST = $oldManifest
    $env:MOREAD_RETRIEVAL_EVAL_ACTION = $oldAction
}
exit $code
