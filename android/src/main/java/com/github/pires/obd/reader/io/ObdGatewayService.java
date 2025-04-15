package com.github.pires.obd.reader.io;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import com.facebook.react.bridge.ReactApplicationContext;
import com.github.pires.obd.commands.protocol.EchoOffCommand;
import com.github.pires.obd.commands.protocol.LineFeedOffCommand;
import com.github.pires.obd.commands.protocol.ObdResetCommand;
import com.github.pires.obd.commands.protocol.SelectProtocolCommand;
import com.github.pires.obd.commands.protocol.TimeoutCommand;
import com.github.pires.obd.commands.temperature.AmbientAirTemperatureCommand;
import com.github.pires.obd.enums.ObdProtocols;
import com.github.pires.obd.exceptions.UnsupportedCommandException;
import com.github.pires.obd.reader.io.ObdCommandJob.ObdCommandJobState;

import java.io.File;
import java.io.IOException;

/**
 * This service is primarily responsible for establishing and maintaining a
 * permanent connection between the device where the application runs and a more
 * OBD Bluetooth interface.
 *
 * Secondarily, it will serve as a repository of ObdCommandJobs and at the same
 * time the application state-machine.
 */
public class ObdGatewayService extends AbstractGatewayService {

    private static final String TAG = ObdGatewayService.class.getName();

    private BluetoothDevice dev = null;
    private BluetoothSocket sock = null;

    @Override
    public void startService(final String remoteDevice) throws IOException {
        Log.d(TAG, "Starting service..");

        if (remoteDevice == null || "".equals(remoteDevice)) {
            Log.e(TAG, "No Bluetooth device has been selected.");
            stopService();
            throw new IOException("Remote device address is not set.");
        } else {
            final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
            dev = btAdapter.getRemoteDevice(remoteDevice);
            Log.d(TAG, "Remote device set to: " + remoteDevice);

            Log.d(TAG, "Stopping Bluetooth discovery.");
            btAdapter.cancelDiscovery();

            try {
                startObdConnection();
            } catch (Exception e) {
                Log.e(TAG, "Error establishing OBD connection -> " + e.getMessage(), e);
                stopService();
                throw new IOException(e);
            }
        }
    }

    /**
     * Start and configure the connection to the OBD interface.
     *
     * @throws IOException
     */
    private void startObdConnection() throws IOException {
        Log.d(TAG, "Starting OBD connection..");
        isRunning = true;
        try {
            sock = BluetoothManager.connect(dev);
            Log.d(TAG, "Bluetooth socket connected.");
        } catch (Exception e2) {
            Log.e(TAG, "Error establishing Bluetooth connection. Stopping app..", e2);
            stopService();
            throw new IOException(e2);
        }

        Log.d(TAG, "Queueing jobs for connection configuration..");
        // Reset command
        queueJob(new ObdCommandJob(new ObdResetCommand()));
        
        // Give adapter time to reset
        try {
            Log.d(TAG, "Sleeping for 500ms to allow reset.");
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Log.e(TAG, "Sleep interrupted.", e);
        }
        
        // Disable echo (first call)
        queueJob(new ObdCommandJob(new EchoOffCommand()));
        // Disable echo (second call as recommended)
        queueJob(new ObdCommandJob(new EchoOffCommand()));
        
        // Turn off line feeds
        queueJob(new ObdCommandJob(new LineFeedOffCommand()));
        // Set a timeout value
        queueJob(new ObdCommandJob(new TimeoutCommand(62)));
        
        // Select protocol (AUTO)
        queueJob(new ObdCommandJob(new SelectProtocolCommand(ObdProtocols.valueOf("AUTO"))));
        
        // Retrieve ambient air temperature
        queueJob(new ObdCommandJob(new AmbientAirTemperatureCommand()));

        queueCounter = 0L;
        Log.d(TAG, "Initialization jobs queued.");
    }

    /**
     * This method will add a job to the queue while setting its ID to the
     * internal queue counter.
     *
     * @param job the job to queue.
     */
    @Override
    public void queueJob(ObdCommandJob job) {
        // Force metric units (false for imperial)
        job.getCommand().useImperialUnits(false);
        Log.d(TAG, "Queuing job for command: " + job.getCommand().getName());
        super.queueJob(job);
    }

    /**
     * Runs the queue until the service is stopped.
     */
    protected void executeQueue() throws InterruptedException {
        Log.d(TAG, "Executing queue..");
        while (!Thread.currentThread().isInterrupted()) {
            ObdCommandJob job = null;
            try {
                job = jobsQueue.take();
                Log.d(TAG, "Taking job[" + job.getId() + "] from queue..");

                if (job.getState().equals(ObdCommandJobState.NEW)) {
                    Log.d(TAG, "Job state is NEW. Running command: " + job.getCommand().getName());
                    job.setState(ObdCommandJobState.RUNNING);
                    if (sock.isConnected()) {
                        job.getCommand().run(sock.getInputStream(), sock.getOutputStream());
                    } else {
                        job.setState(ObdCommandJobState.EXECUTION_ERROR);
                        Log.e(TAG, "Can't run command on a closed socket.");
                    }
                } else {
                    Log.e(TAG, "Job state was not new, unexpected state in queue. [Job ID: " + job.getId() + "]");
                }
            } catch (InterruptedException i) {
                Thread.currentThread().interrupt();
            } catch (UnsupportedCommandException u) {
                if (job != null) {
                    job.setState(ObdCommandJobState.NOT_SUPPORTED);
                }
                Log.d(TAG, "Command not supported -> " + u.getMessage());
            } catch (IOException io) {
                if (job != null) {
                    if(io.getMessage().contains("Broken pipe"))
                        job.setState(ObdCommandJobState.BROKEN_PIPE);
                    else
                        job.setState(ObdCommandJobState.EXECUTION_ERROR);
                }
                Log.e(TAG, "IO error -> " + io.getMessage());
            } catch (Exception e) {
                if (job != null) {
                    job.setState(ObdCommandJobState.EXECUTION_ERROR);
                }
                Log.e(TAG, "Failed to run command -> " + e.getMessage());
            }

            if (job != null) {
                final ObdCommandJob jobForCallback = job;
                ((ReactApplicationContext) ctx).runOnUiQueueThread(new Runnable() {
                    @Override
                    public void run() {
                        obdProgressListener.stateUpdate(jobForCallback);
                    }
                });
            }
        }
    }

    /**
     * Stop OBD connection and queue processing.
     */
    public void stopService() {
        Log.d(TAG, "Stopping service..");
        jobsQueue.clear();
        isRunning = false;
        if (sock != null) {
            try {
                sock.close();
                Log.d(TAG, "Bluetooth socket closed.");
            } catch (IOException e) {
                Log.e(TAG, "Error closing socket: " + e.getMessage());
            }
        }
        // Kill service gracefully.
        stopSelf();
    }

    public boolean isRunning() {
        return isRunning;
    }

    public static void saveLogcatToFile(Context context, String devemail) {
        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        emailIntent.setType("text/plain");
        emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{devemail});
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, "OBD2 Reader Debug Logs");

        StringBuilder sb = new StringBuilder();
        sb.append("\nManufacturer: ").append(Build.MANUFACTURER);
        sb.append("\nModel: ").append(Build.MODEL);
        sb.append("\nRelease: ").append(Build.VERSION.RELEASE);

        emailIntent.putExtra(Intent.EXTRA_TEXT, sb.toString());

        String fileName = "OBDReader_logcat_" + System.currentTimeMillis() + ".txt";
        File sdCard = Environment.getExternalStorageDirectory();
        File dir = new File(sdCard.getAbsolutePath() + File.separator + "OBD2Logs");
        if (dir.mkdirs()) {
            File outputFile = new File(dir, fileName);
            Uri uri = Uri.fromFile(outputFile);
            emailIntent.putExtra(Intent.EXTRA_STREAM, uri);

            Log.d("savingFile", "Going to save logcat to " + outputFile);
            context.startActivity(Intent.createChooser(emailIntent, "Pick an Email provider").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

            try {
                Process process = Runtime.getRuntime().exec("logcat -f " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
