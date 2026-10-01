// Android media session + foreground service via audio_service.
// Notification/lock screen controls: rewind, play/pause, fast-forward.
import 'dart:async';

import 'package:audio_service/audio_service.dart';
import 'package:just_audio/just_audio.dart';

import 'library.dart';
import 'playback.dart';

class MonotoneHandler extends BaseAudioHandler with SeekHandler {
  final Playback pb;

  MonotoneHandler(this.pb) {
    pb.player.playbackEventStream.listen((_) => _broadcast(), onError: (_) => _broadcast());
    pb.player.playingStream.listen((_) => _broadcast());
    pb.player.speedStream.listen((_) => _broadcast());
    pb.onBookOpened = _setItem;
    // Keep the notification's progress bar honest without flooding updates.
    Timer.periodic(const Duration(seconds: 15), (_) {
      if (pb.player.playing) _broadcast();
    });
  }

  void _setItem(Book b) {
    mediaItem.add(
      MediaItem(
        id: b.path,
        title: b.title,
        artist: b.author.isEmpty ? null : b.author,
        album: b.shelf,
        duration: pb.duration > Duration.zero ? pb.duration : null,
        artUri: b.coverPath != null ? Uri.file(b.coverPath!) : null,
      ),
    );
    _broadcast();
  }

  void _broadcast() {
    final p = pb.player;
    final playing = p.playing;
    playbackState.add(
      playbackState.value.copyWith(
        controls: [MediaControl.rewind, playing ? MediaControl.pause : MediaControl.play, MediaControl.fastForward],
        systemActions: const {MediaAction.seek, MediaAction.seekForward, MediaAction.seekBackward},
        androidCompactActionIndices: const [0, 1, 2],
        processingState: const {
          ProcessingState.idle: AudioProcessingState.idle,
          ProcessingState.loading: AudioProcessingState.loading,
          ProcessingState.buffering: AudioProcessingState.buffering,
          ProcessingState.ready: AudioProcessingState.ready,
          ProcessingState.completed: AudioProcessingState.completed,
        }[p.processingState]!,
        playing: playing,
        updatePosition: p.position,
        bufferedPosition: p.bufferedPosition,
        speed: p.speed,
      ),
    );
  }

  @override
  Future<void> play() => pb.player.playing ? Future.value() : pb.togglePlay();
  @override
  Future<void> pause() => pb.player.playing ? pb.togglePlay() : Future.value();
  @override
  Future<void> stop() async {
    await pb.saveNow();
    await pb.player.pause();
  }

  @override
  Future<void> seek(Duration position) => pb.seek(position);
  @override
  Future<void> rewind() => pb.skipBack();
  @override
  Future<void> fastForward() => pb.skipForward();
  // Headset / car "next" and "previous" buttons also skip, like Audible.
  @override
  Future<void> skipToPrevious() => pb.skipBack();
  @override
  Future<void> skipToNext() => pb.skipForward();
  @override
  Future<void> click([MediaButton button = MediaButton.media]) async {
    switch (button) {
      case MediaButton.media:
        await pb.togglePlay();
      case MediaButton.next:
        await pb.skipForward();
      case MediaButton.previous:
        await pb.skipBack();
    }
  }
}
