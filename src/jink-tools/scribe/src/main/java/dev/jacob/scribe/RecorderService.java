package dev.jacob.scribe;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import java.io.File;
import java.io.IOException;

/**
 * Records 16 kHz mono audio in a foreground service, so recording carries on with the screen off
 * or while you use other apps. The UI reads the static state below.
 */
public class RecorderService extends Service {
  static final String START = "start", PAUSE = "pause", RESUME = "resume", STOP = "stop";
  private static final String CHANNEL = "recording";

  // Shared with the UI; only written by the recording thread / service.
  static volatile boolean recording, paused;
  static volatile long recordedMs;
  static volatile int level; // 0-100, loudness of the last moment
  static volatile String currentDir;
  static volatile String error;

  private Thread worker;

  static void send(Context c, String action, String dir) {
    Intent i = new Intent(c, RecorderService.class).setAction(action).putExtra("dir", dir);
    if (START.equals(action) && Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
    else c.startService(i);
  }

  @Override public IBinder onBind(Intent i) { return null; }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? STOP : intent.getAction();
    if (START.equals(action) && !recording) {
      goForeground();
      start(new File(intent.getStringExtra("dir")));
    } else if (PAUSE.equals(action)) {
      paused = true;
    } else if (RESUME.equals(action)) {
      paused = false;
    } else if (STOP.equals(action)) {
      recording = false;
      if (worker == null) stopSelf();
    }
    return START_NOT_STICKY;
  }

  private void goForeground() {
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW));
    PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    Notification n = new Notification.Builder(this, CHANNEL).setContentTitle("Scribe is recording")
        .setContentText("Tap to open").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(open).setOngoing(true).build();
    if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    else startForeground(1, n);
  }

  @SuppressLint("MissingPermission") // RECORD_AUDIO is granted by the activity before it starts us.
  private void start(File dir) {
    error = null;
    recordedMs = 0;
    level = 0;
    paused = false;
    currentDir = dir.getAbsolutePath();
    recording = true;
    worker = new Thread(() -> {
      int min = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
      AudioRecord rec = null;
      try (Wav.Writer w = new Wav.Writer(new File(dir, "audio.wav"), Wav.RATE, 1)) {
        rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Wav.RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, Wav.RATE));
        if (rec.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("The microphone is busy or unavailable");
        rec.startRecording();
        short[] buf = new short[Wav.RATE / 10];
        while (recording) {
          int n = rec.read(buf, 0, buf.length);
          if (n <= 0) continue;
          int peak = 0;
          for (int i = 0; i < n; i++) peak = Math.max(peak, Math.abs(buf[i]));
          level = Math.min(100, peak * 100 / 20000);
          if (paused) continue;
          w.write(buf, n);
          recordedMs = w.bytes() / 2 * 1000 / Wav.RATE;
        }
      } catch (IOException | RuntimeException e) {
        error = e.getMessage();
      } finally {
        if (rec != null) {
          try { rec.stop(); } catch (RuntimeException ignored) { /* never started */ }
          rec.release();
        }
        recording = false;
        worker = null;
        stopForeground(true);
        stopSelf();
      }
    }, "recorder");
    worker.start();
  }
}
