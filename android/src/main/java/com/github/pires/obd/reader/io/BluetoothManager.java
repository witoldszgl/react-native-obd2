package com.github.pires.obd.reader.io;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.UUID;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

public class BluetoothManager {
    
    private static final String TAG = BluetoothManager.class.getName();
    /*
     * http://developer.android.com/reference/android/bluetooth/BluetoothDevice.html
     * #createRfcommSocketToServiceRecord(java.util.UUID)
     *
     * "Hint: If you are connecting to a Bluetooth serial board then try using the
     * well-known SPP UUID 00001101-0000-1000-8000-00805F9B34FB. However if you
     * are connecting to an Android peer then please generate your own unique
     * UUID."
     */
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /**
     * Instantiates a BluetoothSocket for the remote device and connects it.
     *
     * @param dev The remote device to connect to.
     * @return The connected BluetoothSocket.
     * @throws IOException
     */
    public static BluetoothSocket connect(BluetoothDevice dev) throws IOException {
        Log.d(TAG, "Starting Bluetooth connection with device: " + dev.getAddress());
        BluetoothSocket sock = null;
        BluetoothSocket sockFallback = null;

        try {
            // Standardowe połączenie
            sock = dev.createRfcommSocketToServiceRecord(MY_UUID);
            Log.d(TAG, "Standard socket created. Attempting connection..");
            sock.connect();
            Log.d(TAG, "Standard socket connection successful.");
        } catch (Exception e1) {
            Log.e(TAG, "Error while establishing standard Bluetooth connection. Falling back...", e1);
            // Gdy standardowe połączenie się nie powiedzie
            try {
                Class<?> clazz = dev.getClass();
                Class<?>[] paramTypes = new Class<?>[]{Integer.TYPE};
                Method m = clazz.getMethod("createRfcommSocket", paramTypes);
                Object[] params = new Object[]{Integer.valueOf(1)};
                sockFallback = (BluetoothSocket) m.invoke(dev, params);
                Log.d(TAG, "Fallback socket created. Attempting connection..");
                sockFallback.connect();
                Log.d(TAG, "Fallback socket connection successful.");
                sock = sockFallback;
            } catch (Exception e2) {
                Log.e(TAG, "Fallback connection also failed.", e2);
                throw new IOException(e2.getMessage());
            }
        }
        Log.d(TAG, "Bluetooth connection established successfully.");
        return sock;
    }
}
