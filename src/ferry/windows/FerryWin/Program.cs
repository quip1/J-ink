using System;
using System.IO;
using System.IO.Pipes;
using System.Linq;
using System.Security.Principal;
using System.Threading;
using System.Windows.Forms;

namespace FerryWin;

static class Program
{
    static string Tag => "Ferry-" + (WindowsIdentity.GetCurrent().User?.Value ?? Environment.UserName);

    [STAThread]
    static void Main(string[] args)
    {
        using var mutex = new Mutex(true, Tag, out bool first);
        if (!first)
        {
            // Already running (e.g. in the tray): hand our files to it and quit.
            try
            {
                using var pipe = new NamedPipeClientStream(".", Tag, PipeDirection.Out);
                pipe.Connect(3000);
                using var w = new StreamWriter(pipe);
                w.WriteLine(args.Length == 0 ? "--show" : string.Join("\n", args.Where(a => a != "--tray")));
            }
            catch { }
            return;
        }

        ApplicationConfiguration.Initialize();
        var form = new MainForm(args);
        var listener = new Thread(() =>
        {
            while (true)
            {
                try
                {
                    using var pipe = new NamedPipeServerStream(Tag, PipeDirection.In);
                    pipe.WaitForConnection();
                    using var r = new StreamReader(pipe);
                    var lines = r.ReadToEnd().Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
                    form.BeginInvoke(() =>
                    {
                        form.QueueFiles(lines.Where(l => l != "--show"));
                        form.Show(); form.Activate();
                    });
                }
                catch { Thread.Sleep(500); }
            }
        }) { IsBackground = true };
        listener.Start();
        Application.Run(form);
    }
}
