package com.github.warren_bank.ptt_bluetooth_speaker.client;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {
  private static final int      PERMISSIONS_REQUEST_CODE = 0;
  private static final String   tag                      = MainActivity.class.getSimpleName();
  private static final int      bufferSize               = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

  private TextView              textView;
  private ListView              pairedDevices;
  private ImageButton           microphoneToggle;
  private SeekBar               microphoneVolume;

  private BluetoothAdapter      btAdapter;
  private UUID                  uuid;
  private BluetoothDevice       receiverDevice;
  private BluetoothSocket       socket;
  private OutputStream          audioStream;
  private AudioRecord           microphoneRecorder;
  private Thread                thread;
  private boolean               isRecording;
  private float                 volumeGain;  // range: 0.0 to 2.0

  private BroadcastReceiver     btAdapterStateChangeReceiver;
  private boolean               didChangeBtAdapterState;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    textView         = (TextView)    findViewById(R.id.text_view);
    pairedDevices    = (ListView)    findViewById(R.id.paired_devices);
    microphoneToggle = (ImageButton) findViewById(R.id.microphone_toggle);
    microphoneVolume = (SeekBar)     findViewById(R.id.microphone_volume);

    btAdapter          = BluetoothAdapter.getDefaultAdapter();
    uuid               = UUID.fromString(getString(R.string.server_uuid));
    receiverDevice     = null;
    socket             = null;
    audioStream        = null;
    microphoneRecorder = null;
    thread             = null;
    isRecording        = false;
    volumeGain         = getVolumeGain(microphoneVolume.getProgress());

    /**
     * Handle an intent that is broadcast by the Bluetooth adapter whenever it changes its state.
     * Action is {@link BluetoothAdapter#ACTION_STATE_CHANGED}.
     * Extras describe the old and the new states.
     */
    btAdapterStateChangeReceiver = new BroadcastReceiver() {
      public void onReceive(Context context, Intent intent) {
        close_audioStream();
        close_socket();
        showPairedDevices();
      }
    };

    // hide UI
    reset_views();

    // first: check runtime permission access to microphone
    checkPermissions();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();

    unregisterReceiver(btAdapterStateChangeReceiver);

    close_microphoneRecorder();
    close_audioStream();
    close_socket();

    disableBT();
  }

  // -------------------------------------------------------------------------
  // house cleaning

  private void close_socket() {
    close_socket(/* keepDevice= */ false);
  }

  private void close_socket(boolean keepDevice) {
    if (socket != null) {
      try {
        socket.close();
      }
      catch(Exception e) {}
      finally {
        socket = null;

        if (!keepDevice)
          receiverDevice = null;
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

  private void close_microphoneRecorder() {
    if (microphoneRecorder != null) {
      try {
        if (isRecording) {
          isRecording = false;
          microphoneRecorder.stop();
        }
      }
      catch(Exception e) {}

      try {
        microphoneRecorder.release();
      }
      catch(Exception e) {}
      finally {
        microphoneRecorder = null;
      }
    }
  }

  private void reset_views() {
    textView.setText("");
    pairedDevices.setAdapter(null);
    pairedDevices.setVisibility(View.GONE);
    microphoneToggle.setVisibility(View.GONE);
    microphoneVolume.setVisibility(View.GONE);
  }

  // -------------------------------------------------------------------------
  // runtime permissions

  private void checkPermissions() {
    if (Build.VERSION.SDK_INT < 23) {
      passedPermissionsCheck();
    }
    else {
      String permission = Manifest.permission.RECORD_AUDIO;
      requestPermissions(new String[]{permission}, PERMISSIONS_REQUEST_CODE);
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);

    if (requestCode == PERMISSIONS_REQUEST_CODE) {
      if ((grantResults.length > 0) && (grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
        passedPermissionsCheck();
      }
      else {
        showToast(getString(R.string.toast_permissions_required));
        finish();
      }
    }
  }

  private void passedPermissionsCheck() {
    microphoneRecorder = new AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);

    registerReceiver(btAdapterStateChangeReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

    addClickHandlers();
    enableBT();
    showPairedDevices();
  }

  // -------------------------------------------------------------------------
  // add click handlers to visible elements

  private void addClickHandlers() {
    addPairedDeviceClickHander();
    addMicrophoneToggleClickHandler();
    addMicrophoneVolumeChangeHandler();
  }

  private void addPairedDeviceClickHander() {
    pairedDevices.setOnItemClickListener(new AdapterView.OnItemClickListener() {
      @Override
      public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        try {
          String receiverMacAddress = devices.get(position).getAddress();
          receiverDevice = btAdapter.getRemoteDevice(receiverMacAddress);

          openSocketToReceiver();
        }
        catch(Exception e) {
          close_audioStream();
          close_socket();

          showToast(getString(R.string.toast_connection_failed));
          return;
        }
        showConnectedReceiver();
      }
    });
  }

  private void addMicrophoneToggleClickHandler() {
    microphoneToggle.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        isRecording = !isRecording;

        int resource_id = (isRecording)
          ? R.drawable.microphone_active
          : R.drawable.microphone_mute;

        // change visible image that represents the active state of the microphone
        microphoneToggle.setImageBitmap(BitmapFactory.decodeResource(getResources(), resource_id));

        // start a new Thread that will automatically stop when recording is toggled off
        if (isRecording)
          pipeAudioToReceiver();
      }
    });
  }

  private void addMicrophoneVolumeChangeHandler() {
    microphoneVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        volumeGain = getVolumeGain(progress);
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {}

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {}
    });
  }

  private float getVolumeGain(int progress) {
    // progress is an integer in the range: 0 to 2000
    return (float) progress / 1000;
  }

  // -------------------------------------------------------------------------
  // bluetooth

  private void enableBT() {
    try {
      if (!btAdapter.isEnabled()) {
        btAdapter.enable();
        didChangeBtAdapterState = true;
      }
      else {
        didChangeBtAdapterState = false;
      }
    }
    catch(Exception e) {}
  }

  private void disableBT() {
    try {
      if (didChangeBtAdapterState && btAdapter.isEnabled())
        btAdapter.disable();
    }
    catch(Exception e) {}
  }

  // -------------------------------------------------------------------------
  // show paired devices

  private final class DeviceInfo {
    private String name;
    private String address;

    public DeviceInfo(BluetoothDevice device) {
      this(device.getName(), device.getAddress());
    }

    public DeviceInfo(String name, String address) {
      this.name    = name;
      this.address = address;
    }

    public String getName() {
      return name;
    }

    public String getAddress() {
      return address;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private ArrayList<DeviceInfo> devices;

  private void showPairedDevices() {
    reset_views();

    devices = new ArrayList<DeviceInfo>();

    Set<BluetoothDevice> bluetoothDevices = btAdapter.getBondedDevices();
    for (BluetoothDevice device : bluetoothDevices) {
      devices.add(new DeviceInfo(device));
    }

    if (devices.size() == 0) {
      devices.add(new DeviceInfo(getString(R.string.listview_no_devices), null));
    }

    ArrayAdapter<DeviceInfo> adapter = new ArrayAdapter<DeviceInfo>(MainActivity.this, android.R.layout.simple_list_item_1, devices);

    textView.setText(R.string.heading_paired_devices);

    pairedDevices.setAdapter(adapter);
    pairedDevices.setVisibility(View.VISIBLE);
  }

  // -------------------------------------------------------------------------
  // show connected receiver

  private void showConnectedReceiver() {
    reset_views();

    textView.setText(R.string.heading_connected_receiver);

    microphoneToggle.setVisibility(View.VISIBLE);
    microphoneVolume.setVisibility(View.VISIBLE);
  }

  // -------------------------------------------------------------------------
  // start a Thread to pipe audio from microphone through outbound socket

  private void openSocketToReceiver() throws Exception {
    // open socket connection
    socket = receiverDevice.createRfcommSocketToServiceRecord(uuid);
    socket.connect();

    audioStream = socket.getOutputStream();
  }

  private void pipeAudioToReceiver() {
    // prevent the user from starting multiple threads by toggling the microphone button too quickly
    if ((thread != null) && (thread.isAlive()))
      return;

    thread = new Thread(
      new Runnable() {
        @Override
        public void run() {
          byte[] buffer = new byte[bufferSize];
          int bytesRead = 0;
          int retry_count = 0;
          int retry_limit = 10;
          int status = 0;

          microphoneRecorder.startRecording();
          while (isRecording && (microphoneRecorder != null)) {
            bytesRead = microphoneRecorder.read(buffer, 0, bufferSize);

            if (bytesRead < 0) {
              // error reading audio from microphone
              //  1) check whether the app is closing
              //  2) continue looping to try again,
              //     but keep a count of retry attempts
              //  3) give up after failing a max number of attempts

              if (isFinishing() || (microphoneRecorder == null)) {
                status = 1;
                break;
              }

              retry_count++;
              if (retry_count <= retry_limit) continue;

              status = 2;
              break;
            }
            else {
              retry_count = 0;
            }

            applyVolumeGain(buffer, bytesRead);

            try {
              audioStream.write(buffer);
            }
            catch(Exception e) {
              // error writing audio stream to socket
              //  1) check whether the app is closing
              //  2) close socket
              //  3) try to reconnect to same receiver
              //  4) give up and show list of paired devices (again)

              if (isFinishing()) {
                status = 1;
                break;
              }

              close_audioStream();
              close_socket(true);

              try {
                openSocketToReceiver();
                continue;
              }
              catch(Exception e2) {
                status = 3;
                break;
              }
            }
          }

          try {
            microphoneRecorder.stop();
          }
          catch(Exception e) {
            if ((status == 0) && !isFinishing())
              status = 2;
          }

          switch(status) {
            case 0:
              // user toggled microphone to mute
              break;
            case 1:
              // the app is closing
              break;
            case 2:
              // problem with microphone
              showToast_NonUiThread(getString(R.string.toast_microphone_failed));
              finish();
              break;
            case 3:
              // problem with socket
              close_audioStream();
              close_socket();
              showPairedDevices_NonUiThread();
              break;
          }
        }
      },
      "pipeAudioToReceiver Thread"
    );
    thread.start();
  }

  /**
   * Scale the amplitude of 16-bit audio samples by a gain factor between 0 and 2
   *
   * `volumeGain` is the value of `microphoneVolume` SeekBar,
   * converted to a float within the range: 0.0 to 2.0
   *
   * Based on the answer:
   *   https://stackoverflow.com/a/26037576
   */
  private void applyVolumeGain(byte[] buffer, int bytesRead) {
    short buf1, buf2, res;

    if (bytesRead < 2) return;

    for (int i = 0; (i+1) < bytesRead; i+=2) {
      // convert byte pair to int
      buf1 = buffer[i+1];
      buf2 = buffer[i];

      buf1 = (short) ((buf1 & 0xff) << 8);
      buf2 = (short) (buf2 & 0xff);

      res = (short) (buf1 | buf2);
      res = (short) Math.min((int)(res * volumeGain), (int)Short.MAX_VALUE);

      // convert back
      buffer[i]   = (byte) res;
      buffer[i+1] = (byte) (res >> 8);
    }
  }

  private void showToast_NonUiThread(String text) {
    MainActivity.this.runOnUiThread(new Runnable() {
      public void run() {
        showToast(text);
      }
    });
  }

  private void showPairedDevices_NonUiThread() {
    MainActivity.this.runOnUiThread(new Runnable() {
      public void run() {
        showPairedDevices();
      }
    });
  }

  // -------------------------------------------------------------------------
  // helpers

  private void showToast(String text) {
    Toast toast = Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG);
    toast.show();
  }

}
