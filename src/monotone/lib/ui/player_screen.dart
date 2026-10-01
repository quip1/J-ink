import 'package:flutter/material.dart';

import '../app_state.dart';
import '../library.dart';
import 'widgets.dart';

class PlayerScreen extends StatelessWidget {
  const PlayerScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: ListenableBuilder(
            listenable: Listenable.merge([store, playback]),
            builder: (context, _) {
              final b = playback.book;
              if (b == null) {
                return Column(
                  children: [
                    Row(children: [EButton('← Back', onTap: () => Navigator.pop(context))]),
                    const Expanded(
                      child: Center(child: Text('Nothing playing.', style: TextStyle(fontSize: 20))),
                    ),
                  ],
                );
              }
              return LayoutBuilder(
                builder: (context, c) {
                  final wide = c.maxWidth > 700 && c.maxWidth > c.maxHeight;
                  final showCover = store.settings.covers != 'off';
                  final info = _InfoBlock(book: b);
                  const controls = _Controls();
                  final header = Row(
                    children: [
                      EButton('← Library', onTap: () => Navigator.pop(context)),
                      const Spacer(),
                      EButton('Info', onTap: () => showBookMenu(context, b)),
                    ],
                  );
                  // Cover takes whatever space is left and shrinks (or hides) to fit.
                  Widget cover() => LayoutBuilder(
                    builder: (context, cc) {
                      final sz = cc.maxWidth < cc.maxHeight ? cc.maxWidth : cc.maxHeight;
                      if (sz < 72) return const SizedBox.shrink();
                      return Center(child: CoverImage(b, size: sz));
                    },
                  );
                  if (wide) {
                    return Column(
                      children: [
                        header,
                        const SizedBox(height: 12),
                        Expanded(
                          child: Row(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              if (showCover) ...[
                                SizedBox(width: c.maxWidth * 0.38, child: cover()),
                                const SizedBox(width: 20),
                              ],
                              Expanded(
                                child: SingleChildScrollView(
                                  child: Column(children: [info, const SizedBox(height: 16), controls]),
                                ),
                              ),
                            ],
                          ),
                        ),
                      ],
                    );
                  }
                  return Column(
                    children: [
                      header,
                      const SizedBox(height: 8),
                      Expanded(
                        child: showCover
                            ? Padding(padding: const EdgeInsets.only(bottom: 8), child: cover())
                            : const SizedBox(),
                      ),
                      info,
                      const SizedBox(height: 8),
                      controls,
                    ],
                  );
                },
              );
            },
          ),
        ),
      ),
    );
  }
}

class _InfoBlock extends StatelessWidget {
  final Book book;
  const _InfoBlock({required this.book});

  @override
  Widget build(BuildContext context) {
    final by = [
      if (book.author.isNotEmpty) book.author,
      if (book.narrator.isNotEmpty && book.narrator != book.author) 'read by ${book.narrator}',
    ].join(' · ');
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          book.title,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
        ),
        if (by.isNotEmpty)
          Text(
            by,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: const TextStyle(fontSize: 16),
          ),
        if (playback.error != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(playback.error!, style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
          ),
        const SizedBox(height: 12),
        Ticking(
          builder: (context) {
            final pos = playback.position;
            final dur = playback.duration;
            final ci = playback.chapterIndexAt(pos);
            final chs = playback.chapters;
            final cs = playback.chapterStart(ci), ce = playback.chapterEnd(ci);
            final cLen = ce - cs;
            final cPos = pos - cs;
            final left = dur - pos;
            final spd = playback.speed;
            final leftAtSpeed = Duration(microseconds: (left.inMicroseconds / spd).round());
            return Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                GestureDetector(
                  onTap: () => pushPage(context, const ChaptersPage()),
                  child: Text(
                    chs.length > 1 ? 'Ch ${ci + 1}/${chs.length} · ${chs[ci].title}' : chs[ci].title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
                  ),
                ),
                TapBar(
                  value: cLen.inMilliseconds == 0 ? 0 : cPos.inMilliseconds / cLen.inMilliseconds,
                  onSeek: (f) => playback.seek(cs + cLen * f),
                ),
                Row(
                  children: [
                    Text(fmtDur(cPos), style: const TextStyle(fontSize: 16)),
                    const Spacer(),
                    Text('-${fmtDur(ce - pos)}', style: const TextStyle(fontSize: 16)),
                  ],
                ),
                const SizedBox(height: 10),
                Text('Book', style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
                TapBar(
                  height: 12,
                  value: dur.inMilliseconds == 0 ? 0 : pos.inMilliseconds / dur.inMilliseconds,
                  onSeek: (f) => playback.seek(dur * f),
                ),
                Row(
                  children: [
                    Text('${fmtDur(pos)} / ${fmtDur(dur)}', style: const TextStyle(fontSize: 14)),
                    const Spacer(),
                    Text(
                      spd == 1.0 ? '${fmtLeft(left)} left' : '${fmtLeft(leftAtSpeed)} left at ${fmtSpeed(spd)}',
                      style: const TextStyle(fontSize: 14),
                    ),
                  ],
                ),
                if (store.settings.refreshSecs == 0)
                  Align(
                    alignment: Alignment.centerRight,
                    child: Padding(
                      padding: const EdgeInsets.only(top: 6),
                      child: EButton('Refresh', onTap: () => (context as Element).markNeedsBuild()),
                    ),
                  ),
              ],
            );
          },
        ),
      ],
    );
  }
}

class _Controls extends StatelessWidget {
  const _Controls();

  @override
  Widget build(BuildContext context) {
    final s = store.settings;
    final sleep = playback.sleepRemaining;
    Widget cell(Widget w) => Expanded(
      child: Padding(padding: const EdgeInsets.all(3), child: w),
    );
    return Column(
      children: [
        Row(
          children: [
            cell(
              EButton('Previous chapter', icon: Icons.skip_previous_rounded, big: true, onTap: playback.prevChapter),
            ),
            cell(EButton('−${s.skipBack}', big: true, fontSize: 22, onTap: playback.skipBack)),
            Expanded(
              flex: 2,
              child: Padding(
                padding: const EdgeInsets.all(3),
                child: EButton(
                  playback.playing ? 'Pause' : 'Play',
                  icon: playback.playing ? Icons.pause_rounded : Icons.play_arrow_rounded,
                  fontSize: 52,
                  big: true,
                  selected: true,
                  onTap: playback.togglePlay,
                ),
              ),
            ),
            cell(EButton('+${s.skipFwd}', big: true, fontSize: 22, onTap: playback.skipForward)),
            cell(EButton('Next chapter', icon: Icons.skip_next_rounded, big: true, onTap: playback.nextChapter)),
          ],
        ),
        Row(
          children: [
            cell(EButton('Speed ${fmtSpeed(playback.speed)}', onTap: () => showSpeedDialog(context))),
            cell(
              EButton(
                sleep == null
                    ? 'Sleep'
                    : playback.sleepChapterEnd != null
                    ? 'Sleep: ch end'
                    : 'Sleep ${fmtLeft(sleep)}',
                selected: sleep != null,
                onTap: () => showSleepDialog(context),
              ),
            ),
          ],
        ),
        Row(
          children: [
            cell(EButton('Chapters', onTap: () => pushPage(context, const ChaptersPage()))),
            cell(EButton('Bookmarks', onTap: () => pushPage(context, const BookmarksPage()))),
            cell(EButton('+ Mark', onTap: () => showAddBookmark(context))),
          ],
        ),
      ],
    );
  }
}

// ---------- dialogs ----------
void showSpeedDialog(BuildContext context) {
  showEDialog(context, (ctx) {
    return ListenableBuilder(
      listenable: playback,
      builder: (ctx, _) {
        final sp = playback.speed;
        const presets = [0.8, 1.0, 1.1, 1.2, 1.3, 1.5, 1.75, 2.0, 2.25, 2.5, 3.0, 3.5];
        return Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('Playback speed', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
            const SizedBox(height: 10),
            Row(
              children: [
                EButton('−0.05', onTap: () => playback.setSpeed(sp - 0.05)),
                Expanded(
                  child: Text(
                    fmtSpeed(sp),
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 34, fontWeight: FontWeight.w800),
                  ),
                ),
                EButton('+0.05', onTap: () => playback.setSpeed(sp + 0.05)),
              ],
            ),
            const SizedBox(height: 10),
            Wrap(
              spacing: 6,
              runSpacing: 6,
              children: [
                for (final p in presets)
                  SizedBox(
                    width: 72,
                    child: EButton(fmtSpeed(p), selected: (sp - p).abs() < 0.001, onTap: () => playback.setSpeed(p)),
                  ),
              ],
            ),
            const SizedBox(height: 12),
            EButton(
              'Make ${fmtSpeed(sp)} the default for new books',
              onTap: () {
                store.settings.defaultSpeed = sp;
                store.saveSettings();
              },
              fontSize: 14,
            ),
            const SizedBox(height: 6),
            EButton('Done', onTap: () => Navigator.pop(ctx)),
          ],
        );
      },
    );
  });
}

void showSleepDialog(BuildContext context) {
  showEDialog(context, (ctx) {
    void pick(Duration? d) {
      playback.setSleep(d);
      Navigator.pop(ctx);
    }

    const mins = [5, 10, 15, 20, 30, 45, 60, 90];
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text('Sleep timer', style: TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
        const SizedBox(height: 4),
        const Text('Fades out over the last 10 seconds.', style: TextStyle(fontSize: 14)),
        const SizedBox(height: 10),
        Wrap(
          spacing: 6,
          runSpacing: 6,
          children: [
            for (final m in mins)
              SizedBox(
                width: 90,
                child: EButton('$m min', onTap: () => pick(Duration(minutes: m))),
              ),
          ],
        ),
        const SizedBox(height: 8),
        EButton(
          'End of this chapter',
          onTap: () {
            playback.setSleepEndOfChapter();
            Navigator.pop(ctx);
          },
        ),
        const SizedBox(height: 6),
        EButton('Turn off', onTap: () => pick(null)),
      ],
    );
  });
}

void showAddBookmark(BuildContext context) {
  final pos = playback.position;
  final ctrl = TextEditingController();
  showEDialog(context, (ctx) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('Bookmark at ${fmtDur(pos)}', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
        const SizedBox(height: 10),
        TextField(
          controller: ctrl,
          autofocus: true,
          decoration: const InputDecoration(hintText: 'Note (optional)'),
        ),
        const SizedBox(height: 10),
        Row(
          children: [
            Expanded(child: EButton('Cancel', onTap: () => Navigator.pop(ctx))),
            const SizedBox(width: 6),
            Expanded(
              child: EButton(
                'Save',
                selected: true,
                onTap: () {
                  playback.addBookmark(ctrl.text.trim());
                  Navigator.pop(ctx);
                },
              ),
            ),
          ],
        ),
      ],
    );
  });
}

void showBookMenu(BuildContext context, Book b) {
  showEDialog(context, (ctx) {
    final st = store.stateFor(b.path);
    final rows = <(String, String)>[
      if (b.author.isNotEmpty) ('Author', b.author),
      if (b.narrator.isNotEmpty) ('Narrator', b.narrator),
      ('Length', b.duration > Duration.zero ? fmtDur(b.duration) : 'unknown'),
      ('Chapters', '${b.chapters.isEmpty ? 1 : b.chapters.length}'),
      ('Shelf', b.shelf),
      ('Progress', st.finished ? 'Finished' : '${(store.progress(b) * 100).round()}%'),
      ('File', b.path),
    ];
    return ConstrainedBox(
      constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(ctx).height * 0.8),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(b.title, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w800)),
          const SizedBox(height: 8),
          Flexible(
            child: SingleChildScrollView(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  for (final r in rows)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 4),
                      child: Text.rich(
                        TextSpan(
                          children: [
                            TextSpan(
                              text: '${r.$1}: ',
                              style: const TextStyle(fontWeight: FontWeight.w800),
                            ),
                            TextSpan(text: r.$2),
                          ],
                        ),
                        style: const TextStyle(fontSize: 15),
                      ),
                    ),
                  if (b.description.isNotEmpty) ...[
                    const SizedBox(height: 6),
                    Text(b.description, style: const TextStyle(fontSize: 15)),
                  ],
                ],
              ),
            ),
          ),
          const SizedBox(height: 10),
          if (playback.book?.path != b.path)
            Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: EButton(
                'Play',
                selected: true,
                onTap: () {
                  Navigator.pop(ctx);
                  playback.open(b);
                },
              ),
            ),
          Row(
            children: [
              Expanded(
                child: EButton(
                  st.finished ? 'Mark unfinished' : 'Mark finished',
                  onTap: () {
                    playback.setFinished(b, !st.finished);
                    Navigator.pop(ctx);
                  },
                  fontSize: 14,
                ),
              ),
              const SizedBox(width: 6),
              Expanded(
                child: EButton(
                  'Reset progress',
                  onTap: () {
                    playback.setFinished(b, false);
                    Navigator.pop(ctx);
                  },
                  fontSize: 14,
                ),
              ),
            ],
          ),
          const SizedBox(height: 6),
          EButton('Close', onTap: () => Navigator.pop(ctx)),
        ],
      ),
    );
  });
}

// ---------- chapters & bookmarks pages ----------
class ChaptersPage extends StatelessWidget {
  const ChaptersPage({super.key});

  @override
  Widget build(BuildContext context) {
    final chs = playback.chapters;
    final cur = playback.chapterIndex;
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            children: [
              Row(
                children: [
                  EButton('← Back', onTap: () => Navigator.pop(context)),
                  const SizedBox(width: 12),
                  const Expanded(
                    child: Text('Chapters', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800)),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Expanded(
                child: PagedList(
                  itemCount: chs.length,
                  itemHeight: 56,
                  focusIndex: cur,
                  itemBuilder: (context, i) {
                    final sel = i == cur;
                    final len = playback.chapterEnd(i) - chs[i].start;
                    return GestureDetector(
                      behavior: HitTestBehavior.opaque,
                      onTap: () {
                        playback.seek(chs[i].start);
                        if (!playback.playing) playback.togglePlay();
                        Navigator.pop(context);
                      },
                      child: Container(
                        color: sel ? fgOf(context) : null,
                        padding: const EdgeInsets.symmetric(horizontal: 8),
                        decoration: sel
                            ? null
                            : BoxDecoration(
                                border: Border(bottom: BorderSide(color: fgOf(context), width: 1)),
                              ),
                        child: DefaultTextStyle.merge(
                          style: TextStyle(color: sel ? bgOf(context) : fgOf(context), fontSize: 17),
                          child: Row(
                            children: [
                              SizedBox(
                                width: 44,
                                child: Text('${i + 1}', style: const TextStyle(fontWeight: FontWeight.w800)),
                              ),
                              Expanded(child: Text(chs[i].title, maxLines: 1, overflow: TextOverflow.ellipsis)),
                              Text(fmtDur(len)),
                            ],
                          ),
                        ),
                      ),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class BookmarksPage extends StatelessWidget {
  const BookmarksPage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: ListenableBuilder(
            listenable: playback,
            builder: (context, _) {
              final bms = playback.state?.bookmarks ?? [];
              return Column(
                children: [
                  Row(
                    children: [
                      EButton('← Back', onTap: () => Navigator.pop(context)),
                      const SizedBox(width: 12),
                      const Expanded(
                        child: Text('Bookmarks', style: TextStyle(fontSize: 22, fontWeight: FontWeight.w800)),
                      ),
                      EButton('+ Mark', onTap: () => showAddBookmark(context)),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Expanded(
                    child: PagedList(
                      itemCount: bms.length,
                      itemHeight: 68,
                      emptyText: 'No bookmarks yet.\nTap "+ Mark" while listening.',
                      itemBuilder: (context, i) {
                        final bm = bms[i];
                        final ch = playback.chapters[playback.chapterIndexAt(bm.pos)];
                        return Container(
                          decoration: BoxDecoration(
                            border: Border(bottom: BorderSide(color: fgOf(context), width: 1)),
                          ),
                          child: Row(
                            children: [
                              Expanded(
                                child: GestureDetector(
                                  behavior: HitTestBehavior.opaque,
                                  onTap: () {
                                    playback.seek(bm.pos);
                                    Navigator.pop(context);
                                  },
                                  child: Column(
                                    mainAxisAlignment: MainAxisAlignment.center,
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        '${fmtDur(bm.pos)} · ${ch.title}',
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
                                      ),
                                      if (bm.note.isNotEmpty)
                                        Text(
                                          bm.note,
                                          maxLines: 1,
                                          overflow: TextOverflow.ellipsis,
                                          style: const TextStyle(fontSize: 15),
                                        ),
                                    ],
                                  ),
                                ),
                              ),
                              EButton('✕', onTap: () => playback.removeBookmark(bm)),
                            ],
                          ),
                        );
                      },
                    ),
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}
