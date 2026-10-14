param([Parameter(Mandatory=$true)][string]$Database,[string]$Server='127.0.0.1',[int]$Port=3306,[Parameter(Mandatory=$true)][string]$Username)
$ErrorActionPreference='Stop'
if($Database -notmatch '^[A-Za-z0-9_]+$'){throw '数据库名称只能包含字母数字下划线。'}
$backupDirectory=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../backend/.local/backups'))
New-Item -ItemType Directory -Force $backupDirectory | Out-Null
$backupFile=Join-Path $backupDirectory ("mysql-$Database-"+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.sql')
# mysqldump prompts for the password; do not put it in command arguments or source.
& mysqldump "--host=$Server" "--port=$Port" "--user=$Username" --password --single-transaction --routines --triggers "--result-file=$backupFile" $Database
if($LASTEXITCODE -ne 0){throw '备份失败，请检查日志和备份文件完整性。'}
Write-Output "备份完成：$backupFile。恢复到独立空数据库后再进行验收。"
