package org.emugo.frontend;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/**
 * Devices actually connected over Bluetooth, from the system's profile connections. Built-in
 * handheld controls never show up here, unlike InputDevice.isExternal().
 */
final class BluetoothDevices {
  // HEADSET, A2DP, HID_HOST (hidden constant 4), LE_AUDIO (API 33).
  private static final int[] PROFILES = {1, 2, 4, 22};

  private BluetoothDevices() {}

  static boolean permitted(Context context) {
    return Build.VERSION.SDK_INT < 31
        || context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED;
  }

  static void query(Context context, Consumer<List<String>> done) {
    Handler main = new Handler(Looper.getMainLooper());
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter == null || !permitted(context)) {
      done.accept(new ArrayList<>());
      return;
    }
    LinkedHashSet<String> names = new LinkedHashSet<>();
    try {
      BluetoothManager manager = context.getSystemService(BluetoothManager.class);
      for (BluetoothDevice device : manager.getConnectedDevices(BluetoothProfile.GATT))
        add(names, device);
    } catch (Exception ignored) {
    }
    int[] pending = {PROFILES.length};
    boolean[] finished = {false};
    Runnable finish = () -> {
      if (finished[0]) return;
      finished[0] = true;
      done.accept(new ArrayList<>(names));
    };
    for (int profile : PROFILES) {
      boolean requested = false;
      try {
        requested = adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
          @Override
          public void onServiceConnected(int type, BluetoothProfile proxy) {
            try {
              for (BluetoothDevice device : proxy.getConnectedDevices()) add(names, device);
            } catch (Exception ignored) {
            }
            try {
              adapter.closeProfileProxy(type, proxy);
            } catch (Exception ignored) {
            }
            main.post(() -> {
              if (--pending[0] == 0) finish.run();
            });
          }
          @Override
          public void onServiceDisconnected(int type) {}
        }, profile);
      } catch (Exception ignored) {
      }
      if (!requested) pending[0]--;
    }
    if (pending[0] <= 0) finish.run();
    else main.postDelayed(finish, 1500); // a profile service may never answer
  }

  private static synchronized void add(LinkedHashSet<String> names, BluetoothDevice device) {
    try {
      String name = device.getAlias();
      if (name == null || name.isEmpty()) name = device.getName();
      if (name != null && !name.isEmpty()) names.add(name);
    } catch (SecurityException ignored) {
    }
  }
}
