using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security;
using System.Threading;
using System.Threading.Tasks;
using Ferry.Core;

namespace FerryWin;

/// <summary>
/// Native Wi-Fi API (wlanapi.dll): radio on, temporarily join a Boox's Wi-Fi Direct group as a normal
/// WPA2 network, then put the PC back on the network it was using.
/// </summary>
public static class Wlan
{
    [DllImport("wlanapi.dll")] static extern uint WlanOpenHandle(uint ver, IntPtr r, out uint neg, out IntPtr h);
    [DllImport("wlanapi.dll")] static extern uint WlanCloseHandle(IntPtr h, IntPtr r);
    [DllImport("wlanapi.dll")] static extern uint WlanEnumInterfaces(IntPtr h, IntPtr r, out IntPtr list);
    [DllImport("wlanapi.dll")] static extern void WlanFreeMemory(IntPtr p);
    [DllImport("wlanapi.dll")] static extern uint WlanScan(IntPtr h, ref Guid iface, IntPtr ssid, IntPtr ie, IntPtr r);
    [DllImport("wlanapi.dll", CharSet = CharSet.Unicode)]
    static extern uint WlanSetProfile(IntPtr h, ref Guid iface, uint flags, string xml, string? sd, bool overwrite, IntPtr r, out uint reason);
    [DllImport("wlanapi.dll", CharSet = CharSet.Unicode)] static extern uint WlanDeleteProfile(IntPtr h, ref Guid iface, string name, IntPtr r);
    [DllImport("wlanapi.dll")] static extern uint WlanConnect(IntPtr h, ref Guid iface, ref ConnParams p, IntPtr r);
    [DllImport("wlanapi.dll")] static extern uint WlanQueryInterface(IntPtr h, ref Guid iface, int op, IntPtr r, out int size, out IntPtr data, IntPtr type);
    [DllImport("wlanapi.dll")] static extern uint WlanSetInterface(IntPtr h, ref Guid iface, int op, int size, ref PhyRadioState data, IntPtr r);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct ConnParams
    {
        public int Mode;                // 0 = wlan_connection_mode_profile
        [MarshalAs(UnmanagedType.LPWStr)] public string Profile;
        public IntPtr Ssid, Bssids;
        public int BssType;             // 1 = infrastructure
        public uint Flags;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct PhyRadioState { public uint PhyIndex; public int Software; public int Hardware; } // DOT11_RADIO_STATE: 1 on, 2 off

    const int OpRadioState = 4, OpCurrentConnection = 7, OpInterfaceState = 6;

    sealed class Handle : IDisposable
    {
        public IntPtr H;
        public Handle() { if (WlanOpenHandle(2, IntPtr.Zero, out _, out H) != 0) throw new InvalidOperationException("no Wi-Fi service"); }
        public void Dispose() { if (H != IntPtr.Zero) WlanCloseHandle(H, IntPtr.Zero); H = IntPtr.Zero; }
    }

    static List<Guid> Interfaces(IntPtr h)
    {
        var res = new List<Guid>();
        if (WlanEnumInterfaces(h, IntPtr.Zero, out var list) != 0) return res;
        try
        {
            int n = Marshal.ReadInt32(list);
            for (int i = 0; i < n; i++)
            {
                // WLAN_INTERFACE_INFO: GUID (16) + WCHAR[256] (512) + state (4) = 532 bytes; list header is 8 bytes
                var p = list + 8 + i * 532;
                res.Add(Marshal.PtrToStructure<Guid>(p));
            }
        }
        finally { WlanFreeMemory(list); }
        return res;
    }

    public static bool Available
    {
        get { try { using var h = new Handle(); return Interfaces(h.H).Count > 0; } catch { return false; } }
    }

    /// <summary>Software-switch the Wi-Fi radio on (Airplane-mode style off). Returns true if it had been off.</summary>
    public static bool EnsureRadioOn()
    {
        try
        {
            using var h = new Handle();
            bool changed = false;
            foreach (var g in Interfaces(h.H))
            {
                var iface = g;
                if (WlanQueryInterface(h.H, ref iface, OpRadioState, IntPtr.Zero, out _, out var data, IntPtr.Zero) != 0) continue;
                try
                {
                    int phys = Marshal.ReadInt32(data);
                    for (int i = 0; i < phys; i++)
                    {
                        var st = Marshal.PtrToStructure<PhyRadioState>(data + 4 + i * 12);
                        if (st.Software == 2)
                        {
                            st.Software = 1;
                            if (WlanSetInterface(h.H, ref iface, OpRadioState, 12, ref st, IntPtr.Zero) == 0) changed = true;
                        }
                    }
                }
                finally { WlanFreeMemory(data); }
            }
            return changed;
        }
        catch { return false; }
    }

    static (int state, string? profile) Current(IntPtr h, Guid iface)
    {
        if (WlanQueryInterface(h, ref iface, OpCurrentConnection, IntPtr.Zero, out _, out var data, IntPtr.Zero) != 0) return (0, null);
        try { return (Marshal.ReadInt32(data), Marshal.PtrToStringUni(data + 8)); }
        finally { WlanFreeMemory(data); }
    }

    /// <summary>Join a WPA2 network (the Boox's Wi-Fi Direct group). Disposing restores the previous network.</summary>
    public static IDisposable Join(P2pInfo info, Action<string> status, int timeoutMs = 25_000)
    {
        EnsureRadioOn();
        var h = new Handle();
        try
        {
            var ifaces = Interfaces(h.H);
            if (ifaces.Count == 0) throw new InvalidOperationException("this PC has no Wi-Fi adapter");
            var iface = ifaces[0];
            var (_, previous) = Current(h.H, iface);
            string xml = $"""
                <?xml version="1.0"?>
                <WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
                  <name>{SecurityElement.Escape(info.Ssid)}</name>
                  <SSIDConfig><SSID><name>{SecurityElement.Escape(info.Ssid)}</name></SSID></SSIDConfig>
                  <connectionType>ESS</connectionType>
                  <connectionMode>manual</connectionMode>
                  <MSM><security>
                    <authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption>
                    <sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>{SecurityElement.Escape(info.Pass)}</keyMaterial></sharedKey>
                  </security></MSM>
                </WLANProfile>
                """;
            uint rc = WlanSetProfile(h.H, ref iface, 0, xml, null, true, IntPtr.Zero, out uint reason);
            if (rc != 0) throw new InvalidOperationException($"couldn't add Wi-Fi profile (error {rc}, reason {reason})");
            var restore = new Restore(h, iface, info.Ssid, previous);
            try
            {
                status(previous is null ? "Joining the Boox's direct Wi-Fi…" : $"Joining the Boox's direct Wi-Fi (will switch back to {previous})…");
                var deadline = Environment.TickCount64 + timeoutMs;
                long nextConnect = 0;
                while (Environment.TickCount64 < deadline)
                {
                    var (state, prof) = Current(h.H, iface);
                    if (state == 1 && prof == info.Ssid) return restore;   // wlan_interface_state_connected
                    if (Environment.TickCount64 >= nextConnect)
                    {
                        WlanScan(h.H, ref iface, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
                        Thread.Sleep(2500);   // let the scan see the brand-new network
                        var p = new ConnParams { Mode = 0, Profile = info.Ssid, BssType = 1 };
                        WlanConnect(h.H, ref iface, ref p, IntPtr.Zero);
                        nextConnect = Environment.TickCount64 + 7000;
                    }
                    Thread.Sleep(500);
                }
                throw new TimeoutException("couldn't join the Boox's Wi-Fi Direct network");
            }
            catch { restore.Dispose(); throw; }
        }
        catch { h.Dispose(); throw; }
    }

    sealed class Restore(Handle h, Guid iface, string ssid, string? previous) : IDisposable
    {
        int _done;
        public void Dispose()
        {
            if (Interlocked.Exchange(ref _done, 1) != 0) return;
            try
            {
                var i = iface;
                WlanDeleteProfile(h.H, ref i, ssid, IntPtr.Zero);
                if (!string.IsNullOrEmpty(previous) && previous != ssid)
                {
                    var p = new ConnParams { Mode = 0, Profile = previous, BssType = 1 };
                    WlanConnect(h.H, ref i, ref p, IntPtr.Zero);
                }
            }
            catch { }
            finally { h.Dispose(); }
        }
    }

    /// <summary>IPv4 addresses a peer on the same network could reach us at.</summary>
    public static List<string> LanAddresses()
    {
        var res = new List<string>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel) continue;
            var d = ni.Description + " " + ni.Name;
            bool virt = d.Contains("Hyper-V", StringComparison.OrdinalIgnoreCase) || d.Contains("VirtualBox", StringComparison.OrdinalIgnoreCase)
                || d.Contains("VMware", StringComparison.OrdinalIgnoreCase) || d.Contains("WSL", StringComparison.OrdinalIgnoreCase)
                || d.Contains("vEthernet", StringComparison.OrdinalIgnoreCase);
            foreach (var ua in ni.GetIPProperties().UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                var b = ua.Address.GetAddressBytes();
                if (b[0] == 169 && b[1] == 254) continue;
                if (virt) continue;
                res.Add(ua.Address.ToString());
            }
        }
        return res;
    }

    /// <summary>Wait (up to timeout) until some non-virtual adapter has an IPv4 address — after we woke the radio.</summary>
    public static void WaitForLan(int timeoutMs)
    {
        var until = Environment.TickCount64 + timeoutMs;
        while (Environment.TickCount64 < until && LanAddresses().Count == 0) Thread.Sleep(400);
    }
}

/// <summary>Bluetooth radio switch via Windows.Devices.Radios (the same toggle as Action Center).</summary>
public static class Radios
{
    public static bool EnsureBluetoothOn()
    {
        try
        {
            if (Bt.RadioAvailable()) return true;
            var access = Windows.Devices.Radios.Radio.RequestAccessAsync().AsTask().GetAwaiter().GetResult();
            if (access != Windows.Devices.Radios.RadioAccessStatus.Allowed) return false;
            var radios = Windows.Devices.Radios.Radio.GetRadiosAsync().AsTask().GetAwaiter().GetResult();
            foreach (var r in radios.Where(r => r.Kind == Windows.Devices.Radios.RadioKind.Bluetooth && r.State != Windows.Devices.Radios.RadioState.On))
                r.SetStateAsync(Windows.Devices.Radios.RadioState.On).AsTask().GetAwaiter().GetResult();
            for (int i = 0; i < 20 && !Bt.RadioAvailable(); i++) Thread.Sleep(250);
            return Bt.RadioAvailable();
        }
        catch { return Bt.RadioAvailable(); }
    }

    public static void EnsureWifiOn()
    {
        if (Wlan.EnsureRadioOn()) { Wlan.WaitForLan(10_000); return; }
        try
        {
            var radios = Windows.Devices.Radios.Radio.GetRadiosAsync().AsTask().GetAwaiter().GetResult();
            bool changed = false;
            foreach (var r in radios.Where(r => r.Kind == Windows.Devices.Radios.RadioKind.WiFi && r.State != Windows.Devices.Radios.RadioState.On))
            { r.SetStateAsync(Windows.Devices.Radios.RadioState.On).AsTask().GetAwaiter().GetResult(); changed = true; }
            if (changed) Wlan.WaitForLan(10_000);
        }
        catch { }
    }
}
