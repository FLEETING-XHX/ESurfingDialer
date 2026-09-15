using System.IO;
using System.IO.Compression;
using System.Text.Json;

namespace ESurfingDialerLite;

public static class LogExporter
{
    public static string Export(string? target = null)
    {
        Directory.CreateDirectory(AppPaths.LogsDirectory);
        target ??= Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
            $"ESurfingDialer-logs-{DateTime.Now:yyyyMMdd-HHmmssfff}.zip");
        var temporary = target + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            using (var zip = ZipFile.Open(temporary, ZipArchiveMode.Create))
            {
                foreach (var path in Directory.EnumerateFiles(AppPaths.LogsDirectory).Where(p => p.EndsWith(".log") || p.EndsWith(".log.1")))
                {
                    using var source = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                    using var reader = new StreamReader(source);
                    using var writer = new StreamWriter(zip.CreateEntry(Path.GetFileName(path)).Open());
                    while (reader.ReadLine() is { } line) writer.WriteLine(ClientLog.Redact(line));
                }
                if (File.Exists(AppPaths.HealthFile))
                {
                    using var writer = new StreamWriter(zip.CreateEntry("health.json").Open());
                    writer.Write(ClientLog.ReadTail(AppPaths.HealthFile));
                }
                var config = new ConfigStore().Load();
                using var settings = new StreamWriter(zip.CreateEntry("settings.json").Open());
                settings.Write(JsonSerializer.Serialize(new { config.AutoStart, config.StartMinimized, config.AutoConnect,
                    config.EnhancedConnection, AccountCount = config.Accounts.Count,
                    ClientVersion = typeof(LogExporter).Assembly.GetName().Version?.ToString() }, new JsonSerializerOptions { WriteIndented = true }));
            }
            File.Move(temporary, target, overwrite: true);
            return target;
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
}
