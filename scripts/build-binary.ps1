# 打包 Windows 自包含可执行（app-image：内置运行时，无需系统 Java）
# 产物：dist\agent-litmus-windows-x64.zip
# CI 在 Release 发布时调用（见 .github/workflows/ci.yml 的 binaries job）。
param(
    # 复用已有 jar（CI 已单独构建时传入），不给就现场构建
    [string]$JarPath = ""
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$AppName = "agent-litmus"
$Version = (Select-Xml -Path pom.xml -XPath "//project/version" | Select-Object -First 1).Node.InnerText.Trim()
if (-not $Version) { throw "无法从 pom.xml 读出版本号" }
$MainJar = "memory-bench-$Version.jar"

if (-not $JarPath) {
    Write-Host "==> 1/3 构建可执行 jar"
    $GitCommit = (git rev-parse --short HEAD 2>$null)
    if (-not $GitCommit) { $GitCommit = "unknown" }
    if (git status --porcelain 2>$null) { $GitCommit = "$GitCommit-dirty" }
    & .\mvnw -q clean package "-DskipTests" "-Dlitmus.git.commit=$GitCommit"
    if ($LASTEXITCODE -ne 0) { throw "mvn package 失败" }
    $JarPath = "target\$MainJar"
}
if (-not (Test-Path $JarPath)) { throw "未找到 $JarPath" }

Write-Host "==> 2/3 jpackage 打包 app-image（windows-x64）"
$Stage = New-Item -ItemType Directory -Path ([System.IO.Path]::GetTempPath()) -Name ("litmus-stage-" + [guid]::NewGuid())
$OutDir = New-Item -ItemType Directory -Path ([System.IO.Path]::GetTempPath()) -Name ("litmus-out-" + [guid]::NewGuid())
Copy-Item $JarPath $Stage.FullName

& jpackage `
    --name $AppName `
    --input $Stage.FullName `
    --main-jar $MainJar `
    --main-class io.github.agentlitmus.Cli `
    --type app-image `
    --app-version $Version `
    --vendor "AgentLitmus" `
    --description "AgentLitmus 智能体长期记忆评测基准" `
    --dest $OutDir.FullName
if ($LASTEXITCODE -ne 0) { throw "jpackage 失败" }

Write-Host "==> 3/3 压缩产物"
New-Item -ItemType Directory -Force -Path dist | Out-Null
$ZipPath = "dist\$AppName-windows-x64.zip"
if (Test-Path $ZipPath) { Remove-Item $ZipPath }
Compress-Archive -Path (Join-Path $OutDir.FullName $AppName) -DestinationPath $ZipPath

# 自检：--help 必须能跑
$Exe = Join-Path $OutDir.FullName "$AppName\bin\$AppName.exe"
& $Exe --help | Out-Null
if ($LASTEXITCODE -ne 0) { throw "自检失败：打包出的可执行跑不起来" }

$Sha256 = (Get-FileHash $ZipPath -Algorithm SHA256).Hash.ToLower()
Remove-Item -Recurse -Force $Stage.FullName, $OutDir.FullName
Write-Host "产物: $ZipPath"
Write-Host "sha256: $Sha256"
