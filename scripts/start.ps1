param([ValidateSet('lite','distributed')][string]$Mode='lite',[switch]$Monitoring)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& "$PSScriptRoot/setup.ps1"
$composeArgs=@('compose','--profile',$Mode)
if($Monitoring){$composeArgs+=@('--profile','monitoring')}
& docker @composeArgs up -d --build --wait --wait-timeout 240
if($LASTEXITCODE -ne 0){throw 'Docker startup failed. Check Docker Desktop and the output above.'}
$port=if($Mode -eq 'lite'){8088}else{8089}
Write-Host "Open http://localhost:$port after services finish starting. Local email: http://localhost:8025"
