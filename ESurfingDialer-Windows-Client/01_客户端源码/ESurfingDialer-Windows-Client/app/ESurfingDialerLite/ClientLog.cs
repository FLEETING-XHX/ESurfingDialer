using System.IO;
using System.Text;
using System.Text.RegularExpressions;

namespace ESurfingDialerLite;

public static class ClientLog
{
    private static readonly object Gate = new();
    private static string[] _secrets = [];
    public static void SetSecrets(IEnumerable<string> values)
    {
        lock (Gate) _secrets = _secrets.Concat(values).Where(v => !string.IsNullOrEmpty(v)).Distinct().OrderByDescending(v => v.Length).ToArray();
    }
    public static string Redact(string text)
    {
        lock (Gate)
        {
            foreach (var secret in _secrets) text = text.Replace(secret, "[REDACTED]", StringComparison.Ordinal);
            return Regex.Replace(text, @"(?i)(password|passwd|ticket|token|authorization)(\s*[:=]\s*)[^\s<,]+", "$1$2[REDACTED]");
        }
    }
    public static void Write(string path, string message)
    {
        lock (Gate)
        {
            try
            {
                Directory.CreateDirectory(AppPaths.LogsDirectory);
                if (File.Exists(path) && new FileInfo(path).Length > 4 * 1024 * 1024)
                    File.Move(path, path + ".1", overwrite: true);
                File.AppendAllText(path, $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {Redact(message)}{Environment.NewLine}");
            }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }
    public static string ReadTail(string path)
    {
        if (!File.Exists(path)) return "";
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        var skipped = stream.Length > 192 * 1024;
        if (skipped) stream.Seek(-192 * 1024, SeekOrigin.End);
        using var reader = new StreamReader(stream, Encoding.UTF8);
        if (skipped) reader.ReadLine();
        return (skipped ? "（更早记录请导出 ZIP 查看）" + Environment.NewLine : "") + Redact(reader.ReadToEnd());
    }
}
