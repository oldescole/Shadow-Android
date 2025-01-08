package su.sres.securesms.profiles.manage;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import su.sres.securesms.database.ShadowDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.core.util.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.core.util.concurrent.SignalExecutors;
import su.sres.signalservice.api.SignalServiceAccountManager;
import su.sres.signalservice.api.push.exceptions.UsernameMalformedException;
import su.sres.signalservice.api.push.exceptions.UsernameTakenException;

import java.io.IOException;
import java.util.concurrent.Executor;

class UsernameEditRepository {

    private static final String TAG = Log.tag(UsernameEditRepository.class);

    private final Application                 application;
    private final SignalServiceAccountManager accountManager;
    private final Executor                    executor;

    UsernameEditRepository() {
        this.application    = ApplicationDependencies.getApplication();
        this.accountManager = ApplicationDependencies.getSignalServiceAccountManager();
        this.executor       = SignalExecutors.UNBOUNDED;
    }

    void setUsername(@NonNull String username, @NonNull Callback<UsernameSetResult> callback) {
        executor.execute(() -> callback.onComplete(setUsernameInternal(username)));
    }

    void deleteUsername(@NonNull Callback<UsernameDeleteResult> callback) {
        executor.execute(() -> callback.onComplete(deleteUsernameInternal()));
    }

    @WorkerThread
    private @NonNull UsernameSetResult setUsernameInternal(@NonNull String username) {
        try {
            accountManager.setUsername(username);
            ShadowDatabase.recipients().setUsername(Recipient.self().getId(), username);
            Log.i(TAG, "[setUsername] Successfully set username.");
            return UsernameSetResult.SUCCESS;
        } catch (UsernameTakenException e) {
            Log.w(TAG, "[setUsername] Username taken.");
            return UsernameSetResult.USERNAME_UNAVAILABLE;
        } catch (UsernameMalformedException e) {
            Log.w(TAG, "[setUsername] Username malformed.");
            return UsernameSetResult.USERNAME_INVALID;
        } catch (IOException e) {
            Log.w(TAG, "[setUsername] Generic network exception.", e);
            return UsernameSetResult.NETWORK_ERROR;
        }
    }

    @WorkerThread
    private @NonNull UsernameDeleteResult deleteUsernameInternal() {
        try {
            accountManager.deleteUsername();
            ShadowDatabase.recipients().setUsername(Recipient.self().getId(), null);
            Log.i(TAG, "[deleteUsername] Successfully deleted the username.");
            return UsernameDeleteResult.SUCCESS;
        } catch (IOException e) {
            Log.w(TAG, "[deleteUsername] Generic network exception.", e);
            return UsernameDeleteResult.NETWORK_ERROR;
        }
    }

    enum UsernameSetResult {
        SUCCESS, USERNAME_UNAVAILABLE, USERNAME_INVALID, NETWORK_ERROR
    }

    enum UsernameDeleteResult {
        SUCCESS, NETWORK_ERROR
    }

    enum UsernameAvailableResult {
        TRUE, FALSE, NETWORK_ERROR
    }

    interface Callback<E> {
        void onComplete(E result);
    }
}