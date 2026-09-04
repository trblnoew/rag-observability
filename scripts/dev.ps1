param(
    [ValidateSet('up', 'down', 'status', 'logs')]
    [string]$Action = 'up'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$dockerPath = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'

if (-not (Get-Command docker -ErrorAction SilentlyContinue) -and (Test-Path $dockerPath)) {
    $env:Path = "$(Split-Path $dockerPath);$env:Path"
}

Push-Location $projectRoot
try {
    switch ($Action) {
        'up' { docker compose up -d --wait }
        'down' { docker compose down }
        'status' { docker compose ps }
        'logs' { docker compose logs --tail 200 -f }
    }
} finally {
    Pop-Location
}

