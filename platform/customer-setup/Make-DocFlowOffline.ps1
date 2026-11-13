<#
.SYNOPSIS
  Builds ooa-docflow-offline-<ref>.zip: Document Automation (DocFlow) with everything it needs, so Install.cmd -DocFlow
  -DocFlowPackage <zip> installs it on a server with no internet (14 Nov 2026, round 4 item A5).

.DESCRIPTION
  Run it once per release on a Windows computer WITH internet and Python 3.12 (py -3.12). It collects:
    docflow\            DocFlow's code at the release this product is tested with (docflow-compat.properties), from
                        GitHub (DOCFLOW_GITHUB_TOKEN for the private repository) or from a local checkout (-Source)
    python\             the Python 3.12 installer (installed silently, for this program only, not on PATH)
    tesseract\          the Tesseract OCR installer (UB Mannheim build)
    tessdata\           English, Arabic and orientation language data, and Tesseract's configs
    wheels\             every Python package of requirements.txt (and PaddleOCR unless -NoPaddle), for Windows x64
    manifest.json       the versions, and a SHA-256 for every file, checked by the installer
  The result is large (about 0.5 to 1 GB with PaddleOCR).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File Make-DocFlowOffline.ps1 -Ref v3.1.1 -Out D:\releases
  powershell -ExecutionPolicy Bypass -File Make-DocFlowOffline.ps1 -Source C:\OOA\oneoffice-docauto -NoPaddle
#>
param(
    [string]$Ref = "",                     # DocFlow release; empty: the one in lib\document-automation-*.jar beside this script
    [string]$Source = "",                  # a local DocFlow checkout instead of GitHub
    [string]$Out = ".",
    [string]$Repo = "OneOfficeautomation-Private-Limited/oneoffice-docauto",
    [string]$PythonVersion = "3.12.8",
    [string]$TesseractUrl = "https://github.com/UB-Mannheim/tesseract/releases/download/v5.4.0.20240606/tesseract-ocr-w64-setup-5.4.0.20240606.exe",
    [switch]$NoPaddle
)
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
function Step($m) { Write-Host "== $m" }

if (-not $Ref) {
    $jar = Get-ChildItem (Join-Path $PSScriptRoot "lib") -Filter "document-automation-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jar) { throw "Give -Ref (the DocFlow release), or run this from the product folder (lib\document-automation-*.jar names it)." }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
    try { $r = New-Object IO.StreamReader($z.GetEntry("docflow-compat.properties").Open()); $Ref = [regex]::Match($r.ReadToEnd(), '(?m)^ref=(\S+)').Groups[1].Value; $r.Close() } finally { $z.Dispose() }
}
$work = Join-Path $env:TEMP "ooa-docflow-offline-$Ref"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force (Join-Path $work "docflow"), (Join-Path $work "python"), (Join-Path $work "tesseract"), (Join-Path $work "tessdata\configs"), (Join-Path $work "wheels") | Out-Null

Step "DocFlow $Ref"
if ($Source) {
    $skip = @(".venv", "data", "backups", ".git", ".pytest_cache", "tests", ".github")
    Get-ChildItem $Source | Where-Object { $skip -notcontains $_.Name } | ForEach-Object { Copy-Item -Recurse -Force $_.FullName (Join-Path $work "docflow\$($_.Name)") }
} else {
    $h = @{ "User-Agent" = "ooa-docflow-offline" }
    if ($env:DOCFLOW_GITHUB_TOKEN) { $h["Authorization"] = "Bearer $($env:DOCFLOW_GITHUB_TOKEN)" }
    $zip = Join-Path $work "docflow.zip"
    Invoke-WebRequest "https://api.github.com/repos/$Repo/zipball/$Ref" -Headers $h -OutFile $zip -UseBasicParsing
    $tmp = Join-Path $work "unzip"; Expand-Archive $zip $tmp
    $top = Get-ChildItem $tmp | Select-Object -First 1
    Get-ChildItem $top.FullName | Where-Object { @("tests", ".github", "data") -notcontains $_.Name } | ForEach-Object { Move-Item $_.FullName (Join-Path $work "docflow\$($_.Name)") }
    Remove-Item -Recurse -Force $tmp, $zip
}
if (-not (Test-Path (Join-Path $work "docflow\requirements.txt"))) { throw "DocFlow's requirements.txt is missing: is $Ref a DocFlow release?" }

Step "Python $PythonVersion"
Invoke-WebRequest "https://www.python.org/ftp/python/$PythonVersion/python-$PythonVersion-amd64.exe" -OutFile (Join-Path $work "python\python-$PythonVersion-amd64.exe") -UseBasicParsing

Step "Tesseract OCR"
Invoke-WebRequest $TesseractUrl -OutFile (Join-Path $work "tesseract\$(Split-Path $TesseractUrl -Leaf)") -UseBasicParsing

Step "Language data"
foreach ($f in @(@("eng.traineddata", "https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata"), @("osd.traineddata", "https://github.com/tesseract-ocr/tessdata_fast/raw/main/osd.traineddata"),
                 @("ara.traineddata", "https://github.com/tesseract-ocr/tessdata_fast/raw/main/ara.traineddata"), @("pdf.ttf", "https://github.com/tesseract-ocr/tesseract/raw/main/tessdata/pdf.ttf"),
                 @("configs\pdf", "https://github.com/tesseract-ocr/tesseract/raw/main/tessdata/configs/pdf"), @("configs\hocr", "https://github.com/tesseract-ocr/tesseract/raw/main/tessdata/configs/hocr"),
                 @("configs\txt", "https://github.com/tesseract-ocr/tesseract/raw/main/tessdata/configs/txt"))) {
    Invoke-WebRequest $f[1] -OutFile (Join-Path $work "tessdata\$($f[0])") -UseBasicParsing
}

Step "Python packages (wheels for Windows x64, Python 3.12)"
$req = Join-Path $work "docflow\requirements.txt"
& py -3.12 -m pip download -r $req -d (Join-Path $work "wheels") --only-binary=:all: --platform win_amd64 --python-version 3.12 --implementation cp --quiet
if ($LASTEXITCODE -ne 0) {
    Write-Host "   some packages have no ready wheel; downloading them as this computer builds them"
    & py -3.12 -m pip download -r $req -d (Join-Path $work "wheels") --quiet
    if ($LASTEXITCODE -ne 0) { throw "pip download failed - see above." }
}
& py -3.12 -m pip download pip setuptools wheel -d (Join-Path $work "wheels") --only-binary=:all: --platform win_amd64 --python-version 3.12 --quiet
if (-not $NoPaddle) {
    & py -3.12 -m pip download paddlepaddle paddleocr -d (Join-Path $work "wheels") --only-binary=:all: --platform win_amd64 --python-version 3.12 --quiet
    if ($LASTEXITCODE -ne 0) { Write-Host "   PaddleOCR is not in the package: DocFlow works without it (weak pages are not re-read)." }
}

Step "manifest.json"
$files = Get-ChildItem $work -Recurse -File | ForEach-Object {
    [ordered]@{ path = $_.FullName.Substring($work.Length + 1).Replace("\", "/"); sha256 = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower(); size = $_.Length } }
[ordered]@{ kind = "ooa-docflow-offline"; ref = $Ref; python = $PythonVersion; tesseract = (Split-Path $TesseractUrl -Leaf); paddle = (-not $NoPaddle)
            made = (Get-Date).ToUniversalTime().ToString("s") + "Z"; files = @($files) } | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $work "manifest.json") -Encoding UTF8

$dest = Join-Path (Resolve-Path $Out) "ooa-docflow-offline-$Ref.zip"
Step "Zipping to $dest"
Remove-Item $dest -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $work "*") -DestinationPath $dest -CompressionLevel Optimal
Remove-Item -Recurse -Force $work
Write-Host "Done: $dest ($([math]::Round((Get-Item $dest).Length / 1MB)) MB). Install with: Install.cmd -Role CommandCenter -DocFlow -DocFlowPackage `"$dest`""
