using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using Ferry.Core;
using InTheHand.Net;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;

namespace FerryWin;

/// <summary>Bluetooth plumbing: paired-device filtering, connect-with-timeout, probing, listening.</summary>
public static class Bt
{
    static readonly HashSet<DeviceClass> Skip =
    [
        DeviceClass.AudioVideoUnclassified, DeviceClass.Peripheral, DeviceClass.Imaging,
        DeviceClass.Wearable, DeviceClass.Toy, DeviceClass.Medical,
    ];

    public static bool RadioAvailable()
    {
        try { return BluetoothRadio.Default is { } r && r.Mode != RadioMode.PowerOff; } catch { return false; }
    }

    /// <summary>Paired devices that could run Ferry — phones, tablets, computers; not headsets or mice.</summary>
    public static List<BluetoothDeviceInfo> Candidates()
    {
        try
        {
            using var c = new BluetoothClient();
            return c.PairedDevices
                .Where(d => !Skip.Contains(d.ClassOfDevice.MajorDevice))
                .OrderBy(d => d.DeviceName)
                .ToList();
        }
        catch { return []; }
    }

    public static string Key(BluetoothAddress a) => a.ToString("N").ToUpperInvariant();

    public static BluetoothClient Connect(BluetoothAddress addr, int timeoutMs = 10000)
    {
        var client = new BluetoothClient();
        var t = Task.Run(() => client.Connect(addr, Proto.ServiceUuid));
        try
        {
            if (!t.Wait(timeoutMs))
            {
                client.Dispose();
                throw new IOException("didn't answer — is Ferry open on it and Bluetooth on?");
            }
        }
        catch (AggregateException ae)
        {
            client.Dispose();
            var inner = ae.InnerException ?? ae;
            throw new IOException(Friendly(inner), inner);
        }
        return client;
    }

    public static PeerHello Probe(BluetoothAddress addr, Settings s)
    {
        using var c = Connect(addr, 8000);
        var h = new FerryClient(s.Identity, c.GetStream()).Hello("probe");
        s.Remember(Key(addr), h);
        return h;
    }

    static string Friendly(Exception e)
    {
        if (e is SocketException se)
            return se.SocketErrorCode switch
            {
                SocketError.TimedOut => "no answer (out of range or asleep?)",
                SocketError.HostDown or SocketError.HostUnreachable => "device is off or out of range",
                SocketError.ConnectionRefused or SocketError.AddressNotAvailable => "Ferry isn't running there",
                _ => se.Message,
            };
        return e.Message;
    }
}

/// <summary>Accept loop. Each connection runs on its own thread against a <see cref="IReceiveHost"/>.</summary>
public sealed class Receiver(Func<string, IReceiveHost> hostFor, Action<string> status, Action<string> error)
{
    BluetoothListener? _listener;
    TcpListener? _data;
    public int DataPort { get; private set; }
    Thread? _thread;
    volatile bool _running;

    public bool Running => _running;

    public void Start()
    {
        if (_running) return;
        Radios.EnsureBluetoothOn();
        try
        {
            _listener = new BluetoothListener(Proto.ServiceUuid) { ServiceName = Proto.ServiceName };
            _listener.Start();
        }
        catch (Exception e)
        {
            _listener = null;
            status(Bt.RadioAvailable() ? $"Couldn't start receiving: {e.Message}" : "Bluetooth is off or missing");
            return;
        }
        _running = true;
        StartData();
        _thread = new Thread(Loop) { IsBackground = true, Name = "ferry-accept" };
        _thread.Start();
    }

    void StartData()
    {
        if (_data != null) return;
        try
        {
            var l = new TcpListener(System.Net.IPAddress.Any, 0);
            l.Server.ReceiveBufferSize = 1 << 20;
            l.Start();
            _data = l; DataPort = ((System.Net.IPEndPoint)l.LocalEndpoint).Port;
        }
        catch { return; }
        new Thread(() =>
        {
            var l = _data;
            while (_running && l != null)
            {
                TcpClient c;
                try { c = l.AcceptTcpClient(); } catch { break; }
                new Thread(() =>
                {
                    using (c)
                    {
                        try { new FerryServer(hostFor("")).RunData(c); }
                        catch (EndOfStreamException) { }
                        catch (Exception e) { error("Receive failed: " + e.Message); }
                    }
                }) { IsBackground = true }.Start();
            }
        }) { IsBackground = true, Name = "ferry-data" }.Start();
    }

    public void Stop()
    {
        _running = false;
        try { _data?.Stop(); } catch { }
        _data = null; DataPort = 0;
        try { _listener?.Stop(); } catch { }
        _listener = null;
    }

    void Loop()
    {
        var l = _listener!;
        while (_running)
        {
            BluetoothClient c;
            try { c = l.AcceptBluetoothClient(); }
            catch { if (_running) { Thread.Sleep(1000); continue; } break; }
            new Thread(() =>
            {
                using (c)
                {
                    string addr = "";
                    try { addr = (c.Client.RemoteEndPoint as BluetoothEndPoint)?.Address is { } a ? Bt.Key(a) : ""; } catch { }
                    try { new FerryServer(hostFor(addr)).RunControl(c.GetStream(), addr); }
                    catch (EndOfStreamException) { }
                    catch (Exception e) { error("Receive failed: " + e.Message); }
                }
            }) { IsBackground = true }.Start();
        }
    }
}
