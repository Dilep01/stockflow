param([Parameter(Mandatory=$true)][string]$File,[Parameter(Mandatory=$true)][ValidateSet('inventory','orders','fulfillment','notification')][string]$Target)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
$event=Get-Content -LiteralPath $File -Raw | ConvertFrom-Json
if($event.target -ne $Target -or $event.schemaVersion -ne 1 -or -not $event.eventId -or -not $event.aggregateId -or -not $event.kind){throw 'Expected one schemaVersion=1 event with matching target and identifiers'}
if($event.aggregateId -match '[\r\n\t]'){throw 'Invalid aggregate key'}
$json=$event | ConvertTo-Json -Depth 20 -Compress
"$($event.aggregateId)`t$json" | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic "stockflow.$Target" --property parse.key=true --property "key.separator=`t"
if($LASTEXITCODE -ne 0){throw 'Replay publication failed'}
Write-Host "Published original event $($event.eventId) to stockflow.$Target. Verify the resulting business state and inbox."
