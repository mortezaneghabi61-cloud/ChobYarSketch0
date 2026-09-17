# ChobYar Windows laptop monitor

This folder installs the existing ChobYar web monitor as a Microsoft Edge app shortcut on a Windows laptop.

The setup is intentionally read-only and fail-closed. Before creating the shortcut it verifies:

- the URL is HTTPS;
- `/public-report` is healthy and explicitly public;
- trading mode is `paper`;
- the live-trading lock is active;
- report version is 8 or newer;
- paper exploration has no execution authority.

## Run on the laptop

Open PowerShell in this folder and run:

```powershell
powershell -ExecutionPolicy Bypass -File .\setup-chobyar-monitor.ps1 -BaseUrl "https://MONITOR_HOST" -Launch
```

Replace `MONITOR_HOST` with the verified HTTPS hostname of the VPS monitor. If the endpoint or any safety lock is unavailable, setup stops without creating the shortcut.

The shortcut opens `/monitor/` in Edge app mode. It stores no exchange credentials and exposes no order, cancel, withdrawal, or execution controls.
