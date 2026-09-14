using System.IO;
using System.IO.Compression;

namespace ESurfingDialerLite;

public static class LogExporter
{
    public static string Export()
    {
        Directory.CreateDirectory(AppPaths.LogsDirectory);
        var target = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
            $"ESurfingDialerLite-logs-{DateTime.Now:yyyyMMdd-HHmmss}.zip");

        if (File.Exists(target)) File.Delete(target);

        using var zip = ZipFile.Open(target, ZipArchiveMode.Create);
        AddFile(zip, AppPaths.ClientLogFile, "client.log");
        AddFile(zip, AppPaths.CoreLogFile, "core.log");
        AddFile(zip, AppPaths.HealthFile, "health.json");
        AddFile(zip, AppPaths.ConfigFile, "config.json");
        return target;
    }

    private static void AddFile(ZipArchive zip, string path, string name)
    {
        if (File.Exists(path)) zip.CreateEntryFromFile(path, name);
    }
}
