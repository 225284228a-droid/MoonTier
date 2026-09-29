param(
    [Parameter(Mandatory = $true)][string]$SourceRepo,
    [string]$NdkRoot = 'C:\Android\Sdk\ndk\27.2.12479018',
    [switch]$RunTests
)

$ErrorActionPreference = 'Stop'
$commit = 'ff3921ce6842f9660d629eafac499498026c6784'
$toolchain = '1.96.0'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$checkout = Join-Path $PSScriptRoot 'upstream'
$patch = Join-Path $PSScriptRoot 'easytier-ffi-android.patch'
$bin = Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\bin'

if (-not (Test-Path (Join-Path $bin 'aarch64-linux-android24-clang.cmd'))) {
    throw "Android NDK 27 clang not found: $bin"
}
$rustVersion = (& rustc "+$toolchain" -V).Trim()
if ($LASTEXITCODE -ne 0 -or $rustVersion -notmatch '^rustc 1\.96\.0 ') {
    throw "Rust 1.96.0 is required; found: $rustVersion"
}
if (-not (Test-Path $checkout)) {
    & git -C $SourceRepo cat-file -e "$commit^{commit}"
    if ($LASTEXITCODE -ne 0) { throw "SourceRepo does not contain $commit" }
    & git clone --local --no-hardlinks --no-checkout $SourceRepo $checkout
    if ($LASTEXITCODE -ne 0) { throw 'Source clone failed' }
    & git -C $checkout checkout --detach $commit
    if ($LASTEXITCODE -ne 0) { throw 'Source checkout failed' }
}

$head = (& git -C $checkout rev-parse HEAD).Trim()
if ($head -ne $commit) { throw "Unexpected source commit: $head" }
& git -C $checkout apply --reverse --check $patch 2>$null
if ($LASTEXITCODE -ne 0) {
    & git -C $checkout apply --check $patch
    if ($LASTEXITCODE -ne 0) { throw 'Checkout differs from the expected source/patch' }
    & git -C $checkout apply $patch
    if ($LASTEXITCODE -ne 0) { throw 'Source patch failed' }
}

function Invoke-VerifiedTest([string]$Package, [string]$Filter, [int]$Expected) {
    $list = & cargo "+$toolchain" test --locked -j 1 -p $Package --lib $Filter -- --list
    if ($LASTEXITCODE -ne 0) { throw "Failed to list $Filter tests" }
    $testCount = @($list | Where-Object { $_ -match '^.+: test$' }).Count
    if ($testCount -ne $Expected) { throw "$Filter matched $testCount tests; expected $Expected" }
    & cargo "+$toolchain" test --locked -j 1 -p $Package --lib $Filter -- --test-threads=1
    if ($LASTEXITCODE -ne 0) { throw "$Filter failed" }
}

$buildEnvironment = @{}
@(
    'CARGO_PROFILE_DEV_DEBUG',
    'CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER',
    'CC_aarch64_linux_android',
    'AR_aarch64_linux_android',
    'BINDGEN_EXTRA_CLANG_ARGS',
    'BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android'
) | ForEach-Object { $buildEnvironment[$_] = [Environment]::GetEnvironmentVariable($_, 'Process') }

Push-Location $checkout
try {
    if ($RunTests) {
        $env:BINDGEN_EXTRA_CLANG_ARGS = $null
        $env:CARGO_PROFILE_DEV_DEBUG = '0'
        Invoke-VerifiedTest easytier-ffi runtime_configs_ 2
        Invoke-VerifiedTest easytier-ffi detached_web_instance_is_adopted_on_reconnect_and_removed_by_stop 1
        Invoke-VerifiedTest easytier-ffi connected_client_stop_removes_web_instance_started_locally 1
        Invoke-VerifiedTest easytier-ffi restored_web_instance_rejoins_tracking_and_catalog 1
        Invoke-VerifiedTest easytier-ffi callback_delivers_uuid_and_unregister_drains 1
        Invoke-VerifiedTest easytier-core owned_preemption_waits_for_remote_mutation_before_selecting 1
        Invoke-VerifiedTest easytier-core process_management_rpc_rolls_back_instance_and_file_on_hook_failure 1
    }
    $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = Join-Path $bin 'aarch64-linux-android24-clang.cmd'
    $env:CC_aarch64_linux_android = $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER
    $env:AR_aarch64_linux_android = Join-Path $bin 'llvm-ar.exe'
    $sysroot = (Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\sysroot').Replace('\', '/')
    $clangHeaders = (Join-Path $NdkRoot 'toolchains\llvm\prebuilt\windows-x86_64\lib\clang\18\include').Replace('\', '/')
    $env:BINDGEN_EXTRA_CLANG_ARGS = "--target=aarch64-linux-android24 --sysroot=$sysroot -isystem$clangHeaders"
    $env:BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android = $env:BINDGEN_EXTRA_CLANG_ARGS
    & cargo "+$toolchain" rustc --locked -j 2 -p easytier-ffi --lib --release --target aarch64-linux-android -- '-C' 'link-arg=-Wl,-z,max-page-size=16384'
    if ($LASTEXITCODE -ne 0) { throw 'FFI Android build failed' }
} finally {
    foreach ($key in $buildEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $buildEnvironment[$key], 'Process')
    }
    Pop-Location
}

$library = Join-Path $checkout 'target\aarch64-linux-android\release\libeasytier_ffi.so'
$destination = Join-Path $root 'app\src\main\jniLibs\arm64-v8a\libeasytier_ffi.so'
$loads = & (Join-Path $bin 'llvm-readelf.exe') -l $library | Select-String ' LOAD '
if ($LASTEXITCODE -ne 0 -or $loads.Count -eq 0 -or ($loads | Where-Object { $_.Line -notmatch '0x4000\s*$' })) {
    throw 'The library LOAD segments are not all 16 KiB aligned'
}
Copy-Item -LiteralPath $library -Destination $destination -Force
Get-FileHash -Algorithm SHA256 $patch, $destination | Select-Object Path, Hash
