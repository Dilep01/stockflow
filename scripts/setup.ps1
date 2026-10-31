$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Test-Path '.env')) {
    function New-LocalSecret { $bytes = [byte[]]::new(32); [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes); [Convert]::ToHexString($bytes).ToLower() }
    @("POSTGRES_PASSWORD=$(New-LocalSecret)", "JWT_SECRET=$(New-LocalSecret)", 'DEMO_PASSWORD=StockflowDemo2026!', "GRAFANA_PASSWORD=$(New-LocalSecret)") | Set-Content '.env'
    Write-Host 'Created local credentials in .env. Demo login password: StockflowDemo2026!'
} else { Write-Host 'Keeping existing .env credentials.' }
