package id.flutter.plugins;

import static android.os.Build.VERSION.SDK_INT;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

public class FlutterBluetoothPrinterPlugin implements FlutterPlugin, ActivityAware, MethodCallHandler {
    private MethodChannel channel;
    private Activity activity;
    private Context mContext;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket bluetoothSocket;
    private OutputStream writeStream;
    private InputStream inputStream;

    private BluetoothDevice connectedDevice;
    private Map<String, BluetoothDevice> discoveredDevices = new HashMap<>();
    IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "id.flutter.plugins/bluetooth_printer");
        channel.setMethodCallHandler(this);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        // 监视蓝牙设备与APP连接的状态
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        // BluetoothManager mBluetoothManager = (BluetoothManager) flutterPluginBinding.getApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
        // bluetoothAdapter = mBluetoothManager.getAdapter();
        mContext = flutterPluginBinding.getApplicationContext();
        flutterPluginBinding.getApplicationContext().registerReceiver(receiver, filter);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        private static final String TAG = "FlutterBluetoothPrinter";

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.e("FlutterBluetoothPrinter", "onReceive: " + action);
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                final Map<String, Object> map = deviceToMap(device);
                Log.d(TAG, "onReceive: " + map);
                channel.invokeMethod("onDiscovered", map);
                discoveredDevices.put(device.getAddress(), device);
            }
            if(BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)){
                final HashMap<String, Object> map = new HashMap<>();
                map.put("id", 1);
                channel.invokeMethod("onStateChanged", map);
            }
            if(BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)){
                final HashMap<String, Object> map = new HashMap<>();
                map.put("id", 3);
                channel.invokeMethod("onStateChanged", map);
            }
        }
    };

    private Map<String, Object> deviceToMap(BluetoothDevice device) {
        final HashMap<String, Object> map = new HashMap<>();
        map.put("name", device.getName());
        map.put("address", device.getAddress());
        map.put("type", device.getType());

        if (connectedDevice == null) {
            map.put("is_connected", false);
        } else {
            map.put("is_connected", device.getAddress().equals(connectedDevice.getAddress()));
        }
        return map;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        final String method = call.method;
        switch (method) {
            case "isEnabled": {
                isEnabled(result);
                break;
            }
            case "startScan": {
                mContext.unregisterReceiver(receiver);
                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
                filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
                mContext.registerReceiver(receiver, filter);
                if (!bluetoothAdapter.isDiscovering()) {
                    discoveredDevices.clear();
                    bluetoothAdapter.startDiscovery();

                    Set<BluetoothDevice> bonded = bluetoothAdapter.getBondedDevices();
                    for (BluetoothDevice device : bonded) {
                        final Map<String, Object> map = deviceToMap(device);
                        channel.invokeMethod("onDiscovered", map);
                        discoveredDevices.put(device.getAddress(), device);
                    }
                }
                result.success(true);
                break;
            }

            case "getDevice": {
                String address = call.argument("address");
                final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                if (device != null) {
                    final Map<String, Object> map = deviceToMap(device);
                    result.success(map);
                    return;
                }

                result.success(null);
                break;
            }

            case "stopScan": {
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }
                result.success(true);
                break;
            }

            case "isConnected": {
                result.success(connectedDevice != null);
                break;
            }

            case "connectedDevice": {
                if (connectedDevice != null) {
                    result.success(deviceToMap(connectedDevice));
                    return;
                }

                result.success(null);
                break;
            }

            case "connect": {
                AsyncTask.execute(() -> {
                    try {
                        String address = call.argument("address");
                        final BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
                        bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid);
                        bluetoothSocket.connect();
                        connectedDevice = device;
                        writeStream = bluetoothSocket.getOutputStream();
                        inputStream = bluetoothSocket.getInputStream();
                        new Handler(Looper.getMainLooper()).post(() -> {
                            final HashMap<String, Object> map = new HashMap<>();
                            map.put("id", 1);
                            channel.invokeMethod("onStateChanged", map);
                            result.success(true);
                        });
                    } catch (Exception e) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            result.error("error", e.getMessage(), null);
                        });
                    }
                });

                break;
            }

            case "disconnect": {
                AsyncTask.execute(() -> {
                    try {
                        writeStream.close();
                        bluetoothSocket.close();

                        connectedDevice = null;

                        new Handler(Looper.getMainLooper()).post(() -> {
                            final HashMap<String, Object> map = new HashMap<>();
                            map.put("id", 3);
                            channel.invokeMethod("onStateChanged", map);
                            result.success(true);
                        });
                    } catch (Exception e) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            result.error("error", e.getMessage(), null);
                        });
                    }
                });
                break;
            }

            case "print": {
                if (connectedDevice == null) {
                    result.error("error", "No connected devices", null);
                    return;
                }

                final byte[] bytes = (byte[]) call.arguments;
                AsyncTask.execute(() -> {
                    new Thread() {
                        /// Thread main code
                        public void run() {
                            byte[] buffer = new byte[1024];
                            int bytes;

                            try {
                                bytes = inputStream.read(buffer);

                                if (SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
                                    byte[] resultBytes = Arrays.copyOf(buffer, bytes);
                                    String s = new String(resultBytes);
                                    Log.e("printer2", "" + Arrays.toString(resultBytes));
                                    Log.e("printer2", "" + s);
                                    result.success(resultBytes);
                                }else{

                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }.start();
                    try {
                        writeStream.write(bytes);
                        writeStream.flush();

                        new Handler(Looper.getMainLooper()).post(() -> {
//                            result.success(true);

                            final HashMap<String, Object> map = new HashMap<>();
                            map.put("total", bytes.length);
                            map.put("progress", bytes.length);
                            channel.invokeMethod("onPrintingProgress", map);
                        });

                    } catch (Exception e) {
                        new Handler(Looper.getMainLooper()).post(() -> {
                            result.error("error", e.getMessage(), null);
                        });
                        e.printStackTrace();
                    }
                });
            }
            break;

            default:
                result.notImplemented();
                break;
        }
    }

    private void isEnabled(MethodChannel.Result result) {
        if (!isPermitted(result)) {
            return;
        }

        final boolean isEnabled = bluetoothAdapter.isEnabled();
        result.success(isEnabled);
    }

    private boolean isPermitted(MethodChannel.Result result) {
        if (SDK_INT < 23) {
            return true;
        }

        final int res = activity.checkSelfPermission("android.permission.BLUETOOTH");
        if (res != PackageManager.PERMISSION_GRANTED) {
            result.error("permission_denied", "Permission denied", null);
            return false;
        }

        return true;
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {

    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {

    }

    @Override
    public void onDetachedFromActivity() {
        activity = null;
    }
}
