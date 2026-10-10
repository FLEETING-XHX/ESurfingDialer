using System.Diagnostics;
using System.IO;
using System.Reflection;
using ESurfingDialerLite;

internal static partial class Program
{
    private static void StartFixture(Process process)
    {
        var info = new ProcessStartInfo("dotnet")
        {
            UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true,
            RedirectStandardOutput = true, RedirectStandardError = true
        };
        info.ArgumentList.Add(Assembly.GetExecutingAssembly().Location);
        info.ArgumentList.Add("--idle");
        process.StartInfo = info;
        process.Start();
        RegisterWorker(process);
    }

    private static bool ProcessExists(int pid)
    {
        try { using var process = Process.GetProcessById(pid); return !process.HasExited; }
        catch (ArgumentException) { return false; }
    }

    private static void ProcessBoundaryChecks()
    {
        var config = new ClientConfig { UserName = "fixture-process-user" };
        var secondObserver = 0;
        var core = new DialerCoreProcess(StartFixture, _ => TimeSpan.FromMilliseconds(10));
        core.RecoveryMessage += _ => throw new InvalidOperationException("synthetic observer failure");
        core.RecoveryMessage += _ => Interlocked.Increment(ref secondObserver);
        try
        {
            core.StartAsync(config, "fixture-password", _ => throw new IOException("synthetic log failure")).GetAwaiter().GetResult();
            Check(core.IsRunning && core.IsRequested, "Throwing startup log observer cannot kill a live core");
            Check(core.RestartForRecoveryAsync(config, "fixture-password", _ => throw new IOException("synthetic log failure")).GetAwaiter().GetResult()
                && core.IsRunning && !core.IsRecovering && secondObserver > 0, "Throwing recovery observer does not block later observers or restart");
            Task.WhenAll(Enumerable.Range(0, 4).Select(_ => Task.Run(core.Stop))).GetAwaiter().GetResult();
            Check(!core.IsRunning && !core.IsRequested && !core.IsRecovering, "Concurrent repeated stop leaves no core or recovery intent");
        }
        finally { core.Stop(); }

        var failedPid = 0;
        core = new DialerCoreProcess(process => { StartFixture(process); failedPid = process.Id; throw new IOException("synthetic post-start failure"); }, null);
        try
        {
            try { core.StartAsync(config, "fixture-password", _ => { }).GetAwaiter().GetResult(); }
            catch (IOException) { }
            Check(failedPid > 0 && !ProcessExists(failedPid) && !core.IsRunning && !core.IsRequested,
                "Post-start failure terminates owned process and clears intent");
        }
        finally { core.Stop(); }

        var starts = 0;
        core = new DialerCoreProcess(process => { starts++; StartFixture(process); throw new IOException("synthetic post-start failure"); },
            null, _ => throw new IOException("synthetic termination failure"));
        try
        {
            try { core.StartAsync(config, "fixture-password", _ => { }).GetAwaiter().GetResult(); }
            catch (AggregateException) { }
            Check(core.IsRunning && !core.IsRequested, "Failed termination retains ownership of live process");
            try { core.StartAsync(config, "fixture-password", _ => { }).GetAwaiter().GetResult(); }
            catch (InvalidOperationException) { }
            Check(starts == 1, "Uncleared old core blocks duplicate startup");
        }
        finally { core.Stop(); }
        Check(!core.IsRunning, "Retained process can still be stopped cleanly");

        var fail = false;
        core = new DialerCoreProcess(process => { if (fail) throw new IOException("synthetic startup failure"); StartFixture(process); },
            _ => TimeSpan.Zero);
        try
        {
            core.StartAsync(config, "fixture-password", _ => { }).GetAwaiter().GetResult();
            fail = true;
            Check(!core.RestartForRecoveryAsync(config, "fixture-password", _ => { }).GetAwaiter().GetResult()
                && core.RecoveryExhausted && !core.IsRequested, "Recovery exhaustion is observable instead of appearing as a manual stop");
            core.EnhancedConnection = false;
            Check(!core.RecoveryExhausted, "Disabling enhancement clears its recovery failure state");
            core.Stop();
            Check(!core.RecoveryExhausted, "Manual stop clears recovery failure state");
        }
        finally { core.Stop(); }
    }
}
