namespace ESurfingDialerLite;

public sealed class ClientConfig
{
    public string UserName { get; set; } = "";

    public string ProtectedPassword { get; set; } = "";

    public bool AutoStart { get; set; }

    public bool StartMinimized { get; set; }

    public bool AutoConnect { get; set; } = true;
}
