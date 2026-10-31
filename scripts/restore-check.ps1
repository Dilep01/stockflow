param([Parameter(Mandatory=$true)][string]$Backup)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if(-not(Test-Path -LiteralPath $Backup)){throw 'Backup file not found'}
$database='restore_check_'+(Get-Date -Format yyyyMMddHHmmss)
docker compose exec -T postgres createdb -U postgres $database
if($LASTEXITCODE -ne 0){throw 'Could not create isolated restore database'}
Get-Content -LiteralPath $Backup | docker compose exec -T postgres psql -U postgres -v ON_ERROR_STOP=1 -d $database
if($LASTEXITCODE -ne 0){throw 'Restore failed'}
docker compose exec -T postgres psql -U postgres -d $database -c '\dt'
Write-Host "Restored to isolated database $database. Live databases were not overwritten."
