using System.IO;
using System.Text.Json;

namespace ESurfingDialerLite;

public sealed class ConfigStore
{
    public string? LoadError { get; private set; }
    internal Action<ConfigSaveStage>? BeforeSaveStage { get; set; }
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
        using var pending = PrepareSave(config);
        pending.Commit();
    }

    internal PreparedConfigSave PrepareSave(ClientConfig config)
    {
        if (LoadError != null) throw new InvalidOperationException("原账户配置读取失败，已保留原文件。请先检查 config.json。");
        Directory.CreateDirectory(AppPaths.AppDataDirectory);
        config.SynchronizeSelectedAccount();
        var json = JsonSerializer.Serialize(config, JsonOptions);
        var temporary = AppPaths.ConfigFile + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            BeforeSaveStage?.Invoke(ConfigSaveStage.WriteTemporary);
            using (var stream = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                stream.Write(System.Text.Encoding.UTF8.GetBytes(json));
                stream.Flush(flushToDisk: true);
            }
            BeforeSaveStage?.Invoke(ConfigSaveStage.Backup);
            if (File.Exists(AppPaths.ConfigFile)) File.Copy(AppPaths.ConfigFile, AppPaths.ConfigFile + ".bak", overwrite: true);
            return new PreparedConfigSave(temporary, () => BeforeSaveStage?.Invoke(ConfigSaveStage.Commit));
        }
        catch { PreparedConfigSave.CleanUp(temporary); throw; }
    }
}

internal enum ConfigSaveStage { WriteTemporary, Backup, Commit }

internal sealed class PreparedConfigSave(string temporary, Action beforeCommit) : IDisposable
{
    private bool _committed;
    public void Commit()
    {
        if (_committed) throw new InvalidOperationException("配置已提交。");
        beforeCommit();
        RetryFileOperation(() => File.Move(temporary, AppPaths.ConfigFile, overwrite: true));
        _committed = true;
    }
    public void Dispose() { if (!_committed) CleanUp(temporary); }
    internal static void CleanUp(string path)
    {
        try { RetryFileOperation(() => File.Delete(path)); }
        catch (IOException) { ClientLog.Write(AppPaths.ClientLogFile, "配置临时文件仍被占用，未能清理；原账户配置未覆盖。"); }
        catch (UnauthorizedAccessException) { ClientLog.Write(AppPaths.ClientLogFile, "配置临时文件无法删除；原账户配置未覆盖。"); }
    }

    private static void RetryFileOperation(Action operation)
    {
        for (var attempt = 0; ; attempt++)
        {
            try { operation(); return; }
            catch (Exception ex) when (attempt < 3 && (ex is IOException or UnauthorizedAccessException)
                && (ex.HResult & 0xffff) is 5 or 32 or 33)
            {
                // Brief sharing/access races are retried within 90 ms; permanent failure still propagates.
                Thread.Sleep(15 * (attempt + 1));
            }
        }
    }
}
