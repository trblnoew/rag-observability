$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$configuredJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
if (-not $env:JAVA_HOME -and $configuredJavaHome) {
    $env:JAVA_HOME = $configuredJavaHome
}
if (-not $env:JAVA_HOME) {
    $installedJdk = Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($installedJdk) {
        $env:JAVA_HOME = $installedJdk.FullName
    }
}
if ($env:JAVA_HOME) {
    $env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
}
$env:MAVEN_USER_HOME = Join-Path $env:USERPROFILE '.m2'

$dockerPath = 'C:\Program Files\Docker\Docker\resources\bin\docker.exe'
if (-not (Get-Command docker -ErrorAction SilentlyContinue) -and (Test-Path $dockerPath)) {
    $env:Path = "$(Split-Path $dockerPath);$env:Path"
}

Write-Host '== Java =='
java -version
javac -version

Write-Host "`n== Maven Wrapper =="
Push-Location $projectRoot
try {
    & .\mvnw.cmd "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" --version
} finally {
    Pop-Location
}

Write-Host "`n== Docker =="
docker version
docker compose version

Write-Host "`nEnvironment check passed."

