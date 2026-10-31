param([string]$Java='java')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
foreach($line in Get-Content '.env'){if($line -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')}}
$env:DB_URL='jdbc:postgresql://localhost:55432/stockflow'
$env:DB_USER='sf_stockflow'
$env:DB_PASSWORD=$env:POSTGRES_PASSWORD
$env:PORT='18080'
& $Java -jar backend/target/stockflow-1.0.0.jar
