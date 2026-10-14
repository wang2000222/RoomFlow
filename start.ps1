param([switch]$Background)
$ErrorActionPreference='Stop'
$backendDirectory=Join-Path $PSScriptRoot 'backend'
$jarFile=Join-Path $backendDirectory 'target/roomflow-0.1.0.jar'
if(!(Test-Path -LiteralPath $jarFile)){throw '请先在 backend 目录运行 mvn clean package。'}
New-Item -ItemType Directory -Force (Join-Path $backendDirectory '.local') | Out-Null
if($Background){
 $runningProcess=Start-Process java -ArgumentList @('-Dfile.encoding=UTF-8','-jar',$jarFile) -WorkingDirectory $backendDirectory -WindowStyle Hidden -RedirectStandardOutput (Join-Path $backendDirectory '.local/server.log') -RedirectStandardError (Join-Path $backendDirectory '.local/server-error.log') -PassThru
 $runningProcess.Id | Set-Content (Join-Path $backendDirectory '.local/server.pid')
 Write-Output "RoomFlow 已启动，访问 http://localhost:8091，进程 $($runningProcess.Id)。"
}else{Push-Location $backendDirectory;try{& java '-Dfile.encoding=UTF-8' -jar $jarFile}finally{Pop-Location}}
