using System.IO;
using System.Text.Json;

namespace ESurfingDialerLite;

public sealed class ConfigStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true
    };

    public ClientConfig Load()
    {
        if (!File.Exists(AppPaths.ConfigFile)) return new ClientConfig();

        try
        {
            var json = File.ReadAllText(AppPaths.ConfigFile);
            return JsonSerializer.Deserialize<ClientConfig>(json, JsonOptions) ?? new ClientConfig();
        }
        catch
        {
            return new ClientConfig();
        }
    }

    public void Save(ClientConfig config)
    {
        Directory.CreateDirectory(AppPaths.AppDataDirectory);
        var json = JsonSerializer.Serialize(config, JsonOptions);
        File.WriteAllText(AppPaths.ConfigFile, json);
    }
}
