using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace ESurfingDialerLite;

public sealed class HealthSnapshot
{
    [JsonPropertyName("processAlive")]
    public bool ProcessAlive { get; set; }

    [JsonPropertyName("processInstanceId")]
    public string? ProcessInstanceId { get; set; }

    [JsonPropertyName("clientThreadAlive")]
    public bool ClientThreadAlive { get; set; }

    [JsonPropertyName("networkCheckThreadAlive")]
    public bool NetworkCheckThreadAlive { get; set; }

    [JsonPropertyName("authenticated")]
    public bool Authenticated { get; set; }

    [JsonPropertyName("networkReachable")]
    public bool NetworkReachable { get; set; }
    [JsonPropertyName("lastNetworkSuccessAt")]
    public long LastNetworkSuccessAt { get; set; }
    [JsonPropertyName("autoReauthStatus")]
    public string? AutoReauthStatus { get; set; }
    [JsonPropertyName("autoReauthNextPlannedAt")]
    public long AutoReauthNextPlannedAt { get; set; }

    [JsonPropertyName("authenticationFailure")]
    public string? AuthenticationFailure { get; set; }

    [JsonPropertyName("authenticationFailureDeterministic")]
    public bool AuthenticationFailureDeterministic { get; set; }

    [JsonPropertyName("authenticationBlocked")]
    public bool AuthenticationBlocked { get; set; }

    public bool HasAuthenticationFailureFor(DateTimeOffset startedAt, DateTimeOffset now, TimeSpan? maximumAge = null, string? expectedInstanceId = null) =>
        !Authenticated && !string.IsNullOrWhiteSpace(AuthenticationFailure) && IsCurrentFor(startedAt, now, maximumAge, expectedInstanceId);

    [JsonPropertyName("authenticationStage")]
    public string? AuthenticationStage { get; set; }

    [JsonPropertyName("authenticationStageStartedAt")]
    public long AuthenticationStageStartedAt { get; set; }

    [JsonPropertyName("clientProgressAgeMillis")]
    public long ClientProgressAgeMillis { get; set; } = -1;

    [JsonPropertyName("clientProgressTimeoutMillis")]
    public long ClientProgressTimeoutMillis { get; set; }

    public bool HasKnownOperation => AuthenticationStage is "portal" or "session" or "ticket" or "login"
        or "confirm" or "heartbeat" or "terminate" or "cleanup";

    public bool HasValidProgressBudget => ClientProgressTimeoutMillis > 0
        && ClientProgressTimeoutMillis <= (AuthenticationStage == "heartbeat-wait" ? 690_000 : 90_000)
        && (HasKnownOperation || AuthenticationStage is "heartbeat-wait" or "network-online-unowned");

    // The core reports elapsed monotonic time; wall-clock changes cannot renew a stalled operation.
    public bool HasLiveProgress => HasValidProgressBudget
        && ClientProgressAgeMillis >= 0 && ClientProgressAgeMillis < ClientProgressTimeoutMillis;

    public bool HasActiveAuthenticationStage(DateTimeOffset startedAt, DateTimeOffset now) =>
        !Authenticated && HasKnownOperation && HasLiveProgress;

    [JsonPropertyName("enhancedConnection")]
    public bool EnhancedConnection { get; set; }

    [JsonPropertyName("networkCheckIntervalSeconds")]
    public int NetworkCheckIntervalSeconds { get; set; }

    [JsonPropertyName("healthWriteIntervalSeconds")]
    public int HealthWriteIntervalSeconds { get; set; }

    [JsonPropertyName("lastUpdatedAt")]
    public long LastUpdatedAt { get; set; }

    [JsonPropertyName("lastNetworkCheckAt")]
    public long LastNetworkCheckAt { get; set; }

    [JsonPropertyName("lastLoginSuccessAt")]
    public long LastLoginSuccessAt { get; set; }

    [JsonPropertyName("lastHeartbeatSuccessAt")]
    public long LastHeartbeatSuccessAt { get; set; }

    [JsonPropertyName("consecutiveHeartbeatFailures")]
    public int ConsecutiveHeartbeatFailures { get; set; }

    [JsonPropertyName("lastError")]
    public string? LastError { get; set; }

    public bool IsCurrentFor(DateTimeOffset startedAt, DateTimeOffset now, TimeSpan? maximumAge = null, string? expectedInstanceId = null)
        => CurrentFailureReason(startedAt, now, maximumAge, expectedInstanceId) == null;

    public string? CurrentFailureReason(DateTimeOffset startedAt, DateTimeOffset now, TimeSpan? maximumAge = null, string? expectedInstanceId = null)
    {
        var age = now.ToUnixTimeSeconds() - LastUpdatedAt;
        var maximumAgeSeconds = (long)((maximumAge ?? TimeSpan.FromSeconds(60)).TotalSeconds);
        if (!ProcessAlive) return "认证核心已退出";
        if (expectedInstanceId != null && ProcessInstanceId != expectedInstanceId) return "健康实例与当前核心不匹配";
        if (LastUpdatedAt < startedAt.ToUnixTimeSeconds()) return "健康状态早于当前核心启动";
        if (age < 0) return "健康时间晚于系统时间";
        if (age > maximumAgeSeconds) return "健康状态已过期";
        return null;
    }

    public bool IsOnlineWithoutSessionFor(DateTimeOffset startedAt, DateTimeOffset now, TimeSpan maximumAge, string? instanceId) =>
        !Authenticated && NetworkReachable && ClientThreadAlive && NetworkCheckThreadAlive
        && AuthenticationStage == "network-online-unowned" && HasLiveProgress
        && now.ToUnixTimeSeconds() - LastNetworkSuccessAt is >= 0 and <= 75
        && IsCurrentFor(startedAt, now, maximumAge, instanceId);

    public bool IsConnectedFor(DateTimeOffset startedAt, DateTimeOffset now, TimeSpan? maximumAge = null, string? expectedInstanceId = null) =>
        Authenticated && ClientThreadAlive && NetworkCheckThreadAlive
        && AuthenticationStage is "heartbeat" or "heartbeat-wait" && HasLiveProgress
        && IsCurrentFor(startedAt, now, maximumAge, expectedInstanceId);

    public bool HasRecentAuthentication(DateTimeOffset now) =>
        now.ToUnixTimeSeconds() - Math.Max(LastLoginSuccessAt, LastHeartbeatSuccessAt) is >= 0 and <= 600;

    public static HealthSnapshot? Load()
    {
        if (!File.Exists(AppPaths.HealthFile)) return null;
        try
        {
            return JsonSerializer.Deserialize<HealthSnapshot>(File.ReadAllText(AppPaths.HealthFile));
        }
        catch
        {
            return null;
        }
    }

    public string ToDisplayText()
    {
        var now = DateTimeOffset.Now.ToUnixTimeSeconds();
        var updatedAge = LastUpdatedAt > 0 ? now - LastUpdatedAt : -1;
        var heartbeatAge = LastHeartbeatSuccessAt > 0 ? now - LastHeartbeatSuccessAt : -1;
        var error = string.IsNullOrWhiteSpace(LastError) ? "无" : LastError;
        static string Age(long seconds) => seconds < 0 ? "暂无有效记录" : $"{seconds} 秒前";
        var plan = AutoReauthStatus switch
        {
            "disabled" => "已关闭", "state-write-failed" => "计划记录写入失败，已暂停",
            "state-schema-unsupported" => "计划记录版本较新，已保留并暂停",
            "waiting-for-portal" => "等待认证入口，暂不接管其他会话",
            "completed-or-claimed" => "今日已登录或已发起计划", "due" => "等待执行",
            "waiting" => "等待下个窗口", _ => "暂无记录"
        };
        return $"网络={(NetworkReachable ? "外网可达" : "未确认")}, 每日重登={plan}, 认证={(Authenticated ? "已确认" : "未确认")}, 客户端线程={(ClientThreadAlive ? "运行中" : "未运行")}, 网络线程={(NetworkCheckThreadAlive ? "运行中" : "未运行")}, 状态更新={Age(updatedAge)}, 心跳={Age(heartbeatAge)}, 心跳失败={ConsecutiveHeartbeatFailures}, 错误={error}, 认证重试={(AuthenticationBlocked ? "已暂停，请断开后重新连接" : "运行中")}";
    }
}
