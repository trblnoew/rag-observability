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
if (-not $env:JAVA_HOME) {
    throw 'JAVA_HOME is not configured. Run scripts/check-env.ps1 after installing Java 21.'
}
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
$env:MAVEN_USER_HOME = Join-Path $env:USERPROFILE '.m2'

Push-Location $projectRoot
try {
    & .\mvnw.cmd "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" spring-boot:run
} finally {
    Pop-Location
}

