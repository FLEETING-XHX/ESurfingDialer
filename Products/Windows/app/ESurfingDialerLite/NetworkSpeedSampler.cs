using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Windows;
using System.Windows.Media;
using Point = System.Windows.Point;

namespace ESurfingDialerLite;

public sealed class NetworkSpeedSampler
{
    private Dictionary<string, (long Received, long Sent)> _previous = [];
    private long _lastTick;
    private readonly Queue<(double Download, double Upload)> _history = new();
    public void Reset() { _previous.Clear(); _lastTick = 0; _history.Clear(); }
    public (double Download, double Upload)? Sample()
    {
        try
        {
            var current = new Dictionary<string, (long Received, long Sent)>();
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up
                         && n.NetworkInterfaceType is NetworkInterfaceType.Ethernet or NetworkInterfaceType.Wireless80211))
            {
                var stats = nic.GetIPStatistics();
                current[nic.Id] = (stats.BytesReceived, stats.BytesSent);
            }
            var tick = Stopwatch.GetTimestamp();
            var seconds = _lastTick == 0 ? 0 : (tick - _lastTick) / (double)Stopwatch.Frequency;
            double down = 0, up = 0;
            foreach (var (id, value) in current)
                if (_previous.TryGetValue(id, out var old))
                {
                    down += Math.Max(0, value.Received - old.Received);
                    up += Math.Max(0, value.Sent - old.Sent);
                }
            _previous = current;
            _lastTick = tick;
            if (seconds <= 0 || current.Count == 0) return null;
            var sample = (down / seconds, up / seconds);
            _history.Enqueue(sample);
            while (_history.Count > 60) _history.Dequeue();
            return sample;
        }
        catch (NetworkInformationException) { Reset(); return null; }
    }
    public PointCollection Points(bool download, double width, double height)
    {
        var points = new PointCollection();
        var samples = _history.ToArray();
        if (samples.Length < 2) return new PointCollection { new(0, height - 2), new(width, height - 2) };
        var maximum = Math.Max(1024, samples.Select(s => Math.Max(s.Download, s.Upload)).DefaultIfEmpty().Max());
        for (var i = 0; i < samples.Length; i++)
            points.Add(new Point(i * width / 59, height - 2 - (download ? samples[i].Download : samples[i].Upload) / maximum * Math.Max(1, height - 4)));
        return points;
    }
    public static string Format(double bytes) => bytes >= 1024 * 1024 ? $"{bytes / 1024 / 1024:F2} MB/s" : $"{bytes / 1024:F1} KB/s";
}
