using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Windows.Threading;
using ESurfingDialerLite;

internal static partial class Program
{
    private static readonly ConcurrentDictionary<int, DateTime> OwnedWorkers = new();
    private static Exception? _dispatcherFailure;

    private static int RunHarness(string[] args)
    {
        Exception? failure = null;
        var previousContext = SynchronizationContext.Current;
        Console.OutputEncoding = new UTF8Encoding(false);
        try
        {
            Run(args);
            if (_dispatcherFailure != null) System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(_dispatcherFailure).Throw();
        }
        catch (Exception ex) { failure = ex; }
        finally
        {
            SynchronizationContext.SetSynchronizationContext(previousContext);
            try { CleanOwnedWorkers(); }
            catch (Exception ex) { failure = failure == null ? ex : new AggregateException(failure, ex); }
        }
        if (failure == null) return 0;
        var text = "客户端回归失败（不会计为通过）：" + Environment.NewLine + ClientLog.Redact(failure.ToString());
        Console.Error.WriteLine(text);
        try
        {
            var output = Path.GetFullPath(args.FirstOrDefault(a => !a.StartsWith("--", StringComparison.Ordinal)) ?? "artifacts/ui");
            Directory.CreateDirectory(output);
            File.WriteAllText(Path.Combine(output, "failure.log"), text, new UTF8Encoding(false));
        }
        catch (Exception reportError) { Console.Error.WriteLine("失败日志无法写入：" + reportError.GetType().Name); }
        return 1;
    }

    private static void CleanOwnedWorkers()
    {
        List<Exception> failures = [];
        foreach (var worker in OwnedWorkers)
        {
            try
            {
                using var process = Process.GetProcessById(worker.Key);
                if (process.StartTime.ToUniversalTime() != worker.Value) continue;
                if (!process.HasExited)
                {
                    process.Kill(entireProcessTree: true);
                    if (!process.WaitForExit(3000)) throw new TimeoutException("测试子进程清理超时。");
                }
            }
            catch (ArgumentException) { }
            catch (InvalidOperationException) { }
            catch (Exception ex) { failures.Add(ex); }
            finally { OwnedWorkers.TryRemove(worker.Key, out _); }
        }
        if (failures.Count > 0) throw new AggregateException("测试子进程清理失败。", failures);
    }

    private static void FailureFixture(string[] args)
    {
        var output = Path.GetFullPath(args.First(a => !a.StartsWith("--", StringComparison.Ordinal)));
        Directory.CreateDirectory(output);
        var worker = Worker("--idle");
        File.WriteAllText(Path.Combine(output, "worker.pid"), worker.Id.ToString());
        if (args.Contains("--dispatcher-failure-fixture"))
        {
            var app = new System.Windows.Application();
            var frame = new DispatcherFrame();
            app.DispatcherUnhandledException += (_, e) =>
            {
                _dispatcherFailure = e.Exception;
                e.Handled = true;
                frame.Continue = false;
            };
            app.Dispatcher.BeginInvoke(() => throw new InvalidOperationException("synthetic dispatcher failure token=fixture-ticket"));
            try { Dispatcher.PushFrame(frame); }
            finally { app.Shutdown(); }
            if (_dispatcherFailure == null) throw new Exception("Expected dispatcher failure did not occur");
            System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(_dispatcherFailure).Throw();
        }
        throw new InvalidOperationException("synthetic managed failure token=fixture-ticket");
    }

    private static void HarnessChecks(string output)
    {
        foreach (var mode in new[] { "--failure-fixture", "--dispatcher-failure-fixture" })
        {
            var directory = Path.Combine(output, mode.TrimStart('-'));
            using var process = Worker(directory, mode);
            try
            {
                Check(process.WaitForExit(10000) && process.ExitCode == 1, "Test fixture reports failure with nonzero exit: " + mode);
                var report = File.ReadAllText(Path.Combine(directory, "failure.log"));
                Check(process.StandardError.ReadToEnd().Contains("客户端回归失败"), "Failure is reported to stderr in Chinese: " + mode);
                Check(report.Contains("InvalidOperationException") && report.Contains("Program.")
                    && !report.Contains("fixture-ticket"), "Failure report preserves stack and redacts token: " + mode);
                var pid = int.Parse(File.ReadAllText(Path.Combine(directory, "worker.pid")));
                var alive = false;
                try { using var child = Process.GetProcessById(pid); alive = !child.HasExited; }
                catch (ArgumentException) { }
                Check(!alive, "Failed test fixture cleans its own worker: " + mode);
            }
            finally { if (!process.HasExited) { process.Kill(entireProcessTree: true); process.WaitForExit(); } }
        }
    }
}
