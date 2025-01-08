package su.sres.securesms.jobs;

import android.app.Application;

import androidx.annotation.NonNull;

import com.annimon.stream.Stream;

import su.sres.securesms.database.ShadowDatabase;
import su.sres.securesms.groups.GroupsV1MigrationUtil;
import su.sres.securesms.database.model.ThreadRecord;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.groups.GroupChangeBusyException;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.core.util.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.transport.RetryLaterException;
import su.sres.core.util.concurrent.SignalExecutors;
import su.sres.signalservice.api.groupsv2.NoCredentialForRedemptionTimeException;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class GroupV1MigrationJob extends BaseJob {

  private static final String TAG = Log.tag(GroupV1MigrationJob.class);

  public static final String KEY = "GroupV1MigrationJob";

  private static final String KEY_RECIPIENT_ID = "recipient_id";

  private static final int  ROUTINE_LIMIT    = 20;
  private static final long REFRESH_INTERVAL = TimeUnit.HOURS.toMillis(1);

  private final RecipientId recipientId;

  private GroupV1MigrationJob(@NonNull RecipientId recipientId) {
    this(new Parameters.Builder()
             .setQueue(recipientId.toQueueKey())
             .setMaxAttempts(Parameters.UNLIMITED)
             .setLifespan(TimeUnit.DAYS.toMillis(7))
             .addConstraint(NetworkConstraint.KEY)
             .build(),
         recipientId);
  }

  private GroupV1MigrationJob(@NonNull Parameters parameters, @NonNull RecipientId recipientId) {
    super(parameters);
    this.recipientId = recipientId;
  }

  public static void enqueuePossibleAutoMigrate(@NonNull RecipientId recipientId) {

    SignalExecutors.BOUNDED.execute(() -> {
      if (Recipient.resolved(recipientId).isPushV1Group()) {
        ApplicationDependencies.getJobManager().add(new GroupV1MigrationJob(recipientId));
      }
    });
  }

  public static void enqueueRoutineMigrationsIfNecessary(@NonNull Application application) {

    if (!SignalStore.registrationValues().isRegistrationComplete() ||
        !SignalStore.account().isRegistered() ||
        SignalStore.account().getAci() == null)
    {
      Log.i(TAG, "Registration not complete. Skipping.");
      return;
    }

    long timeSinceRefresh = System.currentTimeMillis() - SignalStore.misc().getLastGv1RoutineMigrationTime();
    if (timeSinceRefresh < REFRESH_INTERVAL) {
      Log.i(TAG, "Too soon to refresh. Did the last refresh " + timeSinceRefresh + " ms ago.");
      return;
    }

    SignalStore.misc().setLastGv1RoutineMigrationTime(System.currentTimeMillis());

    SignalExecutors.BOUNDED.execute(() -> {
      JobManager         jobManager   = ApplicationDependencies.getJobManager();
      List<ThreadRecord> threads      = ShadowDatabase.threads().getRecentV1Groups(ROUTINE_LIMIT);
      Set<RecipientId>   needsRefresh = new HashSet<>();

      if (threads.size() > 0) {
        Log.d(TAG, "About to enqueue refreshes for " + threads.size() + " groups.");
      }

      for (ThreadRecord thread : threads) {
        jobManager.add(new GroupV1MigrationJob(thread.getRecipient().getId()));

        needsRefresh.addAll(Stream.of(thread.getRecipient().getParticipants())
                                  .filter(r -> r.getGroupsV2Capability() != Recipient.Capability.SUPPORTED ||
                                               r.getGroupsV1MigrationCapability() != Recipient.Capability.SUPPORTED)
                                  .map(Recipient::getId)
                                  .toList());
      }

      if (needsRefresh.size() > 0) {
        Log.w(TAG, "Enqueuing profile refreshes for " + needsRefresh.size() + " GV1 participants.");
        RetrieveProfileJob.enqueue(needsRefresh);
      }
    });
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putString(KEY_RECIPIENT_ID, recipientId.serialize())
                             .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected void onRun() throws IOException, GroupChangeBusyException, RetryLaterException {
    try {
      GroupsV1MigrationUtil.migrate(context, recipientId, false);
    } catch (GroupsV1MigrationUtil.InvalidMigrationStateException e) {
      Log.w(TAG, "Invalid migration state. Skipping.");
    }
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof PushNetworkException ||
           e instanceof NoCredentialForRedemptionTimeException ||
           e instanceof GroupChangeBusyException ||
           e instanceof RetryLaterException;
  }

  @Override
  public void onFailure() {
  }

  public static final class Factory implements Job.Factory<GroupV1MigrationJob> {
    @Override
    public @NonNull GroupV1MigrationJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new GroupV1MigrationJob(parameters, RecipientId.from(data.getString(KEY_RECIPIENT_ID)));
    }
  }
}