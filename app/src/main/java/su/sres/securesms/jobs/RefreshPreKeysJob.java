package su.sres.securesms.jobs;

import androidx.annotation.NonNull;

import su.sres.securesms.crypto.IdentityKeyUtil;
import su.sres.securesms.crypto.PreKeyUtil;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.core.util.logging.Log;
import su.sres.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import su.sres.signalservice.api.SignalServiceAccountManager;
import su.sres.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class RefreshPreKeysJob extends BaseJob  {

  public static final String KEY = "RefreshPreKeysJob";

  private static final String TAG = Log.tag(RefreshPreKeysJob.class);

  private static final int PREKEY_MINIMUM = 10;

  private static final long REFRESH_INTERVAL = TimeUnit.DAYS.toMillis(3);

  public RefreshPreKeysJob() {
    this(new Job.Parameters.Builder()
            .setQueue("RefreshPreKeysJob")
            .addConstraint(NetworkConstraint.KEY)
            .setMaxInstancesForFactory(1)
            .setMaxAttempts(Parameters.UNLIMITED)
            .setLifespan(TimeUnit.DAYS.toMillis(30))
            .build());
  }

  public static void scheduleIfNecessary() {
    long timeSinceLastRefresh = System.currentTimeMillis() - SignalStore.misc().getLastPrekeyRefreshTime();

    if (timeSinceLastRefresh > REFRESH_INTERVAL) {
      Log.i(TAG, "Scheduling a prekey refresh. Time since last schedule: " + timeSinceLastRefresh + " ms");
      ApplicationDependencies.getJobManager().add(new RefreshPreKeysJob());
    }
  }

  private RefreshPreKeysJob(@NonNull Job.Parameters parameters) {
    super(parameters);
  }

  @Override
  public @NonNull Data serialize() {
    return Data.EMPTY;
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;

  }

  @Override
  public void onRun() throws IOException {
    if (!SignalStore.account().isRegistered()) {
      Log.w(TAG, "Not registered. Skipping.");
      return;
    }

    SignalServiceAccountManager accountManager = ApplicationDependencies.getSignalServiceAccountManager();

    int availableKeys = accountManager.getPreKeysCount();

    Log.i(TAG, "Available keys: " + availableKeys);

    if (availableKeys >= PREKEY_MINIMUM && TextSecurePreferences.isSignedPreKeyRegistered(context)) {
      Log.i(TAG, "Available keys sufficient.");
      SignalStore.misc().setLastPrekeyRefreshTime(System.currentTimeMillis());
      return;
    }

    List<PreKeyRecord> preKeyRecords       = PreKeyUtil.generatePreKeys(context);
    IdentityKeyPair    identityKey         = IdentityKeyUtil.getIdentityKeyPair(context);
    SignedPreKeyRecord signedPreKeyRecord  = PreKeyUtil.generateSignedPreKey(context, identityKey, false);

    Log.i(TAG, "Registering new prekeys...");

    accountManager.setPreKeys(identityKey.getPublicKey(), signedPreKeyRecord, preKeyRecords);

    PreKeyUtil.setActiveSignedPreKeyId(context, signedPreKeyRecord.getId());
    TextSecurePreferences.setSignedPreKeyRegistered(context, true);

    ApplicationDependencies.getJobManager().add(new CleanPreKeysJob());

    SignalStore.misc().setLastPrekeyRefreshTime(System.currentTimeMillis());
    Log.i(TAG, "Successfully refreshed prekeys.");
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    if (exception instanceof NonSuccessfulResponseCodeException) return false;
    if (exception instanceof PushNetworkException)               return true;

    return false;
  }

  @Override
  public void onFailure() {
  }

  public static final class Factory implements Job.Factory<RefreshPreKeysJob> {
    @Override
    public @NonNull RefreshPreKeysJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new RefreshPreKeysJob(parameters);
    }
  }
}
