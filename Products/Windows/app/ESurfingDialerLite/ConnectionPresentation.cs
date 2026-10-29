namespace ESurfingDialerLite;

internal sealed record ConnectionPresentation(string Tone, TrayStatus Tray, string Detail)
{
    public static ConnectionPresentation For(string status) => status switch
    {
        "已连接" => new("Green", TrayStatus.Connected, "认证与心跳已确认。"),
        "网络已可用" => new("Green", TrayStatus.Connected, "当前网络可以访问外网；本客户端尚未建立认证会话。"),
        "核心被占用" => new("Coral", TrayStatus.Error, "其他认证核心正在使用此数据目录，请退出旧客户端后重新连接。"),
        "连接中" => new("Coral", TrayStatus.Connecting, "正在检测校园网并验证当前账户。"),
        "正在恢复" => new("Coral", TrayStatus.Connecting, "正在自动恢复连接；点击连接按钮可停止恢复。"),
        "认证已暂停" => new("Coral", TrayStatus.Error, "认证重试已暂停，请查看详细日志，处理原因后断开并重新连接。"),
        "恢复已暂停" => new("Coral", TrayStatus.Error, "自动恢复已达到上限，请查看详细日志后断开并重新连接。"),
        "连接失败" => new("Coral", TrayStatus.Error, "连接未成功，请查看提示或详细日志。"),
        "用户已停止" => new("Offline", TrayStatus.Disconnected, "已按你的操作停止连接，点击连接按钮可重新连接。"),
        _ => new("Offline", TrayStatus.Disconnected, "点击连接按钮可连接校园网。")
    };
}
