[CmdletBinding()]
param(
    [string] $JavaHome = $env:JAVA_HOME,
    [string] $Generator = 'Visual Studio 17 2022'
)

$ErrorActionPreference = 'Stop'
if (-not $JavaHome) {
    $JavaHome = Split-Path (Split-Path (Get-Command javac.exe).Source)
}
$JavaHome = (Resolve-Path -LiteralPath $JavaHome).Path
$javac = Join-Path $JavaHome 'bin\javac.exe'
$jar = Join-Path $JavaHome 'bin\jar.exe'
$cmake = (Get-Command cmake.exe).Source

function Invoke-Checked([string] $Tool, [string[]] $Arguments) {
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Tool exited with code $LASTEXITCODE" }
}

$compilerVersion = (& $javac -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) { throw "Cannot run $javac" }
$javaTarget = if ($compilerVersion -match '(?m)^javac 1\.8\.') {
    @('-source', '8', '-target', '8')
} else {
    @('--release', '8')
}

$target = [IO.Directory]::CreateDirectory((Join-Path $PSScriptRoot 'target')).FullName
$headers = [IO.Directory]::CreateDirectory((Join-Path $target 'jni-headers')).FullName
$stage = [IO.Directory]::CreateDirectory((Join-Path $target ('stage-' + [guid]::NewGuid()))).FullName

try {
    $classes = [IO.Directory]::CreateDirectory((Join-Path $stage 'classes')).FullName
    $sources = (Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java') -Filter '*.java' -Recurse -File).FullName
    Invoke-Checked $javac ($javaTarget + @('-encoding', 'UTF-8', '-d', $classes, '-h', $headers) + $sources)

    foreach ($arch in 'x64', 'x86') {
        $platform = if ($arch -eq 'x64') { 'x64' } else { 'Win32' }
        $build = Join-Path $target "native-$arch"
        Invoke-Checked $cmake @('-S', $PSScriptRoot, '-B', $build, '-G', $Generator, '-A', $platform,
            "-DJAVA_HOME:PATH=$JavaHome", "-DJNI_HEADERS:PATH=$headers")
        Invoke-Checked $cmake @('--build', $build, '--config', 'Release', '--target', 'shelliconkit')

        $resources = [IO.Directory]::CreateDirectory((Join-Path $classes "online\pigeonshouse\shelliconkit\native\$arch")).FullName
        Copy-Item -LiteralPath (Join-Path $build 'Release\shelliconkit.dll') -Destination $resources
    }

    $meta = [IO.Directory]::CreateDirectory((Join-Path $classes 'META-INF')).FullName
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'LICENSE') -Destination $meta
    $name = 'shelliconkit-2.0.0.jar'
    $candidate = Join-Path $stage $name
    Invoke-Checked $jar @('cfm', $candidate, (Join-Path $PSScriptRoot 'src\main\manifest.mf'), '-C', $classes, '.')
    $archive = Join-Path $target $name
    Move-Item -LiteralPath $candidate -Destination $archive -Force
    Write-Host "Built $archive"
} finally {
    $resolvedStage = Get-Item -LiteralPath $stage
    $resolvedTarget = Get-Item -LiteralPath $target
    if ($resolvedStage.Parent.FullName -eq $resolvedTarget.FullName -and
        -not ($resolvedStage.Attributes -band [IO.FileAttributes]::ReparsePoint) -and
        -not ($resolvedTarget.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        Remove-Item -LiteralPath $resolvedStage.FullName -Recurse -Force
    }
}
