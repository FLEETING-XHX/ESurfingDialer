using System.IO;

namespace ESurfingDialerLite;

public static class AppPaths
{
    public static string AppDataDirectory { get; } =
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ESurfingDialerLite");

    public static string LogsDirectory { get; } = Path.Combine(AppDataDirectory, "logs");

    public static string DataDirectory { get; } = Path.Combine(AppDataDirectory, "data");

    public static string ConfigFile { get; } = Path.Combine(AppDataDirectory, "config.json");

    public static string HealthFile { get; } = Path.Combine(DataDirectory, "health.json");

    public static string CoreJar { get; } = Path.Combine(AppContext.BaseDirectory, "core", "client.jar");

    public static string ClientLogFile { get; } = Path.Combine(LogsDirectory, "client.log");

    public static string CoreLogFile { get; } = Path.Combine(LogsDirectory, "core.log");
}
