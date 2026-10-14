# Windows-side diagnostics. Run in PowerShell:  powershell -ExecutionPolicy Bypass -File master\tools\gpu_diag.ps1
$out = "gpu_diag_windows_$(Get-Date -Format yyyyMMdd_HHmmss).txt"
& {
  "# Windows diagnostics $(Get-Date -Format o)"
  "### OS";            (Get-CimInstance Win32_OperatingSystem | Select-Object Caption, Version, BuildNumber | Format-List | Out-String)
  "### CPU";           (Get-CimInstance Win32_Processor | Select-Object Name, NumberOfCores, NumberOfLogicalProcessors | Format-List | Out-String)
  "### RAM (GB)";      [math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory / 1GB, 1)
  "### GPU";           (Get-CimInstance Win32_VideoController | Select-Object Name, DriverVersion | Format-List | Out-String)
  "### nvidia-smi";    try { nvidia-smi } catch { "nvidia-smi not found" }
  "### WSL";           try { wsl --status; wsl -l -v } catch { "wsl not installed" }
  "### Docker";        try { docker version --format '{{.Server.Version}}' } catch { "docker not found" }
} *>&1 | Out-File -Encoding utf8 $out
Write-Host "wrote $out"
