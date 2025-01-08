package su.sres.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.telephony.SmsMessage;

import su.sres.securesms.database.MessageDatabase;
import su.sres.securesms.database.ShadowDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.SqlCipherMigrationConstraint;
import su.sres.core.util.logging.Log;

import su.sres.securesms.database.MessageDatabase.InsertResult;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.sms.IncomingTextMessage;
import su.sres.securesms.transport.RetryLaterException;
import su.sres.securesms.util.Base64;
import su.sres.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SmsReceiveJob extends BaseJob {

  public static final String KEY = "SmsReceiveJob";

  private static final String TAG = Log.tag(SmsReceiveJob.class);

  private static final String KEY_PDUS            = "pdus";
  private static final String KEY_SUBSCRIPTION_ID = "subscription_id";

  private @Nullable Object[] pdus;

  private int subscriptionId;

  public SmsReceiveJob(@Nullable Object[] pdus, int subscriptionId) {
    this(new Job.Parameters.Builder()
                    .addConstraint(SqlCipherMigrationConstraint.KEY)
                    .setLifespan(TimeUnit.DAYS.toMillis(1))
                    .build(),
            pdus,
            subscriptionId);
  }

  private SmsReceiveJob(@NonNull Job.Parameters parameters, @Nullable Object[] pdus, int subscriptionId) {
    super(parameters);

    this.pdus           = pdus;
    this.subscriptionId = subscriptionId;
  }

  @Override
  public @NonNull Data serialize() {
    String[] encoded = new String[pdus.length];
    for (int i = 0; i < pdus.length; i++) {
      encoded[i] = Base64.encodeBytes((byte[]) pdus[i]);
    }

    return new Data.Builder().putStringArray(KEY_PDUS, encoded)
            .putInt(KEY_SUBSCRIPTION_ID, subscriptionId)
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws MigrationPendingException, RetryLaterException {
    Optional<IncomingTextMessage> message = assembleMessageFragments(pdus, subscriptionId);
    if (SignalStore.account().getUserLogin() == null) {
      Log.i(TAG, "Received an SMS before we're registered...");

      if (message.isPresent()) {
          Log.w(TAG, "Received an SMS before registration is complete. We'll try again later.");
          throw new RetryLaterException();
      } else {
        Log.w(TAG, "Received an SMS before registration is complete, but couldn't assemble the message anyway. Ignoring.");
        return;
      }
    }

    if (message.isPresent() && !isBlocked(message.get())) {
      Optional<InsertResult> insertResult = storeMessage(message.get());

      if (insertResult.isPresent()) {
        ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId());
      }
    } else if (message.isPresent()) {
      Log.w(TAG, "*** Received blocked SMS, ignoring...");
    } else {
      Log.w(TAG, "*** Failed to assemble message fragments!");
    }
  }

  @Override
  public void onFailure() {

  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    return exception instanceof MigrationPendingException ||
            exception instanceof RetryLaterException;
  }

  private boolean isBlocked(IncomingTextMessage message) {
    if (message.getSender() != null) {
      Recipient recipient = Recipient.resolved(message.getSender());
      return recipient.isBlocked();
    }

    return false;
  }

  private Optional<InsertResult> storeMessage(IncomingTextMessage message) throws MigrationPendingException {
    MessageDatabase database = ShadowDatabase.sms();
    database.ensureMigration();

    if (TextSecurePreferences.getNeedsSqlCipherMigration(context)) {
      throw new MigrationPendingException();
    }

    if (message.isSecureMessage()) {
      IncomingTextMessage    placeholder  = new IncomingTextMessage(message, "");
      Optional<InsertResult> insertResult = database.insertMessageInbox(placeholder);
      database.markAsLegacyVersion(insertResult.get().getMessageId());

      return insertResult;
    } else {
      return database.insertMessageInbox(message);
    }
  }

  private Optional<IncomingTextMessage> assembleMessageFragments(@Nullable Object[] pdus, int subscriptionId) {
    if (pdus == null) {
      return Optional.absent();
    }

    List<IncomingTextMessage> messages = new LinkedList<>();

    for (Object pdu : pdus) {
      SmsMessage message   = SmsMessage.createFromPdu((byte[])pdu);
      Recipient  recipient = Recipient.external(context, message.getDisplayOriginatingAddress());
      messages.add(new IncomingTextMessage(recipient.getId(), message, subscriptionId));
    }

    if (messages.isEmpty()) {
      return Optional.absent();
    }

    return Optional.of(new IncomingTextMessage(messages));
  }

  public static final class Factory implements Job.Factory<SmsReceiveJob> {
    @Override
    public @NonNull SmsReceiveJob create(@NonNull Parameters parameters, @NonNull Data data) {
      try {
        int subscriptionId = data.getInt(KEY_SUBSCRIPTION_ID);
        String[] encoded   = data.getStringArray(KEY_PDUS);
        Object[] pdus      = new Object[encoded.length];

        for (int i = 0; i < encoded.length; i++) {
          pdus[i] = Base64.decode(encoded[i]);
        }

        return new SmsReceiveJob(parameters, pdus, subscriptionId);
      } catch (IOException e) {
        throw new AssertionError(e);
      }
    }
  }

  private class MigrationPendingException extends Exception {
  }
}
