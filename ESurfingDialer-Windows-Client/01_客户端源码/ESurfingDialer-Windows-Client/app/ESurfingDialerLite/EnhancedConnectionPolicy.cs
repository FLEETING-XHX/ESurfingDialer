namespace ESurfingDialerLite;

public static class EnhancedConnectionPolicy
{
    public static readonly TimeSpan EnhancedHealthCheckInterval = TimeSpan.FromSeconds(5);
    public static readonly TimeSpan ConservativeHealthCheckInterval = TimeSpan.FromSeconds(10);
    public static readonly TimeSpan EnhancedHealthSnapshotMaxAge = TimeSpan.FromSeconds(40);
    public static readonly TimeSpan ConservativeHealthSnapshotMaxAge = TimeSpan.FromSeconds(75);
    public const int EnhancedCoreNetworkCheckSeconds = 5;
    public const int ConservativeCoreNetworkCheckSeconds = 20;
    public const int EnhancedCoreHealthWriteSeconds = 15;
    public const int ConservativeCoreHealthWriteSeconds = 30;
    public static readonly TimeSpan EnhancedStartupGrace = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan AuthenticationRecoveryGrace = TimeSpan.FromSeconds(45);
    public static readonly TimeSpan ResumeRecoveryGrace = TimeSpan.FromSeconds(20);
    public const int ConsecutiveUnhealthyChecksBeforeRecovery = 2;
    public const int MaximumAutomaticRecoveries = 3;

    public static TimeSpan HealthCheckInterval(bool enhanced) =>
        enhanced ? EnhancedHealthCheckInterval : ConservativeHealthCheckInterval;

    public static TimeSpan HealthSnapshotMaxAge(bool enhanced) =>
        enhanced ? EnhancedHealthSnapshotMaxAge : ConservativeHealthSnapshotMaxAge;

    public static int CoreNetworkCheckSeconds(bool enhanced) =>
        enhanced ? EnhancedCoreNetworkCheckSeconds : ConservativeCoreNetworkCheckSeconds;

    public static int CoreHealthWriteSeconds(bool enhanced) =>
        enhanced ? EnhancedCoreHealthWriteSeconds : ConservativeCoreHealthWriteSeconds;

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
        if (health.Authenticated)
            return null;
        if (health.AuthenticationFailureDeterministic && health.HasAuthenticationFailureFor(startedAt, now, EnhancedHealthSnapshotMaxAge))
            return null; // The core owns bounded protocol retries; restarting would reset its failure budget.
        if (health.HasActiveAuthenticationStage(startedAt, now))
            return null; // Bounded grace for an in-flight request/native initialization, never a perpetual lease.
        if (!health.HasRecentAuthentication(now))
            return "认证心跳长时间未确认";

        var lastAuthentication = Math.Max(health.LastLoginSuccessAt, health.LastHeartbeatSuccessAt);
        var unauthenticatedFor = lastAuthentication > 0
            ? now - DateTimeOffset.FromUnixTimeSeconds(lastAuthentication)
            : now - startedAt;
        return unauthenticatedFor >= AuthenticationRecoveryGrace ? "认证状态持续丢失" : null;
    }
}
