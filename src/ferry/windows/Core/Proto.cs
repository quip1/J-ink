// Ferry wire protocol v2 — C# side. Mirrors android/.../core/Proto.kt exactly; see PROTOCOL.md.
using System;
using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace Ferry.Core;

public static class Proto
{
    public const int Version = 2;
    public static readonly Guid ServiceUuid = new("6f0a7c2e-4b1d-4e8a-9c35-f3a1d0b7e2c9");
    public const string ServiceName = "Ferry";
    public const int Chunk = 128 * 1024;
    const int MaxFrame = 65536;

    public static void WriteFrame(Stream s, JsonObject o)
    {
        var bytes = Encoding.UTF8.GetBytes(o.ToJsonString());
        var len = new byte[4];
        BinaryPrimitives.WriteInt32BigEndian(len, bytes.Length);
        s.Write(len); s.Write(bytes); s.Flush();
    }

    public static JsonObject ReadFrame(Stream s)
    {
        var len = new byte[4];
        ReadExactly(s, len, 4);
        int n = BinaryPrimitives.ReadInt32BigEndian(len);
        if (n < 0 || n > MaxFrame) throw new IOException($"bad frame length {n}");
        var buf = new byte[n];
        ReadExactly(s, buf, n);
        return JsonNode.Parse(Encoding.UTF8.GetString(buf))!.AsObject();
    }

    public static void ReadExactly(Stream s, byte[] buf, int n)
    {
        int got = 0;
        while (got < n)
        {
            int r = s.Read(buf, got, n - got);
            if (r <= 0) throw new EndOfStreamException("connection closed");
            got += r;
        }
    }

    public static JsonObject Expect(JsonObject o, string type)
    {
        var t = Str(o, "t");
        if (t is "error" or "reject" or "p2p_fail") throw new RemoteRefusal(Str(o, "reason") ?? t ?? "refused");
        if (t != type) throw new IOException($"expected '{type}', got '{t}'");
        return o;
    }

    public static string? Str(JsonObject o, string k) => o.TryGetPropertyValue(k, out var v) && v is not null ? v.ToString() : null;
    public static long Long(JsonObject o, string k) => o.TryGetPropertyValue(k, out var v) && v is not null ? v.GetValue<long>() : 0;
    public static int Int(JsonObject o, string k, int def = 0) => o.TryGetPropertyValue(k, out var v) && v is not null ? v.GetValue<int>() : def;
    public static bool Bool(JsonObject o, string k) => o.TryGetPropertyValue(k, out var v) && v is not null && v.GetValue<bool>();
}

public sealed class RemoteRefusal(string msg) : IOException(msg);

public record Identity(string Id, string Name, string Os);
public record Endpoint(string Host, int Port)
{
    public JsonObject ToJson() => new() { ["ip"] = Host, ["port"] = Port };
    public static List<Endpoint> List(JsonNode? a) =>
        a is JsonArray arr ? arr.Select(e => new Endpoint(Proto.Str(e!.AsObject(), "ip")!, Proto.Int(e.AsObject(), "port"))).ToList() : [];
}

public enum Route { Lan, P2p, Bt }
public static class RouteExt
{
    public static string Label(this Route r) => r switch { Route.Lan => "Wi-Fi", Route.P2p => "Wi-Fi Direct", _ => "Bluetooth" };
}

public record PeerHello(string Id, string Name, string Os, bool TrustedByThem, int Version = 1,
    string? Token = null, byte[]? Key = null, List<Endpoint>? Lan = null, bool CanHostP2p = false);

public record P2pInfo(string Ssid, string Pass, string Ip, int Port);

public sealed class OutgoingFile(string path, long size, Func<Stream> open)
{
    public string Path { get; } = path;
    public long Size { get; } = size;
    public Func<Stream> Open { get; } = open;
}

/// <summary>Standard CRC-32 (IEEE / zlib), same as java.util.zip.CRC32. Slicing-by-8 for speed.</summary>
public sealed class Crc32
{
    static readonly uint[][] T = Build();
    static uint[][] Build()
    {
        var t = new uint[8][];
        for (int k = 0; k < 8; k++) t[k] = new uint[256];
        for (uint i = 0; i < 256; i++)
        {
            uint c = i;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            t[0][i] = c;
        }
        for (int i = 0; i < 256; i++)
            for (int k = 1; k < 8; k++) t[k][i] = (t[k - 1][i] >> 8) ^ t[0][t[k - 1][i] & 0xFF];
        return t;
    }
    uint _crc = 0xFFFFFFFFu;
    public void Update(byte[] b, int off, int len)
    {
        uint c = _crc; int i = off, end = off + len;
        var t0 = T[0]; var t1 = T[1]; var t2 = T[2]; var t3 = T[3]; var t4 = T[4]; var t5 = T[5]; var t6 = T[6]; var t7 = T[7];
        while (end - i >= 8)
        {
            uint a = c ^ BinaryPrimitives.ReadUInt32LittleEndian(b.AsSpan(i));
            uint d = BinaryPrimitives.ReadUInt32LittleEndian(b.AsSpan(i + 4));
            c = t7[a & 0xFF] ^ t6[(a >> 8) & 0xFF] ^ t5[(a >> 16) & 0xFF] ^ t4[a >> 24]
              ^ t3[d & 0xFF] ^ t2[(d >> 8) & 0xFF] ^ t1[(d >> 16) & 0xFF] ^ t0[d >> 24];
            i += 8;
        }
        for (; i < end; i++) c = t0[(c ^ b[i]) & 0xFF] ^ (c >> 8);
        _crc = c;
    }
    public long Value => ~_crc & 0xFFFFFFFFL;
}

// ------------------------------------------------------------------ AES-256-CTR (Java's AES/CTR/NoPadding: 128-bit big-endian counter)

public static class Crypt
{
    public static byte[] IvC2S => Iv('C');
    public static byte[] IvS2C => Iv('S');
    static byte[] Iv(char c) { var b = new byte[16]; b[0] = (byte)c; return b; }
}

public sealed class AesCtr : IDisposable
{
    readonly Aes _aes;
    readonly byte[] _counter;
    readonly byte[] _ctrBlocks = new byte[16 * 1024];
    readonly byte[] _ks = new byte[16 * 1024];
    int _pos;

    public AesCtr(byte[] key, byte[] iv)
    {
        _aes = Aes.Create(); _aes.Key = key;
        _counter = (byte[])iv.Clone();
        _pos = _ks.Length;
    }

    void Refill()
    {
        for (int off = 0; off < _ctrBlocks.Length; off += 16)
        {
            Buffer.BlockCopy(_counter, 0, _ctrBlocks, off, 16);
            for (int j = 15; j >= 0; j--) if (++_counter[j] != 0) break;
        }
        _aes.EncryptEcb(_ctrBlocks, _ks, PaddingMode.None);
        _pos = 0;
    }

    public void Transform(Span<byte> data)
    {
        int i = 0;
        while (i < data.Length)
        {
            if (_pos == _ks.Length) Refill();
            int n = Math.Min(data.Length - i, _ks.Length - _pos);
            var d = data.Slice(i, n);
            var k = _ks.AsSpan(_pos, n);
            int v = System.Numerics.Vector<byte>.Count, j = 0;
            for (; j + v <= n; j += v)
                (new System.Numerics.Vector<byte>(d.Slice(j)) ^ new System.Numerics.Vector<byte>(k.Slice(j))).CopyTo(d.Slice(j));
            for (; j < n; j++) d[j] ^= k[j];
            i += n; _pos += n;
        }
    }

    public void Dispose() => _aes.Dispose();
}

/// <summary>Duplex stream: encrypts writes with one keystream and decrypts reads with another.</summary>
public sealed class CtrStream(Stream inner, AesCtr rx, AesCtr tx) : Stream
{
    byte[] _scratch = new byte[Proto.Chunk];
    public override int Read(byte[] buffer, int offset, int count)
    {
        int n = inner.Read(buffer, offset, count);
        if (n > 0) rx.Transform(buffer.AsSpan(offset, n));
        return n;
    }
    public override void Write(byte[] buffer, int offset, int count)
    {
        if (_scratch.Length < count) _scratch = new byte[count];
        Buffer.BlockCopy(buffer, offset, _scratch, 0, count);
        tx.Transform(_scratch.AsSpan(0, count));
        inner.Write(_scratch, 0, count);
    }
    public override void Flush() => inner.Flush();
    public override bool CanRead => true;
    public override bool CanWrite => true;
    public override bool CanSeek => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException();
    public override void SetLength(long v) => throw new NotSupportedException();
    protected override void Dispose(bool disposing) { if (disposing) { inner.Dispose(); rx.Dispose(); tx.Dispose(); } base.Dispose(disposing); }
}

// ------------------------------------------------------------------ client

public sealed class FerryClient(Identity me, Stream control)
{
    Stream _s = new BufferedStream(control, 16 * 1024);
    PeerHello? _peer;
    TcpClient? _data;
    public Route Route { get; private set; } = Route.Bt;

    public PeerHello Hello(string purpose, long total = 0)
    {
        Proto.WriteFrame(_s, new JsonObject
        {
            ["t"] = "hello", ["v"] = Proto.Version, ["id"] = me.Id, ["name"] = me.Name, ["os"] = me.Os, ["purpose"] = purpose, ["total"] = total,
        });
        var h = Proto.Expect(Proto.ReadFrame(_s), "hello");
        var key = Proto.Str(h, "key");
        _peer = new PeerHello(Proto.Str(h, "id") ?? "", Proto.Str(h, "name") ?? "?", Proto.Str(h, "os") ?? "?", Proto.Bool(h, "trusted"),
            Proto.Int(h, "v", 1), Proto.Str(h, "token"), key is null ? null : Convert.FromBase64String(key),
            Endpoint.List(h["lan"]), Proto.Bool(h, "canHost"));
        return _peer;
    }

    public P2pInfo RequestP2p()
    {
        Proto.WriteFrame(_s, new JsonObject { ["t"] = "p2p_req" });
        var r = Proto.Expect(Proto.ReadFrame(_s), "p2p");
        return new P2pInfo(Proto.Str(r, "ssid")!, Proto.Str(r, "pass")!, Proto.Str(r, "ip")!, Proto.Int(r, "port"));
    }

    /// <summary>Race TCP connects to every endpoint; the first that passes the token check becomes the data link.</summary>
    public bool TryAttach(IReadOnlyList<Endpoint> endpoints, Route via, int timeoutMs = 2500)
    {
        if (_peer?.Token is not { } token || _peer.Key is not { } key || endpoints.Count == 0) return false;
        using var cts = new CancellationTokenSource(timeoutMs);
        var tasks = endpoints.Select(ep => Task.Run(async () =>
        {
            var c = new TcpClient { NoDelay = true, SendBufferSize = 1 << 20, ReceiveBufferSize = 1 << 20 };
            try
            {
                await c.ConnectAsync(ep.Host, ep.Port, cts.Token);
                var ns = c.GetStream();
                ns.ReadTimeout = 5000;
                Proto.WriteFrame(ns, new JsonObject { ["t"] = "attach", ["token"] = token });
                Proto.Expect(Proto.ReadFrame(ns), "attached");
                ns.ReadTimeout = 60_000;
                return c;
            }
            catch { c.Dispose(); return null; }
        })).ToList();

        TcpClient? winner = null;
        var pending = tasks.ToList();
        while (pending.Count > 0 && winner is null)
        {
            int idx = Task.WaitAny([.. pending], Math.Max(1, timeoutMs));
            if (idx < 0) break;
            var t = pending[idx]; pending.RemoveAt(idx);
            if (t.Result is { } c) winner = c;
        }
        foreach (var t in pending) t.ContinueWith(x => { if (x.Result is { } c && c != winner) c.Dispose(); });
        if (winner is null) return false;
        _data = winner;
        _s = new BufferedStream(new CtrStream(winner.GetStream(), new AesCtr(key, Crypt.IvS2C), new AesCtr(key, Crypt.IvC2S)), Proto.Chunk);
        Route = via;
        return true;
    }

    public void Close() { try { _data?.Dispose(); } catch { } }

    /// <param name="progress">(sentBytes, totalBytes, currentPath, index, count)</param>
    public List<string> Send(IReadOnlyList<OutgoingFile> files, Action<long, long, string, int, int>? progress = null)
    {
        long total = files.Sum(f => f.Size);
        var arr = new JsonArray();
        foreach (var f in files) arr.Add(new JsonObject { ["path"] = f.Path, ["size"] = f.Size });
        Proto.WriteFrame(_s, new JsonObject { ["t"] = "offer", ["count"] = files.Count, ["total"] = total, ["files"] = arr });
        Proto.Expect(Proto.ReadFrame(_s), "accept");

        var saved = new List<string>();
        var buf = new byte[Proto.Chunk];
        long sent = 0;
        for (int i = 0; i < files.Count; i++)
        {
            var f = files[i];
            progress?.Invoke(sent, total, f.Path, i, files.Count);
            Proto.WriteFrame(_s, new JsonObject { ["t"] = "file", ["path"] = f.Path, ["size"] = f.Size });
            var crc = new Crc32();
            long remaining = f.Size;
            using (var src = f.Open())
            {
                while (remaining > 0)
                {
                    int n = src.Read(buf, 0, (int)Math.Min(buf.Length, remaining));
                    if (n <= 0) throw new EndOfStreamException($"{f.Path} ended {remaining} bytes early (file changed?)");
                    _s.Write(buf, 0, n);
                    crc.Update(buf, 0, n);
                    remaining -= n; sent += n;
                    progress?.Invoke(sent, total, f.Path, i, files.Count);
                }
            }
            Proto.WriteFrame(_s, new JsonObject { ["t"] = "fend", ["crc32"] = crc.Value });
            var r = Proto.Expect(Proto.ReadFrame(_s), "saved");
            saved.Add(Proto.Str(r, "as") ?? f.Path);
        }
        Proto.WriteFrame(_s, new JsonObject { ["t"] = "done" });
        try { Proto.ReadFrame(_s); } catch { /* "bye" is a courtesy */ }
        return saved;
    }
}

// ------------------------------------------------------------------ server

public enum TrustDecision { Once, Always, Decline }

public sealed class Session(PeerHello peer, bool trusted, string address)
{
    public PeerHello Peer { get; } = peer;
    public bool Trusted { get; } = trusted;
    public string Address { get; } = address;
    public string Token { get; } = Convert.ToBase64String(RandomNumberGenerator.GetBytes(18));
    public byte[] Key { get; } = RandomNumberGenerator.GetBytes(32);
    public DateTime Created { get; } = DateTime.UtcNow;
    public volatile bool UsedP2p;
}

public static class Sessions
{
    static readonly ConcurrentDictionary<string, Session> Map = new();
    public static void Put(Session s)
    {
        foreach (var kv in Map) if (kv.Value.Created < DateTime.UtcNow.AddMinutes(-2)) Map.TryRemove(kv.Key, out _);
        Map[s.Token] = s;
    }
    public static Session? Take(string token) => Map.TryRemove(token, out var s) ? s : null;
    public static void Drop(Session s) => Map.TryRemove(s.Token, out _);
}

public interface IIncomingSink
{
    Stream Stream { get; }
    string Commit();
    void Abort();
}

public interface IReceiveHost
{
    Identity Me { get; }
    bool IsTrusted(string peerId);
    TrustDecision AskTrust(PeerHello peer, int count, long total);
    void RememberTrust(PeerHello peer);
    IIncomingSink OpenSink(string path, long size);
    void Prepare(PeerHello peer, long total) { }
    List<Endpoint> LanEndpoints() => [];
    bool CanHostP2p() => false;
    P2pInfo StartP2p(Session s) => throw new IOException("Wi-Fi Direct hosting isn't supported on Windows");
    void OnSessionEnd(Session s) { }
    void OnStart(PeerHello peer, int count, long total, Route route) { }
    void OnProgress(PeerHello peer, long received, long total, string path) { }
    void OnFinished(PeerHello peer, List<string> saved, Route route) { }
}

public sealed class FerryServer(IReceiveHost host)
{
    public void RunControl(Stream stream, string address = "")
    {
        var s = new BufferedStream(stream, 16 * 1024);
        var h = Proto.Expect(Proto.ReadFrame(s), "hello");
        var peer = new PeerHello(Proto.Str(h, "id") ?? "", Proto.Str(h, "name") ?? "?", Proto.Str(h, "os") ?? "?", false, Proto.Int(h, "v", 1));
        bool trusted = host.IsTrusted(peer.Id);
        var reply = new JsonObject
        {
            ["t"] = "hello", ["v"] = Proto.Version, ["id"] = host.Me.Id, ["name"] = host.Me.Name, ["os"] = host.Me.Os, ["trusted"] = trusted,
        };
        if (Proto.Str(h, "purpose") == "probe") { Proto.WriteFrame(s, reply); return; }

        var session = new Session(peer, trusted, address);
        if (peer.Version >= 2)
        {
            try { host.Prepare(peer, Proto.Long(h, "total")); } catch { }
            Sessions.Put(session);
            var lan = new JsonArray();
            foreach (var ep in host.LanEndpoints()) lan.Add(ep.ToJson());
            reply["token"] = session.Token; reply["key"] = Convert.ToBase64String(session.Key);
            reply["lan"] = lan; reply["canHost"] = host.CanHostP2p();
        }
        Proto.WriteFrame(s, reply);
        try
        {
            while (true)
            {
                JsonObject f;
                try { f = Proto.ReadFrame(s); } catch (EndOfStreamException) { return; }
                switch (Proto.Str(f, "t"))
                {
                    case "p2p_req":
                        try
                        {
                            var info = host.StartP2p(session);
                            session.UsedP2p = true;
                            Proto.WriteFrame(s, new JsonObject { ["t"] = "p2p", ["ssid"] = info.Ssid, ["pass"] = info.Pass, ["ip"] = info.Ip, ["port"] = info.Port });
                        }
                        catch (Exception e) { Proto.WriteFrame(s, new JsonObject { ["t"] = "p2p_fail", ["reason"] = e.Message }); }
                        break;
                    case "offer":
                        Sessions.Drop(session);
                        try { Transfer(session, f, s, Route.Bt); } finally { host.OnSessionEnd(session); }
                        return;
                    default: throw new IOException($"unexpected frame {Proto.Str(f, "t")}");
                }
            }
        }
        catch (IOException e)
        {
            if (Sessions.Take(session.Token) != null) host.OnSessionEnd(session);
            if (e is not EndOfStreamException) throw;
        }
    }

    public void RunData(TcpClient client)
    {
        client.NoDelay = true;
        var ns = client.GetStream();
        ns.ReadTimeout = 15_000;
        var a = Proto.Expect(Proto.ReadFrame(ns), "attach");
        var session = Sessions.Take(Proto.Str(a, "token") ?? "");
        if (session is null) { Proto.WriteFrame(ns, new JsonObject { ["t"] = "error", ["reason"] = "unknown session" }); return; }
        try
        {
            Proto.WriteFrame(ns, new JsonObject { ["t"] = "attached" });
            ns.ReadTimeout = 120_000;
            var s = new BufferedStream(new CtrStream(ns, new AesCtr(session.Key, Crypt.IvC2S), new AesCtr(session.Key, Crypt.IvS2C)), Proto.Chunk);
            var offer = Proto.ReadFrame(s);
            Transfer(session, offer, s, session.UsedP2p ? Route.P2p : Route.Lan);
        }
        finally { host.OnSessionEnd(session); }
    }

    void Transfer(Session session, JsonObject offer, Stream s, Route route)
    {
        var peer = session.Peer;
        Proto.Expect(offer, "offer");
        int count = Proto.Int(offer, "count");
        long total = Proto.Long(offer, "total");
        if (!session.Trusted && !host.IsTrusted(peer.Id))
        {
            switch (host.AskTrust(peer, count, total))
            {
                case TrustDecision.Decline:
                    Proto.WriteFrame(s, new JsonObject { ["t"] = "reject", ["reason"] = $"declined on {host.Me.Name}" });
                    return;
                case TrustDecision.Always: host.RememberTrust(peer); break;
            }
        }
        Proto.WriteFrame(s, new JsonObject { ["t"] = "accept" });
        host.OnStart(peer, count, total, route);

        var saved = new List<string>();
        var buf = new byte[Proto.Chunk];
        long got = 0;
        while (true)
        {
            var f = Proto.ReadFrame(s);
            switch (Proto.Str(f, "t"))
            {
                case "done":
                    Proto.WriteFrame(s, new JsonObject { ["t"] = "bye" });
                    host.OnFinished(peer, saved, route);
                    return;
                case "file":
                {
                    string path = Proto.Str(f, "path") ?? "file";
                    long size = Proto.Long(f, "size");
                    IIncomingSink? sink = null; string? openError = null;
                    try { sink = host.OpenSink(path, size); } catch (Exception e) { openError = e.Message; }
                    var crc = new Crc32();
                    long remaining = size;
                    try
                    {
                        while (remaining > 0)
                        {
                            int n = s.Read(buf, 0, (int)Math.Min(buf.Length, remaining));
                            if (n <= 0) throw new EndOfStreamException($"connection lost during {path}");
                            sink?.Stream.Write(buf, 0, n);
                            crc.Update(buf, 0, n);
                            remaining -= n; got += n;
                            host.OnProgress(peer, got, total, path);
                        }
                        sink?.Stream.Dispose();
                    }
                    catch { sink?.Abort(); throw; }
                    var end = Proto.Expect(Proto.ReadFrame(s), "fend");
                    if (sink is null)
                        Proto.WriteFrame(s, new JsonObject { ["t"] = "error", ["reason"] = openError ?? "cannot write" });
                    else if (Proto.Long(end, "crc32") != crc.Value)
                    {
                        sink.Abort();
                        Proto.WriteFrame(s, new JsonObject { ["t"] = "error", ["reason"] = $"checksum mismatch on {path}" });
                    }
                    else
                    {
                        var where = sink.Commit();
                        saved.Add(where);
                        Proto.WriteFrame(s, new JsonObject { ["t"] = "saved", ["as"] = where });
                    }
                    break;
                }
                default: throw new IOException($"unexpected frame {Proto.Str(f, "t")}");
            }
        }
    }
}
