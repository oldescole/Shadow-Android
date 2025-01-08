package su.sres.securesms.jobs;

import androidx.annotation.NonNull;

import com.annimon.stream.Stream;

import su.sres.securesms.database.ShadowDatabase;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.storage.StorageSyncHelper;
import su.sres.securesms.storage.StorageSyncModels;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.database.UnknownStorageIdDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.core.util.logging.Log;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.storage.StorageSyncValidations;
import su.sres.securesms.transport.RetryLaterException;

import org.whispersystems.libsignal.InvalidKeyException;

import su.sres.signalservice.api.SignalServiceAccountManager;
import su.sres.signalservice.api.storage.StorageId;
import su.sres.signalservice.api.storage.StorageKey;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;
import su.sres.signalservice.api.storage.SignalStorageManifest;
import su.sres.signalservice.api.storage.SignalStorageRecord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Forces remote storage to match our local state. This should only be done when we detect that the
 * remote data is badly-encrypted (which should only happen after re-registering without a PIN).
 */
public class StorageForcePushJob extends BaseJob {

  public static final String KEY = "StorageForcePushJob";

  private static final String TAG = Log.tag(StorageForcePushJob.class);

  public StorageForcePushJob() {
    this(new Parameters.Builder().addConstraint(NetworkConstraint.KEY)
                                 .setQueue(StorageSyncJob.QUEUE_KEY)
                                 .setMaxInstancesForFactory(1)
                                 .setLifespan(TimeUnit.DAYS.toMillis(1))
                                 .build());
  }

  private StorageForcePushJob(@NonNull Parameters parameters) {
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
  protected void onRun() throws IOException, RetryLaterException {
    StorageKey                  storageServiceKey = SignalStore.storageService().getOrCreateStorageKey();
    SignalServiceAccountManager accountManager    = ApplicationDependencies.getSignalServiceAccountManager();
    RecipientDatabase           recipientDatabase = ShadowDatabase.recipients();
    UnknownStorageIdDatabase    storageIdDatabase = ShadowDatabase.unknownStorageIds();

    long                        currentVersion       = accountManager.getStorageManifestVersion();
    Map<RecipientId, StorageId> oldContactStorageIds = recipientDatabase.getContactStorageSyncIdsMap();

    long                        newVersion           = currentVersion + 1;
    Map<RecipientId, StorageId> newContactStorageIds = generateContactStorageIds(oldContactStorageIds);
    List<SignalStorageRecord> inserts = Stream.of(oldContactStorageIds.keySet())
                                              .map(recipientDatabase::getRecipientSettingsForSync)
                                              .withoutNulls()
                                              .map(s -> StorageSyncModels.localToRemoteRecord(s, Objects.requireNonNull(newContactStorageIds.get(s.getId())).getRaw()))
                                              .toList();

    SignalStorageRecord accountRecord    = StorageSyncHelper.buildAccountRecord(context, Recipient.self().fresh());
    List<StorageId>     allNewStorageIds = new ArrayList<>(newContactStorageIds.values());

    inserts.add(accountRecord);
    allNewStorageIds.add(accountRecord.getId());

    SignalStorageManifest manifest = new SignalStorageManifest(newVersion, allNewStorageIds);
    StorageSyncValidations.validateForcePush(manifest, inserts, Recipient.self().fresh());

    try {
      if (newVersion > 1) {
        Log.i(TAG, String.format(Locale.ENGLISH, "Force-pushing data. Inserting %d IDs.", inserts.size()));
        if (accountManager.resetStorageRecords(storageServiceKey, manifest, inserts).isPresent()) {
          Log.w(TAG, "Hit a conflict. Trying again.");
          throw new RetryLaterException();
        }
      } else {
        Log.i(TAG, String.format(Locale.ENGLISH, "First version, normal push. Inserting %d IDs.", inserts.size()));
        if (accountManager.writeStorageRecords(storageServiceKey, manifest, inserts, Collections.emptyList()).isPresent()) {
          Log.w(TAG, "Hit a conflict. Trying again.");
          throw new RetryLaterException();
        }
      }
    } catch (InvalidKeyException e) {
      Log.w(TAG, "Hit an invalid key exception, which likely indicates a conflict.");
      throw new RetryLaterException(e);
    }

    Log.i(TAG, "Force push succeeded. Updating local manifest version to: " + newVersion);
    SignalStore.storageService().setManifest(manifest);
    recipientDatabase.applyStorageIdUpdates(newContactStorageIds);
    recipientDatabase.applyStorageIdUpdates(Collections.singletonMap(Recipient.self().getId(), accountRecord.getId()));
    storageIdDatabase.deleteAll();
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof PushNetworkException || e instanceof RetryLaterException;
  }

  @Override
  public void onFailure() {
  }

  private static @NonNull Map<RecipientId, StorageId> generateContactStorageIds(@NonNull Map<RecipientId, StorageId> oldKeys) {
    Map<RecipientId, StorageId> out = new HashMap<>();

    for (Map.Entry<RecipientId, StorageId> entry : oldKeys.entrySet()) {
      out.put(entry.getKey(), entry.getValue().withNewBytes(StorageSyncHelper.generateKey()));
    }

    return out;
  }

  public static final class Factory implements Job.Factory<StorageForcePushJob> {

    @Override
    public @NonNull StorageForcePushJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new StorageForcePushJob(parameters);
    }
  }
}