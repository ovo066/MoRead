$ErrorActionPreference = "Stop"

if ($args.Count -eq 0) {
    throw "请至少传入一个 Gradle 任务，例如 assembleDebug。"
}

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$requiresAsciiAlias = $root.ToCharArray() | Where-Object { [int]$_ -gt 127 }

if (-not $requiresAsciiAlias) {
    & (Join-Path $root "gradlew.bat") @args
    exit $LASTEXITCODE
}

$driveName = @("M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z") |
    Where-Object { -not (Get-PSDrive -Name $_ -PSProvider FileSystem -ErrorAction SilentlyContinue) } |
    Select-Object -First 1

if (-not $driveName) {
    throw "没有可用的临时盘符，无法规避中文路径下的 Gradle classpath 问题。"
}

$drive = $driveName + ":"
$created = $false
$originalJavaHome = $env:JAVA_HOME
$locationPushed = $false
$exitCode = 1

subst $drive $root
if ($LASTEXITCODE -ne 0) {
    throw "创建临时盘符 $drive 失败。"
}
$created = $true

try {
    if ($env:JAVA_HOME -and $env:JAVA_HOME.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        $env:JAVA_HOME = $drive + $env:JAVA_HOME.Substring($root.Length)
    }
    Push-Location ($drive + "\")
    $locationPushed = $true
    & ".\gradlew.bat" @args
    $exitCode = $LASTEXITCODE
} finally {
    if ($locationPushed) {
        Pop-Location
    }
    $env:JAVA_HOME = $originalJavaHome
    if ($created) {
        subst $drive /D
    }
}

exit $exitCode
