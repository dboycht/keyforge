# Publish a GitHub Release for keyforge: create the release for a tag and attach the APK.
#
# Why this script exists: there is no `gh` CLI on this machine, and uploading an asset
# through the streaming path fails ("Bad Content-Length"), so the release is created
# with the REST API and the asset is uploaded with an explicit Content-Length.
#
# Usage:
#   pwsh -File tools\publish-release.ps1 -Tag 1.0.1 -Asset <path-to.apk> -NotesFile <path.md>
#
# Token: read from the Windows Credential Manager entry `git:https://github.com`
# (CRED_TYPE_GENERIC). It is used in-process only and never printed.
#
# NOTE: pure ASCII on purpose (PowerShell 5.1 reads BOM-less .ps1 as the system ANSI
# codepage; see the project ERROR.md E12 / workspace rules/01 8.2).

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$Asset,
    [Parameter(Mandatory = $true)][string]$NotesFile,
    [string]$Owner = 'dboycht',
    [string]$Repo = 'keyforge'
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-GitHubToken {
    $sig = @'
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class CredMan {
    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CredReadW(string target, int type, int reservedFlag, out IntPtr credentialPtr);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern void CredFree(IntPtr cred);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct CREDENTIAL {
        public int Flags;
        public int Type;
        public string TargetName;
        public string Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public int CredentialBlobSize;
        public IntPtr CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public IntPtr Attributes;
        public string TargetAlias;
        public string UserName;
    }

    public const int CRED_TYPE_GENERIC = 1;
    private const int ERROR_NOT_FOUND = 1168;

    public static string GetCredential(string target) {
        IntPtr ptr;
        if (!CredReadW(target, CRED_TYPE_GENERIC, 0, out ptr)) {
            int err = Marshal.GetLastWin32Error();
            if (err == ERROR_NOT_FOUND) return null;
            throw new Exception("CredRead failed with error " + err);
        }
        try {
            CREDENTIAL cred = (CREDENTIAL)Marshal.PtrToStructure(ptr, typeof(CREDENTIAL));
            byte[] blob = new byte[cred.CredentialBlobSize];
            Marshal.Copy(cred.CredentialBlob, blob, 0, cred.CredentialBlobSize);
            return Encoding.Unicode.GetString(blob).TrimEnd('\0');
        }
        finally {
            CredFree(ptr);
        }
    }
}
'@

    if (-not ('CredMan' -as [type])) {
        Add-Type -TypeDefinition $sig -Language CSharp | Out-Null
    }

    $token = $null
    foreach ($target in @('git:https://github.com', 'git:https://github.com/')) {
        try { $token = [CredMan]::GetCredential($target) } catch { $token = $null }
        if ($token) { break }
    }
    if (-not $token) { throw "no GitHub credential found in Windows Credential Manager" }
    return $token
}

function Get-GitHubJson {
    param([string]$Url, [string]$Token)
    $headers = @{
        Authorization          = "token $Token"
        Accept                 = 'application/vnd.github+json'
        'X-GitHub-Api-Version' = '2022-11-28'
        'User-Agent'           = 'keyforge-release-script'
    }
    # NOTE: no body on GET - PowerShell raises "Cannot send a content-body with this
    # verb-type" if a body is attached to a GET/DELETE request.
    return Invoke-RestMethod -Method Get -Uri $Url -Headers $headers
}

function Send-GitHubJson {
    param([string]$Method, [string]$Url, [string]$Token, [string]$Body)
    $headers = @{
        Authorization          = "token $Token"
        Accept                 = 'application/vnd.github+json'
        'X-GitHub-Api-Version' = '2022-11-28'
        'User-Agent'           = 'keyforge-release-script'
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    return Invoke-RestMethod -Method $Method -Uri $Url -Headers $headers -Body $bytes -ContentType 'application/json; charset=utf-8'
}

# ---- checks -----------------------------------------------------------------
if (-not (Test-Path -LiteralPath $Asset)) { throw "asset not found: $Asset" }
if (-not (Test-Path -LiteralPath $NotesFile)) { throw "notes file not found: $NotesFile" }

$token = Get-GitHubToken
$api = "https://api.github.com/repos/$Owner/$Repo"
$assetFile = Get-Item -LiteralPath $Asset
$notes = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $NotesFile), [System.Text.Encoding]::UTF8)

Write-Host "release $Tag on $Owner/$Repo"
Write-Host ("asset  : {0} ({1} bytes)" -f $assetFile.Name, $assetFile.Length)

# ---- tag exists? ------------------------------------------------------------
$ref = Get-GitHubJson -Url "$api/git/ref/tags/$Tag" -Token $token
Write-Host ("tag    : {0} -> {1}" -f $Tag, $ref.object.sha)

# ---- create (or reuse) the release -----------------------------------------
$existing = $null
try {
    $existing = Get-GitHubJson -Url "$api/releases/tags/$Tag" -Token $token
} catch {
    $existing = $null
}

if ($existing) {
    Write-Host ("release already exists (id {0}); uploading the asset only" -f $existing.id)
    $release = $existing
} else {
    $payload = @{
        tag_name   = $Tag
        name       = $Tag
        body       = $notes
        draft      = $false
        prerelease = $false
    } | ConvertTo-Json -Depth 5
    $release = Send-GitHubJson -Method Post -Url "$api/releases" -Token $token -Body $payload
    Write-Host ("created release id {0}: {1}" -f $release.id, $release.html_url)
}

# ---- upload the asset -------------------------------------------------------
# `Invoke-RestMethod` has no -ContentLength parameter, and the plain streaming path
# fails with "Bad Content-Length", so HttpClient sets the header explicitly.
$uploadUrl = "https://uploads.github.com/repos/$Owner/$Repo/releases/$($release.id)/assets?name=$($assetFile.Name)"
Add-Type -AssemblyName System.Net.Http | Out-Null
$client = New-Object System.Net.Http.HttpClient
$client.DefaultRequestHeaders.Authorization =
    New-Object System.Net.Http.Headers.AuthenticationHeaderValue('token', $token)
$client.DefaultRequestHeaders.Accept.Add(
    (New-Object System.Net.Http.Headers.MediaTypeWithQualityHeaderValue('application/vnd.github+json')))
$client.DefaultRequestHeaders.UserAgent.ParseAdd('keyforge-release-script')

$stream = [System.IO.File]::OpenRead($assetFile.FullName)
$content = New-Object System.Net.Http.StreamContent($stream)
$content.Headers.ContentType =
    New-Object System.Net.Http.Headers.MediaTypeHeaderValue('application/vnd.android.package-archive')
$content.Headers.ContentLength = $assetFile.Length

try {
    $response = $client.PostAsync($uploadUrl, $content).GetAwaiter().GetResult()
    $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw ("asset upload failed: HTTP {0} {1}`n{2}" -f [int]$response.StatusCode, $response.ReasonPhrase, $bodyText)
    }
    $uploaded = $bodyText | ConvertFrom-Json
    Write-Host ("asset  : {0} ({1} bytes) state={2}" -f $uploaded.name, $uploaded.size, $uploaded.state)
} finally {
    $content.Dispose()
    $stream.Dispose()
    $client.Dispose()
}

# ---- read back and verify zero-download (digest + size) ---------------------
$check = Get-GitHubJson -Url "$api/releases/tags/$Tag" -Token $token
Write-Host ""
Write-Host ("release: {0}" -f $check.html_url)
Write-Host ("name   : {0}   draft={1} prerelease={2}" -f $check.name, $check.draft, $check.prerelease)
foreach ($a in $check.assets) {
    Write-Host ("asset  : {0}  size={1}  digest={2}  downloads={3}" -f $a.name, $a.size, $a.digest, $a.download_count)
}

$localHash = (Get-FileHash -LiteralPath $assetFile.FullName -Algorithm SHA256).Hash.ToLower()
$remoteDigest = ($check.assets | Where-Object { $_.name -eq $assetFile.Name } | Select-Object -First 1).digest
if ($remoteDigest) {
    $remoteHash = $remoteDigest -replace '^sha256:', ''
    if ($remoteHash -eq $localHash) {
        Write-Host "VERIFY : SHA256 matches (local == uploaded) OK"
    } else {
        Write-Host "VERIFY : MISMATCH local=$localHash remote=$remoteHash"
        exit 1
    }
} else {
    Write-Host "VERIFY : API did not return a digest; compare manually"
}
