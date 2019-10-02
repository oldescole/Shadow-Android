package su.sres.securesms.dependencies;

import android.content.Context;

import androidx.annotation.NonNull;

import org.greenrobot.eventbus.EventBus;
import su.sres.securesms.BuildConfig;
import su.sres.securesms.crypto.storage.SignalProtocolStoreImpl;
import su.sres.securesms.events.ReminderUpdateEvent;
import su.sres.securesms.logging.Log;
import su.sres.securesms.push.SecurityEventListener;
import su.sres.securesms.push.SignalServiceNetworkAccess;
import su.sres.securesms.service.IncomingMessageObserver;
import su.sres.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.SignalServiceAccountManager;
import su.sres.signalservice.api.SignalServiceMessageReceiver;
import su.sres.signalservice.api.SignalServiceMessageSender;
import su.sres.signalservice.api.util.CredentialsProvider;
import su.sres.signalservice.api.util.RealtimeSleepTimer;
import su.sres.signalservice.api.util.SleepTimer;
import su.sres.signalservice.api.util.UptimeSleepTimer;
import su.sres.signalservice.api.websocket.ConnectivityListener;

/**
 * Implementation of {@link ApplicationDependencies.Provider} that provides real app dependencies.
 */
public class ApplicationDependencyProvider implements ApplicationDependencies.Provider {

    private static final String TAG = Log.tag(ApplicationDependencyProvider.class);

    private final Context                    context;
    private final SignalServiceNetworkAccess networkAccess;

    private SignalServiceAccountManager  accountManager;
    private SignalServiceMessageSender   messageSender;
    private SignalServiceMessageReceiver messageReceiver;

    public ApplicationDependencyProvider(@NonNull Context context, @NonNull SignalServiceNetworkAccess networkAccess) {
        this.context       = context.getApplicationContext();
        this.networkAccess = networkAccess;
    }

    @Override
    public @NonNull SignalServiceAccountManager getSignalServiceAccountManager() {
        if (accountManager == null) {
            this.accountManager = new SignalServiceAccountManager(networkAccess.getConfiguration(context),
                    new DynamicCredentialsProvider(context),
                    BuildConfig.USER_AGENT);
        }

        return accountManager;
    }

    @Override
    public @NonNull SignalServiceMessageSender getSignalServiceMessageSender() {
        if (this.messageSender == null) {
            this.messageSender = new SignalServiceMessageSender(networkAccess.getConfiguration(context),
                    new DynamicCredentialsProvider(context),
                    new SignalProtocolStoreImpl(context),
                    BuildConfig.USER_AGENT,
                    TextSecurePreferences.isMultiDevice(context),
                    Optional.fromNullable(IncomingMessageObserver.getPipe()),
                    Optional.fromNullable(IncomingMessageObserver.getUnidentifiedPipe()),
                    Optional.of(new SecurityEventListener(context)));
        }else {
            this.messageSender.setMessagePipe(IncomingMessageObserver.getPipe(), IncomingMessageObserver.getUnidentifiedPipe());
            this.messageSender.setIsMultiDevice(TextSecurePreferences.isMultiDevice(context));
        }

        return this.messageSender;
    }

    @Override
    public @NonNull SignalServiceMessageReceiver getSignalServiceMessageReceiver() {
        if (this.messageReceiver == null) {
            SleepTimer sleepTimer = TextSecurePreferences.isFcmDisabled(context) ? new RealtimeSleepTimer(context)
                    : new UptimeSleepTimer();

            this.messageReceiver = new SignalServiceMessageReceiver(networkAccess.getConfiguration(context),
                    new DynamicCredentialsProvider(context),
                    BuildConfig.USER_AGENT,
                    new PipeConnectivityListener(),
                    sleepTimer);
        }

        return this.messageReceiver;
    }

    @Override
    public @NonNull SignalServiceNetworkAccess getSignalServiceNetworkAccess() {
        return networkAccess;
    }

    private static class DynamicCredentialsProvider implements CredentialsProvider {

        private final Context context;

        private DynamicCredentialsProvider(Context context) {
            this.context = context.getApplicationContext();
        }

        @Override
        public String getUser() {
            return TextSecurePreferences.getLocalNumber(context);
        }

        @Override
        public String getPassword() {
            return TextSecurePreferences.getPushServerPassword(context);
        }

        @Override
        public String getSignalingKey() {
            return TextSecurePreferences.getSignalingKey(context);
        }
    }

    private class PipeConnectivityListener implements ConnectivityListener {

        @Override
        public void onConnected() {
            Log.i(TAG, "onConnected()");
        }

        @Override
        public void onConnecting() {
            Log.i(TAG, "onConnecting()");
        }

        @Override
        public void onDisconnected() {
            Log.w(TAG, "onDisconnected()");
        }

        @Override
        public void onAuthenticationFailure() {
            Log.w(TAG, "onAuthenticationFailure()");
            TextSecurePreferences.setUnauthorizedReceived(context, true);
            EventBus.getDefault().post(new ReminderUpdateEvent());
        }
    }
}