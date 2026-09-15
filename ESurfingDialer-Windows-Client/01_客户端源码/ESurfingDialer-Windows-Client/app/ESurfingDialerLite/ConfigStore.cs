using System.IO;
using System.Text.Json;

namespace ESurfingDialerLite;

public sealed class ConfigStore
{
    public string? LoadError { get; private set; }
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true
    };

    public ClientConfig Load()
    {
        LoadError = null;
        if (!File.Exists(AppPaths.ConfigFile)) return new ClientConfig();

        try
        {
            var json = File.ReadAllText(AppPaths.ConfigFile);
            var config = JsonSerializer.Deserialize<ClientConfig>(json, JsonOptions) ?? new ClientConfig();
            config.NormalizeAccounts();
            return config;
        }
        catch (Exception ex)
        {
            LoadError = ex.Message;
            return new ClientConfig();
        }
    }

    public void Save(ClientConfig config)
    {
        if (LoadError != null) throw new InvalidOperationException("原账户配置读取失败，已保留原文件。请先检查 config.json。");
        Directory.CreateDirectory(AppPaths.AppDataDirectory);
        config.SynchronizeSelectedAccount();
        var json = JsonSerializer.Serialize(config, JsonOptions);
        var temporary = AppPaths.ConfigFile + ".tmp";
        File.WriteAllText(temporary, json);
        if (File.Exists(AppPaths.ConfigFile)) File.Copy(AppPaths.ConfigFile, AppPaths.ConfigFile + ".bak", overwrite: true);
        File.Move(temporary, AppPaths.ConfigFile, overwrite: true);
    }
}
