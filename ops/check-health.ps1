param([string]$Url='http://127.0.0.1:8091/health')
$ErrorActionPreference='Stop'
try{$result=Invoke-RestMethod -Uri $Url -TimeoutSec 5;if($result.status -ne 'UP'){throw '服务未就绪'};Write-Output "RoomFlow healthy ($($result.mode))"}catch{Write-Error 'RoomFlow 健康检查失败，请查看服务日志并处理告警。';exit 1}
