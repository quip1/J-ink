import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../app_state.dart';
import '../library.dart';

// ---------- formatting ----------
String fmtDur(Duration d, {bool forceHours = false}) {
  if (d.isNegative) d = Duration.zero;
  final h = d.inHours, m = d.inMinutes % 60, s = d.inSeconds % 60;
  String two(int v) => v.toString().padLeft(2, '0');
  return (h > 0 || forceHours) ? '$h:${two(m)}:${two(s)}' : '$m:${two(s)}';
}

String fmtLeft(Duration d) {
  if (d.isNegative) d = Duration.zero;
  final h = d.inHours, m = d.inMinutes % 60;
  if (h > 0) return '${h}h ${m}m';
  if (m > 0) return '${m}m';
  return '${d.inSeconds}s';
}

String fmtSpeed(double s) {
  final t = s.toStringAsFixed(2);
  return '${t.endsWith('0') ? t.substring(0, t.length - 1) : t}x';
}

// ---------- theme ----------
class _NoTransition extends PageTransitionsBuilder {
  const _NoTransition();
  @override
  Widget buildTransitions<T>(
    PageRoute<T> route,
    BuildContext context,
    Animation<double> a,
    Animation<double> b,
    Widget child,
  ) => child;
}

ThemeData buildTheme(bool invert) {
  final fg = invert ? Colors.white : Colors.black;
  final bg = invert ? Colors.black : Colors.white;
  const nt = _NoTransition();
  return ThemeData(
    useMaterial3: true,
    brightness: invert ? Brightness.dark : Brightness.light,
    colorScheme: ColorScheme(
      brightness: invert ? Brightness.dark : Brightness.light,
      primary: fg,
      onPrimary: bg,
      secondary: fg,
      onSecondary: bg,
      error: fg,
      onError: bg,
      surface: bg,
      onSurface: fg,
    ),
    scaffoldBackgroundColor: bg,
    canvasColor: bg,
    dividerColor: fg,
    splashFactory: NoSplash.splashFactory,
    highlightColor: Colors.transparent,
    splashColor: Colors.transparent,
    hoverColor: Colors.transparent,
    focusColor: Colors.transparent,
    textSelectionTheme: TextSelectionThemeData(cursorColor: fg, selectionColor: fg.withAlpha(60)),
    pageTransitionsTheme: const PageTransitionsTheme(
      builders: {
        TargetPlatform.android: nt,
        TargetPlatform.windows: nt,
        TargetPlatform.linux: nt,
        TargetPlatform.macOS: nt,
        TargetPlatform.iOS: nt,
      },
    ),
    textTheme: Typography.blackMountainView.apply(bodyColor: fg, displayColor: fg),
    inputDecorationTheme: InputDecorationTheme(
      isDense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 12),
      enabledBorder: OutlineInputBorder(
        borderSide: BorderSide(color: fg, width: 2),
        borderRadius: BorderRadius.zero,
      ),
      focusedBorder: OutlineInputBorder(
        borderSide: BorderSide(color: fg, width: 3),
        borderRadius: BorderRadius.zero,
      ),
      hintStyle: TextStyle(color: fg.withAlpha(140)),
    ),
  );
}

Color fgOf(BuildContext c) => Theme.of(c).colorScheme.onSurface;
Color bgOf(BuildContext c) => Theme.of(c).colorScheme.surface;

Future<T?> pushPage<T>(BuildContext context, Widget page) => Navigator.of(context).push<T>(
  PageRouteBuilder(
    pageBuilder: (_, _, _) => page,
    transitionDuration: Duration.zero,
    reverseTransitionDuration: Duration.zero,
  ),
);

Future<T?> showEDialog<T>(BuildContext context, Widget Function(BuildContext) builder) {
  return showGeneralDialog<T>(
    context: context,
    barrierDismissible: true,
    barrierLabel: 'close',
    barrierColor: Colors.transparent,
    transitionDuration: Duration.zero,
    pageBuilder: (ctx, _, _) => Center(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 520),
          child: Material(
            color: bgOf(ctx),
            shape: Border.all(color: fgOf(ctx), width: 3),
            child: Padding(padding: const EdgeInsets.all(16), child: builder(ctx)),
          ),
        ),
      ),
    ),
  );
}

// ---------- buttons ----------
class EButton extends StatelessWidget {
  final String label;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;
  final bool selected;
  final bool big;
  final bool expand;
  final double? fontSize;
  final IconData? icon;
  const EButton(
    this.label, {
    super.key,
    this.onTap,
    this.onLongPress,
    this.selected = false,
    this.big = false,
    this.expand = false,
    this.fontSize,
    this.icon,
  });

  @override
  Widget build(BuildContext context) {
    final fg = fgOf(context), bg = bgOf(context);
    final disabled = onTap == null && onLongPress == null;
    final color = selected ? bg : (disabled ? fg.withAlpha(90) : fg);
    final Widget text = icon != null
        ? Icon(icon, size: fontSize ?? (big ? 44 : 28), color: color, semanticLabel: label)
        : Text(
            label,
            textAlign: TextAlign.center,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(fontSize: fontSize ?? (big ? 26 : 16), fontWeight: FontWeight.w700, color: color),
          );
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      onLongPress: onLongPress,
      child: Container(
        constraints: BoxConstraints(minHeight: big ? 64 : 44, minWidth: big ? 64 : 44),
        alignment: Alignment.center,
        padding: EdgeInsets.symmetric(horizontal: big ? 8 : 12, vertical: 6),
        decoration: BoxDecoration(
          color: selected ? fg : bg,
          border: Border.all(color: disabled ? fg.withAlpha(90) : fg, width: 2),
        ),
        child: expand ? SizedBox(width: double.infinity, child: text) : text,
      ),
    );
  }
}

class ETabBar extends StatelessWidget {
  final List<String> labels;
  final int index;
  final ValueChanged<int> onTap;
  const ETabBar({super.key, required this.labels, required this.index, required this.onTap});

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        for (var i = 0; i < labels.length; i++)
          Expanded(
            child: Padding(
              padding: EdgeInsets.only(left: i == 0 ? 0 : 4),
              child: EButton(labels[i], selected: i == index, onTap: () => onTap(i), fontSize: 15),
            ),
          ),
      ],
    );
  }
}

class SectionTitle extends StatelessWidget {
  final String text;
  final Widget? trailing;
  const SectionTitle(this.text, {super.key, this.trailing});
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(0, 10, 0, 6),
    child: Row(
      children: [
        Expanded(
          child: Text(
            text,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800),
          ),
        ),
        ?trailing,
      ],
    ),
  );
}

class HLine extends StatelessWidget {
  const HLine({super.key});
  @override
  Widget build(BuildContext context) => Container(height: 2, color: fgOf(context));
}

// ---------- paged list (no scrolling = no e-ink ghosting) ----------
class PagedList extends StatefulWidget {
  final int itemCount;
  final double itemHeight;
  final IndexedWidgetBuilder itemBuilder;
  final int? focusIndex;
  final String emptyText;
  const PagedList({
    super.key,
    required this.itemCount,
    required this.itemHeight,
    required this.itemBuilder,
    this.focusIndex,
    this.emptyText = 'Nothing here.',
  });

  @override
  State<PagedList> createState() => _PagedListState();
}

class _PagedListState extends State<PagedList> {
  int page = 0;
  bool _focused = false;

  @override
  Widget build(BuildContext context) {
    if (widget.itemCount == 0) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Text(widget.emptyText, textAlign: TextAlign.center, style: const TextStyle(fontSize: 18)),
        ),
      );
    }
    if (!store.settings.paged) {
      return ListView.builder(
        itemCount: widget.itemCount,
        itemExtent: widget.itemHeight,
        itemBuilder: widget.itemBuilder,
      );
    }
    return LayoutBuilder(
      builder: (context, c) {
        const navH = 56.0;
        final perPage = math.max(1, ((c.maxHeight - navH) / widget.itemHeight).floor());
        final pages = (widget.itemCount / perPage).ceil();
        if (!_focused && widget.focusIndex != null) {
          page = widget.focusIndex! ~/ perPage;
          _focused = true;
        }
        page = page.clamp(0, pages - 1);
        final start = page * perPage;
        final end = math.min(start + perPage, widget.itemCount);
        return GestureDetector(
          onHorizontalDragEnd: (d) {
            final v = d.primaryVelocity ?? 0;
            if (v < -200 && page < pages - 1) setState(() => page++);
            if (v > 200 && page > 0) setState(() => page--);
          },
          child: Column(
            children: [
              Expanded(
                child: Column(
                  children: [
                    for (var i = start; i < end; i++)
                      SizedBox(height: widget.itemHeight, child: widget.itemBuilder(context, i)),
                  ],
                ),
              ),
              if (pages > 1)
                SizedBox(
                  height: navH,
                  child: Row(
                    children: [
                      EButton('◀ Prev', onTap: page > 0 ? () => setState(() => page--) : null),
                      Expanded(
                        child: Text(
                          '${page + 1} / $pages',
                          textAlign: TextAlign.center,
                          style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
                        ),
                      ),
                      EButton('Next ▶', onTap: page < pages - 1 ? () => setState(() => page++) : null),
                    ],
                  ),
                ),
            ],
          ),
        );
      },
    );
  }
}

// ---------- covers ----------
const _grayMatrix = <double>[
  0.299, 0.587, 0.114, 0, 0, //
  0.299, 0.587, 0.114, 0, 0, //
  0.299, 0.587, 0.114, 0, 0, //
  0, 0, 0, 1, 0,
];

class CoverImage extends StatelessWidget {
  final Book book;
  final double size;
  const CoverImage(this.book, {super.key, required this.size});

  @override
  Widget build(BuildContext context) {
    final fg = fgOf(context);
    Widget child;
    final cp = book.coverPath;
    if (cp != null && File(cp).existsSync()) {
      child = Image.file(
        File(cp),
        fit: BoxFit.cover,
        width: size,
        height: size,
        cacheWidth: (size * MediaQuery.devicePixelRatioOf(context)).round(),
        gaplessPlayback: true,
        errorBuilder: (_, _, _) => _placeholder(fg),
      );
      if (store.settings.covers == 'gray') {
        child = ColorFiltered(colorFilter: const ColorFilter.matrix(_grayMatrix), child: child);
      }
    } else {
      child = _placeholder(fg);
    }
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(border: Border.all(color: fg, width: 2)),
      child: ClipRect(child: child),
    );
  }

  Widget _placeholder(Color fg) {
    final initials = book.title.trim().isEmpty ? '?' : book.title.trim()[0].toUpperCase();
    return Center(
      child: Text(
        initials,
        style: TextStyle(fontSize: size * 0.45, fontWeight: FontWeight.w800, color: fg),
      ),
    );
  }
}

// ---------- progress bar you tap to seek ----------
class TapBar extends StatelessWidget {
  final double value;
  final ValueChanged<double>? onSeek;
  final double height;
  const TapBar({super.key, required this.value, this.onSeek, this.height = 22});

  @override
  Widget build(BuildContext context) {
    final fg = fgOf(context);
    return LayoutBuilder(
      builder: (context, c) {
        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapUp: onSeek == null ? null : (d) => onSeek!((d.localPosition.dx / c.maxWidth).clamp(0.0, 1.0)),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Container(
              height: height,
              decoration: BoxDecoration(border: Border.all(color: fg, width: 2)),
              alignment: Alignment.centerLeft,
              child: FractionallySizedBox(
                widthFactor: value.clamp(0.0, 1.0),
                child: Container(color: fg),
              ),
            ),
          ),
        );
      },
    );
  }
}

// ---------- throttled redraw for time displays ----------
class Ticking extends StatefulWidget {
  final WidgetBuilder builder;
  const Ticking({super.key, required this.builder});
  @override
  State<Ticking> createState() => _TickingState();
}

class _TickingState extends State<Ticking> {
  Timer? _t;
  int _secs = -1;

  void _setup() {
    final s = store.settings.refreshSecs;
    if (s == _secs) return;
    _secs = s;
    _t?.cancel();
    _t = null;
    if (s > 0) {
      _t = Timer.periodic(Duration(seconds: s), (_) {
        if (mounted && playback.playing) setState(() {});
      });
    }
  }

  @override
  void dispose() {
    _t?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    _setup();
    return ListenableBuilder(listenable: playback, builder: (c, _) => widget.builder(c));
  }
}

// ---------- book row ----------
class BookRow extends StatelessWidget {
  final Book book;
  final VoidCallback onTap;
  final VoidCallback? onLongPress;
  const BookRow({super.key, required this.book, required this.onTap, this.onLongPress});

  static double get height => store.settings.covers == 'off' ? 68 : 92;

  @override
  Widget build(BuildContext context) {
    final st = store.states[book.path];
    final isCurrent = playback.book?.path == book.path;
    String status;
    if (st?.finished == true) {
      status = 'Done ✓';
    } else if (st != null && st.started) {
      status = '${(store.progress(book) * 100).round()}%';
    } else {
      status = 'New';
    }
    final sub = [
      if (book.author.isNotEmpty) book.author,
      if (book.duration > Duration.zero) fmtLeft(book.duration),
    ].join(' · ');
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      onLongPress: onLongPress,
      onSecondaryTap: onLongPress,
      child: Container(
        decoration: BoxDecoration(
          border: Border(bottom: BorderSide(color: fgOf(context), width: 1)),
        ),
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(
          children: [
            if (store.settings.covers != 'off') ...[CoverImage(book, size: height - 14), const SizedBox(width: 12)],
            Expanded(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    (isCurrent ? '▶ ' : '') + book.title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w800),
                  ),
                  if (sub.isNotEmpty)
                    Text(sub, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 15)),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Text(status, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700)),
            const SizedBox(width: 4),
          ],
        ),
      ),
    );
  }
}
