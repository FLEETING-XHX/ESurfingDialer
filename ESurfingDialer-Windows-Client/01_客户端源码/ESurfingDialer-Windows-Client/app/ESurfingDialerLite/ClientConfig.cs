namespace ESurfingDialerLite;

public sealed class ClientConfig
{
    public string UserName { get; set; } = "";

    public string ProtectedPassword { get; set; } = "";

    public bool AutoStart { get; set; }

    public bool StartMinimized { get; set; }

    public bool AutoConnect { get; set; } = true;

    public bool EnhancedConnection { get; set; } = true;

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
