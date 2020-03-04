package com.github.warren_bank.ptt_bluetooth_speaker.receiver.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.github.warren_bank.ptt_bluetooth_speaker.receiver.service.NetworkingService;

public class StartNetworkingServiceActivity extends Activity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    startListenService();
    finish();
  }

  private void startListenService() {
    Intent intent = new Intent(getApplicationContext(), NetworkingService.class);
    getApplicationContext().startService(intent);
  }
}
