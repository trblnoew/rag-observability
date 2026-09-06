param(
    [Parameter(Mandatory=$true)][string]$ThresholdDir,
    [Parameter(Mandatory=$true)][string]$TopKDir,
    [Parameter(Mandatory=$true)][string]$ChunkSizeDir
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
# Read existing reports only; never import .env, start Spring, or connect to a DB.
$inputs = @($ThresholdDir,$TopKDir,$ChunkSizeDir) | ForEach-Object { (Resolve-Path -LiteralPath $_).Path }
$configuredJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME','User')
if (-not $env:JAVA_HOME -and $configuredJavaHome) { $env:JAVA_HOME = $configuredJavaHome }
if (-not $env:JAVA_HOME) {
    $installedJdk = Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if ($installedJdk) { $env:JAVA_HOME = $installedJdk.FullName }
}
if (-not $env:JAVA_HOME) { throw 'Java 21 required' }
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
$mavenRepository = Join-Path $env:USERPROFILE '.m2/repository'
Push-Location $projectRoot
try {
    & .\mvnw.cmd "-Dmaven.repo.local=$mavenRepository" test-compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath '-DincludeScope=test' '-Dmdep.outputFile=target/frontend-export-classpath.txt'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $dependencies = (Get-Content -LiteralPath target/frontend-export-classpath.txt -Raw).Trim()
    & java -cp "target/test-classes;target/classes;$dependencies" com.portfolio.ragobservability.evaluation.chunksize.FrontendExportMain $inputs[0] $inputs[1] $inputs[2] 'frontend/src/data'
    exit $LASTEXITCODE
} finally { Pop-Location }
