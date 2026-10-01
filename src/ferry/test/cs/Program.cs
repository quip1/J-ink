using System.Net;
using System.Net.Sockets;
using Ferry.Core;

var me = new Identity("cs-id", "Jacob's PC", "windows");
if (args[0] == "server")
{
    var root = args[3]; var policy = args[4]; int dataPort = int.Parse(args[2]);
    Placement.RootFor = c => Path.Combine(root, c.ToString());
    var host = new Host(me, policy, new HashSet<string>(), root, dataPort);
    var srv = new FerryServer(host);
    var dl = new TcpListener(IPAddress.Loopback, dataPort); dl.Start();
    new Thread(() => { while (true) { var c = dl.AcceptTcpClient(); new Thread(() => { using (c) try { srv.RunData(c); } catch (Exception e) { Console.WriteLine("CS data err " + e.Message); } }).Start(); } }) { IsBackground = true }.Start();
    var l = new TcpListener(IPAddress.Loopback, int.Parse(args[1])); l.Start();
    for (int i = 0; i < int.Parse(args[5]); i++)
    {
        using var c = l.AcceptTcpClient();
        try { srv.RunControl(c.GetStream()); } catch (Exception e) { Console.WriteLine("CS ctl err: " + e.Message); }
    }
    Thread.Sleep(1500);
}
else
{
    using var c = new TcpClient(); c.Connect(IPAddress.Loopback, int.Parse(args[1]));
    var mode = args[2];
    var cl = new FerryClient(me, c.GetStream());
    var h = cl.Hello(mode == "probe" ? "probe" : "send");
    Console.WriteLine($"CS-CLIENT hello {h.Name} v{h.Version} lan={h.Lan?.Count} token={h.Token != null}");
    if (mode == "probe") return;
    if (mode == "lan") Console.WriteLine("CS-CLIENT attach=" + cl.TryAttach(h.Lan!, Route.Lan));
    var b = args[3];
    var files = args.Skip(4).Select(p => new OutgoingFile(Path.GetRelativePath(b, p).Replace('\\','/'), new FileInfo(p).Length, () => File.OpenRead(p))).ToList();
    var sw = System.Diagnostics.Stopwatch.StartNew();
    try { var r = cl.Send(files); Console.WriteLine($"CS-CLIENT via {cl.Route} saved {string.Join(", ", r)}  {files.Sum(f => f.Size) / 1048576.0 / sw.Elapsed.TotalSeconds:0.0} MB/s"); }
    catch (RemoteRefusal e) { Console.WriteLine("CS-CLIENT refused: " + e.Message); }
    cl.Close();
}

class Host(Identity me, string policy, HashSet<string> trusted, string root, int dataPort) : IReceiveHost
{
    public Identity Me => me;
    public bool IsTrusted(string id) => trusted.Contains(id);
    public TrustDecision AskTrust(PeerHello p, int n, long t) => policy == "decline" ? TrustDecision.Decline : TrustDecision.Always;
    public void RememberTrust(PeerHello p) => trusted.Add(p.Id);
    public List<Endpoint> LanEndpoints() => [new("10.255.255.1", dataPort), new("127.0.0.1", dataPort)];
    public IIncomingSink OpenSink(string path, long size) => new Sink(Placement.Destination(path), root);
    public void OnFinished(PeerHello p, List<string> saved, Route r) => Console.WriteLine($"CS-SERVER via {r} from {p.Name}: {string.Join(", ", saved)}");
}
class Sink : IIncomingSink
{
    readonly string _d, _root; public Stream Stream { get; }
    public Sink(string d, string root) { _d = d; _root = root; Stream = new FileStream(d + ".ferrypart", FileMode.Create, FileAccess.Write, FileShare.None, 1 << 16); }
    public string Commit() { Stream.Dispose(); var f = Placement.Unique(_d); File.Move(_d + ".ferrypart", f); return Path.GetRelativePath(_root, f); }
    public void Abort() { Stream.Dispose(); File.Delete(_d + ".ferrypart"); }
}
