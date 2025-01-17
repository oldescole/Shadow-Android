package su.sres.securesms.messages;

import android.app.Application;
import android.app.Service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import su.sres.core.util.ThreadUtil;
import su.sres.core.util.concurrent.SignalExecutors;
import su.sres.securesms.jobmanager.impl.BackoffUtil;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.core.util.logging.Log;

import su.sres.securesms.R;
import su.sres.securesms.jobs.PushDecryptDrainedJob;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.messages.IncomingMessageProcessor.Processor;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.notifications.NotificationChannels;
import su.sres.securesms.push.SignalServiceNetworkAccess;
import su.sres.securesms.util.AppForegroundObserver;

import org.whispersystems.libsignal.util.guava.Optional;

import su.sres.signalservice.api.SignalWebSocket;
import su.sres.signalservice.api.messages.SignalServiceEnvelope;
import su.sres.signalservice.api.websocket.WebSocketUnavailableException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class IncomingMessageObserver {

  private static final String TAG = Log.tag(IncomingMessageObserver.class);

  public static final  int  FOREGROUND_ID           = 313399;
  private static final long REQUEST_TIMEOUT_MINUTES = 1;

  private static final AtomicInteger INSTANCE_COUNT = new AtomicInteger(0);

  private final Application                context;
  private final SignalServiceNetworkAccess networkAccess;
  private final List<Runnable>             decryptionDrainedListeners;
  private final BroadcastReceiver          connectionReceiver;

  private boolean appVisible;

  private volatile boolean networkDrained;
  private volatile boolean decryptionDrained;
  private volatile boolean terminated;

  /**
   * The application-level manager of our websocket connection.
   *
   * This class is responsible for opening/closing the websocket based on the app's state and observing new inbound messages received on the websocket.
   */
  public IncomingMessageObserver(@NonNull Application context) {
    if (INSTANCE_COUNT.incrementAndGet() != 1) {
      throw new AssertionError("Multiple observers!");
    }

    this.context                    = context;
    this.networkAccess              = ApplicationDependencies.getSignalServiceNetworkAccess();
    this.decryptionDrainedListeners = new CopyOnWriteArrayList<>();

    new MessageRetrievalThread().start();

    if (!SignalStore.account().isFcmEnabled()) {
      ContextCompat.startForegroundService(context, new Intent(context, ForegroundService.class));
    }

    ApplicationDependencies.getAppForegroundObserver().addListener(new AppForegroundObserver.Listener() {
      @Override
      public void onForeground() {
        onAppForegrounded();
      }

      @Override
      public void onBackground() {
        onAppBackgrounded();
      }
    });

    connectionReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        synchronized (IncomingMessageObserver.this) {
          if (!NetworkConstraint.isMet(context)) {
            Log.w(TAG, "Lost network connection. Shutting down our websocket connections and resetting the drained state.");
            networkDrained    = false;
            decryptionDrained = false;
            disconnect();
          }
          IncomingMessageObserver.this.notifyAll();
        }
      }
    };

    context.registerReceiver(connectionReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
  }

  public synchronized void notifyRegistrationChanged() {
    notifyAll();
  }

  public synchronized void addDecryptionDrainedListener(@NonNull Runnable listener) {
    decryptionDrainedListeners.add(listener);
    if (decryptionDrained) {
      listener.run();
    }
  }

  public boolean isDecryptionDrained() {
    return decryptionDrained;
  }

  public void notifyDecryptionsDrained() {
    List<Runnable> listenersToTrigger = new ArrayList<>(decryptionDrainedListeners.size());

    synchronized (this) {
      if (networkDrained && !decryptionDrained) {
        Log.i(TAG, "Decryptions newly drained.");
        decryptionDrained = true;
        listenersToTrigger.addAll(decryptionDrainedListeners);
      }
    }

    for (Runnable listener : listenersToTrigger) {
      listener.run();
    }
  }

  private synchronized void onAppForegrounded() {
    appVisible = true;
    notifyAll();
  }

  private synchronized void onAppBackgrounded() {
    appVisible = false;
    notifyAll();
  }

  private synchronized boolean isConnectionNecessary() {
    boolean registered = SignalStore.account().isRegistered();
    boolean fcmEnabled = SignalStore.account().isFcmEnabled();
    boolean hasNetwork = NetworkConstraint.isMet(context);
    boolean hasProxy   = SignalStore.proxy().isProxyEnabled();

    Log.d(TAG, String.format("Network: %s, Foreground: %s, FCM: %s, Registered: %s, Proxy: %s",
                             hasNetwork, appVisible, fcmEnabled, registered, hasProxy));

    return registered &&
           (appVisible || !fcmEnabled) &&
           hasNetwork;
  }

  private synchronized void waitForConnectionNecessary() {
    // long timeout = SignalStore.account().isRegistered() ? 0 : 60000;
    long timeout = 60000;

    try {
      while (!isConnectionNecessary()) wait(timeout);
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  public void terminateAsync() {
    INSTANCE_COUNT.decrementAndGet();

    context.unregisterReceiver(connectionReceiver);

    SignalExecutors.BOUNDED.execute(() -> {
      Log.w(TAG, "Beginning termination.");
      terminated = true;
      disconnect();
    });
  }

  private void disconnect() {
    ApplicationDependencies.getSignalWebSocket().disconnect();
  }

  private class MessageRetrievalThread extends Thread implements Thread.UncaughtExceptionHandler {

    MessageRetrievalThread() {
      super("MessageRetrievalService");
      Log.i(TAG, "Initializing! (" + this.hashCode() + ")");
      setUncaughtExceptionHandler(this);
    }

    @Override
    public void run() {
      int attempts = 0;

      while (!terminated) {
        Log.i(TAG, "Waiting for websocket state change....");
        if (attempts > 1) {
          long backoff = BackoffUtil.exponentialBackoff(attempts, TimeUnit.SECONDS.toMillis(30));
          Log.w(TAG, "Too many failed connection attempts,  attempts: " + attempts + " backing off: " + backoff);
          ThreadUtil.sleep(backoff);
        }
        waitForConnectionNecessary();

        Log.i(TAG, "Making websocket connection....");
        SignalWebSocket signalWebSocket = ApplicationDependencies.getSignalWebSocket();
        signalWebSocket.connect();

        try {
          while (isConnectionNecessary()) {
            try {
              Log.d(TAG, "Reading message...");
              Optional<SignalServiceEnvelope> result = signalWebSocket.readOrEmpty(TimeUnit.MINUTES.toMillis(REQUEST_TIMEOUT_MINUTES), envelope -> {
                Log.i(TAG, "Retrieved envelope! " + envelope.getTimestamp());
                try (Processor processor = ApplicationDependencies.getIncomingMessageProcessor().acquire()) {
                  processor.processEnvelope(envelope);
                }
              });
              attempts = 0;

              if (!result.isPresent() && !networkDrained) {
                Log.i(TAG, "Network was newly-drained. Enqueuing a job to listen for decryption draining.");
                networkDrained = true;
                ApplicationDependencies.getJobManager().add(new PushDecryptDrainedJob());
              }
            } catch (WebSocketUnavailableException e) {
              Log.i(TAG, "Pipe unexpectedly unavailable, connecting");
              signalWebSocket.connect();
            } catch (TimeoutException e) {
              Log.w(TAG, "Application level read timeout...");
              attempts = 0;
            }
          }
        } catch (Throwable e) {
          attempts++;
          Log.w(TAG, e);
        } finally {
          Log.w(TAG, "Shutting down pipe...");
          disconnect();
        }

        Log.i(TAG, "Looping...");
      }

      Log.w(TAG, "Terminated! (" + this.hashCode() + ")");
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
      Log.w(TAG, "*** Uncaught exception!");
      Log.w(TAG, e);
    }
  }

  public static class ForegroundService extends Service {

    @Override
    public @Nullable IBinder onBind(Intent intent) {
      return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
      super.onStartCommand(intent, flags, startId);

      NotificationCompat.Builder builder = new NotificationCompat.Builder(getApplicationContext(), NotificationChannels.OTHER);
      builder.setContentTitle(getApplicationContext().getString(R.string.MessageRetrievalService_signal));
      builder.setContentText(getApplicationContext().getString(R.string.MessageRetrievalService_background_connection_enabled));
      builder.setPriority(NotificationCompat.PRIORITY_MIN);
      builder.setWhen(0);
      builder.setSmallIcon(R.drawable.ic_signal_background_connection);
      startForeground(FOREGROUND_ID, builder.build());

      return Service.START_STICKY;
    }
  }
}