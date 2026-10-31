param([ValidateSet('stockflow','identity','catalog','inventory','orders','fulfillment','notification')][string]$Database='stockflow')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force artifacts/backups | Out-Null
$file="artifacts/backups/$Database-$(Get-Date -Format yyyyMMdd-HHmmss).sql"
docker compose exec -T postgres pg_dump -U postgres --no-owner --no-acl $Database | Set-Content -Encoding utf8 $file
if($LASTEXITCODE -ne 0){throw 'Backup failed'}
Write-Host "Saved $file. For a consistent distributed backup, stop API services before backing up all six databases."
