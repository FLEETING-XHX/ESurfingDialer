namespace ESurfingDialerLite;

public static class EnhancedConnectionPolicy
{
    public static readonly TimeSpan EnhancedHealthCheckInterval = TimeSpan.FromSeconds(5);
    public static readonly TimeSpan ConservativeHealthCheckInterval = TimeSpan.FromSeconds(10);
    public static readonly TimeSpan EnhancedHealthSnapshotMaxAge = TimeSpan.FromSeconds(12);
    public static readonly TimeSpan ConservativeHealthSnapshotMaxAge = TimeSpan.FromSeconds(60);
    public static readonly TimeSpan EnhancedStartupGrace = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan AuthenticationRecoveryGrace = TimeSpan.FromSeconds(45);
    public const int ConsecutiveUnhealthyChecksBeforeRecovery = 2;
    public const int MaximumAutomaticRecoveries = 3;

    public static TimeSpan HealthCheckInterval(bool enhanced) =>
        enhanced ? EnhancedHealthCheckInterval : ConservativeHealthCheckInterval;

    public static TimeSpan HealthSnapshotMaxAge(bool enhanced) =>
        enhanced ? EnhancedHealthSnapshotMaxAge : ConservativeHealthSnapshotMaxAge;

    public static TimeSpan RecoveryCooldown(int recoveryAttempt)
    {
        var multiplier = 1 << Math.Clamp(recoveryAttempt - 1, 0, 2);
        return TimeSpan.FromSeconds(Math.Min(120, 30 * multiplier));
    }

    public static string? RecoverableFailureReason(HealthSnapshot? health, DateTimeOffset startedAt, DateTimeOffset now)
    {
        if (health == null || !health.IsCurrentFor(startedAt, now, EnhancedHealthSnapshotMaxAge))
            return "健康状态缺失或已过期";
        if (!health.ClientThreadAlive)
            return "认证线程未运行";
        if (!health.NetworkCheckThreadAlive)
            return "网络检测线程未运行";
        if (!health.HasRecentAuthentication(now))
            return "认证心跳长时间未确认";
        if (health.Authenticated)
            return null;

        var lastAuthentication = Math.Max(health.LastLoginSuccessAt, health.LastHeartbeatSuccessAt);
        var unauthenticatedFor = lastAuthentication > 0
            ? now - DateTimeOffset.FromUnixTimeSeconds(lastAuthentication)
            : now - startedAt;
        return unauthenticatedFor >= AuthenticationRecoveryGrace ? "认证状态持续丢失" : null;
    }
}
