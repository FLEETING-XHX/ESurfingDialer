using System.Diagnostics;

namespace ESurfingDialerLite;

public static class ServiceLinks
{
    public const string RechargeUrl = "https://gd.189.cn/service/pay/czPay/index.html?utm_ch=gd_wt&utm_as=cx&utm_bt=czjf";
    public const string ChangePasswordUrl = "http://enet.10000.gd.cn:10001/qs/password.jsp?wlanacip=&wlanuserip=";
    public const string GitHubUrl = "https://github.com/FLEETING-XHX/ESurfingDialer";

    public static bool TryOpen(string url, Action<ProcessStartInfo>? browserLauncher = null)
    {
        try
        {
            var startInfo = new ProcessStartInfo(url) { UseShellExecute = true };
            if (browserLauncher is null)
            {
                using var process = Process.Start(startInfo);
            }
            else
            {
                browserLauncher(startInfo);
            }
            return true;
        }
        catch (Exception)
        {
            return false;
        }
    }
}
