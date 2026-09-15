# ESurfingDialer（天翼校园）

**ESurfingDialer / Tianyi Campus** 是一个校园网认证工具，提供 Windows 客户端和 Docker 软路由两个版本。

如果你只知道“天翼校园”，也可以用“天翼校园认证”“校园网认证”“ESurfingDialer”等关键词搜索本项目。

## 快速选择

下面两栏可以直接切换浏览：

<table>
<tr>
<td width="50%" valign="top">

### Windows 客户端

适合直接安装在 Windows 电脑上。

[下载 Windows 客户端 v2.0](https://github.com/FLEETING-XHX/ESurfingDialer/releases/download/v2.0/ESurfingDialer-Lite-v2.0-Setup.exe)

</td>
<td width="50%" valign="top">

### Docker 软路由版

适合部署在 iStoreOS / OpenWrt 软路由上。

[下载 Docker v1.3 离线部署包](https://github.com/FLEETING-XHX/ESurfingDialer/releases/download/v1.3/ESurfingDialer-Itsuwarii-Docker-Fixed-v1.3.zip)

[查看 Docker 安装教程](ESurfingDialer-Docker/02_使用教程/ESurfingDialer-Docker部署教程.md)

</td>
</tr>
</table>

Docker 版本提供离线镜像包，软路由没有公网时也可以完成部署。

## 版本说明

- Windows 客户端和 Docker 版本分别维护，互不影响。
- 两个版本使用独立目录，但共用同一个 GitHub 仓库。
- 发布版本会在 Release 页面中分别标明客户端或 Docker。

## 其他文件

源码、历史版本和详细教程请进入 [Releases](https://github.com/FLEETING-XHX/ESurfingDialer/releases) 页面查看。

Windows 客户端和 Docker 版本使用不同的 Release 标签，避免下载时混淆。

## 致谢

核心认证逻辑来源于 [Itsuwarii/ESurfingDialer](https://github.com/Itsuwarii/ESurfingDialer)。
