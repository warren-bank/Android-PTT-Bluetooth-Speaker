package com.github.warren_bank.ptt_bluetooth_speaker.receiver.service;

import com.github.warren_bank.ptt_bluetooth_speaker.receiver.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.widget.RemoteViews;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.InputStream;
import java.util.UUID;

public class NetworkingService extends Service {
  private static final int      DISCOVERABLE_TIMEOUT_SEC = 60;
  private static final int      NOTIFICATION_ID          = 1;
  private static final String   ACTION_STOP              = "STOP";

  private static final String   tag                      = NetworkingService.class.getSimpleName();
  private static final int      bufferSize               = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
  private static final float    audioGain                = AudioTrack.getMaxVolume(); // max gain >= 1.0

  private BluetoothAdapter      btAdapter;
  private UUID                  uuid;
  private BluetoothServerSocket server;
  private BluetoothSocket       socket;
  private InputStream           audioStream;
  private AudioTrack            speaker;

  private BroadcastReceiver     btAdapterStateChangeReceiver;
  private boolean               didChangeBtAdapterState;

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(tag, "starting bluetooth socket server");

    btAdapter   = BluetoothAdapter.getDefaultAdapter();
    uuid        = UUID.fromString(getString(R.string.server_uuid));
    server      = null;
    socket      = null;
    audioStream = null;
    speaker     = new AudioTrack(AudioManager.STREAM_MUSIC, 16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);

    speaker.pause();
    speaker.flush();

    if (Build.VERSION.SDK_INT >= 21) {
      speaker.setVolume(audioGain);
    }
    else {
      speaker.setStereoVolume(audioGain, audioGain);
    }

    /**
     * Handle an intent that is broadcast by the Bluetooth adapter whenever it changes its state.
     * Action is {@link BluetoothAdapter#ACTION_STATE_CHANGED}.
     * Extras describe the old and the new states.
     */
    btAdapterStateChangeReceiver = new BroadcastReceiver() {
      public void onReceive(Context context, Intent intent) {
        int oldState = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, -1);
        int newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);

        switch(newState) {
          case BluetoothAdapter.STATE_ON :
            startServerThread();
            break;
          case BluetoothAdapter.STATE_TURNING_OFF :
          case BluetoothAdapter.STATE_OFF :
            shutdown(true);
            break;
        }
      }
    };

    showNotification();
    registerReceiver(btAdapterStateChangeReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

    if (btAdapter.isEnabled()) {
      didChangeBtAdapterState = false;
      startServerThread();
    }
    else {
      didChangeBtAdapterState = true;
      enableBT();
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    onStart(intent, startId);
    return START_STICKY;
  }

  @Override
  public void onStart(Intent intent, int startId) {
    processIntent(intent);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(tag, "stopping bluetooth socket server");

    unregisterReceiver(btAdapterStateChangeReceiver);
    hideNotification();

    shutdown(false);
    close_speaker();

    if (didChangeBtAdapterState)
      disableBT();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  // -------------------------------------------------------------------------
  // house cleaning

  private void close_server() {
    if (server != null) {
      try {
        server.close();
      }
      catch(Exception e) {}
      finally {
        server = null;
      }
    }
  }

  private void close_socket() {
    if (socket != null) {
      try {
        socket.close();
      }
      catch(Exception e) {}
      finally {
        socket = null;
      }
    }
  }

  private void close_audioStream() {
    if (audioStream != null) {
      try {
        audioStream.close();
      }
      catch(Exception e) {}
      finally {
        audioStream = null;
      }
    }
  }

  private void close_speaker() {
    if (speaker != null) {
      try {
        speaker.release();
      }
      catch(Exception e) {}
      finally {
        speaker = null;
      }
    }
  }

  private void shutdown(boolean killService) {
    close_audioStream();
    close_socket();
    close_server();

    if (killService) stopSelf();
  }

  // -------------------------------------------------------------------------
  // foregrounding..

  private String getNotificationChannelId() {
    return getPackageName();
  }

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= 26) {
      String channelId       = getNotificationChannelId();
      NotificationManager NM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      NotificationChannel NC = new NotificationChannel(channelId, channelId, NotificationManager.IMPORTANCE_HIGH);

      NC.setDescription(channelId);
      NC.setSound(null, null);
      NM.createNotificationChannel(NC);
    }
  }

  private void showNotification() {
    Notification notification = getNotification();

    if (Build.VERSION.SDK_INT >= 5) {
      createNotificationChannel();
      startForeground(NOTIFICATION_ID, notification);
    }
    else {
      NotificationManager NM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      NM.notify(NOTIFICATION_ID, notification);
    }
  }

  private void hideNotification() {
    if (Build.VERSION.SDK_INT >= 5) {
      stopForeground(true);
    }
    else {
      NotificationManager NM = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
      NM.cancel(NOTIFICATION_ID);
    }
  }

  private Notification getNotification() {
    Notification notification  = (Build.VERSION.SDK_INT >= 26)
      ? (new Notification.Builder(/* context= */ NetworkingService.this, /* channelId= */ getNotificationChannelId())).build()
      :  new Notification()
    ;

    notification.when          = System.currentTimeMillis();
    notification.flags         = 0;
    notification.flags        |= Notification.FLAG_ONGOING_EVENT;
    notification.flags        |= Notification.FLAG_NO_CLEAR;
    notification.icon          = R.drawable.phone_bluetooth_speaker;
    notification.tickerText    = getString(R.string.notification_service_ticker);
    notification.contentIntent = getPendingIntent_StopService();
    notification.deleteIntent  = getPendingIntent_StopService();

    if (Build.VERSION.SDK_INT >= 16) {
      notification.priority    = Notification.PRIORITY_HIGH;
    }
    else {
      notification.flags      |= Notification.FLAG_HIGH_PRIORITY;
    }

    if (Build.VERSION.SDK_INT >= 21) {
      notification.visibility  = Notification.VISIBILITY_PUBLIC;
    }

    RemoteViews contentView    = new RemoteViews(getPackageName(), R.layout.service_notification);
    contentView.setImageViewResource(R.id.notification_icon,  R.drawable.phone_bluetooth_speaker);
    contentView.setTextViewText(R.id.notification_text_line1, getString(R.string.notification_service_content_line1));
    contentView.setTextViewText(R.id.notification_text_line2, getString(R.string.notification_service_content_line2));
    notification.contentView   = contentView;

    return notification;
  }

  private PendingIntent getPendingIntent_StopService() {
    Intent intent = new Intent(NetworkingService.this, NetworkingService.class);
    intent.setAction(ACTION_STOP);

    return PendingIntent.getService(NetworkingService.this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
  }

  // -------------------------------------------------------------------------
  // process inbound intents

  private void processIntent(Intent intent) {
    if (intent == null) return;

    String action = intent.getAction();
    if (action == ACTION_STOP)
      shutdown(true);
  }

  // -------------------------------------------------------------------------
  // bluetooth

  private void enableBT() {
    try {
      if (!btAdapter.isEnabled())
        btAdapter.enable();
    }
    catch(Exception e) {}
  }

  private void disableBT() {
    try {
      if (btAdapter.isEnabled())
        btAdapter.disable();
    }
    catch(Exception e) {}
  }

  private void startServerThread() {
    Thread thread = new Thread(
      new Runnable() {
        @Override
        public void run() {
          startServer(/* shouldMakeDiscoverable= */ true);
        }
      },
      "startServer Thread"
    );
    thread.start();
  }

  private void startServer() {
    startServer(/* shouldMakeDiscoverable= */ false);
  }

  private void startServer(boolean shouldMakeDiscoverable) {
    if (server != null)
      return;
    if ((socket != null) && (audioStream != null))
      return;

    try {
      server = btAdapter.listenUsingInsecureRfcommWithServiceRecord(tag, uuid);
    }
    catch(Exception e) {
      Log.e(tag, "startServer", e);
      showToast("Bluetooth server registration failed");
      shutdown(true);
      return;
    }

    if (shouldMakeDiscoverable)
      makeDiscoverable();

    waitForClientConnection();
  }

  /**
   * Make the current {@link BluetoothAdapter} discoverable (available for pairing)
   * for the next {@link #DISCOVERABLE_TIMEOUT_SEC} seconds.
   */
  private void makeDiscoverable() {
    if (btAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
      Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
      discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_TIMEOUT_SEC);
      discoverableIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |Intent.FLAG_ACTIVITY_CLEAR_TASK);
      startActivity(discoverableIntent);
    }
  }

  private void waitForClientConnection() {
    if (socket != null)
      return;

    if (server == null) {
      startServer();
      return;
    }

    while (socket == null) {
      try {
        socket = server.accept();
      }
      catch(Exception e) {
        socket = null;
      }
    }
    close_server();
    getAudioStream();
  }

  private void getAudioStream() {
    if (audioStream != null)
      return;

    if (socket == null) {
      waitForClientConnection();
      return;
    }

    try {
      audioStream = socket.getInputStream();
    }
    catch(Exception e) {
      audioStream = null;
      close_socket();
      waitForClientConnection();
      return;
    }
    pipeAudioToSink();
  }

  private void pipeAudioToSink() {
    byte[] buffer = new byte[bufferSize];
    int bytesRead;

    speaker.play();

    while (true) {
      try {
        bytesRead = audioStream.read(buffer);

        if (bytesRead == -1)
          break;
      }
      catch(Exception e) {
        break;
      }

      speaker.write(buffer, 0, buffer.length);
    }

    // client has disconnected
    speaker.pause();
    speaker.flush();

    shutdown(false);
    startServer();
  }

  // -------------------------------------------------------------------------
  // helpers

  private void showToast_UiThread(String text) {
    Toast toast = Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG);
    toast.setGravity(Gravity.CENTER, 0, 0);
    toast.show();
  }

  private void showToast_NonUiThread(String text) {
    new Handler(Looper.getMainLooper()).post(new Runnable() {
      public void run() {
        showToast_UiThread(text);
      }
    });
  }

  private void showToast(String text) {
    showToast_NonUiThread(text);
  }

}
