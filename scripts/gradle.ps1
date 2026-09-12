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

# Serialize wrapper invocations for the same workspace, including alias teardown. Otherwise
# the process that created a temporary alias could remove it while another build still uses it.
$hasher = [System.Security.Cryptography.SHA256]::Create()
try {
    $workspaceKey = [BitConverter]::ToString($hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes($root.ToUpperInvariant()))).Replace('-', '').Substring(0, 20)
} finally { $hasher.Dispose() }
$buildMutex = [System.Threading.Mutex]::new($false, "Local\MoReadGradle-$workspaceKey")
$mutexHeld = $false
try {
    try { $null = $buildMutex.WaitOne(); $mutexHeld = $true }
    catch [System.Threading.AbandonedMutexException] { $mutexHeld = $true }

# Reuse an existing alias of THIS workspace. Choosing a new letter for every overlapping
# invocation makes Kotlin's incremental cache see different roots and fall back to a full build.
# QueryDosDevice uses Unicode; parsing subst's console output corrupts non-ASCII paths.
if (-not ("MoReadBuild.DriveAliases" -as [type])) {
    Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
using System.Text;
namespace MoReadBuild {
    public static class DriveAliases {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint QueryDosDevice(string name, StringBuilder target, int size);
        public static string Target(string drive) {
            var buffer = new StringBuilder(32768);
            return QueryDosDevice(drive, buffer, buffer.Capacity) == 0 ? null : buffer.ToString();
        }
    }
}
'@
}
$candidateNames = @("M", "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z")
$existingDriveName = $candidateNames | Where-Object {
    $target = [MoReadBuild.DriveAliases]::Target($_ + ":")
    $target -and $target.StartsWith('\??\') -and
        [string]::Equals($target.Substring(4).TrimEnd('\'), $root.TrimEnd('\'), [System.StringComparison]::OrdinalIgnoreCase)
} | Select-Object -First 1
$driveName = $existingDriveName
if (-not $driveName) {
    $driveName = $candidateNames |
        Where-Object { -not (Get-PSDrive -Name $_ -PSProvider FileSystem -ErrorAction SilentlyContinue) } |
        Select-Object -First 1
}

if (-not $driveName) {
    throw "没有可用的临时盘符，无法规避中文路径下的 Gradle classpath 问题。"
}

$drive = $driveName + ":"
$created = $false
$originalJavaHome = $env:JAVA_HOME
$locationPushed = $false
$exitCode = 1

if (-not $existingDriveName) {
    subst $drive $root
    if ($LASTEXITCODE -ne 0) {
        throw "创建临时盘符 $drive 失败。"
    }
    $created = $true
}

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
} finally {
    if ($mutexHeld) { $buildMutex.ReleaseMutex() }
    $buildMutex.Dispose()
}
