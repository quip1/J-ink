using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Ferry.Core;
using InTheHand.Net;
using InTheHand.Net.Sockets;
using Microsoft.Win32;

namespace FerryWin;

public sealed class MainForm : Form
{
    readonly Settings _s = Settings.Load();
    readonly Receiver _rx;
    readonly NotifyIcon _tray;
    readonly SemaphoreSlim _sendLock = new(1, 1);
    bool _exiting;
    readonly bool _startHidden;

    // UI
    readonly Label _status = new() { AutoSize = true, ForeColor = Color.FromArgb(60, 60, 60) };
    readonly LinkLabel _nameLink = new() { AutoSize = true };
    readonly CheckBox _receiving = new() { Text = "Receive files", AutoSize = true };
    readonly FlowLayoutPanel _devices = new() { FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, Dock = DockStyle.Fill };
    readonly Panel _pendingBar = new() { Height = 40, Dock = DockStyle.Top, Visible = false, BackColor = Color.FromArgb(255, 248, 220), Padding = new Padding(10, 8, 10, 8) };
    readonly Label _pendingText = new() { AutoSize = true };
    readonly Label _progressText = new() { AutoSize = true, Visible = false };
    readonly ProgressBar _progress = new() { Dock = DockStyle.Top, Height = 14, Visible = false, Maximum = 1000 };
    readonly ListBox _history = new() { Dock = DockStyle.Fill, IntegralHeight = false, BorderStyle = BorderStyle.None };

    List<string> _pending = [];
    readonly Dictionary<string, (bool ok, string label)> _peerState = new();
    int _probeGen;

    public MainForm(string[] args)
    {
        _startHidden = args.Contains("--tray");
        Text = "Ferry";
        Font = new Font("Segoe UI", 10f);
        BackColor = Color.White;
        MinimumSize = new Size(520, 560);
        Size = new Size(600, 720);
        StartPosition = FormStartPosition.CenterScreen;
        AllowDrop = true;
        Icon = AppIcon.Get();

        _tray = new NotifyIcon { Icon = Icon, Text = "Ferry", Visible = true };
        _tray.DoubleClick += (_, _) => ShowMain();
        _tray.BalloonTipClicked += (_, _) => ShowMain();
        var trayMenu = new ContextMenuStrip();
        trayMenu.Items.Add("Open Ferry", null, (_, _) => ShowMain());
        trayMenu.Items.Add("Open received folders", null, (_, _) => OpenReceived());
        trayMenu.Items.Add(new ToolStripSeparator());
        trayMenu.Items.Add("Quit", null, (_, _) => { _exiting = true; Close(); });
        _tray.ContextMenuStrip = trayMenu;

        _rx = new Receiver(addr => new WinHost(this, addr), SetStatusSafe, e => UiLog("✕ " + e));

        BuildLayout();
        QueueFiles(args.Where(a => !a.StartsWith("--")));

        DragEnter += (_, e) => e.Effect = e.Data?.GetDataPresent(DataFormats.FileDrop) == true ? DragDropEffects.Copy : DragDropEffects.None;
        DragDrop += (_, e) => { if (e.Data?.GetData(DataFormats.FileDrop) is string[] f) QueueFiles(f); };

        Load += (_, _) =>
        {
            if (_s.Receiving) StartReceiving(); else SetStatus("Receiving is off");
            RefreshDevices(probeAll: true);
            RenderHistory();
        };
        Shown += (_, _) => { if (_startHidden && _pending.Count == 0) Hide(); };
    }

    protected override void SetVisibleCore(bool value)
    {
        // Start straight into the tray when launched at login.
        if (_startHidden && !IsHandleCreated) { CreateHandle(); value = false; }
        base.SetVisibleCore(value);
    }

    // ------------------------------------------------------------------ layout

    void BuildLayout()
    {
        var root = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 1, Padding = new Padding(16), BackColor = Color.White };
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));       // header
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));       // pending
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));       // progress
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 60));    // devices
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 40));    // recent
        root.RowStyles.Add(new RowStyle(SizeType.AutoSize));       // footer

        // header
        var header = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, Dock = DockStyle.Top, WrapContents = false };
        header.Controls.Add(new Label { Text = "Ferry", Font = new Font("Segoe UI Semibold", 20f), AutoSize = true, Margin = new Padding(0) });
        UpdateNameLink();
        _nameLink.LinkClicked += (_, _) => Rename();
        header.Controls.Add(_nameLink);
        var rxRow = new FlowLayoutPanel { AutoSize = true, WrapContents = false, Margin = new Padding(0, 8, 0, 0) };
        _receiving.Checked = _s.Receiving;
        _receiving.CheckedChanged += (_, _) =>
        {
            _s.Receiving = _receiving.Checked; _s.Save();
            if (_s.Receiving) StartReceiving(); else { _rx.Stop(); SetStatus("Receiving is off"); }
        };
        rxRow.Controls.Add(_receiving);
        rxRow.Controls.Add(_status);
        _status.Margin = new Padding(12, 5, 0, 0);
        header.Controls.Add(rxRow);
        root.Controls.Add(header);

        // pending files bar
        var cancel = new LinkLabel { Text = "Cancel", AutoSize = true, Dock = DockStyle.Right };
        cancel.LinkClicked += (_, _) => { _pending = []; RenderPending(); };
        _pendingBar.Controls.Add(_pendingText);
        _pendingBar.Controls.Add(cancel);
        root.Controls.Add(_pendingBar);

        var prog = new Panel { AutoSize = true, Dock = DockStyle.Top, Padding = new Padding(0, 6, 0, 6) };
        prog.Controls.Add(_progress);
        prog.Controls.Add(_progressText);
        _progressText.Dock = DockStyle.Top;
        root.Controls.Add(prog);

        // devices
        var devBox = new Panel { Dock = DockStyle.Fill };
        var devHead = new Panel { Dock = DockStyle.Top, Height = 34 };
        devHead.Controls.Add(new Label { Text = "Devices", Font = new Font("Segoe UI Semibold", 12f), AutoSize = true, Location = new Point(0, 6) });
        var refresh = new Button { Text = "Refresh", AutoSize = true, Dock = DockStyle.Right, FlatStyle = FlatStyle.System };
        refresh.Click += (_, _) => RefreshDevices(probeAll: true);
        devHead.Controls.Add(refresh);
        devBox.Controls.Add(_devices);
        devBox.Controls.Add(devHead);
        _devices.SizeChanged += (_, _) => { foreach (Control c in _devices.Controls) c.Width = _devices.ClientSize.Width - 8; };
        root.Controls.Add(devBox);

        // recent
        var recBox = new Panel { Dock = DockStyle.Fill, Padding = new Padding(0, 8, 0, 0) };
        recBox.Controls.Add(_history);
        recBox.Controls.Add(new Label { Text = "Recent", Font = new Font("Segoe UI Semibold", 12f), AutoSize = true, Dock = DockStyle.Top });
        root.Controls.Add(recBox);

        // footer
        var foot = new FlowLayoutPanel { AutoSize = true, Dock = DockStyle.Top, WrapContents = true, Margin = new Padding(0, 8, 0, 0) };
        var startup = new CheckBox { Text = "Start with Windows", AutoSize = true, Checked = Integration.StartsWithWindows() };
        startup.CheckedChanged += (_, _) => Integration.SetStartWithWindows(startup.Checked);
        var sendTo = new CheckBox { Text = "Show in Explorer's “Send to” menu", AutoSize = true, Checked = Integration.InSendTo() };
        sendTo.CheckedChanged += (_, _) => { try { Integration.SetSendTo(sendTo.Checked); } catch (Exception e) { MessageBox.Show(this, e.Message, "Ferry"); } };
        var trusted = new Button { Text = "Trusted devices…", AutoSize = true, FlatStyle = FlatStyle.System };
        trusted.Click += (_, _) => ShowTrusted();
        var open = new Button { Text = "Open received folders", AutoSize = true, FlatStyle = FlatStyle.System };
        open.Click += (_, _) => OpenReceived();
        foot.Controls.AddRange([startup, sendTo, trusted, open]);
        root.Controls.Add(foot);

        Controls.Add(root);
    }

    // ------------------------------------------------------------------ devices

    void RefreshDevices(bool probeAll)
    {
        int gen = Interlocked.Increment(ref _probeGen);
        var cands = Bt.Candidates();
        RenderDevices(cands);
        if (cands.Count == 0) return;
        // Probe two at a time: Windows serialises paging anyway, and this keeps the UI responsive.
        Task.Run(() =>
        {
            Parallel.ForEach(cands, new ParallelOptions { MaxDegreeOfParallelism = 2 }, d =>
            {
                if (gen != _probeGen) return;
                var key = Bt.Key(d.DeviceAddress);
                bool known = _s.IsKnown(key);
                if (!known && !probeAll) return;
                SetPeer(key, _peerState.TryGetValue(key, out var st) && st.ok ? st : (false, "checking…"), cands);
                try
                {
                    var h = Bt.Probe(d.DeviceAddress, _s);
                    SetPeer(key, (true, $"ready · Ferry on {h.Os}" + (h.TrustedByThem ? " · trusts this PC" : " · will ask once")), cands);
                }
                catch
                {
                    SetPeer(key, (false, _s.IsKnown(key) ? "not reachable right now" : "no Ferry"), cands);
                }
            });
        });
    }

    void SetPeer(string key, (bool ok, string label) st, List<BluetoothDeviceInfo> cands)
    {
        lock (_peerState) _peerState[key] = st;
        BeginInvokeSafe(() => RenderDevices(cands));
    }

    void RenderDevices(List<BluetoothDeviceInfo> cands)
    {
        _devices.SuspendLayout();
        _devices.Controls.Clear();
        if (!Bt.RadioAvailable())
            _devices.Controls.Add(Note("Bluetooth is off or this PC has no Bluetooth radio."));
        var ferry = new List<BluetoothDeviceInfo>();
        var other = new List<string>();
        foreach (var d in cands)
        {
            var key = Bt.Key(d.DeviceAddress);
            (bool ok, string label) st;
            lock (_peerState) _peerState.TryGetValue(key, out st);
            if (_s.IsKnown(key) || st.ok) ferry.Add(d);
            else other.Add(d.DeviceName + (st.label is { } l && l.Length > 0 ? $" ({l})" : ""));
        }
        if (ferry.Count == 0)
            _devices.Controls.Add(Note(cands.Count == 0
                ? "No paired devices. Pair your Boox once in Windows Bluetooth settings, then open Ferry on it."
                : "Looking for Ferry on your paired devices…"));
        foreach (var d in ferry) _devices.Controls.Add(DeviceCard(d));
        if (other.Count > 0) _devices.Controls.Add(Note("Other paired devices: " + string.Join(", ", other)));
        foreach (Control c in _devices.Controls) c.Width = _devices.ClientSize.Width - 8;
        _devices.ResumeLayout();
    }

    Label Note(string t) => new() { Text = t, AutoSize = false, Height = 44, ForeColor = Color.DimGray, Padding = new Padding(2, 6, 0, 0) };

    Control DeviceCard(BluetoothDeviceInfo d)
    {
        var key = Bt.Key(d.DeviceAddress);
        var k = _s.GetKnown(key);
        (bool ok, string label) st;
        lock (_peerState) _peerState.TryGetValue(key, out st);
        string sub = st.label ?? "known";
        if (!st.ok && k != null && k.Seen != default) sub += $" · seen {Ago(k.Seen)}";

        var card = new Panel { Height = 64, BackColor = Color.FromArgb(247, 247, 247), Margin = new Padding(0, 4, 0, 4), AllowDrop = true, Cursor = Cursors.Hand };
        var name = new Label { Text = k?.Name is { Length: > 0 } n ? n : d.DeviceName, Font = new Font("Segoe UI Semibold", 12f), AutoSize = true, Location = new Point(12, 8) };
        var subL = new Label { Text = sub, AutoSize = true, ForeColor = Color.DimGray, Location = new Point(13, 34) };
        var files = new Button { Text = "Send files…", AutoSize = true, FlatStyle = FlatStyle.System, Anchor = AnchorStyles.Right | AnchorStyles.Top };
        var folder = new Button { Text = "Folder…", AutoSize = true, FlatStyle = FlatStyle.System, Anchor = AnchorStyles.Right | AnchorStyles.Top };
        card.Controls.AddRange([name, subL, files, folder]);
        card.Layout += (_, _) =>
        {
            folder.Location = new Point(card.Width - folder.Width - 12, (card.Height - folder.Height) / 2);
            files.Location = new Point(folder.Left - files.Width - 6, folder.Top);
        };
        void SendPending() { if (_pending.Count > 0) { var p = _pending; _pending = []; RenderPending(); StartSend(d, p); } }
        card.Click += (_, _) => SendPending();
        name.Click += (_, _) => SendPending();
        subL.Click += (_, _) => SendPending();
        files.Click += (_, _) =>
        {
            if (_pending.Count > 0) { SendPending(); return; }
            using var ofd = new OpenFileDialog { Multiselect = true, Title = $"Send to {name.Text}" };
            if (ofd.ShowDialog(this) == DialogResult.OK) StartSend(d, ofd.FileNames.ToList());
        };
        folder.Click += (_, _) =>
        {
            using var fbd = new FolderBrowserDialog { Description = $"Send a folder to {name.Text}", UseDescriptionForTitle = true };
            if (fbd.ShowDialog(this) == DialogResult.OK) StartSend(d, [fbd.SelectedPath]);
        };
        card.DragEnter += (_, e) => e.Effect = e.Data?.GetDataPresent(DataFormats.FileDrop) == true ? DragDropEffects.Copy : DragDropEffects.None;
        card.DragDrop += (_, e) => { if (e.Data?.GetData(DataFormats.FileDrop) is string[] f) StartSend(d, f.ToList()); };
        return card;
    }

    static string Ago(DateTime t)
    {
        var s = DateTime.Now - t;
        return s.TotalMinutes < 1 ? "just now" : s.TotalHours < 1 ? $"{(int)s.TotalMinutes} min ago"
            : s.TotalDays < 1 ? $"{(int)s.TotalHours} h ago" : $"{(int)s.TotalDays} d ago";
    }

    // ------------------------------------------------------------------ sending

    public void QueueFiles(IEnumerable<string> paths)
    {
        var list = paths.Where(p => File.Exists(p) || Directory.Exists(p)).ToList();
        if (list.Count == 0) return;
        _pending = _pending.Concat(list).Distinct().ToList();
        RenderPending();
        ShowMain();
    }

    void RenderPending()
    {
        _pendingBar.Visible = _pending.Count > 0;
        _pendingText.Text = $"{_pending.Count} item{(_pending.Count == 1 ? "" : "s")} ready to send — click a device (or drop more here).";
    }

    static List<OutgoingFile> Expand(IEnumerable<string> paths)
    {
        var files = new List<OutgoingFile>();
        foreach (var p in paths)
        {
            if (File.Exists(p))
            {
                var fi = new FileInfo(p);
                files.Add(new OutgoingFile(fi.Name, fi.Length, () => File.OpenRead(fi.FullName)));
            }
            else if (Directory.Exists(p))
            {
                var root = new DirectoryInfo(p);
                foreach (var fi in root.EnumerateFiles("*", new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true }))
                {
                    var rel = root.Name + "/" + Path.GetRelativePath(root.FullName, fi.FullName).Replace('\\', '/');
                    files.Add(new OutgoingFile(rel, fi.Length, () => File.OpenRead(fi.FullName)));
                }
            }
        }
        return files;
    }

    const long Small = 256L * 1024, Big = 4L * 1024 * 1024;

    static string Rate(long bytes, long startTick)
    {
        double s = (Environment.TickCount64 - startTick) / 1000.0;
        if (s < 1) return "";
        double bps = bytes / s;
        return bps >= 1048576 ? $"{bps / 1048576:0.0} MB/s" : $"{bps / 1024:0} KB/s";
    }

    void StartSend(BluetoothDeviceInfo d, List<string> paths)
    {
        var label = d.DeviceName;
        Task.Run(async () =>
        {
            await _sendLock.WaitAsync();
            BluetoothClient? bt = null;
            FerryClient? client = null;
            IDisposable? joined = null;
            try
            {
                var files = Expand(paths);
                if (files.Count == 0) throw new IOException("nothing to send (empty folder?)");
                long total = files.Sum(f => f.Size);
                if (!Radios.EnsureBluetoothOn()) throw new IOException("Bluetooth is off and couldn't be turned on");
                if (total >= Big) { Progress("Waking Wi-Fi…", -1, ""); Radios.EnsureWifiOn(); }
                Progress($"Connecting to {label}…", -1, $"{files.Count} file(s), {Fmt(total)}");
                bt = Bt.Connect(d.DeviceAddress, 12000);
                client = new FerryClient(_s.Identity, bt.GetStream());
                var h = client.Hello("send", total);
                _s.Remember(Bt.Key(d.DeviceAddress), h);

                // fastest route: same network → Boox's Wi-Fi Direct → stay on Bluetooth
                if (h.Version >= 2 && total >= Small)
                {
                    Progress($"Finding the fastest route to {h.Name}…", -1, "trying Wi-Fi");
                    bool ok = client.TryAttach(h.Lan ?? [], Route.Lan);
                    if (!ok && total >= Big && h.CanHostP2p && Wlan.Available)
                    {
                        try
                        {
                            Progress($"Setting up Wi-Fi Direct with {h.Name}…", -1, "a few seconds");
                            var info = client.RequestP2p();
                            joined = Wlan.Join(info, m => Progress(m, -1, ""));
                            var until = Environment.TickCount64 + 20_000;
                            while (!ok && Environment.TickCount64 < until)
                            {
                                ok = client.TryAttach([new Endpoint(info.Ip, info.Port)], Route.P2p, 3500);
                                if (!ok) Thread.Sleep(500);
                            }
                        }
                        catch (Exception e)
                        {
                            _s.Log($"· Wi-Fi Direct with {h.Name} unavailable ({e.Message}); using Bluetooth");
                            joined?.Dispose(); joined = null;
                        }
                    }
                    if (ok) { bt.Dispose(); bt = null; }   // Bluetooth's job is done
                }
                if (client.Route == Route.Bt && total >= Big)
                    Balloon("Ferry: using Bluetooth (slow)", $"No Wi-Fi route to {h.Name}. Put both on the same Wi-Fi for full speed.");

                if (!h.TrustedByThem) Progress($"Waiting for {h.Name} to accept…", -1, "First time — tap Once or Always on the device");
                long last = 0, start = Environment.TickCount64;
                var route = client.Route;
                var saved = client.Send(files, (sent, tot, path, i, n) =>
                {
                    long now = Environment.TickCount64;
                    if (now - last < 250 && sent < tot) return;
                    last = now;
                    Progress($"To {h.Name}: {i + 1}/{n} {Path.GetFileName(path)}", tot > 0 ? (double)sent / tot : -1,
                        $"{Fmt(sent)} / {Fmt(tot)} · {route.Label()} {Rate(sent, start)}");
                });
                DoneProgress();
                var secs = (Environment.TickCount64 - start) / 1000.0;
                UiLog($"⇧ {saved.Count} to {h.Name} via {route.Label()} ({Fmt(total)} in {secs:0} s) → {Summarize(saved)}");
                Balloon($"Sent {saved.Count} to {h.Name}", $"via {route.Label()} · {Summarize(saved)}");
                lock (_peerState) _peerState[Bt.Key(d.DeviceAddress)] = (true, $"ready · Ferry on {h.Os} · trusts this PC");
                BeginInvokeSafe(() => RefreshDevices(false));
            }
            catch (Exception e)
            {
                DoneProgress();
                UiLog($"✕ Send to {label} failed: {e.Message}");
                Balloon("Ferry", $"Send to {label} failed: {e.Message}");
            }
            finally
            {
                client?.Close();
                try { bt?.Dispose(); } catch { }
                joined?.Dispose();
                _sendLock.Release();
            }
        });
    }

    // ------------------------------------------------------------------ receiving

    void StartReceiving()
    {
        _rx.Start();
        if (_rx.Running) SetStatus($"Ready to receive as “{_s.Name}”");
    }

    sealed class WinHost(MainForm f, string address) : IReceiveHost
    {
        long _last;
        public Identity Me => f._s.Identity;
        public bool IsTrusted(string id) => f._s.IsTrusted(id);
        public void RememberTrust(PeerHello p) => f._s.Trust(p.Id, p.Name);

        public TrustDecision AskTrust(PeerHello peer, int count, long total)
        {
            var result = TrustDecision.Decline;
            f.Balloon($"{peer.Name} wants to send {count} file(s)", "Open Ferry to accept.");
            f.Invoke(() =>
            {
                using var dlg = new TrustDialog(peer, count, Fmt(total));
                result = dlg.Ask(f.Visible ? f : null);
            });
            return result;
        }

        public IIncomingSink OpenSink(string path, long size) => new FileSink(Placement.Destination(path));

        long _start;
        Route _route = Route.Bt;
        public void Prepare(PeerHello p, long total)
        {
            if (address.Length > 0) f._s.Remember(address, p);
            if (total >= Big) Radios.EnsureWifiOn();
        }
        public List<Endpoint> LanEndpoints() =>
            f._rx.DataPort > 0 ? Wlan.LanAddresses().Select(ip => new Endpoint(ip, f._rx.DataPort)).ToList() : [];

        public void OnStart(PeerHello p, int count, long total, Route route)
        {
            _route = route; _start = Environment.TickCount64;
            f.Progress($"Receiving {count} from {p.Name} via {route.Label()}", 0, $"0 / {Fmt(total)}");
        }

        public void OnProgress(PeerHello p, long got, long total, string path)
        {
            long now = Environment.TickCount64;
            if (now - _last < 250 && got < total) return;
            _last = now;
            f.Progress($"From {p.Name}: {Path.GetFileName(path)}", total > 0 ? (double)got / total : -1,
                $"{Fmt(got)} / {Fmt(total)} · {_route.Label()} {Rate(got, _start)}");
        }

        public void OnFinished(PeerHello p, List<string> saved, Route route)
        {
            f.DoneProgress();
            f.UiLog($"⇩ {saved.Count} from {p.Name} via {route.Label()} → {Summarize(saved)}");
            f.Balloon($"Received {saved.Count} from {p.Name}", Summarize(saved));
        }
    }

    sealed class FileSink : IIncomingSink
    {
        readonly string _dest, _tmp;
        public Stream Stream { get; }
        public FileSink(string dest)
        {
            _dest = dest; _tmp = dest + ".ferrypart";
            Stream = new FileStream(_tmp, FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16);
        }
        public string Commit()
        {
            Stream.Dispose();
            var final = Placement.Unique(_dest);
            File.Move(_tmp, final);
            return final;
        }
        public void Abort() { try { Stream.Dispose(); File.Delete(_tmp); } catch { } }
    }

    // ------------------------------------------------------------------ misc UI

    void Progress(string title, double fraction, string detail) => BeginInvokeSafe(() =>
    {
        _progressText.Text = $"{title}   {detail}";
        _progressText.Visible = _progress.Visible = true;
        _progress.Style = fraction < 0 ? ProgressBarStyle.Marquee : ProgressBarStyle.Continuous;
        if (fraction >= 0) _progress.Value = Math.Clamp((int)(fraction * 1000), 0, 1000);
    });

    void DoneProgress() => BeginInvokeSafe(() => { _progressText.Visible = _progress.Visible = false; });

    void SetStatus(string t) => _status.Text = t;
    void SetStatusSafe(string t) => BeginInvokeSafe(() => SetStatus(t));

    void UiLog(string text)
    {
        _s.Log(text);
        BeginInvokeSafe(RenderHistory);
    }

    void RenderHistory()
    {
        _history.BeginUpdate();
        _history.Items.Clear();
        foreach (var h in _s.History.Take(30)) _history.Items.Add($"{h.Time:MMM d HH:mm}   {h.Text}");
        if (_history.Items.Count == 0) _history.Items.Add("Nothing yet.");
        _history.EndUpdate();
    }

    void Balloon(string title, string text)
    {
        BeginInvokeSafe(() => { _tray.BalloonTipTitle = title; _tray.BalloonTipText = text.Length > 0 ? text : " "; _tray.ShowBalloonTip(4000); });
    }

    void BeginInvokeSafe(Action a)
    {
        if (IsDisposed) return;
        if (!IsHandleCreated || !InvokeRequired) { if (IsHandleCreated) a(); else CreateHandleAndRun(a); return; }
        try { BeginInvoke(a); } catch (ObjectDisposedException) { } catch (InvalidOperationException) { }
    }

    void CreateHandleAndRun(Action a) { try { CreateHandle(); BeginInvoke(a); } catch { } }

    void UpdateNameLink() => _nameLink.Text = $"This PC appears as “{_s.Name}”  (rename)";

    void Rename()
    {
        var name = Prompt.Show(this, "Name shown to your other devices", _s.Name);
        if (string.IsNullOrWhiteSpace(name)) return;
        _s.Name = name.Trim(); _s.Save();
        UpdateNameLink();
        if (_rx.Running) SetStatus($"Ready to receive as “{_s.Name}”");
    }

    void ShowTrusted()
    {
        var list = _s.TrustedList();
        if (list.Count == 0) { MessageBox.Show(this, "No trusted devices yet. The first transfer from a device asks you; “Always” adds it here.", "Ferry"); return; }
        using var dlg = new Form { Text = "Trusted devices", Size = new Size(380, 320), StartPosition = FormStartPosition.CenterParent, Font = Font, MinimizeBox = false, MaximizeBox = false };
        var lb = new ListBox { Dock = DockStyle.Fill };
        foreach (var kv in list) lb.Items.Add(kv.Value);
        var forget = new Button { Text = "Forget selected", Dock = DockStyle.Bottom, Height = 36 };
        forget.Click += (_, _) =>
        {
            if (lb.SelectedIndex < 0) return;
            _s.Untrust(list[lb.SelectedIndex].Key);
            list.RemoveAt(lb.SelectedIndex); lb.Items.RemoveAt(lb.SelectedIndex);
        };
        dlg.Controls.Add(lb); dlg.Controls.Add(forget);
        dlg.ShowDialog(this);
    }

    static void OpenReceived()
    {
        var dir = Placement.RootFor(Placement.Category.Other);
        Directory.CreateDirectory(dir);
        Process.Start(new ProcessStartInfo("explorer.exe", $"\"{Path.GetDirectoryName(dir)}\"") { UseShellExecute = true });
    }

    void ShowMain()
    {
        BeginInvokeSafe(() =>
        {
            Show();
            if (WindowState == FormWindowState.Minimized) WindowState = FormWindowState.Normal;
            Activate(); BringToFront();
        });
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!_exiting && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true; Hide();
            if (_s.Receiving) Balloon("Ferry is still receiving", "It lives in the tray. Right-click the icon to quit.");
            return;
        }
        _rx.Stop();
        _tray.Visible = false;
        base.OnFormClosing(e);
    }

    public static string Fmt(long b) => b < 1024 ? $"{b} B" : b < 1 << 20 ? $"{b / 1024.0:0} KB"
        : b < 1L << 30 ? $"{b / 1048576.0:0.0} MB" : $"{b / 1073741824.0:0.00} GB";

    public static string Summarize(List<string> saved)
    {
        if (saved.Count == 0) return "nothing saved";
        if (saved.Count == 1) return saved[0];
        return string.Join(", ", saved
            .GroupBy(p => { var parts = p.Replace('\\', '/').Split('/'); return parts.Length > 1 ? string.Join("/", parts.Take(parts.Length - 1).TakeLast(2)) : "."; })
            .Select(g => $"{g.Key} ({g.Count()})"));
    }
}

static class Prompt
{
    public static string? Show(IWin32Window owner, string title, string value)
    {
        using var f = new Form { Text = title, Size = new Size(420, 150), StartPosition = FormStartPosition.CenterParent, FormBorderStyle = FormBorderStyle.FixedDialog, MinimizeBox = false, MaximizeBox = false, Font = new Font("Segoe UI", 10f) };
        var tb = new TextBox { Text = value, Location = new Point(12, 14), Width = 380 };
        var ok = new Button { Text = "Save", DialogResult = DialogResult.OK, Location = new Point(236, 52), Width = 75 };
        var cancel = new Button { Text = "Cancel", DialogResult = DialogResult.Cancel, Location = new Point(317, 52), Width = 75 };
        f.Controls.AddRange([tb, ok, cancel]);
        f.AcceptButton = ok; f.CancelButton = cancel;
        return f.ShowDialog(owner) == DialogResult.OK ? tb.Text : null;
    }
}

/// <summary>First-time request from an unknown device. Auto-declines after 60 s.</summary>
sealed class TrustDialog : Form
{
    TrustDecision _answer = TrustDecision.Decline;
    readonly System.Windows.Forms.Timer _timer = new() { Interval = 1000 };
    int _left = 60;

    public TrustDialog(PeerHello peer, int count, string size)
    {
        Text = "Ferry — new device";
        Font = new Font("Segoe UI", 10f);
        FormBorderStyle = FormBorderStyle.FixedDialog; MinimizeBox = false; MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen; TopMost = true; ShowInTaskbar = true;
        ClientSize = new Size(440, 170);
        Icon = AppIcon.Get();
        var head = new Label { Text = $"{peer.Name} wants to send {count} file{(count == 1 ? "" : "s")}", Font = new Font("Segoe UI Semibold", 12f), AutoSize = true, Location = new Point(16, 14) };
        var body = new Label { Text = $"{size} · from {peer.Os} · first time from this device.\n“Always” lets it send without asking from now on.", AutoSize = true, Location = new Point(16, 48) };
        var countdown = new Label { AutoSize = true, ForeColor = Color.DimGray, Location = new Point(16, 128) };
        Button B(string t, TrustDecision d, int x) { var b = new Button { Text = t, Width = 90, Height = 32, Location = new Point(x, 122) }; b.Click += (_, _) => { _answer = d; Close(); }; return b; }
        var always = B("Always", TrustDecision.Always, 334);
        Controls.AddRange([head, body, countdown, B("Decline", TrustDecision.Decline, 142), B("Once", TrustDecision.Once, 238), always]);
        AcceptButton = always;
        _timer.Tick += (_, _) => { if (--_left <= 0) { _answer = TrustDecision.Decline; Close(); } countdown.Text = $"{_left}s"; };
        countdown.Text = "60s";
    }

    public TrustDecision Ask(IWin32Window? owner)
    {
        _timer.Start();
        System.Media.SystemSounds.Asterisk.Play();
        if (owner != null) ShowDialog(owner); else ShowDialog();
        _timer.Stop();
        return _answer;
    }
}

static class Integration
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    static string Exe => Environment.ProcessPath ?? Application.ExecutablePath;
    static string SendToLink => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "Ferry (Bluetooth).lnk");

    public static bool StartsWithWindows()
    {
        using var k = Registry.CurrentUser.OpenSubKey(RunKey);
        return k?.GetValue("Ferry") is string;
    }

    public static void SetStartWithWindows(bool on)
    {
        using var k = Registry.CurrentUser.CreateSubKey(RunKey);
        if (on) k.SetValue("Ferry", $"\"{Exe}\" --tray"); else k.DeleteValue("Ferry", false);
    }

    public static bool InSendTo() => File.Exists(SendToLink);

    public static void SetSendTo(bool on)
    {
        if (!on) { File.Delete(SendToLink); return; }
        var t = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException("Windows Script Host is unavailable");
        dynamic shell = Activator.CreateInstance(t)!;
        dynamic lnk = shell.CreateShortcut(SendToLink);
        lnk.TargetPath = Exe;
        lnk.IconLocation = Exe + ",0";
        lnk.Description = "Send with Ferry over Bluetooth";
        lnk.Save();
    }
}
