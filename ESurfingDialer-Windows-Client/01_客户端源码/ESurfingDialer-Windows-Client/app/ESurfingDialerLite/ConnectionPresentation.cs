namespace ESurfingDialerLite;

internal sealed record ConnectionPresentation(string Tone, TrayStatus Tray, string Detail)
{
    public static ConnectionPresentation For(string status) => status switch
    {
        "已连接" => new("Green", TrayStatus.Connected, "认证与心跳已确认。"),
        "连接中" => new("Coral", TrayStatus.Connecting, "正在检测校园网并验证当前账户。"),
        "正在恢复" => new("Coral", TrayStatus.Connecting, "正在自动恢复连接；点击连接按钮可停止恢复。"),
        "认证已暂停" => new("Coral", TrayStatus.Error, "认证重试已暂停，请查看详细日志，处理原因后断开并重新连接。"),
        "恢复已暂停" => new("Coral", TrayStatus.Error, "自动恢复已达到上限，请查看详细日志后断开并重新连接。"),
        "连接失败" => new("Coral", TrayStatus.Error, "连接未成功，请查看提示或详细日志。"),
        "用户已停止" => new("Offline", TrayStatus.Disconnected, "已按你的操作停止连接，点击连接按钮可重新连接。"),
        _ => new("Offline", TrayStatus.Disconnected, "点击连接按钮可连接校园网。")
    };
}
