param([string]$MavenCommand)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$project = Join-Path $repoRoot '迭代一-成员1/code/green-campus'
$evidence = Join-Path (Split-Path $PSScriptRoot -Parent) '验证记录'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
if (!$MavenCommand) {
    $installed = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($installed) { $MavenCommand = $installed.Source }
    else { $MavenCommand = Join-Path $env:TEMP 'codex-software-construction-tools/apache-maven-3.9.9/bin/mvn.cmd' }
}
if (!(Test-Path -LiteralPath $MavenCommand)) {
    throw '未找到 Maven。安装 Maven 3.9+ 后运行，或用 -MavenCommand 指定 mvn.cmd 的绝对路径。'
}
$savedJavaOptions = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = "$savedJavaOptions -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8".Trim()
Push-Location $project
try {
    & $MavenCommand -B clean test *> (Join-Path $evidence 'maven-test.txt')
    $mavenExit = $LASTEXITCODE
    $mavenLog = Join-Path $evidence 'maven-test.txt'
    $logLines = @(Get-Content -LiteralPath $mavenLog | ForEach-Object { $_.TrimEnd() })
    $logLines | Set-Content -Encoding utf8 -LiteralPath $mavenLog
    if ($mavenExit -ne 0) { throw 'Maven 测试失败，请查看 验证记录/maven-test.txt' }
    $reports = Get-ChildItem -LiteralPath (Join-Path $project 'target/surefire-reports') -Filter 'TEST-*.xml'
    $summary = foreach ($report in $reports) {
        [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
        $suite = $xml.testsuite
        [pscustomobject]@{ Suite=$suite.name; Tests=[int]$suite.tests; Failures=[int]$suite.failures; Errors=[int]$suite.errors; Skipped=[int]$suite.skipped }
    }
    $summary | ConvertTo-Json -Depth 4 | Set-Content -Encoding utf8 (Join-Path $evidence 'junit-summary.json')
    $summary | Format-Table -AutoSize
    Write-Output ('TOTAL: ' + ($summary | Measure-Object -Property Tests -Sum).Sum)

    # 同时验证仓库随附的旧版 SQLite JDBC 离线编译路径。
    $offline = Join-Path $project 'build/iteration2-offline'
    New-Item -ItemType Directory -Force -Path $offline | Out-Null
    & javac --release 17 -encoding UTF-8 -sourcepath src/main/java -d $offline -cp lib/sqlite-jdbc-3.42.0.0.jar src/main/java/edu/campus/recycle/Main.java src/main/java/edu/campus/recycle/ui/MainApp.java *> (Join-Path $evidence 'offline-compile.txt')
    if ($LASTEXITCODE -ne 0) { throw '离线编译失败' }
    'PASS: javac --release 17 Main + MainApp with SQLite JDBC 3.42.0.0' | Add-Content -Encoding utf8 (Join-Path $evidence 'offline-compile.txt')

    # 控制台仅重建 data/demo.db，不启动或修改 GUI 正式库。
    & java '-Dfile.encoding=UTF-8' -cp "$offline;src/main/resources;lib/sqlite-jdbc-3.42.0.0.jar" edu.campus.recycle.Main *> (Join-Path $evidence 'console-demo.txt')
    if ($LASTEXITCODE -ne 0) { throw '控制台演示失败' }
    $demo = Get-Content -LiteralPath (Join-Path $evidence 'console-demo.txt') -Raw
    if ($demo -notmatch '\[9/9\]') { throw '控制台演示没有到达第9步' }
    Write-Output 'PASS: console demo 9/9'
} finally {
    Pop-Location
    $env:JAVA_TOOL_OPTIONS = $savedJavaOptions
}
