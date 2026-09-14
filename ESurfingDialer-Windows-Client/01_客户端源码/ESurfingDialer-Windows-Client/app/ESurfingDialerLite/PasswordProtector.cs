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
        var protectedBytes = ProtectedData.Protect(bytes, Entropy, DataProtectionScope.CurrentUser);
        return Convert.ToBase64String(protectedBytes);
    }

    public static string Unprotect(string protectedPassword)
    {
        if (string.IsNullOrEmpty(protectedPassword)) return "";
        try
        {
            var protectedBytes = Convert.FromBase64String(protectedPassword);
            var bytes = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(bytes);
        }
        catch
        {
            return "";
        }
    }
}
