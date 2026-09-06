param([switch]$Live, [switch]$ValidateOnly)

$ErrorActionPreference = 'Stop'
if ($Live -and $ValidateOnly) { Write-Host 'Use either -Live or -ValidateOnly.'; exit 1 }
$projectRoot = Split-Path -Parent $PSScriptRoot
$comparisonExit = 1
try {
    $configuredJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
    if (-not $env:JAVA_HOME -and $configuredJavaHome) { $env:JAVA_HOME = $configuredJavaHome }
    if (-not $env:JAVA_HOME) {
        $installedJdk = Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | Select-Object -First 1
        if ($installedJdk) { $env:JAVA_HOME = $installedJdk.FullName }
    }
    if (-not $env:JAVA_HOME) { throw 'Java 21 required' }
    $env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
    $env:MAVEN_USER_HOME = Join-Path $env:USERPROFILE '.m2'
    Push-Location $projectRoot
    try {
        $gitCommit = 'unavailable'; $gitDirty = 'unknown'
        if (Get-Command git -ErrorAction SilentlyContinue) {
            try {
                $commit = & git rev-parse HEAD 2>$null
                if ($LASTEXITCODE -eq 0) {
                    $gitCommit = $commit.Trim()
                    $changes = & git status --porcelain --untracked-files=normal
                    if ($LASTEXITCODE -eq 0) { $gitDirty = if ($changes) { 'true' } else { 'false' } }
                }
            } catch { $gitCommit = 'unavailable'; $gitDirty = 'unknown' }
        }
        & .\mvnw.cmd "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" test-compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath '-DincludeScope=test' '-Dmdep.outputFile=target/chunk-size-classpath.txt'
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        $dependencies = (Get-Content -LiteralPath 'target/chunk-size-classpath.txt' -Raw).Trim()
        $classes = Join-Path $projectRoot 'target/classes'
        $output = Join-Path $projectRoot 'evaluation/results'
        $testClasses = Join-Path $projectRoot 'target/test-classes'
        $mode = if ($Live) { '--live' } else { '--validate-only' }
        $previousCommit = $env:CHUNK_EXPERIMENT_GIT_COMMIT
        $previousDirty = $env:CHUNK_EXPERIMENT_GIT_DIRTY
        try {
            $env:CHUNK_EXPERIMENT_GIT_COMMIT = $gitCommit
            $env:CHUNK_EXPERIMENT_GIT_DIRTY = $gitDirty
            & java '-cp' "$testClasses;$classes;$dependencies" com.portfolio.ragobservability.evaluation.chunksize.ChunkSizeExperimentMain $mode
        } finally {
            $env:CHUNK_EXPERIMENT_GIT_COMMIT = $previousCommit
            $env:CHUNK_EXPERIMENT_GIT_DIRTY = $previousDirty
        }
        $comparisonExit = $LASTEXITCODE
    } finally { Pop-Location }
} catch {
    Write-Host ('Chunk-size comparison could not run: ' + $_.Exception.GetType().Name)
}
exit $comparisonExit



