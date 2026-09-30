using System.IO;
using System.Reflection;
using System.Windows.Controls;
using System.Windows.Threading;
using ESurfingDialerLite;

internal static partial class Program
{
    private static void AwaitUi(Task task)
    {
        if (!task.IsCompleted)
        {
            var frame = new DispatcherFrame();
            var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(10) };
            timer.Tick += (_, _) => frame.Continue = false;
            var dispatcher = Dispatcher.CurrentDispatcher;
            _ = task.ContinueWith(_ => dispatcher.BeginInvoke(() => frame.Continue = false), TaskScheduler.Default);
            timer.Start();
            try { Dispatcher.PushFrame(frame); }
            finally { timer.Stop(); }
            if (_dispatcherFailure != null) System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(_dispatcherFailure).Throw();
            if (!task.IsCompleted) throw new TimeoutException("WPF operation did not complete");
        }
        task.GetAwaiter().GetResult();
    }

    private static void AccountFailureChecks(MainWindow window)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
        var type = typeof(MainWindow);
        var configField = type.GetField("_config", flags)!;
        var original = (ClientConfig)configField.GetValue(window)!;
        var store = (ConfigStore)type.GetField("_configStore", flags)!.GetValue(window)!;
        var dialer = (DialerCoreProcess)type.GetField("_dialer", flags)!.GetValue(window)!;
        var disk = File.ReadAllBytes(AppPaths.ConfigFile);
        var current = new CampusAccount { UserName = "account-fixture-1", ProtectedPassword = PasswordProtector.Protect("only-test-1") };
        var other = new CampusAccount { UserName = "account-fixture-2", ProtectedPassword = PasswordProtector.Protect("only-test-2") };
        var fixture = new ClientConfig { Accounts = [current, other], SelectedAccountId = current.Id, AutoConnect = false };
        store.Save(fixture); configField.SetValue(window, fixture);
        var fixtureDisk = File.ReadAllBytes(AppPaths.ConfigFile);
        void Edit() {
            type.GetMethod("BeginAccountEdit", flags)!.Invoke(window, new object[] { current.Id });
            ((TextBox)window.FindName("AccountNameTextBox")).Text = "changed";
        }
        void Call(string method, params object[] arguments)
        {
            var previous = SynchronizationContext.Current;
            SynchronizationContext.SetSynchronizationContext(new DispatcherSynchronizationContext(window.Dispatcher));
            try { AwaitUi((Task)type.GetMethod(method, flags)!.Invoke(window, arguments)!); }
            finally { SynchronizationContext.SetSynchronizationContext(previous); }
        }
        var worker = Worker("--idle");
        typeof(DialerCoreProcess).GetField("_process", flags)!.SetValue(dialer, worker);
        typeof(DialerCoreProcess).GetField("_requested", flags)!.SetValue(dialer, true);
        try
        {
            foreach (var stage in new[] { ConfigSaveStage.WriteTemporary, ConfigSaveStage.Backup })
            {
                store.BeforeSaveStage = s => { if (s == stage) throw new IOException("synthetic disk failure"); };
                Edit(); Call("SaveAccountAsync");
                Check(dialer.IsRunning && dialer.IsRequested && ReferenceEquals(configField.GetValue(window), fixture)
                    && current.Name == "", "Preparation failure preserves live connection and edited account: " + stage);
                Call("SelectAccountAsync", other.Id);
                Check(dialer.IsRunning && fixture.SelectedAccountId == current.Id, "Failed switch preserves current account: " + stage);
                Call("DeleteAccountAsync", current.Id);
                Check(dialer.IsRunning && fixture.Accounts.Count == 2, "Failed deletion preserves current account: " + stage);
                Check(File.ReadAllBytes(AppPaths.ConfigFile).SequenceEqual(fixtureDisk)
                    && !Directory.GetFiles(AppPaths.AppDataDirectory, "config.json.tmp-*").Any(), "Preparation failure preserves disk and cleans temporary file: " + stage);
            }
            store.BeforeSaveStage = null;
            var backup = AppPaths.ConfigFile + ".bak";
            var savedBackup = backup + ".test-saved";
            File.Move(backup, savedBackup);
            Directory.CreateDirectory(backup);
            try
            {
                Edit(); Call("SaveAccountAsync");
                Check(dialer.IsRunning && dialer.IsRequested && File.ReadAllBytes(AppPaths.ConfigFile).SequenceEqual(fixtureDisk),
                    "Real backup filesystem failure preserves active account and process");
                Check(!Directory.GetFiles(AppPaths.AppDataDirectory, "config.json.tmp-*").Any(), "Real filesystem failure removes staged password file");
            }
            finally { Directory.Delete(backup); File.Move(savedBackup, backup); }
            store.BeforeSaveStage = s => { if (s == ConfigSaveStage.Commit) throw new IOException("synthetic replace failure"); };
            Edit(); Call("SaveAccountAsync");
            Check(!dialer.IsRunning && !dialer.IsRequested && ReferenceEquals(configField.GetValue(window), fixture)
                && File.ReadAllBytes(AppPaths.ConfigFile).SequenceEqual(fixtureDisk), "Commit failure preserves old account without silently reconnecting");
            Check(((TextBlock)window.FindName("AccountErrorText")).Text.Contains("连接已停止")
                && ((Button)window.FindName("CampusConnectionButton")).IsEnabled, "Commit failure explains stopped connection and restores controls");
            Check(!Directory.GetFiles(AppPaths.AppDataDirectory, "config.json.tmp-*").Any(), "Commit failure cleans staged credentials");
            store.BeforeSaveStage = null;
            Edit(); Call("SaveAccountAsync");
            Check(store.Load().CurrentAccount!.Name == "changed" && current.Name == "", "Successful save commits copied account without mutating prior snapshot");
            Call("SelectAccountAsync", other.Id);
            Check(store.Load().SelectedAccountId == other.Id, "Successful switch persists selected account");
            Call("DeleteAccountAsync", current.Id);
            Check(store.Load().Accounts.Count == 1, "Successful deletion persists only remaining account");
        }
        finally
        {
            store.BeforeSaveStage = null;
            dialer.Stop();
            File.WriteAllBytes(AppPaths.ConfigFile, disk);
            configField.SetValue(window, original);
            type.GetMethod("ShowAccountList", flags)!.Invoke(window, null);
            type.GetMethod("CloseAccounts", flags)!.Invoke(window, null);
            type.GetMethod("UpdateStatus", flags)!.Invoke(window, new object[] { "未连接" });
        }
    }
}
