param(
    [Parameter(Mandatory = $true)]
    [string]$BaseUrl,

    [string]$ShortcutName = "ChobYar Trader Monitor",

    [switch]$Launch
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Stop-Setup {
    param([string]$Message)
    throw "ChobYar monitor setup blocked: $Message"
}

$uri = $null
if (-not [System.Uri]::TryCreate($BaseUrl, [System.UriKind]::Absolute, [ref]$uri)) {
    Stop-Setup "BaseUrl is not an absolute URL."
}
if ($uri.Scheme -ne "https") {
    Stop-Setup "HTTPS is required."
}

$root = $BaseUrl.TrimEnd("/")
$reportUrl = "$root/public-report"
$monitorUrl = "$root/monitor/"

try {
    $report = Invoke-RestMethod -Uri $reportUrl -Method Get -TimeoutSec 15
} catch {
    Stop-Setup "The read-only public report is unavailable: $($_.Exception.Message)"
}

if ($report.ok -ne $true) {
    Stop-Setup "The public report is not healthy."
}
if ($report.public_report -ne $true) {
    Stop-Setup "The endpoint is not marked as a public report."
}
if ([string]$report.mode -ne "paper") {
    Stop-Setup "TRADING_MODE must be paper."
}
if ($report.live_locked -ne $true) {
    Stop-Setup "The live-trading lock is not active."
}
if ([int]$report.report_version -lt 8) {
    Stop-Setup "Report version 8 or newer is required."
}
if ($null -eq $report.paper_exploration) {
    Stop-Setup "Paper exploration safety status is missing."
}
if ($report.paper_exploration.execution_authority -ne $false) {
    Stop-Setup "Paper exploration must have no execution authority."
}

$programFilesX86 = [Environment]::GetFolderPath("ProgramFilesX86")
$programFiles = [Environment]::GetFolderPath("ProgramFiles")
$chromeCandidates = @(
    (Join-Path $programFilesX86 "Google\Chrome\Application\chrome.exe"),
    (Join-Path $programFiles "Google\Chrome\Application\chrome.exe")
)
$chrome = $chromeCandidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $chrome) {
    Stop-Setup "Google Chrome was not found."
}

$desktop = [Environment]::GetFolderPath("Desktop")
$shortcutPath = Join-Path $desktop ($ShortcutName + ".lnk")
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $chrome
$shortcut.Arguments = "--app=`"$monitorUrl`" --start-maximized"
$shortcut.WorkingDirectory = Split-Path $chrome
$shortcut.Description = "Read-only ChobYar paper-trading monitor"
$shortcut.Save()

$result = [ordered]@{
    ok = $true
    mode = "paper"
    live_locked = $true
    report_version = [int]$report.report_version
    monitor_url = $monitorUrl
    shortcut = $shortcutPath
    execution_controls = $false
}
$result | ConvertTo-Json -Compress

if ($Launch) {
    Start-Process -FilePath $chrome -ArgumentList "--app=`"$monitorUrl`" --start-maximized"
}
