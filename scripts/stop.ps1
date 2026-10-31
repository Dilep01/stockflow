$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
docker compose --profile lite --profile distributed --profile monitoring --profile cache stop
