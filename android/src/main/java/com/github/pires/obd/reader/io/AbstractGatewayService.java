package com.github.pires.obd.reader.io;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public abstract class AbstractGatewayService extends Service {
    public static final int NOTIFICATION_ID = 1;
    private static final String TAG = AbstractGatewayService.class.getName();
    private final IBinder binder = new AbstractGatewayServiceBinder();
    protected Context ctx;
    protected ObdProgressListener obdProgressListener;
    protected boolean isRunning = false;
    protected Long queueCounter = 0L;
    protected BlockingQueue<ObdCommandJob> jobsQueue = new LinkedBlockingQueue<>();
    
    // Uruchomienie wykonania kolejki w oddzielnym wątku, aby UI nie był obciążony.
    Thread t = new Thread(new Runnable() {
        @Override
        public void run() {
            Log.d(TAG, "Queue execution thread started.");
            try {
                executeQueue();
            } catch (InterruptedException e) {
                Log.e(TAG, "Queue execution thread interrupted.", e);
                t.interrupt();
            }
        }
    });

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(TAG, "onBind called.");
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Creating service..");
        t.start();
        Log.d(TAG, "Service created and queue thread started.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "Destroying service...");
        t.interrupt();
        Log.d(TAG, "Service destroyed.");
    }

    public boolean isRunning() {
        return isRunning;
    }

    public boolean queueEmpty() {
        boolean empty = jobsQueue.isEmpty();
        Log.d(TAG, "Queue empty: " + empty);
        return empty;
    }

    /**
     * Metoda dodaje zadanie do kolejki, ustawiając unikalny identyfikator.
     *
     * @param job zadanie do dodania do kolejki.
     */
    public void queueJob(ObdCommandJob job) {
        queueCounter++;
        Log.d(TAG, "Adding job[" + queueCounter + "] to queue for command: " + job.getCommand().getName());
        job.setId(queueCounter);
        try {
            jobsQueue.put(job);
            Log.d(TAG, "Job queued successfully.");
        } catch (InterruptedException e) {
            job.setState(ObdCommandJob.ObdCommandJobState.QUEUE_ERROR);
            Log.e(TAG, "Failed to queue job.", e);
        }
    }

    /**
     * Metoda odpowiedzialna za wykonywanie kolejki, aż serwis zostanie zatrzymany.
     *
     * @throws InterruptedException
     */
    abstract protected void executeQueue() throws InterruptedException;

    /**
     * Powinna rozpocząć serwis, nawiązując połączenie z urządzeniem OBD.
     *
     * @param remoteDevice adres zdalnego urządzenia.
     * @throws IOException
     */
    abstract public void startService(final String remoteDevice) throws IOException;

    /**
     * Powinna zatrzymać połączenie i przetwarzanie kolejki.
     */
    abstract public void stopService();

    public void setContext(Context c) {
        ctx = c;
        Log.d(TAG, "Context set in AbstractGatewayService.");
    }

    public void setOBDProgressListener(ObdProgressListener aListener) {
        obdProgressListener = aListener;
        Log.d(TAG, "OBD progress listener set.");
    }

    public class AbstractGatewayServiceBinder extends Binder {
        public AbstractGatewayService getService() {
            Log.d(TAG, "AbstractGatewayServiceBinder: getService() called.");
            return AbstractGatewayService.this;
        }
    }
}
