using System.Reflection;
using System.Windows.Controls;
using ESurfingDialerLite;

internal static partial class Program
{
    private static void PasswordChecks(MainWindow window)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
        Check(PasswordProtector.Decrypt(null).Status == PasswordDecryptionStatus.Empty, "Missing protected password is distinct");
        Check(PasswordProtector.Decrypt("!invalid-base64!").Status == PasswordDecryptionStatus.InvalidFormat, "Invalid password format is distinct");
        var invalid = PasswordProtector.Decrypt(Convert.ToBase64String(new byte[] { 1, 2, 3 }));
        Check(invalid.Status == PasswordDecryptionStatus.CannotDecrypt && invalid.Password == "", "Undecryptable password returns no plaintext");
        var cipher = PasswordProtector.Protect("合成密码-test-1");
        var decoded = PasswordProtector.Decrypt(cipher);
        Check(decoded.Success && decoded.Password == "合成密码-test-1", "Existing DPAPI format still round-trips");
        var configField = typeof(MainWindow).GetField("_config", flags)!;
        var original = (ClientConfig)configField.GetValue(window)!;
        var configBytes = System.IO.File.ReadAllBytes(AppPaths.ConfigFile);
        try
        {
            var account = new CampusAccount { UserName = "synthetic-user", ProtectedPassword = "!invalid-base64!" };
            var config = new ClientConfig { Accounts = [account], SelectedAccountId = account.Id };
            config.SynchronizeSelectedAccount();
            configField.SetValue(window, config);
            var started = ((Task<bool>)typeof(MainWindow).GetMethod("StartDialerAsync", flags)!.Invoke(window, new object[] { false })!).GetAwaiter().GetResult();
            var notice = (TextBlock)window.FindName("NoticeText");
            Check(!started && notice.Text.Contains("格式已损坏"), "Connect explains local password failure before launching core");
            var dialer = (DialerCoreProcess)typeof(MainWindow).GetField("_dialer", flags)!.GetValue(window)!;
            Check(!dialer.IsRequested && !dialer.IsRunning, "Invalid password does not create a core or connection intent");
            typeof(MainWindow).GetMethod("BeginAccountEdit", flags)!.Invoke(window, new object[] { account.Id });
            Check(((TextBlock)window.FindName("AccountErrorText")).Text.Contains("重新输入密码")
                && ((PasswordBox)window.FindName("PasswordBox")).Password == "", "Editor explains decrypt failure without erasing saved credentials");
            Check(System.IO.File.ReadAllBytes(AppPaths.ConfigFile).SequenceEqual(configBytes), "Decrypt failure preserves configuration byte-for-byte");
        }
        finally
        {
            configField.SetValue(window, original);
            typeof(MainWindow).GetMethod("ShowAccountList", flags)!.Invoke(window, null);
            typeof(MainWindow).GetMethod("CloseAccounts", flags)!.Invoke(window, null);
            typeof(MainWindow).GetMethod("UpdateStatus", flags)!.Invoke(window, new object[] { "未连接" });
        }
    }
}
