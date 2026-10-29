#requires -Version 7.0
param([Parameter(Mandatory=$true)][string]$Source, [string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$canonicalRoot = Split-Path $PSScriptRoot -Parent
$pinned = Get-Content -LiteralPath (Join-Path $canonicalRoot 'third_party/ciphers/provenance.json') -Raw | ConvertFrom-Json
$root = if($OutputRoot){[IO.Path]::GetFullPath($OutputRoot)}else{Split-Path $PSScriptRoot -Parent}
$destination = Join-Path $root 'third_party/ciphers'
if(Test-Path -LiteralPath $destination){throw '保留已有导入目录；核对清单，不覆盖'}
if(Test-Path -LiteralPath (Join-Path $root 'include/esurfing/cipher_registry.hpp')){throw '保留已有算法注册表，不覆盖'}
$sourceRoot = (Resolve-Path -LiteralPath $Source).Path
$app = Join-Path $sourceRoot 'esurfingclient/app'
$factoryPath = Join-Path $app 'src/cipher/CipherFactory.c'
if((Get-FileHash -Algorithm SHA256 -LiteralPath $factoryPath).Hash.ToLowerInvariant() -ne $pinned.adapted_factory_original_sha256){throw '上游工厂不属于当前已核对版本，停止导入'}
if((Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $sourceRoot 'LICENSE')).Hash -ne (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $canonicalRoot 'third_party/ciphers/LICENSE')).Hash){throw '上游许可证变化，停止导入'}
$factory = Get-Content -LiteralPath $factoryPath -Raw
$end = $factory.IndexOf('void destroy_cipher_factory(void)')
if($end -lt 0){throw '上游结构改变，停止导入'}
$factory = $factory.Substring(0,$end).Replace('#include "core/States.h"','').Replace('#include "utils/log/Logger.h"','').Replace('static cipher_interface_t* create_cipher_factory(', 'cipher_interface_t* es_create_cipher(')
$factory = [regex]::Replace($factory, '(?m)^\s*LOG_DEBUG\([^\r\n]*\);', '')
$matches = [regex]::Matches($factory,'(?s)strcmp\(algo_id,\s*"([A-F0-9-]{36})"\).*?return\s+(create_[a-z0-9_]+)\(')
if($matches.Count -ne 95){throw '分支数量不符'}
$manifest = @()
$files = @(Get-ChildItem -LiteralPath (Join-Path $app 'src/cipher/algo') -File)
$files += Get-Item (Join-Path $app 'src/cipher/CipherUtils.c'),(Join-Path $app 'src/cipher/KeyData.c')
$files += Get-ChildItem -LiteralPath (Join-Path $app 'include/cipher') -File
$files += Get-ChildItem -Path (Join-Path $app 'src/utils/sim/SimEvp*') -File
$files += Get-Item (Join-Path $app 'include/utils/sim/SimEvp.h')
foreach($file in $files) {
    $relative = if($file.Name -eq 'SimEvp.h'){ 'include/utils/sim/'+$file.Name } elseif($file.Name.StartsWith('SimEvp')){ 'sim/'+$file.Name } elseif($file.Directory.Name -eq 'algo'){ 'algo/'+$file.Name } elseif($file.Extension -eq '.h'){ 'include/cipher/'+$file.Name } else { $file.Name }
    $expectedSource = @($pinned.files | Where-Object {$_.output -eq $relative})
    if($expectedSource.Count -ne 1 -or (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant() -ne $expectedSource[0].sha256){throw "上游文件不属于当前已核对版本: $relative"}
    $target = Join-Path $destination $relative
    New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $target
    $entry = [ordered]@{source=$file.FullName.Substring($sourceRoot.Length+1).Replace('\','/'); output=$relative; sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant()}
    if($relative -eq 'algo/sm4_common.c' -or $relative -eq 'algo/snow3g_variant.c'){
        $original = [IO.File]::ReadAllText($target)
        if($relative -eq 'algo/sm4_common.c'){
            if($entry.sha256 -ne '810b5674599beeed445e8a041f14bb3b6b0fa566e3c2e30a2346a1af3ddb30d5'){throw 'SM4源身份变化，不能套用旧修复'}
            $patched = $original.Replace('(SM4_SBOX[A>>24&0xFF]<<24)','((uint32_t)SM4_SBOX[A>>24&0xFF]<<24)').Replace('SM4_SBOX[A>>16&0xFF]<<16','(uint32_t)SM4_SBOX[A>>16&0xFF]<<16').Replace('(SM4_SBOX[A>>8&0xFF]<<8)','((uint32_t)SM4_SBOX[A>>8&0xFF]<<8)')
            $expectedPatched = '9cd4b9b45c4a251557c0e6dbc7523459136eececdd33b8012266e12851cf951f'
        }else{
            if($entry.sha256 -ne 'ac62b1b95d64a831a674dcc6ab3afad32e6dc5938e267b8b34bbb1bcdb3a1208'){throw 'SNOW3G源身份变化，不能套用旧修复'}
            $patched = $original.Replace('((uint16_t)(v9 >> 15) << 16)','((uint32_t)(uint16_t)(v9 >> 15) << 16)').Replace('((uint16_t)(c[21] >> 15) << 16)','((uint32_t)(uint16_t)(c[21] >> 15) << 16)').Replace('((uint16_t)(v26 >> 15) << 16)','((uint32_t)(uint16_t)(v26 >> 15) << 16)')
            $expectedPatched = 'ecaceb1b4e568b39e6f1f6a99a13acfe51a3e9e90d09565f25e46d074a40e10e'
        }
        [IO.File]::WriteAllText($target,$patched,[Text.UTF8Encoding]::new($false))
        $entry.local_sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $target).Hash.ToLowerInvariant()
        if($entry.local_sha256 -ne $expectedPatched){throw 'unsigned修复输出身份不符，停止导入'}
        $entry.local_change='仅将整数提升后的有符号左移改为显式uint32_t左移；475条修改前合成向量全等'
    }
    $manifest += $entry
}
Copy-Item -LiteralPath (Join-Path $sourceRoot 'LICENSE') -Destination (Join-Path $destination 'LICENSE')
$factory | Set-Content -LiteralPath (Join-Path $destination 'factory.c') -Encoding utf8
$entries = foreach($m in $matches) {
    $func=$m.Groups[2].Value
    $block=if($func -match 'aes|sm4'){16}elseif($func -match 'snow3g|zuc'){4}else{8}
    '  {"'+$m.Groups[1].Value+'", '+$block+'},'
}
New-Item -ItemType Directory -Force -Path (Join-Path $root 'include/esurfing') | Out-Null
@('#pragma once','#include <cstddef>','namespace esurfing {','struct CipherSpec { const char* id; std::size_t block; };','inline constexpr CipherSpec cipher_specs[] = {') + $entries + @('};','}') | Set-Content -LiteralPath (Join-Path $root 'include/esurfing/cipher_registry.hpp') -Encoding utf8
[ordered]@{upstream='https://github.com/BadGhost520/ESurfingClient-CVersion'; commit=$pinned.commit; files=$manifest; adapted_factory_original_sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath $factoryPath).Hash.ToLowerInvariant(); adapted_factory_sha256=(Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $destination 'factory.c')).Hash.ToLowerInvariant(); changes='保留独立工厂，重命名入口，移除应用全局状态和日志；仅SM4/SNOW3G重放已验证unsigned修复，逐文件记录原始及本地摘要'} | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $destination 'provenance.json') -Encoding utf8
Write-Output "已导入 $($files.Count) 个独立算法/头文件，95工厂分支；没有导入网络、Web、Mongoose或运行上游程序。"
