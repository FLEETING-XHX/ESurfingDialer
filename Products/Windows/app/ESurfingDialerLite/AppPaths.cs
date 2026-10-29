using System.IO;

namespace ESurfingDialerLite;

public static class AppPaths
{
    public static string AppDataDirectory { get; } =
        Environment.GetEnvironmentVariable("ESURFING_CLIENT_HOME") is { Length: > 0 } custom
            ? Path.GetFullPath(custom)
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ESurfingDialerLite");

    public static string LogsDirectory { get; } = Path.Combine(AppDataDirectory, "logs");

    public static string DataDirectory { get; } = Path.Combine(AppDataDirectory, "data");

    public static string ConfigFile { get; } = Path.Combine(AppDataDirectory, "config.json");

    private static string _healthFile = Path.Combine(DataDirectory, "health.json");
    public static string HealthFile => Volatile.Read(ref _healthFile);

    // Identity/configuration remain shared; only this supervised process may publish its health.
    internal static void UseHealthInstance(string instanceId)
    {
        if (!Guid.TryParseExact(instanceId, "D", out _)) throw new ArgumentException("核心实例标识无效。", nameof(instanceId));
        Volatile.Write(ref _healthFile, Path.Combine(DataDirectory, $"health-{instanceId}.json"));
    }

    public static string CoreJar { get; } = Path.Combine(AppContext.BaseDirectory, "core", "client.jar");

    public static string ClientLogFile { get; } = Path.Combine(LogsDirectory, "client.log");

    public static string CoreLogFile { get; } = Path.Combine(LogsDirectory, "core.log");
}
