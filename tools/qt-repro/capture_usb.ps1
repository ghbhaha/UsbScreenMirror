# Capture USB traffic with USBPcap -- MUST run in an ADMIN PowerShell.
#
#   Open PowerShell as Administrator, then:
#     powershell -NoProfile -ExecutionPolicy Bypass -File "<repo>\tools\qt-repro\capture_usb.ps1"
#
# Usage: start this FIRST, then click "Start" in iPhoneMirror (WIRED), wait until the iPad
# picture is actually moving, then press ENTER here to stop. A fixed time window almost never
# lines up with the moment the app is really streaming, so stopping is manual on purpose.
#
# Why admin: `\\.\USBPcapN` can only be opened by administrators. A normal account gets
# "Access is denied", which is easily mistaken for "driver not installed".
#
# IMPORTANT: -A alone does NOTHING. Its help text is
#   "-A, --capture-from-all-devices: Captures data from all devices connected to
#    selected Root Hub."
# so `-d \\.\USBPcapN` is mandatory. Without it USBPcapCMD opens nothing and
# produces no pcap at all (the classic silent failure).
# `--capture-from-new-devices` is equally mandatory: `activate` makes the device RE-ENUMERATE
# with a new address, and without this flag the new address is never captured (0x86 seen: none).
# `-I` (NonStandardHWIDs) prints the HWID scan and RETURNS immediately -- never combine it here.
#
# iPad on this machine sits on \\.\USBPcap2 (\\.\USBPcap1 only ever yields the 24-byte header).
# Parse afterwards:  python usbpcap_dump.py capture\qt_audio.pcap --fcc

param(
    [int]    $Hub     = 2,
    [string] $Out     = ""
)

$ErrorActionPreference = "Stop"

$cmd = "C:\Program Files\USBPcap\USBPcapCMD.exe"
if (-not (Test-Path $cmd)) { throw "USBPcapCMD.exe not found. Run: winget install desowin.USBPcap" }

if ([string]::IsNullOrWhiteSpace($Out)) {
    $Out = Join-Path $PSScriptRoot "capture\qt_audio.pcap"
}
$dir = Split-Path -Parent $Out
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "ERROR: not elevated. Use 'Run as administrator' PowerShell." -ForegroundColor Red
    exit 1
}

$log = Join-Path $dir "usbpcap.log"
"=== run $(Get-Date -Format o) hub=$Hub ===" | Set-Content -Encoding ASCII $log

# A previous (possibly elevated) USBPcapCMD keeps the pcap file open and makes everything fail
# with "file is being used by another process". Clear it first -- we are elevated, so this works.
Get-Process USBPcapCMD -ErrorAction SilentlyContinue | ForEach-Object {
    try { $_.Kill() } catch { }
}
Start-Sleep -Milliseconds 400
if (Test-Path $Out) { Remove-Item $Out -Force -ErrorAction SilentlyContinue }

$hubPath = "\\.\USBPcap$Hub"
Write-Host ("Capturing on {0} -> {1}" -f $hubPath, $Out) -ForegroundColor Cyan
Write-Host "iPhoneMirror must ALREADY be streaming (iPad picture moving) BEFORE you run this." -ForegroundColor Yellow
Write-Host "Do NOT press Start after this: activate re-enumerates the device and -A only" -ForegroundColor Yellow
Write-Host "covers devices present at capture start, so the new address would be missed." -ForegroundColor Yellow
Write-Host "Press ENTER here to stop." -ForegroundColor Yellow

$p = Start-Process -FilePath $cmd `
    -ArgumentList @('-d', $hubPath, '-A', '--capture-from-new-devices', '--inject-descriptors', '-o', $Out) `
    -PassThru -RedirectStandardOutput "$dir\cap.out" -RedirectStandardError "$dir\cap.err"

$null = Read-Host "Press ENTER to stop capturing"
if (-not $p.HasExited) { $p.Kill(); Start-Sleep -Milliseconds 500 }

$size = 0
if (Test-Path $Out) { $size = (Get-Item $Out).Length }
("hub {0} -> {1} bytes" -f $Hub, $size) | Add-Content -Encoding ASCII $log

foreach ($f in @("$dir\cap.out", "$dir\cap.err")) {
    if ((Test-Path $f) -and (Get-Item $f).Length -gt 0) {
        $t = (Get-Content $f -Raw) -replace "`r`n", " | "
        ("  " + $t) | Add-Content -Encoding ASCII $log
        Write-Host ("  " + $t.Trim()) -ForegroundColor DarkGray
    }
}

if ($size -gt 64) {
    Write-Host ("OK: {0} bytes -> {1}" -f $size, $Out) -ForegroundColor Green
    Write-Host ("Parse: python usbpcap_dump.py `"{0}`" --fcc" -f $Out)
} else {
    Write-Host "FAILED: no traffic captured. See $log" -ForegroundColor Red
    exit 2
}
