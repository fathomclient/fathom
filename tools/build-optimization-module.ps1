<#
.SYNOPSIS
    Compiles and packages the Minecraft-side optimization module's core.

.DESCRIPTION
    Builds `net.fathom.optimizations.core` into a jar with nothing but a JDK 8
    installation. No Gradle, no network, no Fabric maven.

    WHAT THIS PRODUCES, AND WHAT IT DOES NOT

    The module has two halves, split on purpose:

      core    The optimization logic, the registry, the switch board and the
              launcher config bridge. No Minecraft imports, no Fabric imports,
              no third-party dependencies at all. This is what gets built here,
              and it is the half that carries the reasoning.

      fabric  The Fabric entrypoint and the Mixin. Needs fabric-loader, Legacy
              Fabric's 1.8.9 intermediary mappings, and Minecraft itself on the
              compile classpath. None of those can be resolved without Gradle,
              Loom and network access to two maven repositories.

    So the jar this writes is NOT a loadable Fabric mod. Dropping it in `mods/`
    will do nothing: it has no entrypoint compiled in and `fabric.mod.json` is
    not included, precisely so that it cannot be mistaken for one.

    It is a real, tested artifact all the same. It is the code the launcher
    versions and ships, and the thing the Fabric layer will be a thin adapter
    over once Loom can run. Building it here means the logic is compiled and
    its tests are green on every change, rather than waiting on a toolchain
    that is not available yet.

    The tests are plain `main` methods rather than JUnit, for the same reason
    the core has no dependencies: adding a test framework to verify a
    dependency-free module would defeat the point.

.PARAMETER SkipTests
    Package without running the test suites first. Not recommended.

.PARAMETER Output
    Where to write the jar. Defaults to the module's build directory.
#>
[CmdletBinding()]
param(
    [switch] $SkipTests,
    [string] $Output
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$module = Join-Path $PSScriptRoot '..\minecraft\fathom-optimizations'
$module = (Resolve-Path $module).Path
if (-not $Output) { $Output = Join-Path $module 'build\libs' }

function Require-Tool {
    param([string] $Name)
    $found = Get-Command $Name -ErrorAction SilentlyContinue
    if (-not $found) {
        throw "$Name was not found on PATH. This script needs a JDK 8 installation."
    }
    return $found.Source
}

$javac = Require-Tool 'javac'
$jar = Require-Tool 'jar'
Write-Host "javac: $javac"

# The module targets Java 8 because that is what Minecraft 1.8.9 declares.
#
# Deliberately no `2>&1` here: JDK 8's javac writes its version banner to
# stderr, and Windows PowerShell wraps redirected native stderr in ErrorRecords,
# which turns a successful probe into a terminating NativeCommandError.

$classes = Join-Path $module 'build\classes'
$testClasses = Join-Path $module 'build\test-classes'
$resources = Join-Path $module 'src\main\resources'
foreach ($directory in @($classes, $testClasses, $Output)) {
    if (Test-Path $directory) { Remove-Item $directory -Recurse -Force }
    New-Item -ItemType Directory -Force $directory | Out-Null
}

# -- compile -------------------------------------------------------------
#
# Only `core`. Globbing the whole source tree would pull in the fabric package
# and fail on the first Minecraft import, which is the failure this split
# exists to avoid.
$sourceRoots = @(
    (Join-Path $module 'src\main\java\net\fathom\optimizations\core'),
    (Join-Path $module 'src\main\java\net\fathom\optimizations\generated')
)
$sources = Get-ChildItem $sourceRoots -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $sources) { throw "no core sources found under $module" }

Write-Host ""
Write-Host "Compiling $($sources.Count) core sources..."
& $javac -encoding UTF-8 -Xlint:all -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "javac failed on the core sources (exit $LASTEXITCODE)." }

# -- test ----------------------------------------------------------------
if (-not $SkipTests) {
    $testSources = Get-ChildItem (Join-Path $module 'src\test\java') -Recurse -Filter *.java |
                   ForEach-Object { $_.FullName }
    Write-Host "Compiling $($testSources.Count) test sources..."
    # Compile the production sources into the isolated test output as well.
    # This is reliable across the JDKs used on Windows, where javac has failed
    # to discover package classes from an absolute directory classpath here.
    & $javac -encoding UTF-8 -d $testClasses @sources @testSources
    if ($LASTEXITCODE -ne 0) { throw "javac failed on the test sources (exit $LASTEXITCODE)." }

    foreach ($suite in @(
        'CoreTests', 'OptTests', 'ConfigTests', 'FeatureTests', 'HudTests',
        'UiTests', 'ModuleTests', 'ProfileTests', 'ProfileLocationTests'
    )) {
        Write-Host ""
        Write-Host "== $suite =="
        & java -cp "$testClasses;$resources" "net.fathom.optimizations.core.$suite"
        if ($LASTEXITCODE -ne 0) { throw "$suite failed (exit $LASTEXITCODE)." }
    }
}

# -- package -------------------------------------------------------------
#
# Deliberately no fabric.mod.json and no entrypoint: this jar must not look
# like something that can be dropped into a mods folder, because it cannot.
$jarPath = Join-Path $Output 'fathom-optimizations-core.jar'
Write-Host ""
Write-Host "Packaging $jarPath"
& $jar cf $jarPath -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed (exit $LASTEXITCODE)." }

$size = (Get-Item $jarPath).Length
$classCount = @(Get-ChildItem $classes -Recurse -Filter *.class).Count
'{0}: {1:N0} bytes, {2} classes' -f (Split-Path $jarPath -Leaf), $size, $classCount | Write-Host
Write-Host ""
Write-Host "This is the core only. It is not a loadable Fabric mod -- see the notes at the"
Write-Host "top of this script and minecraft/fathom-optimizations/README.md."
