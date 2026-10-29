using System.IO;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using ESurfingDialerLite;

internal static partial class Program
{
    private static void DailySettingsChecks(MainWindow window, string output)
    {
        const BindingFlags flags = BindingFlags.Instance | BindingFlags.NonPublic;
        var field = typeof(MainWindow).GetField("_config", flags)!;
        var previous = (ClientConfig)field.GetValue(window)!;
        var saved = File.ReadAllBytes(AppPaths.ConfigFile);
        void Call(string name) => typeof(MainWindow).GetMethod(name, flags)!.Invoke(window,
            new object[] { window, new RoutedEventArgs() });
        var start = (TextBox)window.FindName("ReauthStartTextBox");
        var end = (TextBox)window.FindName("ReauthEndTextBox");
        var toggle = (CheckBox)window.FindName("AutoReauthCheckBox");
        var error = (TextBlock)window.FindName("DailyReauthValidationText");
        try
        {
            var candidate = new ClientConfig { AutoConnect = false };
            field.SetValue(window, candidate);
            typeof(MainWindow).GetMethod("ApplySettings", flags)!.Invoke(window, null);
            Check(toggle.IsChecked == true && start.Text == "04" && end.Text == "06", "Daily reauth defaults enabled at 04-06");
            start.Text = "08"; end.Text = "06";
            Call("SaveDailyReauth_Click");
            Check(error.Text.Contains("结束时间不能早于") && File.ReadAllBytes(AppPaths.ConfigFile).SequenceEqual(saved),
                "Reversed window is rejected inline without changing saved configuration");
            start.Text = "ab"; Call("SaveDailyReauth_Click");
            Check(error.Text.Contains("数字"), "Nonnumeric window input has an actionable inline error");
            start.Text = "04"; end.Text = "04"; Call("SaveDailyReauth_Click");
            var config = new ConfigStore().Load();
            Check(config.ReauthStartHour == 4 && config.ReauthEndHour == 4, "Equal endpoints persist a fixed-time schedule");
            toggle.IsChecked = false; Call("DailyReauthToggle_Click");
            Check(!new ConfigStore().Load().AutoReauthEnabled
                && !((FrameworkElement)window.FindName("DailyReauthTimeFields")).IsEnabled,
                "Daily switch persists off and disables time editing independently of enhanced connection");
            toggle.IsChecked = true; Call("DailyReauthToggle_Click");
            start.Text = "00"; end.Text = "24"; Call("SaveDailyReauth_Click");
            Check(new ConfigStore().Load().ReauthEndHour == 24, "End of day 24:00 is accepted");
            start.Text = "04"; end.Text = "06"; Call("SaveDailyReauth_Click");
            typeof(MainWindow).GetMethod("ShowPage", flags)!.Invoke(window, new[] { window.FindName("SettingsPage") });
            var button = (Button)window.FindName("SaveDailyReauthButton");
            button.BringIntoView(); window.UpdateLayout(); DrainUiQueue();
            var scroll = (ScrollViewer)window.FindName("SettingsScrollViewer");
            scroll.ScrollToVerticalOffset(scroll.VerticalOffset + 160); window.UpdateLayout(); DrainUiQueue();
            RenderPenPage(window, output, "settings-daily-reauth");
            Check(button.ActualWidth > 0 && start.ActualWidth > 0 && end.ActualWidth > 0,
                "Daily settings render usable time fields and save control");
        }
        finally
        {
            File.WriteAllBytes(AppPaths.ConfigFile, saved); field.SetValue(window, previous);
            typeof(MainWindow).GetMethod("ApplySettings", flags)!.Invoke(window, null);
        }
    }
}
