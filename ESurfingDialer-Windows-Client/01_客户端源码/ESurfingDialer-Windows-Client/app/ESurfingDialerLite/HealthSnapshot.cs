using System.IO;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace ESurfingDialerLite;

public sealed class HealthSnapshot
{
    [JsonPropertyName("processAlive")]
    public bool ProcessAlive { get; set; }

    [JsonPropertyName("clientThreadAlive")]
    public bool ClientThreadAlive { get; set; }

    [JsonPropertyName("networkCheckThreadAlive")]
    public bool NetworkCheckThreadAlive { get; set; }

    [JsonPropertyName("authenticated")]
    public bool Authenticated { get; set; }

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

    public bool IsCurrentFor(DateTimeOffset startedAt, DateTimeOffset now, TimeSpan? maximumAge = null)
    {
        var age = now.ToUnixTimeSeconds() - LastUpdatedAt;
        var maximumAgeSeconds = (long)((maximumAge ?? TimeSpan.FromSeconds(60)).TotalSeconds);
        return ProcessAlive && LastUpdatedAt >= startedAt.ToUnixTimeSeconds()
            && age >= 0 && age <= maximumAgeSeconds;
    }

    public bool IsConnectedFor(DateTimeOffset startedAt, DateTimeOffset now, TimeSpan? maximumAge = null) =>
        Authenticated && IsCurrentFor(startedAt, now, maximumAge);

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
        return $"认证={Authenticated}, 客户端线程={ClientThreadAlive}, 网络线程={NetworkCheckThreadAlive}, 状态更新={updatedAge}s前, 心跳={heartbeatAge}s前, 心跳失败={ConsecutiveHeartbeatFailures}, 错误={error}";
    }
}
