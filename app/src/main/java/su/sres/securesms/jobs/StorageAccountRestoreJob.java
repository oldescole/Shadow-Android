package su.sres.securesms.jobs;

import androidx.annotation.NonNull;

import su.sres.securesms.database.ShadowDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.jobmanager.JobTracker;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.core.util.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.storage.StorageSyncHelper;

import org.whispersystems.libsignal.util.guava.Optional;

import su.sres.signalservice.api.SignalServiceAccountManager;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;
import su.sres.signalservice.api.storage.SignalAccountRecord;
import su.sres.signalservice.api.storage.SignalStorageManifest;
import su.sres.signalservice.api.storage.SignalStorageRecord;
import su.sres.signalservice.api.storage.StorageId;
import su.sres.signalservice.api.storage.StorageKey;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Restored the AccountRecord present in the storage service, if any. This will overwrite any local
 * data that is stored in AccountRecord, so this should only be done immediately after registration.
 */
public class StorageAccountRestoreJob extends BaseJob {

  public static String KEY = "StorageAccountRestoreJob";

  public static long LIFESPAN = TimeUnit.SECONDS.toMillis(20);

  private static final String TAG = Log.tag(StorageAccountRestoreJob.class);

  public StorageAccountRestoreJob() {
    this(new Parameters.Builder()
             .setQueue(StorageSyncJob.QUEUE_KEY)
             .addConstraint(NetworkConstraint.KEY)
             .setMaxInstancesForFactory(1)
             .setMaxAttempts(1)
             .setLifespan(LIFESPAN)
             .build());
  }

  private StorageAccountRestoreJob(@NonNull Parameters parameters) {
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
  protected void onRun() throws Exception {
    SignalServiceAccountManager accountManager    = ApplicationDependencies.getSignalServiceAccountManager();
    StorageKey                  storageServiceKey = SignalStore.storageService().getOrCreateStorageKey();

    Log.i(TAG, "Retrieving manifest...");
    Optional<SignalStorageManifest> manifest = accountManager.getStorageManifest(storageServiceKey);

    if (!manifest.isPresent()) {
      Log.w(TAG, "Manifest did not exist or was undecryptable (bad key). Not restoring. Force-pushing.");
      ApplicationDependencies.getJobManager().add(new StorageForcePushJob());
      return;
    }

    Log.i(TAG, "Resetting the local manifest to an empty state so that it will sync later.");
    SignalStore.storageService().setManifest(SignalStorageManifest.EMPTY);

    Optional<StorageId> accountId = manifest.get().getAccountStorageId();

    if (!accountId.isPresent()) {
      Log.w(TAG, "Manifest had no account record! Not restoring.");
      return;
    }

    Log.i(TAG, "Retrieving account record...");
    List<SignalStorageRecord> records = accountManager.readStorageRecords(storageServiceKey, Collections.singletonList(accountId.get()));
    SignalStorageRecord       record  = records.size() > 0 ? records.get(0) : null;

    if (record == null) {
      Log.w(TAG, "Could not find account record, even though we had an ID! Not restoring.");
      return;
    }

    SignalAccountRecord accountRecord = record.getAccount().orNull();
    if (accountRecord == null) {
      Log.w(TAG, "The storage record didn't actually have an account on it! Not restoring.");
      return;
    }

    Log.i(TAG, "Applying changes locally...");
    ShadowDatabase.getRawDatabase().beginTransaction();
    try {
      StorageSyncHelper.applyAccountStorageSyncUpdates(context, Recipient.self(), accountRecord, false);
      ShadowDatabase.getRawDatabase().setTransactionSuccessful();
    } finally {
      ShadowDatabase.getRawDatabase().endTransaction();
    }

    JobManager jobManager = ApplicationDependencies.getJobManager();

    if (accountRecord.getAvatarUrlPath().isPresent()) {
      Log.i(TAG, "Fetching avatar...");
      Optional<JobTracker.JobState> state = jobManager.runSynchronously(new RetrieveProfileAvatarJob(Recipient.self(), accountRecord.getAvatarUrlPath().get()), LIFESPAN / 2);

      if (state.isPresent()) {
        Log.i(TAG, "Avatar retrieved successfully. " + state.get());
      } else {
        Log.w(TAG, "Avatar retrieval did not complete in time (or otherwise failed).");
      }
    } else {
      Log.i(TAG, "No avatar present. Not fetching.");
    }

    Log.i(TAG, "Refreshing attributes...");
    Optional<JobTracker.JobState> state = jobManager.runSynchronously(new RefreshAttributesJob(), LIFESPAN / 2);

    if (state.isPresent()) {
      Log.i(TAG, "Attributes refreshed successfully. " + state.get());
    } else {
      Log.w(TAG, "Attribute refresh did not complete in time (or otherwise failed).");
    }
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof PushNetworkException;
  }

  @Override
  public void onFailure() {
  }

  public static class Factory implements Job.Factory<StorageAccountRestoreJob> {
    @Override
    public @NonNull
    StorageAccountRestoreJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new StorageAccountRestoreJob(parameters);
    }
  }
}