package su.sres.securesms.jobs;

import android.app.Application;

import androidx.annotation.NonNull;

import su.sres.securesms.crypto.UnidentifiedAccessUtil;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.MessageDatabase.MarkedMessageInfo;
import su.sres.securesms.database.model.MessageId;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.core.util.logging.Log;
import su.sres.securesms.net.NotPushRegisteredException;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.recipients.RecipientUtil;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.securesms.util.Util;
import su.sres.signalservice.api.SignalServiceMessageSender;
import su.sres.signalservice.api.crypto.ContentHint;
import su.sres.signalservice.api.crypto.UntrustedIdentityException;
import su.sres.signalservice.api.messages.SendMessageResult;
import su.sres.signalservice.api.messages.SignalServiceReceiptMessage;
import su.sres.signalservice.api.push.SignalServiceAddress;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;
import su.sres.signalservice.api.push.exceptions.ServerRejectedException;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static su.sres.securesms.jobs.SendReadReceiptJob.MAX_TIMESTAMPS;

public class SendViewedReceiptJob extends BaseJob {

  public static final String KEY = "SendViewedReceiptJob";

  private static final String TAG = Log.tag(SendViewedReceiptJob.class);

  private static final String KEY_THREAD                  = "thread";
  private static final String KEY_ADDRESS                 = "address";
  private static final String KEY_RECIPIENT               = "recipient";
  private static final String KEY_MESSAGE_SENT_TIMESTAMPS = "message_ids";
  private static final String KEY_MESSAGE_IDS             = "message_db_ids";
  private static final String KEY_TIMESTAMP               = "timestamp";

  private final long            threadId;
  private final RecipientId     recipientId;
  private final List<Long>      messageSentTimestamps;
  private final List<MessageId> messageIds;
  private final long            timestamp;

  public SendViewedReceiptJob(long threadId, @NonNull RecipientId recipientId, long syncTimestamp, @NonNull MessageId messageId) {
    this(threadId, recipientId, Collections.singletonList(syncTimestamp), Collections.singletonList(messageId));
  }

  private SendViewedReceiptJob(long threadId, @NonNull RecipientId recipientId, @NonNull List<Long> messageSentTimestamps, @NonNull List<MessageId> messageIds) {
    this(new Parameters.Builder()
             .addConstraint(NetworkConstraint.KEY)
             .setLifespan(TimeUnit.DAYS.toMillis(1))
             .setMaxAttempts(Parameters.UNLIMITED)
             .build(),
         threadId,
         recipientId,
         SendReadReceiptJob.ensureSize(messageSentTimestamps, MAX_TIMESTAMPS),
         SendReadReceiptJob.ensureSize(messageIds, MAX_TIMESTAMPS),
         System.currentTimeMillis());
  }

  private SendViewedReceiptJob(@NonNull Parameters parameters,
                               long threadId,
                               @NonNull RecipientId recipientId,
                               @NonNull List<Long> messageSentTimestamps,
                               @NonNull List<MessageId> messageIds,
                               long timestamp)
  {
    super(parameters);

    this.threadId              = threadId;
    this.recipientId           = recipientId;
    this.messageSentTimestamps = messageSentTimestamps;
    this.messageIds            = messageIds;
    this.timestamp             = timestamp;
  }

  /**
   * Enqueues all the necessary jobs for viewed receipts, ensuring that they're all within the
   * maximum size.
   */
  public static void enqueue(long threadId, @NonNull RecipientId recipientId, List<MarkedMessageInfo> markedMessageInfos) {
    JobManager                    jobManager      = ApplicationDependencies.getJobManager();
    List<List<MarkedMessageInfo>> messageIdChunks = Util.chunk(markedMessageInfos, MAX_TIMESTAMPS);

    if (messageIdChunks.size() > 1) {
      Log.w(TAG, "Large receipt count! Had to break into multiple chunks. Total count: " + markedMessageInfos.size());
    }

    for (List<MarkedMessageInfo> chunk : messageIdChunks) {
      List<Long>      sentTimestamps = chunk.stream().map(info -> info.getSyncMessageId().getTimetamp()).collect(Collectors.toList());
      List<MessageId> messageIds     = chunk.stream().map(MarkedMessageInfo::getMessageId).collect(Collectors.toList());

      jobManager.add(new SendViewedReceiptJob(threadId, recipientId, sentTimestamps, messageIds));
    }
  }

  @Override
  public @NonNull Data serialize() {
    List<String> serializedMessageIds = messageIds.stream().map(MessageId::serialize).collect(Collectors.toList());

    return new Data.Builder().putString(KEY_RECIPIENT, recipientId.serialize())
                             .putLongListAsArray(KEY_MESSAGE_SENT_TIMESTAMPS, messageSentTimestamps)
                             .putStringListAsArray(KEY_MESSAGE_IDS, serializedMessageIds)
                             .putLong(KEY_TIMESTAMP, timestamp)
                             .putLong(KEY_THREAD, threadId)
                             .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException, UntrustedIdentityException {
    if (!Recipient.self().isRegistered()) {
      throw new NotPushRegisteredException();
    }

    if (!TextSecurePreferences.isReadReceiptsEnabled(context)) {
      Log.w(TAG, "Read receipts not enabled!");
      return;
    }

    if (messageSentTimestamps.isEmpty()) {
      Log.w(TAG, "No sync timestamps!");
      return;
    }

    if (!RecipientUtil.isMessageRequestAccepted(context, threadId)) {
      Log.w(TAG, "Refusing to send receipts to untrusted recipient");
      return;
    }

    Recipient recipient = Recipient.resolved(recipientId);
    if (recipient.isBlocked()) {
      Log.w(TAG, "Refusing to send receipts to blocked recipient");
      return;
    }

    if (recipient.isGroup()) {
      Log.w(TAG, "Refusing to send receipts to group");
      return;
    }

    if (recipient.isUnregistered()) {
      Log.w(TAG, recipient.getId() + " not registered!");
      return;
    }

    SignalServiceMessageSender messageSender = ApplicationDependencies.getSignalServiceMessageSender();
    SignalServiceAddress       remoteAddress = RecipientUtil.toSignalServiceAddress(context, recipient);
    SignalServiceReceiptMessage receiptMessage = new SignalServiceReceiptMessage(SignalServiceReceiptMessage.Type.VIEWED,
                                                                                 messageSentTimestamps,
                                                                                 timestamp);

    SendMessageResult result = messageSender.sendReceipt(remoteAddress,
                                                         UnidentifiedAccessUtil.getAccessFor(context, Recipient.resolved(recipientId)),
                                                         receiptMessage);

    if (Util.hasItems(messageIds)) {
      DatabaseFactory.getMessageLogDatabase(context).insertIfPossible(recipientId, timestamp, result, ContentHint.IMPLICIT, messageIds);
    }
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    if (e instanceof ServerRejectedException) return false;
    if (e instanceof PushNetworkException) return true;
    return false;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Failed to send read receipts to: " + recipientId);
  }

  public static final class Factory implements Job.Factory<SendViewedReceiptJob> {

    private final Application application;

    public Factory(@NonNull Application application) {
      this.application = application;
    }

    @Override
    public @NonNull
    SendViewedReceiptJob create(@NonNull Parameters parameters, @NonNull Data data) {
      long            timestamp      = data.getLong(KEY_TIMESTAMP);
      List<Long>      syncTimestamps = data.getLongArrayAsList(KEY_MESSAGE_SENT_TIMESTAMPS);
      List<String>    rawMessageIds  = data.hasStringArray(KEY_MESSAGE_IDS) ? data.getStringArrayAsList(KEY_MESSAGE_IDS) : Collections.emptyList();
      List<MessageId> messageIds     = rawMessageIds.stream().map(MessageId::deserialize).collect(Collectors.toList());
      long            threadId       = data.getLong(KEY_THREAD);
      RecipientId recipientId = data.hasString(KEY_RECIPIENT) ? RecipientId.from(data.getString(KEY_RECIPIENT))
                                                              : Recipient.external(application, data.getString(KEY_ADDRESS)).getId();

      return new SendViewedReceiptJob(parameters, threadId, recipientId, syncTimestamps, messageIds, timestamp);
    }
  }
}
