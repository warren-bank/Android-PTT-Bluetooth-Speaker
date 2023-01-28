package com.github.warren_bank.ptt_bluetooth_speaker.receiver.ui;

import com.github.warren_bank.ptt_bluetooth_speaker.receiver.service.NetworkingService;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

public class StartNetworkingServiceActivity extends Activity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    requestPermissions();
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);

    requestPermissions();
  }

  private void requestPermissions() {
    List<String> permissions = new ArrayList<String>();

    if (Build.VERSION.SDK_INT >= 31) {
      if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
        permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
      }
      if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
      }
      if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
        permissions.add(Manifest.permission.BLUETOOTH_SCAN);
      }
    }

    if (Build.VERSION.SDK_INT >= 33) {
      if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS);
      }
    }

    if (!permissions.isEmpty()) {
      requestPermissions(permissions.toArray(new String[permissions.size()]), 0);
    }
    else {
      startNetworkingService();
      finish();
    }
  }

  private void startNetworkingService() {
    Intent intent = new Intent(getApplicationContext(), NetworkingService.class);
    getApplicationContext().startService(intent);
  }
}
