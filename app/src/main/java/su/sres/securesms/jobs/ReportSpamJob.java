package su.sres.securesms.jobs;

import androidx.annotation.NonNull;

import su.sres.core.util.logging.Log;
import su.sres.securesms.database.MessageDatabase.ReportSpamData;
import su.sres.securesms.database.ShadowDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.recipients.Recipient;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.SignalServiceAccountManager;
import su.sres.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import su.sres.signalservice.api.push.exceptions.ServerRejectedException;

import java.io.IOException;
import java.util.List;

/**
 * Report 1 to {@link #MAX_MESSAGE_COUNT} message guids received prior to {@link #timestamp} in {@link #threadId} to the server as spam.
 */
public class ReportSpamJob extends BaseJob {

  public static final  String KEY = "ReportSpamJob";
  private static final String TAG = Log.tag(ReportSpamJob.class);

  private static final String KEY_THREAD_ID     = "thread_id";
  private static final String KEY_TIMESTAMP     = "timestamp";
  private static final int    MAX_MESSAGE_COUNT = 3;

  private final long threadId;
  private final long timestamp;

  public ReportSpamJob(long threadId, long timestamp) {
    this(new Parameters.Builder().addConstraint(NetworkConstraint.KEY)
                                 .setMaxAttempts(5)
                                 .setQueue("ReportSpamJob")
                                 .build(),
         threadId,
         timestamp);
  }

  private ReportSpamJob(@NonNull Parameters parameters, long threadId, long timestamp) {
    super(parameters);
    this.threadId  = threadId;
    this.timestamp = timestamp;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_THREAD_ID, threadId)
                             .putLong(KEY_TIMESTAMP, timestamp)
                             .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException {
    if (!SignalStore.account().isRegistered()) {
      return;
    }

    int                         count                       = 0;
    List<ReportSpamData>        reportSpamData              = ShadowDatabase.mmsSms().getReportSpamMessageServerData(threadId, timestamp, MAX_MESSAGE_COUNT);
    SignalServiceAccountManager signalServiceAccountManager = ApplicationDependencies.getSignalServiceAccountManager();
    for (ReportSpamData data : reportSpamData) {
      Optional<String> e164 = Recipient.resolved(data.getRecipientId()).getE164();
      if (e164.isPresent()) {
        signalServiceAccountManager.reportSpam(e164.get(), data.getServerGuid());
        count++;
      } else {
        Log.w(TAG, "Unable to report spam without an e164 for " + data.getRecipientId());
      }
    }
    Log.i(TAG, "Reported " + count + " out of " + reportSpamData.size() + " messages in thread " + threadId + " as spam");
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    if (exception instanceof ServerRejectedException) {
      return false;
    } else if (exception instanceof NonSuccessfulResponseCodeException) {
      return ((NonSuccessfulResponseCodeException) exception).is5xx();
    }

    return exception instanceof IOException;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Canceling report spam for thread " + threadId);
  }

  public static final class Factory implements Job.Factory<ReportSpamJob> {
    @Override
    public @NonNull ReportSpamJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new ReportSpamJob(parameters, data.getLong(KEY_THREAD_ID), data.getLong(KEY_TIMESTAMP));
    }
  }
}
