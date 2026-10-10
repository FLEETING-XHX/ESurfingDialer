using System.Collections;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Windows.Threading;
using ESurfingDialerLite;

internal static partial class Program
{
    private static void RestoreLogSecrets(MainWindow window)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
        typeof(MainWindow).GetField("_secretFingerprint", flags)!.SetValue(window, "");
        typeof(MainWindow).GetMethod("RefreshAccounts", flags)!.Invoke(window, null);
    }

    private static void LogBoundsChecks(MainWindow window)
    {
        var path = Path.Combine(AppPaths.LogsDirectory, "bounded-stress.log");
        try
        {
            ClientLog.SetSecrets(new[] { "synthetic-long-secret" });
            ClientLog.Write(path, new string('中', ClientLog.MaximumEntryCharacters - 5) + "synthetic-long-secret" + new string('中', 100_000));
            var text = File.ReadAllText(path);
            Check(text.Contains("已截断") && !text.Contains("synthetic")
                && new FileInfo(path).Length < ClientLog.MaximumLogBytes, "Long Unicode entry is redacted before truncation and stays bounded");
            var message = new string('x', 8192) + " token=synthetic-ticket";
            var writer = Task.Run(() => { for (var i = 0; i < 1200; i++) ClientLog.Write(path, message); });
            var reader = Task.Run(() => { for (var i = 0; i < 300; i++) _ = ClientLog.ReadTail(path); });
            Task.WhenAll(writer, reader).GetAwaiter().GetResult();
            Check(new FileInfo(path).Length <= ClientLog.MaximumLogBytes
                && new FileInfo(path + ".1").Length <= ClientLog.MaximumLogBytes, "Current and rotated logs each stay within four MiB");
            Check(Directory.GetFiles(AppPaths.LogsDirectory, "bounded-stress.log*").Length == 2, "Repeated log rotation keeps only one backup");
            var tail = ClientLog.ReadTail(path);
            Check(tail.Length < 192 * 1024 + 100 && !tail.Contains("synthetic-ticket"), "Concurrent rotation and tail reading stay bounded and redacted");
        }
        finally
        {
            File.Delete(path); File.Delete(path + ".1");
            RestoreLogSecrets(window);
        }
    }

    private sealed record ResourceSample(long ManagedBytes, long WorkingSetBytes, long PrivateBytes, int Handles, int OwnedWorkersCount, double CpuSeconds, int PoolThreads, int ProcessThreads);

    private static void ConfigRetryChecks()
    {
        var saved = File.ReadAllBytes(AppPaths.ConfigFile);
        var store = new ConfigStore();
        var candidate = store.Load().Copy();
        candidate.CurrentAccount!.Name = "synthetic-retry-name";
        try
        {
            using (var pending = store.PrepareSave(candidate))
            {
                var temporary = Directory.GetFiles(AppPaths.AppDataDirectory, "config.json.tmp-*").Single();
                var held = new FileStream(temporary, FileMode.Open, FileAccess.Read, FileShare.Read);
                var release = new Thread(() => { Thread.Sleep(30); held.Dispose(); }) { IsBackground = true };
                release.Start();
                try { pending.Commit(); }
                finally { release.Join(); }
            }
            Check(store.Load().CurrentAccount!.Name == "synthetic-retry-name", "Short file sharing conflict is retried and commits successfully");
            File.WriteAllBytes(AppPaths.ConfigFile, saved);
            using (var pending = store.PrepareSave(candidate))
            {
                var temporary = Directory.GetFiles(AppPaths.AppDataDirectory, "config.json.tmp-*").Single();
                using var held = new FileStream(temporary, FileMode.Open, FileAccess.Read, FileShare.Read);
                var rejected = false;
                try { pending.Commit(); }
                catch (IOException) { rejected = true; }
                catch (UnauthorizedAccessException) { rejected = true; }
                Check(rejected && File.ReadAllBytes(AppPaths.ConfigFile).SequenceEqual(saved), "Persistent file sharing conflict fails without replacing account data");
            }
            Check(!Directory.GetFiles(AppPaths.AppDataDirectory, "config.json.tmp-*").Any(), "Failed file replacement cleans staged credentials after lock release");
        }
        finally { File.WriteAllBytes(AppPaths.ConfigFile, saved); }
    }

    private static ResourceSample SampleResources()
    {
        var managed = GC.GetTotalMemory(forceFullCollection: true);
        using var process = Process.GetCurrentProcess();
        process.Refresh();
        return new(managed, process.WorkingSet64, process.PrivateMemorySize64, process.HandleCount, OwnedWorkers.Count, process.TotalProcessorTime.TotalSeconds,
            ThreadPool.ThreadCount, process.Threads.Count);
    }

    private static void DrainUiQueue()
    {
        var frame = new DispatcherFrame();
        Dispatcher.CurrentDispatcher.BeginInvoke(DispatcherPriority.ApplicationIdle, () => frame.Continue = false);
        Dispatcher.PushFrame(frame);
    }

    private static void ResourceStressChecks(MainWindow window, string output)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
        var timer = (DispatcherTimer)typeof(MainWindow).GetField("_healthTimer", flags)!.GetValue(window)!;
        var timerEnabled = timer.IsEnabled;
        timer.Stop();
        var clock = Stopwatch.StartNew();
        var config = new ClientConfig { UserName = "stress-only-user" };
        var core = new DialerCoreProcess(StartFixture, _ => TimeSpan.Zero);
        var savedConfig = File.ReadAllBytes(AppPaths.ConfigFile);
        ResourceSample before;
        ResourceSample after;
        var childPids = new List<int>();
        var phases = new Dictionary<string, ResourceSample>();
        try
        {
            for (var i = 0; i < 30; i++)
            {
                core.StartAsync(config, "stress-only-password", _ => { }).GetAwaiter().GetResult();
                if (i % 5 == 0) core.RestartForRecoveryAsync(config, "stress-only-password", _ => { }).GetAwaiter().GetResult();
                core.Stop();
            }
            CleanOwnedWorkers();
            DrainUiQueue();
            before = SampleResources();
            phases["before"] = before;
            for (var i = 0; i < 200; i++)
            {
                core.StartAsync(config, "stress-only-password", _ => { }).GetAwaiter().GetResult();
                var process = (Process)typeof(DialerCoreProcess).GetField("_process", flags)!.GetValue(core)!;
                childPids.Add(process.Id);
                if (i % 5 == 0) core.RestartForRecoveryAsync(config, "stress-only-password", _ => { }).GetAwaiter().GetResult();
                core.Stop();
                if ((i + 1) % 25 == 0)
                {
                    DrainUiQueue();
                    phases["processCycles" + (i + 1)] = SampleResources();
                }
            }
            phases["afterProcesses"] = SampleResources();
            var add = typeof(MainWindow).GetMethod("AddSummary", flags)!;
            var refresh = typeof(MainWindow).GetMethod("RefreshHealthAsync", flags)!;
            for (var i = 0; i < 2000; i++)
            {
                add.Invoke(window, new object[] { "离线压力", "合成事件，不代表真实登录。", "Offline" });
                AwaitUi((Task)refresh.Invoke(window, null)!);
            }
            phases["afterUi"] = SampleResources();
            var store = new ConfigStore();
            var candidate = store.Load();
            for (var i = 0; i < 200; i++) store.Save(candidate.Copy());
            phases["afterConfig"] = SampleResources();
            var summaries = (ICollection)typeof(MainWindow).GetField("_summaries", flags)!.GetValue(window)!;
            Check(summaries.Count == 100, "Two thousand UI events retain at most one hundred summaries");
            Check(!Directory.GetFiles(AppPaths.AppDataDirectory, "config.json.tmp-*").Any(), "Two hundred config commits leave no staged credentials");
            Check(!core.IsRunning && !core.IsRequested && childPids.All(pid => !ProcessExists(pid)), "Repeated startup recovery and stop leave no test cores alive");
            Thread.Sleep(200);
            // Observe before final cleanup; cleanup must not hide a surviving process.
            Check(OwnedWorkers.Count == 0, "Worker ownership registry retires exited processes during stress");
            after = SampleResources();
            File.WriteAllText(Path.Combine(output, "resource-stress.json"), JsonSerializer.Serialize(new
            {
                Scope = "短时离线测试进程；不代表真实登录后的客户端资源",
                WarmupCycles = 30, StartStopCycles = 200, RecoveryCycles = 40, SummaryEvents = 2000, ConfigCommits = 200,
                ElapsedSeconds = clock.Elapsed.TotalSeconds, Before = before, After = after, Phases = phases
            }, new JsonSerializerOptions { WriteIndented = true }), new UTF8Encoding(false));
            Console.WriteLine($"离线压力：堆增量={after.ManagedBytes - before.ManagedBytes} 字节，句柄={before.Handles}→{after.Handles}，耗时={clock.Elapsed.TotalSeconds:F1} 秒。");
            Check(after.ManagedBytes - before.ManagedBytes < 16 * 1024 * 1024, "Managed heap does not retain sixteen MiB after short offline stress");
            Check(after.Handles <= phases["processCycles100"].Handles + 8, "Second equal process batch does not retain eight additional handles");
        }
        finally
        {
            core.Stop();
            File.WriteAllBytes(AppPaths.ConfigFile, savedConfig);
            RestoreLogSecrets(window);
            if (timerEnabled) timer.Start();
        }
    }
}
