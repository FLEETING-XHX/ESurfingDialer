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
        && (HasKnownOperation || AuthenticationStage == "heartbeat-wait");

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
    {
        var age = now.ToUnixTimeSeconds() - LastUpdatedAt;
        var maximumAgeSeconds = (long)((maximumAge ?? TimeSpan.FromSeconds(60)).TotalSeconds);
        return ProcessAlive && (expectedInstanceId == null || ProcessInstanceId == expectedInstanceId)
            && LastUpdatedAt >= startedAt.ToUnixTimeSeconds()
            && age >= 0 && age <= maximumAgeSeconds;
    }

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
        return $"认证={Authenticated}, 客户端线程={ClientThreadAlive}, 网络线程={NetworkCheckThreadAlive}, 状态更新={updatedAge}s前, 心跳={heartbeatAge}s前, 心跳失败={ConsecutiveHeartbeatFailures}, 错误={error}, 认证重试={(AuthenticationBlocked ? "已暂停，请断开后重新连接" : "运行中")}";
    }
}
