$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$vendor=Join-Path $root 'third_party/ciphers'
$data=Get-Content -LiteralPath (Join-Path $vendor 'provenance.json') -Raw | ConvertFrom-Json
$checked=0
foreach($file in $data.files){
    $expected=if($file.PSObject.Properties.Name -contains 'local_sha256'){$file.local_sha256}else{$file.sha256}
    $path=Join-Path $vendor $file.output
    if((Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant() -ne $expected){throw "第三方文件身份不符: $($file.output)"}
    $checked++
}
if((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $vendor 'factory.c')).Hash.ToLowerInvariant() -ne $data.adapted_factory_sha256){throw '工厂身份不符'}
$vectors=Get-Content -LiteralPath (Join-Path $root 'tests/reference-vectors.tsv')
if($vectors.Count -ne 475){throw '参考向量数量不符'}
if(@($vectors | ForEach-Object {($_ -split "`t")[0]} | Select-Object -Unique).Count -ne 95){throw '参考分支数量不符'}
Write-Output "来源核对通过：$checked 文件 + 独立工厂；475合成向量/95分支。没有执行认证。"
