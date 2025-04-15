/*
 * Copyright (c) 2016-present JetBridge LLC
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

 package com.jetbridge.reactobd2;

 import android.bluetooth.BluetoothAdapter;
 import android.bluetooth.BluetoothDevice;
 import android.content.ComponentName;
 import android.content.Context;
 import android.content.Intent;
 import android.content.ServiceConnection;
 import android.os.Handler;
 import android.os.IBinder;
 import androidx.annotation.Nullable;
 
 import android.util.Log;
 
 import com.facebook.react.bridge.Arguments;
 import com.facebook.react.bridge.ReactContext;
 import com.facebook.react.bridge.WritableMap;
 import com.facebook.react.modules.core.DeviceEventManagerModule;
 import com.github.pires.obd.commands.ObdCommand;
 import com.github.pires.obd.enums.AvailableCommandNames;
 import com.github.pires.obd.reader.config.ObdConfig;
 import com.github.pires.obd.reader.io.AbstractGatewayService;
 import com.github.pires.obd.reader.io.MockObdGatewayService;
 import com.github.pires.obd.reader.io.ObdCommandJob;
 import com.github.pires.obd.reader.io.ObdGatewayService;
 import com.github.pires.obd.reader.io.ObdProgressListener;
 
 import java.io.IOException;
 import java.util.Set;
 
 public class OBD2Handler implements ObdProgressListener {
   private static final String TAG = "OBD2Handler";
 
   private static final String EVENTNAME_OBD2_DATA = "obd2LiveData";
   private static final String EVENTNAME_BT_STATUS = "obd2BluetoothStatus";
   private static final String EVENTNAME_OBD_STATUS = "obd2Status";
 
   private ReactContext mReactContext = null;
   private ObdProgressListener mObdProgressListener = null;
   // Zwróć uwagę, że używamy Arguments.createMap() lub createArray() zamiast zmiennej mArguments,
   // dlatego nie inicjujemy pola mArguments w konstruktorze.
   
   private boolean mPreRequisites = true;
   private String mRemoteDeviceName = "";
   private boolean mMockUpMode = false;
 
   private boolean mIsServiceBound;
   private AbstractGatewayService service;
   
   // Dodano logowanie przy każdorazowym wywołaniu Runnable do odpytywania kolejki
   private final Runnable mQueueCommands = new Runnable() {
     public void run() {
       Log.d(TAG, "mQueueCommands run: Checking service and queue status.");
       if (service != null && service.isRunning() && service.queueEmpty()) {
         Log.d(TAG, "Queue is empty, queuing commands.");
         queueCommands();
       } else {
         Log.d(TAG, "Service not running or queue not empty.");
       }
       // Zaplanowanie kolejnego wywołania po 100 ms
       new Handler().postDelayed(mQueueCommands, 100);
     }
   };
 
   private ServiceConnection serviceConn = new ServiceConnection() {
     @Override
     public void onServiceConnected(ComponentName className, IBinder binder) {
       Log.d(TAG, className.toString() + " service is bound");
       mIsServiceBound = true;
       service = ((AbstractGatewayService.AbstractGatewayServiceBinder) binder).getService();
       service.setContext(mReactContext);
       service.setOBDProgressListener(mObdProgressListener);
       Log.d(TAG, "Starting live data");
       try {
         service.startService(mRemoteDeviceName);
         Log.d(TAG, "Service started with remote device: " + mRemoteDeviceName);
         if (mPreRequisites) {
           sendDeviceStatus(EVENTNAME_BT_STATUS, "connected");
         }
       } catch (IOException ioe) {
         Log.e(TAG, "Failure Starting live data", ioe);
         sendDeviceStatus(EVENTNAME_BT_STATUS, "error");
         doUnbindService();
       }
     }
 
     @Override
     protected Object clone() throws CloneNotSupportedException {
       return super.clone();
     }
 
     @Override
     public void onServiceDisconnected(ComponentName className) {
       Log.d(TAG, className.toString() + " service is unbound");
       mIsServiceBound = false;
     }
   };
 
   public OBD2Handler(ReactContext aContext) {
     mReactContext = aContext;
     mObdProgressListener = this;
     Log.d(TAG, "OBD2Handler instantiated.");
   }
 
   public void ready() {
     Log.d(TAG, "ready() called.");
     final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
     mPreRequisites = btAdapter != null && btAdapter.isEnabled();
     if (!mPreRequisites) {
       Log.d(TAG, "Bluetooth is not enabled, attempting to enable.");
       mPreRequisites = btAdapter != null && btAdapter.enable();
     }
 
     if (!mPreRequisites) {
       Log.d(TAG, "Bluetooth prerequisites not met.");
       sendDeviceStatus(EVENTNAME_BT_STATUS, "disabled");
     } else {
       Log.d(TAG, "Bluetooth is enabled and ready.");
       sendDeviceStatus(EVENTNAME_BT_STATUS, "ready");
     }
 
     sendDeviceStatus(EVENTNAME_OBD_STATUS, "disconnected");
   }
 
   public void startLiveData() {
     Log.d(TAG, "startLiveData() called.");
     doBindService();
     // Rozpoczynamy wykonywanie komend
     new Handler().post(mQueueCommands);
   }
 
   public void stopLiveData() {
     Log.d(TAG, "Stopping live data..");
     doUnbindService();
   }
 
   public void setRemoteDeviceName(String aRemoteDeviceName) {
     Log.d(TAG, "Setting remote device name/address to: " + aRemoteDeviceName);
     mRemoteDeviceName = aRemoteDeviceName;
   }
 
   public void setMockUpMode(boolean enabled) {
     Log.d(TAG, "Setting mock-up mode: " + enabled);
     mMockUpMode = enabled;
   }
 
   private void sendEvent(String eventName, @Nullable WritableMap params) {
     try {
       mReactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, params);
       Log.d(TAG, "Event sent: " + eventName + " with params: " + (params != null ? params.toString() : "null"));
     } catch (RuntimeException e) {
       Log.e(TAG, "Error sending event to jsModule", e);
     }
   }
 
   private void queueCommands() {
     if (mIsServiceBound) {
       Log.d(TAG, "Queuing commands from ObdConfig.");
       for (ObdCommand Command : ObdConfig.getCommands()) {
         Log.d(TAG, "Queueing command: " + Command.getName());
         service.queueJob(new ObdCommandJob(Command));
       }
     } else {
       Log.d(TAG, "Service not bound, skipping queuing commands.");
     }
   }
 
   private void doBindService() {
     if (!mIsServiceBound) {
       Log.d(TAG, "Binding OBD service..");
       if (mPreRequisites && !mMockUpMode) {
         sendDeviceStatus(EVENTNAME_BT_STATUS, "connecting");
         Intent serviceIntent = new Intent(mReactContext, ObdGatewayService.class);
         mReactContext.bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
         Log.d(TAG, "Binding to ObdGatewayService.");
       } else {
         sendDeviceStatus(EVENTNAME_BT_STATUS, "disabled");
         Intent serviceIntent = new Intent(mReactContext, MockObdGatewayService.class);
         mReactContext.bindService(serviceIntent, serviceConn, Context.BIND_AUTO_CREATE);
         Log.d(TAG, "Binding to MockObdGatewayService (mock-up mode).");
       }
     } else {
       Log.d(TAG, "Service already bound, skipping bind.");
     }
   }
 
   private void doUnbindService() {
     if (mIsServiceBound) {
       Log.d(TAG, "Unbinding OBD service..");
       if (service.isRunning()) {
         service.stopService();
         Log.d(TAG, "Service was running, now stopped.");
         if (mPreRequisites) {
           sendDeviceStatus(EVENTNAME_BT_STATUS, "ready");
         }
       }
       mReactContext.unbindService(serviceConn);
       mIsServiceBound = false;
       sendDeviceStatus(EVENTNAME_OBD_STATUS, "disconnected");
     } else {
       Log.d(TAG, "Service not bound, nothing to unbind.");
     }
   }
 
   public static String LookUpCommand(String txt) {
     for (AvailableCommandNames item : AvailableCommandNames.values()) {
       if (item.getValue().equals(txt)) {
         Log.d(TAG, "LookUpCommand: Found match for " + txt + " as " + item.name());
         return item.name();
       }
     }
     Log.d(TAG, "LookUpCommand: No match found for " + txt + ", returning input.");
     return txt;
   }
 
   public Set<BluetoothDevice> getBondedDevices() throws IOException {
     if (!mPreRequisites) {
       throw new IOException("Bluetooth is not enabled");
     }
     final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
     if (btAdapter == null || !btAdapter.isEnabled()) {
       throw new IOException("This device does not support Bluetooth or it is disabled.");
     }
     Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
     Log.d(TAG, "getBondedDevices: Found " + pairedDevices.size() + " paired devices.");
     return pairedDevices;
   }
 
   @Override
   public void stateUpdate(ObdCommandJob job) {
     final String cmdName = job.getCommand().getName();
     String cmdResult = "";
     final String cmdID = LookUpCommand(cmdName);
 
     Log.d(TAG, "stateUpdate called for command: " + cmdName + " with state: " + job.getState());
 
     if (job.getState().equals(ObdCommandJob.ObdCommandJobState.EXECUTION_ERROR)) {
       cmdResult = job.getCommand().getResult();
       Log.d(TAG, "EXECUTION_ERROR encountered for command: " + cmdName + ". Result: " + cmdResult);
       if (cmdResult != null && mIsServiceBound) {
         sendDeviceStatus(EVENTNAME_OBD_STATUS, cmdResult.toLowerCase());
       }
     } else if (job.getState().equals(ObdCommandJob.ObdCommandJobState.BROKEN_PIPE)) {
       Log.d(TAG, "BROKEN_PIPE encountered for command: " + cmdName);
       if (mIsServiceBound) {
         stopLiveData();
       }
     } else if (job.getState().equals(ObdCommandJob.ObdCommandJobState.NOT_SUPPORTED)) {
       cmdResult = "N/A";
       Log.d(TAG, "Command not supported: " + cmdName);
     } else {
       cmdResult = job.getCommand().getFormattedResult();
       Log.d(TAG, "Received result for command " + cmdName + ": " + cmdResult);
       if(mIsServiceBound) {
         sendDeviceStatus(EVENTNAME_OBD_STATUS, "receiving");
       }
     }
 
     WritableMap map = Arguments.createMap();
     map.putString("cmdID", cmdID);
     map.putString("cmdName", cmdName);
     map.putString("cmdResult", cmdResult);
     sendEvent(EVENTNAME_OBD2_DATA, map);
   }
 
   private void sendDeviceStatus(String eventName, String status) {
     WritableMap btMap = Arguments.createMap();
     btMap.putString("status", status);
     Log.d(TAG, "sendDeviceStatus: " + eventName + " = " + status);
     sendEvent(eventName, btMap);
   }
 }
 