using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace Ferry.Core;

/// <summary>"Smart by type" placement for Windows. Mirrors Placement.kt; roots differ per OS.</summary>
public static class Placement
{
    public enum Category { Books, Audiobooks, Music, Pictures, Video, Fonts, Notes, Documents, Other }

    static readonly Dictionary<string, Category> ByExt = new(StringComparer.OrdinalIgnoreCase);
    static Placement()
    {
        void Add(Category c, params string[] e) { foreach (var x in e) ByExt[x] = c; }
        Add(Category.Books, "epub", "pdf", "mobi", "azw", "azw3", "fb2", "djvu", "cbz", "cbr", "chm");
        Add(Category.Audiobooks, "m4b", "aax");
        Add(Category.Music, "mp3", "flac", "m4a", "ogg", "opus", "wav", "aac", "wma");
        Add(Category.Pictures, "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "tif", "tiff", "svg");
        Add(Category.Video, "mp4", "mkv", "mov", "avi", "webm", "m4v");
        Add(Category.Fonts, "ttf", "otf", "ttc", "woff", "woff2");
        Add(Category.Notes, "note");
        Add(Category.Documents, "txt", "md", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "json");
    }

    public static Category CategoryOf(string name)
    {
        var dot = name.LastIndexOf('.');
        return dot >= 0 && ByExt.TryGetValue(name[(dot + 1)..], out var c) ? c : Category.Other;
    }

    /// <summary>Folder for each category. Overridable so tests can point at a temp dir.</summary>
    public static Func<Category, string> RootFor = DefaultRoot;

    public static string DefaultRoot(Category c)
    {
        string docs = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
        string music = Environment.GetFolderPath(Environment.SpecialFolder.MyMusic);
        string pics = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures);
        string vids = Environment.GetFolderPath(Environment.SpecialFolder.MyVideos);
        string dl = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");
        return c switch
        {
            Category.Books => Path.Combine(docs, "Books"),
            Category.Audiobooks => Path.Combine(music, "Audiobooks"),
            Category.Music => music,
            Category.Pictures => Path.Combine(pics, "Ferry"),
            Category.Video => Path.Combine(vids, "Ferry"),
            Category.Fonts => Path.Combine(dl, "Ferry", "Fonts"),
            Category.Notes => Path.Combine(docs, "Ferry", "Notes"),
            Category.Documents => Path.Combine(docs, "Ferry"),
            _ => Path.Combine(dl, "Ferry"),
        };
    }

    static readonly char[] Bad = "<>:\"|?*".ToCharArray();
    static readonly HashSet<string> Reserved = new(StringComparer.OrdinalIgnoreCase)
        { "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "LPT1", "LPT2", "LPT3" };

    public static List<string> Sanitize(string path)
    {
        var segs = path.Replace('\\', '/').Split('/')
            .Select(s => new string(s.Where(ch => ch >= ' ' && Array.IndexOf(Bad, ch) < 0).ToArray()).Trim().TrimEnd('.', ' '))
            .Where(s => s.Length > 0 && s != "." && s != "..")
            .Select(s => Reserved.Contains(Path.GetFileNameWithoutExtension(s)) ? "_" + s : s)
            .ToList();
        return segs.Count > 0 ? segs : ["file"];
    }

    public static string Destination(string path)
    {
        var segs = Sanitize(path);
        var dir = RootFor(CategoryOf(segs[^1]));
        foreach (var s in segs.Take(segs.Count - 1)) dir = Path.Combine(dir, s);
        Directory.CreateDirectory(dir);
        return Unique(Path.Combine(dir, segs[^1]));
    }

    public static string Unique(string f)
    {
        if (!File.Exists(f)) return f;
        var dir = Path.GetDirectoryName(f)!;
        var baseName = Path.GetFileNameWithoutExtension(f);
        var ext = Path.GetExtension(f);
        for (int i = 1; ; i++)
        {
            var c = Path.Combine(dir, $"{baseName} ({i}){ext}");
            if (!File.Exists(c)) return c;
        }
    }
}
