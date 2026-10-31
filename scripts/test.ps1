param([string]$Maven='mvn')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
foreach($line in Get-Content '.env'){if($line -match '^([^#=]+)=(.*)$'){[Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process')}}
docker compose up -d postgres
if($LASTEXITCODE -ne 0){throw 'PostgreSQL startup failed'}
docker compose exec -T postgres psql -U postgres -tAc "SELECT 1 FROM pg_database WHERE datname='stockflow_test'" | ForEach-Object {$exists=$_}
if($exists -ne '1'){docker compose exec -T postgres createdb -U postgres -O sf_stockflow stockflow_test}
$env:TEST_DB_PASSWORD=$env:POSTGRES_PASSWORD
& $Maven -B -f backend/pom.xml test
if($LASTEXITCODE -ne 0){throw 'Backend tests failed'}
npm.cmd --prefix frontend run build
if($LASTEXITCODE -ne 0){throw 'Frontend build failed'}
npm.cmd --prefix frontend test
if($LASTEXITCODE -ne 0){throw 'Frontend tests failed'}
