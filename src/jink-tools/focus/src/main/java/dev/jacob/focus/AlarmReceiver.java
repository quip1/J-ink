package dev.jacob.focus;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Beeps (and buzzes, where there's a motor) when a focus or break period ends, even if the app is closed. */
@SuppressWarnings("deprecation") // VIBRATOR_SERVICE: the replacement needs API 31, we support 26+.
public class AlarmReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context c, Intent i) {
    PendingResult pending = goAsync();
    ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_ALARM, 90);
    tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 1500);
    Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
    if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 200, 400}, -1));
    new Handler(Looper.getMainLooper()).postDelayed(() -> {
      tone.release();
      pending.finish();
    }, 1800);
  }

  private static PendingIntent intent(Context c) {
    return PendingIntent.getBroadcast(c, 1, new Intent(c, AlarmReceiver.class),
        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
  }

  static void schedule(Context c, long at) {
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
    if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c));
    else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(c));
  }

  static void cancel(Context c) {
    ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(intent(c));
  }
}
