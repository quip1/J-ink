using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using Ferry.Core;

namespace FerryWin;

public sealed class KnownPeer
{
    public string Id { get; set; } = "";
    public string Name { get; set; } = "";
    public string Os { get; set; } = "";
    public DateTime Seen { get; set; }
}

public sealed class HistoryItem
{
    public DateTime Time { get; set; }
    public string Text { get; set; } = "";
}

/// <summary>Persisted in %APPDATA%\Ferry\settings.json.</summary>
public sealed class Settings
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string Name { get; set; } = Environment.MachineName;
    public bool Receiving { get; set; } = true;
    public Dictionary<string, string> Trusted { get; set; } = new();
    public Dictionary<string, KnownPeer> Known { get; set; } = new();   // key: bluetooth address "AABBCCDDEEFF"
    public List<HistoryItem> History { get; set; } = new();

    public Identity Identity => new(Id, Name, "windows");

    static readonly object Gate = new();
    static string Dir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Ferry");
    static string FilePath => Path.Combine(Dir, "settings.json");
    static readonly JsonSerializerOptions Opts = new() { WriteIndented = true };

    public static Settings Load()
    {
        try { if (File.Exists(FilePath)) return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new(); }
        catch { /* corrupt settings: start fresh rather than refuse to open */ }
        var s = new Settings(); s.Save(); return s;
    }

    public void Save()
    {
        lock (Gate)
        {
            Directory.CreateDirectory(Dir);
            var tmp = FilePath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(this, Opts));
            File.Move(tmp, FilePath, true);
        }
    }

    public void Log(string text)
    {
        lock (Gate)
        {
            History.Insert(0, new HistoryItem { Time = DateTime.Now, Text = text });
            if (History.Count > 50) History.RemoveRange(50, History.Count - 50);
        }
        Save();
    }

    public void Remember(string address, PeerHello h)
    {
        lock (Gate) Known[address] = new KnownPeer { Id = h.Id, Name = h.Name, Os = h.Os, Seen = DateTime.Now };
        Save();
    }

    public bool IsKnown(string address) { lock (Gate) return Known.ContainsKey(address); }
    public KnownPeer? GetKnown(string address) { lock (Gate) return Known.TryGetValue(address, out var k) ? k : null; }
    public bool IsTrusted(string id) { lock (Gate) return Trusted.ContainsKey(id); }
    public void Trust(string id, string name) { lock (Gate) Trusted[id] = name; Save(); }
    public void Untrust(string id) { lock (Gate) Trusted.Remove(id); Save(); }
    public List<KeyValuePair<string, string>> TrustedList() { lock (Gate) return Trusted.ToList(); }
}
