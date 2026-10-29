using System.Security.Cryptography;
using System.Text;

namespace ESurfingDialerLite;

public static class PasswordProtector
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("ESurfingDialerLite.v1");

    public static string Protect(string password)
    {
        if (string.IsNullOrEmpty(password)) return "";
        var bytes = Encoding.UTF8.GetBytes(password);
        try
        {
            var protectedBytes = ProtectedData.Protect(bytes, Entropy, DataProtectionScope.CurrentUser);
            try { return Convert.ToBase64String(protectedBytes); }
            finally { CryptographicOperations.ZeroMemory(protectedBytes); }
        }
        finally { CryptographicOperations.ZeroMemory(bytes); }
    }

    public static string Unprotect(string protectedPassword)
    {
        return Decrypt(protectedPassword).Password;
    }

    public static PasswordDecryptionResult Decrypt(string? protectedPassword)
    {
        if (string.IsNullOrEmpty(protectedPassword)) return new(PasswordDecryptionStatus.Empty, "");
        byte[] protectedBytes;
        try { protectedBytes = Convert.FromBase64String(protectedPassword); }
        catch (FormatException) { return new(PasswordDecryptionStatus.InvalidFormat, ""); }
        try
        {
            var bytes = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.CurrentUser);
            try
            {
                var password = Encoding.UTF8.GetString(bytes);
                return new(password.Length == 0 ? PasswordDecryptionStatus.Empty : PasswordDecryptionStatus.Success, password);
            }
            finally { CryptographicOperations.ZeroMemory(bytes); }
        }
        catch (CryptographicException) { return new(PasswordDecryptionStatus.CannotDecrypt, ""); }
        finally { CryptographicOperations.ZeroMemory(protectedBytes); }
    }
}

public enum PasswordDecryptionStatus { Success, Empty, InvalidFormat, CannotDecrypt }

public sealed record PasswordDecryptionResult(PasswordDecryptionStatus Status, string Password)
{
    public bool Success => Status == PasswordDecryptionStatus.Success;
    public string ErrorMessage => Status switch
    {
        PasswordDecryptionStatus.Empty => "此账户尚未保存密码，请编辑账户并填写密码。",
        PasswordDecryptionStatus.InvalidFormat => "保存的密码格式已损坏，请编辑账户并重新输入密码。原配置已保留。",
        PasswordDecryptionStatus.CannotDecrypt => "保存的密码无法解密，可能已损坏或不属于当前 Windows 用户，请编辑账户并重新输入密码。原配置已保留。",
        _ => ""
    };
}
