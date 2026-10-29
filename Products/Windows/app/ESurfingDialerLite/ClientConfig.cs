namespace ESurfingDialerLite;

public sealed class ClientConfig
{
    public string UserName { get; set; } = "";

    public string ProtectedPassword { get; set; } = "";

    public bool AutoStart { get; set; }

    public bool StartMinimized { get; set; }

    public bool AutoConnect { get; set; } = true;

    public bool EnhancedConnection { get; set; } = true;

    public bool AutoReauthEnabled { get; set; } = true;
    public int ReauthStartHour { get; set; } = 4;
    public int ReauthEndHour { get; set; } = 6;

    public void ValidateDailyReauth()
    {
        if (ReauthStartHour is < 0 or > 23 || ReauthEndHour is < 0 or > 24)
            throw new InvalidOperationException("重登时间范围为开始 00～23 点、结束 00～24 点。");
        if (ReauthEndHour < ReauthStartHour)
            throw new InvalidOperationException("结束时间不能早于起始时间。");
    }

    public bool BackgroundTips { get; set; } = true;

    public string? SelectedAccountId { get; set; }

    public List<CampusAccount> Accounts { get; set; } = [];

    [System.Text.Json.Serialization.JsonIgnore]
    public CampusAccount? CurrentAccount => Accounts.FirstOrDefault(a => a.Id == SelectedAccountId);

    public void NormalizeAccounts()
    {
        Accounts ??= [];
        if (Accounts.Count == 0 && !string.IsNullOrWhiteSpace(UserName))
            Accounts.Add(new CampusAccount { UserName = UserName, ProtectedPassword = ProtectedPassword });
        if (CurrentAccount == null) SelectedAccountId = Accounts.FirstOrDefault()?.Id;
        SynchronizeSelectedAccount();
    }

    public void SynchronizeSelectedAccount()
    {
        UserName = CurrentAccount?.UserName ?? "";
        ProtectedPassword = CurrentAccount?.ProtectedPassword ?? "";
    }

    internal ClientConfig Copy() => new()
    {
        UserName = UserName, ProtectedPassword = ProtectedPassword,
        AutoStart = AutoStart, StartMinimized = StartMinimized, AutoConnect = AutoConnect,
        EnhancedConnection = EnhancedConnection, BackgroundTips = BackgroundTips, SelectedAccountId = SelectedAccountId,
        AutoReauthEnabled = AutoReauthEnabled, ReauthStartHour = ReauthStartHour, ReauthEndHour = ReauthEndHour,
        Accounts = Accounts.Select(a => new CampusAccount
        {
            Id = a.Id, Name = a.Name, UserName = a.UserName, ProtectedPassword = a.ProtectedPassword
        }).ToList()
    };
}

public sealed class CampusAccount
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "";
    public string UserName { get; set; } = "";
    public string ProtectedPassword { get; set; } = "";
    [System.Text.Json.Serialization.JsonIgnore]
    public string DisplayName => string.IsNullOrWhiteSpace(Name) ? UserName : Name;
}
