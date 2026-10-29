namespace ESurfingDialerLite;

public partial class MainWindow
{
    private async Task SaveAccountAsync()
    {
        if (_busy || _accountOperation || _disposed) return;
        var username = UserTextBox.Text.Trim();
        if (username.Length == 0 || PasswordBox.Password.Length == 0)
        {
            AccountErrorText.Text = "请填写校园网账号和密码。";
            return;
        }
        if (_config.Accounts.Any(a => a.Id != _editingId && a.UserName == username))
        {
            AccountErrorText.Text = "这个账号已经添加，请直接编辑已有账户。";
            return;
        }
        try
        {
            var candidate = _config.Copy();
            var account = candidate.Accounts.FirstOrDefault(a => a.Id == _editingId) ?? new CampusAccount();
            var isCurrent = candidate.SelectedAccountId == account.Id;
            var reconnect = isCurrent && _dialer.IsRequested;
            var stopConnection = isCurrent && (_dialer.IsRequested || _dialer.IsRunning);
            account.UserName = username;
            account.Name = AccountNameTextBox.Text.Trim();
            account.ProtectedPassword = PasswordProtector.Protect(PasswordBox.Password);
            if (!candidate.Accounts.Contains(account)) candidate.Accounts.Add(account);
            candidate.SelectedAccountId ??= account.Id;
            if (!await ApplyAccountChangeAsync(candidate, stopConnection)) return;
            ShowAccountList();
            Notify("账户已保存");
            if (reconnect && !_disposed) await StartDialerAsync();
        }
        catch (Exception ex) { AccountErrorText.Text = "保存失败：" + ex.Message; }
    }

    private async Task SelectAccountAsync(string id)
    {
        if (_busy || _accountOperation || _disposed) return;
        if (!_config.Accounts.Any(a => a.Id == id)) return;
        if (_config.SelectedAccountId == id) { CloseAccounts(); return; }
        var reconnect = _dialer.IsRequested;
        try
        {
            var candidate = _config.Copy();
            candidate.SelectedAccountId = id;
            if (!await ApplyAccountChangeAsync(candidate, reconnect || _dialer.IsRunning)) return;
            RefreshAccounts();
            CloseAccounts();
            AddSummary("账户已切换", "当前校园网账户已更新。", "Offline");
            if (reconnect && !_disposed) await StartDialerAsync();
        }
        catch (Exception ex) { Notify("切换失败：" + ex.Message); }
    }

    private async Task DeleteAccountAsync(string id)
    {
        if (_busy || _accountOperation || _disposed || !_config.Accounts.Any(a => a.Id == id)) return;
        try
        {
            var candidate = _config.Copy();
            candidate.Accounts.RemoveAll(a => a.Id == id);
            var current = candidate.SelectedAccountId == id;
            if (current) candidate.SelectedAccountId = candidate.Accounts.FirstOrDefault()?.Id;
            if (!await ApplyAccountChangeAsync(candidate, current && (_dialer.IsRequested || _dialer.IsRunning))) return;
            RefreshAccounts();
            Notify("账户已删除");
        }
        catch (Exception ex) { Notify("删除失败：" + ex.Message); }
    }

    private async Task<bool> ApplyAccountChangeAsync(ClientConfig candidate, bool stopConnection)
    {
        _accountOperation = true;
        AccountSheet.IsEnabled = CampusConnectionButton.IsEnabled = false;
        var stopped = false;
        try
        {
            using var pending = _configStore.PrepareSave(candidate);
            if (_disposed) return false;
            if (stopConnection)
            {
                if (!await StopConnectionAsync()) return false;
                stopped = true;
            }
            if (_disposed) return false;
            pending.Commit();
            _config = candidate;
            return true;
        }
        catch (Exception ex)
        {
            throw new InvalidOperationException(stopped
                ? "连接已停止，原账户已保留，请手动重新连接。原因：" + ex.Message
                : "原账户和连接状态已保留。原因：" + ex.Message, ex);
        }
        finally
        {
            _accountOperation = false;
            AccountSheet.IsEnabled = CampusConnectionButton.IsEnabled = !_disposed;
        }
    }
}
