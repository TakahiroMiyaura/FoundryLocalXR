# Copyright (c) 2026 Takahiro Miyaura
# SPDX-License-Identifier: BSL-1.0
# Distributed under the Boost Software License, Version 1.0.
# See the LICENSE file in the project root.

# Downloads the Foundry Local for Android AARs from GitHub Releases and verifies SHA-256.
# Also downloads the SDK license and third-party notices.
param([string]$Version = "0.1.6")

$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    foreach ($mode in "embedded", "ipc") {
        $aar = "foundry-local-$mode-sdk-$Version.aar"
        foreach ($name in $aar, "$aar.sha256") {
            if (-not (Test-Path $name)) {
                gh release download "v$Version" -R microsoft/FoundryLocalAndroid -p $name
            }
        }
        $expected = ((Get-Content "$aar.sha256" -Raw).Trim() -split '\s+')[0].ToLower()
        $actual = (Get-FileHash $aar -Algorithm SHA256).Hash.ToLower()
        if ($expected -ne $actual) { throw "SHA-256 mismatch: $aar" }
        Write-Host "$aar OK"
    }
    foreach ($name in "foundry-local-android-sdk-license-$Version.txt", "foundry-local-android-third-party-notices-$Version.md") {
        if (-not (Test-Path $name)) {
            gh release download "v$Version" -R microsoft/FoundryLocalAndroid -p $name
        }
        Write-Host "$name OK"
    }
} finally {
    Pop-Location
}
