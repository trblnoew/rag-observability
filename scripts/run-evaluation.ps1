param([switch]$Live, [switch]$ValidateOnly, [string]$DatasetPath = 'evaluation/retrieval-golden-set.json')

$ErrorActionPreference = 'Stop'
if ($Live -and $ValidateOnly) {
    Write-Host 'Use either -Live or -ValidateOnly, not both.'
    exit 1
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$datasetAbsolute = (Resolve-Path -LiteralPath $DatasetPath).Path
$configuredJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
if (-not $env:JAVA_HOME -and $configuredJavaHome) { $env:JAVA_HOME = $configuredJavaHome }
if (-not $env:JAVA_HOME) {
    $installedJdk = Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($installedJdk) { $env:JAVA_HOME = $installedJdk.FullName }
}
if (-not $env:JAVA_HOME) { throw 'Java 21 is required. Run scripts/check-env.ps1.' }
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
$env:MAVEN_USER_HOME = Join-Path $env:USERPROFILE '.m2'

$exitCode = 1
Push-Location $projectRoot
try {
    & .\mvnw.cmd "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" compile org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath '-DincludeScope=runtime' '-Dmdep.outputFile=target/evaluation-classpath.txt'
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $dependencies = (Get-Content -LiteralPath 'target/evaluation-classpath.txt' -Raw).Trim()
    $classes = Join-Path $projectRoot 'target/classes'
    & java '-cp' "$classes;$dependencies" com.portfolio.ragobservability.evaluation.DatasetValidationMain $datasetAbsolute
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if (-not $Live) {
        Write-Host 'Usage: .\scripts\run-evaluation.ps1 [-DatasetPath <json>] -Live'
        Write-Host 'Validation only: no Spring, DB or AI API calls.'
        exit 0
    }
    $gitCommit = 'unavailable'
    $gitDirty = 'unknown'
    if (Get-Command git -ErrorAction SilentlyContinue) {
        $commit = & git rev-parse HEAD 2>$null
        if ($LASTEXITCODE -eq 0) {
            $gitCommit = $commit.Trim()
            $changes = & git status --porcelain --untracked-files=normal
            if ($LASTEXITCODE -eq 0) { $gitDirty = if ($changes) { 'true' } else { 'false' } }
        }
    }
    $appArgs = @(
        '--spring.profiles.active=evaluation', '--spring.main.web-application-type=none',
        '--spring.flyway.enabled=false', '--management.tracing.enabled=false',
        '--spring.ai.model.chat=none', '--rag.generation.provider=none', '--rag.evaluation.live=true',
        "--rag.evaluation.git-commit=$gitCommit", "--rag.evaluation.git-dirty=$gitDirty"
    ) -join ' '
    # Pass paths as an inherited environment value, not a reparsed Maven argument string.
    $previousDatasetPath = $env:RAG_EVALUATION_DATASET_PATH
    $env:RAG_EVALUATION_DATASET_PATH = $datasetAbsolute
    try {
    & .\mvnw.cmd "-Dmaven.repo.local=$env:MAVEN_USER_HOME\repository" spring-boot:run "-Dspring-boot.run.arguments=$appArgs"
    $exitCode = $LASTEXITCODE
    } finally { $env:RAG_EVALUATION_DATASET_PATH = $previousDatasetPath }
} finally { Pop-Location }
exit $exitCode
