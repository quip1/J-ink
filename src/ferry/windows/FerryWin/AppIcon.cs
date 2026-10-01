using System.Drawing;
using System.Reflection;

namespace FerryWin;

static class AppIcon
{
    static Icon? _icon;
    public static Icon Get() => _icon ??= LoadIcon();
    static Icon LoadIcon()
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("FerryWin.ferry.ico");
        return s != null ? new Icon(s) : SystemIcons.Application;
    }
}
