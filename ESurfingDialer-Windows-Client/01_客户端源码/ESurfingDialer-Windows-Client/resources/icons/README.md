# Windows 客户端图标说明

图标采用白色圆角方形背景与三格认证信号条。三格信号条组成完整标志，认证状态通过颜色区分，外形保持一致。

| 文件状态名 | 颜色与含义 |
| --- | --- |
| `default`、`disconnected` | 灰色，尚未认证或已停止 |
| `connecting` | 琥珀色，正在认证 |
| `connected` | 绿色，认证与心跳已确认 |
| `error` | 红色，启动或运行错误 |

`ESurfingDialer-default.ico` 用于应用、安装器和桌面快捷方式；托盘控制器在运行时切换各状态图标。文件名为代码引用标识，保持英文。

SVG 是可编辑的源文件。每个 ICO 包含 16、24、32、48、64、128、256 像素的 PNG 图层，供 Windows 外壳、任务栏和安装器使用。

10-09 界面图标按当前 Pen 导出的实际 Lucide 字形转换为 `app/ESurfingDialerLite/PenIcons.xaml`，用于账户、校园网、导航和编辑/删除等入口。许可保存在本目录的 `LUCIDE-LICENSE.txt`；应用和托盘的 ICO 继续使用上面这套状态标志。
