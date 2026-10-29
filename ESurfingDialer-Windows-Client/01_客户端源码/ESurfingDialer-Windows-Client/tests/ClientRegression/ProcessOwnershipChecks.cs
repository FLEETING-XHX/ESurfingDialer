using System.Diagnostics;
using System.IO;
using System.Reflection;
using ESurfingDialerLite;

internal static partial class Program
{
    // Child-only modes use synthetic workers and never load the authentication core.
    private static bool RunProcessOwnershipFixture(string[] args)
    {
        if (args.Length == 2 && args[0] == "--process-owner-fixture")
        {
            using var job = new CoreProcessJob();
            using var child = Worker("--idle");
            job.Assign(child);
            File.WriteAllText(args[1], child.Id.ToString());
            Console.ReadLine();
            return true;
        }
        if (args.Length == 3 && args[0] == "--process-control-fixture")
        {
            while (Console.ReadLine() is { } command)
            {
                File.AppendAllText(args[1], command + Environment.NewLine);
                if (args[2] == "conflict") Environment.Exit(73);
                if (command == "stop") return true;
            }
            if (args[2] == "keep-on-eof") Thread.Sleep(Timeout.Infinite);
            return true;
        }
        return false;
    }

    private static void ProcessOwnershipChecks()
    {
        var directory = Path.Combine(AppPaths.AppDataDirectory, "process-ownership");
        Directory.CreateDirectory(directory);
        var ownerPidFile = Path.Combine(directory, "job-child.pid");
        if (File.Exists(ownerPidFile)) File.Delete(ownerPidFile);
        using (var owner = Worker("--process-owner-fixture", ownerPidFile))
        {
            Process? observed = null;
            try
            {
                Check(SpinWait.SpinUntil(() => File.Exists(ownerPidFile), 5000), "Job ownership fixture publishes its child PID");
                observed = Process.GetProcessById(int.Parse(File.ReadAllText(ownerPidFile)));
                RegisterWorker(observed);
                Check(!observed.HasExited, "Supervised fixture child remains alive while its owner lives");
                owner.Kill(); // Deliberately kill only the parent; the job must reclaim its child.
                Check(owner.WaitForExit(3000) && observed.WaitForExit(3000),
                    "Abrupt parent exit closes job handle and terminates its supervised child");
            }
            finally
            {
                if (!owner.HasExited) { owner.Kill(); owner.WaitForExit(3000); }
                if (observed is { HasExited: false }) { observed.Kill(); observed.WaitForExit(3000); }
                observed?.Dispose();
            }
        }

        void StartControl(Process process, string commands, string mode)
        {
            var info = new ProcessStartInfo("dotnet")
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true
            };
            info.ArgumentList.Add(Assembly.GetExecutingAssembly().Location);
            foreach (var arg in new[] { "--process-control-fixture", commands, mode }) info.ArgumentList.Add(arg);
            process.StartInfo = info;
            process.Start();
            RegisterWorker(process);
        }
        var config = new ClientConfig
        {
            UserName = "ownership-fixture-user", AutoReauthEnabled = true, ReauthStartHour = 4, ReauthEndHour = 6
        };
        var legacy = Path.Combine(AppPaths.DataDirectory, "health.json");
        Directory.CreateDirectory(AppPaths.DataDirectory);
        File.WriteAllText(legacy, "legacy-owner-marker");
        var commandsFile = Path.Combine(directory, "commands.log");
        if (File.Exists(commandsFile)) File.Delete(commandsFile);
        Dictionary<string, string?>? environment = null;
        var core = new DialerCoreProcess(process =>
        {
            environment = process.StartInfo.Environment.ToDictionary(p => p.Key, p => p.Value);
            StartControl(process, commandsFile, "normal");
        }, _ => TimeSpan.Zero);
        try
        {
            core.StartAsync(config, "ownership-fixture-password", _ => { }).GetAwaiter().GetResult();
            var firstHealth = AppPaths.HealthFile;
            Check(Path.GetFileName(firstHealth) == $"health-{core.InstanceId}.json"
                && firstHealth != legacy && File.ReadAllText(legacy) == "legacy-owner-marker",
                "Startup isolates current-instance health without deleting legacy owner health");
            Check(environment!["HEALTH_FILE_NAME"] == Path.GetFileName(firstHealth)
                && environment["CORE_INSTANCE_ID"] == core.InstanceId
                && environment["CORE_PARENT_PID"] == Environment.ProcessId.ToString(),
                "Core receives the supervised instance, parent PID and isolated health filename");
            Check(environment["AUTO_REAUTH_ENABLED"] == "1"
                && environment["AUTO_REAUTH_SAFE_WINDOW_START_HOUR"] == "4"
                && environment["AUTO_REAUTH_SAFE_WINDOW_END_HOUR"] == "6", "Daily settings reach the core environment");
            core.ConfigureDailyReauth(false, 5, 7);
            Check(SpinWait.SpinUntil(() => File.Exists(commandsFile) && File.ReadAllText(commandsFile).Contains("reauth off 5 7"), 3000),
                "Daily schedule can be disabled live without stopping the core");
            Check(core.IsRunning && core.IsRequested, "Disabling daily reauthentication preserves normal connection intent");
            var rejected = false;
            try { core.ConfigureDailyReauth(true, 6, 4); } catch (ArgumentException) { rejected = true; }
            Check(rejected && core.IsRunning, "Reverse daily window is rejected without affecting the live core");
            core.Stop();
            core.StartAsync(config, "ownership-fixture-password", _ => { }).GetAwaiter().GetResult();
            Check(AppPaths.HealthFile != firstHealth, "Every new core gets a different health publication path");
        }
        finally { core.Stop(); }

        Process? brokenPipeWorker = null;
        core = new DialerCoreProcess(process =>
        {
            StartControl(process, commandsFile, "keep-on-eof");
            brokenPipeWorker = process;
        }, _ => TimeSpan.Zero);
        try
        {
            Check(core.ConfigureDailyReauth(false, 4, 6), "Daily settings are accepted for the next launch when no core is running");
            core.StartAsync(config, "ownership-fixture-password", _ => { }).GetAwaiter().GetResult();
            brokenPipeWorker!.StandardInput.Close();
            Check(!core.ConfigureDailyReauth(false, 4, 6) && core.IsRunning,
                "A broken control pipe is reported instead of claiming daily settings reached a live core");
        }
        finally { core.Stop(); }

        var starts = 0;
        core = new DialerCoreProcess(process => { starts++; StartControl(process, commandsFile, "conflict"); }, _ => TimeSpan.Zero);
        try
        {
            core.StartAsync(config, "ownership-fixture-password", _ => { }).GetAwaiter().GetResult();
            core.RequestNetworkRecheck();
            Check(SpinWait.SpinUntil(() => core.CoreStateInUse, 5000), "State-directory lock refusal is surfaced by the supervisor");
            Check(starts == 1 && !core.IsRunning && !core.IsRequested && core.RecoveryExhausted,
                "State-directory conflict pauses rather than repeatedly restarting another core");
        }
        finally { core.Stop(); }
    }
}
