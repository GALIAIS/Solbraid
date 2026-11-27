# Solbraid Windows 一键启动脚本（自动建立 USB adb reverse 隧道并启动 PC 守护代理）
param(
    [string]$BindHost = "127.0.0.1",
    [int]$Port = 29840,
    [ValidateSet("observe", "operate", "admin")]
    [string]$Permission = "operate",
    [string]$Relay = "",
    [string]$AdbDevice = "",
    [switch]$SkipAdbReverse
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$agentScript = Join-Path $scriptDir "solbraid-pc-agent.mjs"

if (-not $SkipAdbReverse) {
    $adb = Get-Command adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
    if (-not $adb -and (Test-Path "E:\WorkSpace\android_sdk\platform-tools\adb.exe")) {
        $adb = "E:\WorkSpace\android_sdk\platform-tools\adb.exe"
    }
    if ($adb) {
        $devArgs = if ($AdbDevice) { @("-s", $AdbDevice) } else { @() }
        & $adb @devArgs reverse "tcp:$Port" "tcp:$Port" 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[USB] 已建立 adb reverse tcp:$Port -> tcp:$Port（手机可通过 127.0.0.1:$Port 零延迟直连）" -ForegroundColor Green
        }
    }
}

$nodeArgs = @($agentScript, "--host", $BindHost, "--port", "$Port", "--permission", $Permission)
if ($Relay) { $nodeArgs += @("--relay", $Relay) }
& node @nodeArgs
