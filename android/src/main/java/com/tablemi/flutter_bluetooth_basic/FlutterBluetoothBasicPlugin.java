package com.tablemi.flutter_bluetooth_basic;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Vector;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

/**
 * FlutterBluetoothBasicPlugin
 */
public class FlutterBluetoothBasicPlugin implements FlutterPlugin, MethodCallHandler, ActivityAware, ActivityResultListener, RequestPermissionsResultListener {
    private static final String TAG = "BluetoothBasicPlugin";
    private final int id = 0;
    private ThreadPool threadPool;
    private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1451;
    private static final int REQUEST_ENABLE_BLUETOOTH = 1452;
    private static final String NAMESPACE = "flutter_bluetooth_basic";
    private Activity activity;
    private MethodChannel channel;
    private EventChannel stateChannel;
    private BluetoothAdapter bluetoothAdapter;
    private ActivityPluginBinding activityPluginBinding;

    private Result pendingResult;

    private boolean supportBluetoothLE;

    // plugin should still contain the static registerWith() method to remain compatible with apps
    // that don’t use the v2 Android embedding.
    public static void registerWith(Registrar registrar) {
        FlutterBluetoothBasicPlugin instance = new FlutterBluetoothBasicPlugin();
        instance.createChannel(registrar.messenger());
        registrar.addActivityResultListener(instance);
        registrar.addRequestPermissionsResultListener(instance);
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        if (bluetoothAdapter == null && !"isAvailable".equals(call.method)) {
            result.error("bluetooth_unavailable", "Bluetooth is unavailable", null);
            return;
        }

        final Map<String, Object> args = call.arguments();

        switch (call.method) {
            case "state":
                state(result);
                break;
            case "isAvailable":
                result.success(bluetoothAdapter != null);
                break;
            case "isOn":
                result.success(bluetoothAdapter.isEnabled());
                break;
            case "isConnected":
                result.success(threadPool != null);
                break;
            case "startScan":
                startScan(result);
                break;
            case "stopScan":
                stopScan();
                result.success(null);
                break;
            case "connect":
                connect(result, args);
                break;
            case "disconnect":
                result.success(disconnect());
                break;
            case "destroy":
                result.success(destroy());
                break;
            case "writeData":
                writeData(result, args);
                break;
            case "enablePermission":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(activity,
                            Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(activity,
                                    Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(activity,
                                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(activity,
                                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                        ActivityCompat.requestPermissions(
                                activity,
                                new String[]{
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                        Manifest.permission.BLUETOOTH_SCAN,
                                        Manifest.permission.BLUETOOTH_CONNECT,
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                },
                                REQUEST_COARSE_LOCATION_PERMISSIONS
                        );

                        pendingResult = result;
                        break;
                    }
                } else {
                    if (ContextCompat.checkSelfPermission(activity,
                            Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(activity,
                                    Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(activity,
                                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                        ActivityCompat.requestPermissions(
                                activity,
                                new String[]{
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                        Manifest.permission.BLUETOOTH_ADMIN,
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                },
                                REQUEST_COARSE_LOCATION_PERMISSIONS
                        );

                        pendingResult = result;
                        break;
                    }
                }
                result.success(true);
                break;
            case "enableBluetooth":
                if (!bluetoothAdapter.isEnabled()) {
                    Intent enableBtIntent1 = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    activity.startActivityForResult(enableBtIntent1, REQUEST_ENABLE_BLUETOOTH, null);
                    pendingResult = result;
                    break;
                }
                result.success(true);
                break;
            case "checkSupportBLE":
                if (activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                    supportBluetoothLE = true;
                    result.success(true);
                    break;
                } else {
                    supportBluetoothLE = false;
                    result.success(false);
                    break;
                }
//                result.error("check_failed", "check support for bluetoothLE fail", null);
//                break;
            case "getBondedDevice":
                getBondedDevice(result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    @Override
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == Activity.RESULT_OK) {
                pendingResult.success(true);
                pendingResult = null;
                return true;
            } else {
                pendingResult.error("bluetooth_disable", "User did NOT enabled Bluetooth", null);
                pendingResult = null;
                return true;
            }
        }
        return false;
    }

    private void state(Result result) {
        try {
            switch (bluetoothAdapter.getState()) {
                case BluetoothAdapter.STATE_OFF:
                    result.success(BluetoothAdapter.STATE_OFF);
                    break;
                case BluetoothAdapter.STATE_ON:
                    result.success(BluetoothAdapter.STATE_ON);
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                    result.success(BluetoothAdapter.STATE_TURNING_OFF);
                    break;
                case BluetoothAdapter.STATE_TURNING_ON:
                    result.success(BluetoothAdapter.STATE_TURNING_ON);
                    break;
                default:
                    result.success(0);
                    break;
            }
        } catch (SecurityException e) {
            result.error("invalid_argument", "Argument 'address' not found", null);
        }
    }

    private void startScan(Result result) {
        Log.d(TAG, "start scan ");

        try {
            startScan();
            result.success(null);
        } catch (Exception e) {
            result.error("start_scan", e.getMessage(), null);
        }
    }

    private void getBondedDevice(Result result) {
        final List<Object> deviceResultMap = new ArrayList<>();
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                final Map<String, Object> ret = new HashMap<>();
                ret.put("address", device.getAddress());
                ret.put("name", device.getName());
                ret.put("type", device.getType());

                deviceResultMap.add(ret);
            }
        }

        result.success(deviceResultMap);
    }

    private void invokeMethodUIThread(final String name, final BluetoothDevice device) {
        final Map<String, Object> ret = new HashMap<>();
        ret.put("address", device.getAddress());
        ret.put("name", device.getName());
        ret.put("type", device.getType());

        activity.runOnUiThread(() -> channel.invokeMethod(name, ret));
    }

    private final ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (device != null && device.getName() != null) {
                invokeMethodUIThread("ScanResult", device);
            }
        }
    };

    private void startScan() throws IllegalStateException {
        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            throw new IllegalStateException("getBluetoothLeScanner() is null. Is the Adapter on?");
        } else {
            // 0:lowPower 1:balanced 2:lowLatency -1:opportunistic
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            scanner.startScan(null, settings, mScanCallback);
        }
    }

    private void stopScan() {
        BluetoothLeScanner scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner != null) scanner.stopScan(mScanCallback);
    }

    private void connect(Result result, Map<String, Object> args) {
        if (args.containsKey("address")) {
            String address = (String) args.get("address");
            disconnect();

            new DeviceConnFactoryManager.Build()
                    .setId(id)
                    // Set the connection method
                    .setConnMethod(DeviceConnFactoryManager.CONN_METHOD.BLUETOOTH)
                    // Set the connected Bluetooth mac address
                    .setMacAddress(address)
                    .build();
            // Open port
            threadPool = ThreadPool.getInstantiation();
            threadPool.addSerialTask(() -> DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].openPort());

            result.success(true);
        } else {
            result.error("invalid_argument", "Argument 'address' not found", null);
        }
    }

    /**
     * Reconnect to recycle the last connected object to avoid memory leaks
     */
    private boolean disconnect() {

        if (DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id] != null && DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].mPort != null) {
            DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].reader.cancel();
            DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].mPort.closePort();
            DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].mPort = null;
        }
        return true;
    }

    private boolean destroy() {
        DeviceConnFactoryManager.closeAllPort();
        if (threadPool != null) {
            threadPool.stopThreadPool();
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    private void writeData(Result result, Map<String, Object> args) {
        if (args.containsKey("bytes")) {
            final ArrayList<Integer> bytes = Objects.requireNonNull((ArrayList<Integer>) args.get("bytes"));

            threadPool = ThreadPool.getInstantiation();
            threadPool.addSerialTask(() -> {
                Vector<Byte> vectorData = new Vector<>();
                for (int i = 0; i < bytes.size(); ++i) {
                    Integer val = bytes.get(i);
                    vectorData.add(Byte.valueOf(Integer.toString(val > 127 ? val - 256 : val)));
                }

                DeviceConnFactoryManager.getDeviceConnFactoryManagers()[id].sendDataImmediately(vectorData);
            });
        } else {
            result.error("bytes_empty", "Bytes param is empty", null);
        }
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_COARSE_LOCATION_PERMISSIONS) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingResult.success(true);
                pendingResult = null;
            } else {
                pendingResult.error("no_permissions", "This app requires location permissions for scanning", null);
                pendingResult = null;
            }
            return true;
        }
        return false;
    }

    private void createChannel(BinaryMessenger binaryMessenger) {
        channel = new MethodChannel(binaryMessenger, NAMESPACE + "/methods");
        channel.setMethodCallHandler(this);

        stateChannel = new EventChannel(binaryMessenger, NAMESPACE + "/state");
        StreamHandler stateStreamHandler = new StreamHandler() {
            private EventSink sink;

            private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final String action = intent.getAction();
                    Log.d(TAG, "stateStreamHandler, current action: " + action);

                    if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                        threadPool = null;
                        sink.success(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
                    } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                        sink.success(1);
                    } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                        threadPool = null;
                        sink.success(0);
                    }
                }
            };

            @Override
            public void onListen(Object o, EventSink eventSink) {
                sink = eventSink;
                IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
                filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
                filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
                filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
                activity.registerReceiver(mReceiver, filter);
            }

            @Override
            public void onCancel(Object o) {
                sink = null;
                activity.unregisterReceiver(mReceiver);
            }
        };
        stateChannel.setStreamHandler(stateStreamHandler);
    }

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        createChannel(binding.getBinaryMessenger());
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        stateChannel.setStreamHandler(null);
    }

    @Override
    public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
        activity = binding.getActivity();
        BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        activityPluginBinding = binding;
        binding.addRequestPermissionsResultListener(this);
        binding.addActivityResultListener(this);
    }

    @Override
    public void onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity();
    }

    @Override
    public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
        onAttachedToActivity(binding);
    }

    @Override
    public void onDetachedFromActivity() {
        if (activityPluginBinding != null) {
            activityPluginBinding.removeRequestPermissionsResultListener(this);
            activityPluginBinding.removeActivityResultListener(this);
            activityPluginBinding = null;
        }
    }
}
