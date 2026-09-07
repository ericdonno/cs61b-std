[CmdletBinding()]
param(
    [string]$Brain = "",
    [switch]$Check,
    [switch]$ProviderSmoke
)

$ErrorActionPreference = "Stop"
$projectRoot = $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$providerConfigPath = Join-Path $projectRoot "config\provider.local"

function ConvertTo-PlainText {
    param([Security.SecureString]$SecureValue)
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

function Read-ProtectedProviderSettings {
    param([string]$Path)
    try {
        $protectedValue = (Get-Content -Raw -LiteralPath $Path).Trim()
        $json = ConvertTo-PlainText (
            ConvertTo-SecureString -String $protectedValue
        )
        return $json | ConvertFrom-Json
    } catch {
        throw "The saved provider settings cannot be decrypted by this Windows account. Delete config/provider.local and retry."
    }
}

function Save-ProviderSettings {
    param(
        [string]$ConfigPath,
        [string]$ApiBase,
        [string]$Model,
        [string]$TokenLimitField,
        [string]$ApiKey
    )
    $directory = Split-Path -Parent $ConfigPath
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
    $json = [ordered]@{
        schemaVersion = "provider-config.v1"
        apiBase = $ApiBase
        model = $Model
        tokenLimitField = $TokenLimitField
        apiKey = $ApiKey
    } | ConvertTo-Json -Compress
    ConvertTo-SecureString -String $json -AsPlainText -Force |
        ConvertFrom-SecureString |
        Set-Content -LiteralPath $ConfigPath -Encoding UTF8
}

function Start-WithEnvironment {
    param(
        [System.Diagnostics.Process]$Process,
        [hashtable]$Environment
    )
    $previous = @{}
    foreach ($entry in $Environment.GetEnumerator()) {
        $previous[$entry.Key] = [Environment]::GetEnvironmentVariable(
            $entry.Key, [EnvironmentVariableTarget]::Process
        )
        [Environment]::SetEnvironmentVariable(
            $entry.Key, $entry.Value, [EnvironmentVariableTarget]::Process
        )
    }
    try {
        return $Process.Start()
    } finally {
        foreach ($entry in $previous.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable(
                $entry.Key, $entry.Value, [EnvironmentVariableTarget]::Process
            )
        }
    }
}

if ($ProviderSmoke -and [string]::IsNullOrWhiteSpace($Brain)) {
    $Brain = "model"
}
if ([string]::IsNullOrWhiteSpace($Brain)) {
    $Brain = $env:DUNGEONMIND_BRAIN
}
if ([string]::IsNullOrWhiteSpace($Brain)) {
    $Brain = "scripted"
}
if ($Brain -notin @("scripted", "model")) {
    throw "Brain must be 'scripted' or 'model'."
}

$modelEnvironment = @{}
if ($Brain -eq "model") {
    $savedProvider = $null
    if (Test-Path -LiteralPath $providerConfigPath) {
        $savedProvider = Read-ProtectedProviderSettings $providerConfigPath
    }
    $apiBase = $env:DUNGEONMIND_API_BASE
    if ([string]::IsNullOrWhiteSpace($apiBase) -and $null -ne $savedProvider) {
        $apiBase = $savedProvider.apiBase
    }
    if ([string]::IsNullOrWhiteSpace($apiBase)) {
        $apiBase = Read-Host "[model] API base [https://api.xiaomimimo.com/v1]"
        if ([string]::IsNullOrWhiteSpace($apiBase)) {
            $apiBase = "https://api.xiaomimimo.com/v1"
        }
    }
    $model = $env:DUNGEONMIND_MODEL
    if ([string]::IsNullOrWhiteSpace($model) -and $null -ne $savedProvider) {
        $model = $savedProvider.model
    }
    if ([string]::IsNullOrWhiteSpace($model)) {
        $model = Read-Host "[model] Model ID [mimo-v2.5]"
        if ([string]::IsNullOrWhiteSpace($model)) {
            $model = "mimo-v2.5"
        }
    }
    if ([string]::IsNullOrWhiteSpace($model)) {
        throw "Model ID must not be blank."
    }
    $apiKey = $env:DUNGEONMIND_API_KEY
    if ([string]::IsNullOrWhiteSpace($apiKey) -and
            $null -ne $savedProvider) {
        $apiKey = $savedProvider.apiKey
    }
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        $secureKey = Read-Host "[model] API key (input is hidden)" -AsSecureString
        $apiKey = ConvertTo-PlainText $secureKey
    }
    if ([string]::IsNullOrWhiteSpace($apiKey)) {
        throw "API key must not be blank."
    }
    $tokenLimitField = $env:DUNGEONMIND_TOKEN_LIMIT_FIELD
    if ([string]::IsNullOrWhiteSpace($tokenLimitField) -and
            $null -ne $savedProvider) {
        $tokenLimitField = $savedProvider.tokenLimitField
    }
    if ([string]::IsNullOrWhiteSpace($tokenLimitField)) {
        $tokenLimitField = if ($apiBase -match "xiaomimimo\.com") {
            "max_completion_tokens"
        } else {
            "max_tokens"
        }
    }
    $modelEnvironment = @{
        DUNGEONMIND_API_BASE = $apiBase
        DUNGEONMIND_MODEL = $model
        DUNGEONMIND_API_KEY = $apiKey
        DUNGEONMIND_TOKEN_LIMIT_FIELD = $tokenLimitField
    }
}

foreach ($command in @("java", "javac")) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw "$command was not found in PATH. Install a JDK and retry."
    }
}

$python = Join-Path $projectRoot "agent\python\.venv\Scripts\python.exe"
if (-not (Test-Path -LiteralPath $python)) {
    $systemPython = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $systemPython) {
        throw "Python 3.14 was not found. Install it and run play.bat again."
    }
    $version = & $systemPython.Source -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
    if ($LASTEXITCODE -ne 0 -or $version -ne "3.14") {
        throw "DungeonMind requires Python 3.14; found $version."
    }
    Write-Host "[play] Creating the local Python environment..."
    & $systemPython.Source -m venv "agent\python\.venv"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create agent/python/.venv."
    }
    & $python -m pip install -r "agent\python\pylock.toml"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to install the locked Python runtime dependencies."
    }
}

& $python -c "import langgraph, pydantic"
if ($LASTEXITCODE -ne 0) {
    throw "The Python runtime is incomplete. Delete agent/python/.venv and retry."
}

if ($ProviderSmoke) {
    Write-Host "[model] Running one bounded provider/tool/plan smoke..."
    $smokeInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $smokeInfo.FileName = $python
    $smokeInfo.Arguments = '"agent/python/smoke_test.py" --spawn-server --brain model --timeout-seconds 12'
    $smokeInfo.WorkingDirectory = $projectRoot
    $smokeInfo.UseShellExecute = $false
    $smokeInfo.CreateNoWindow = $true
    $smoke = [System.Diagnostics.Process]::new()
    $smoke.StartInfo = $smokeInfo
    if (-not (Start-WithEnvironment $smoke $modelEnvironment)) {
        throw "Could not start the provider smoke."
    }
    $smoke.WaitForExit()
    if ($smoke.ExitCode -ne 0) {
        throw "Provider smoke failed with code $($smoke.ExitCode)."
    }
    Save-ProviderSettings $providerConfigPath $apiBase `
        $model $tokenLimitField $apiKey
    Write-Host "[model] Provider smoke passed."
    Write-Host "[model] Provider settings saved with Windows user encryption."
    exit 0
}

$libraryRoot = (Resolve-Path "..\library-sp18\javalib").Path
$outputRoot = Join-Path $projectRoot "out\play"
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
$javaSources = Get-ChildItem "byog" -Recurse -Filter "*.java" |
    ForEach-Object { $_.FullName }

Write-Host "[play] Compiling DungeonMind..."
& javac -encoding UTF-8 -cp "$libraryRoot\*" -d $outputRoot $javaSources
if ($LASTEXITCODE -ne 0) {
    throw "Java compilation failed."
}

$runtimeArguments = @(
    "agent/python/run.py",
    "--host", "127.0.0.1",
    "--port", "0",
    "--mode", "normal",
    "--brain", $Brain
)
$quotedArguments = $runtimeArguments | ForEach-Object {
    '"' + $_.Replace('"', '\"') + '"'
}
$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.FileName = $python
$startInfo.Arguments = $quotedArguments -join " "
$startInfo.WorkingDirectory = $projectRoot
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardOutput = $true
$runtime = [System.Diagnostics.Process]::new()
$runtime.StartInfo = $startInfo

try {
    if (-not (Start-WithEnvironment $runtime $modelEnvironment)) {
        throw "Could not start the local Agent runtime."
    }
    $readyTask = $runtime.StandardOutput.ReadLineAsync()
    if (-not $readyTask.Wait([TimeSpan]::FromSeconds(15))) {
        throw "The local Agent runtime did not become ready within 15 seconds."
    }
    $readyLine = $readyTask.Result
    if ([string]::IsNullOrWhiteSpace($readyLine)) {
        throw "The local Agent runtime exited before its ready signal."
    }
    $ready = $readyLine | ConvertFrom-Json
    if ($ready.event -ne "ready" -or $ready.port -le 0) {
        throw "Invalid Agent ready signal: $readyLine"
    }
    Write-Host "[play] Agent ready: $($ready.brain) on $($ready.host):$($ready.port)"

    if ($Check) {
        $probe = [System.Net.Sockets.TcpClient]::new()
        try {
            $probe.Connect([string]$ready.host, [int]$ready.port)
        } finally {
            $probe.Dispose()
        }
        Write-Host "[play] Startup check passed."
    } else {
        Write-Host "[play] Opening DungeonMind..."
        & java "-Dfile.encoding=UTF-8" `
            "-Ddungeonmind.agent.host=$($ready.host)" `
            "-Ddungeonmind.agent.port=$($ready.port)" `
            -cp "$outputRoot;$libraryRoot\*" byog.Core.Main
        if ($LASTEXITCODE -ne 0) {
            throw "DungeonMind exited with code $LASTEXITCODE."
        }
    }
} finally {
    if ($null -ne $runtime -and -not $runtime.HasExited) {
        & "$env:SystemRoot\System32\taskkill.exe" `
            /PID $($runtime.Id) /T /F | Out-Null
        $runtime.WaitForExit(3000) | Out-Null
    }
}
