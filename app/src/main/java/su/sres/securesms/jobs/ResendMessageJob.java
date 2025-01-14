package su.sres.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import su.sres.core.util.ThreadUtil;
import su.sres.core.util.logging.Log;
import su.sres.securesms.crypto.UnidentifiedAccessUtil;
import su.sres.securesms.database.GroupDatabase;
import su.sres.securesms.database.ShadowDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.groups.GroupId;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.recipients.RecipientUtil;

import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.protocol.SenderKeyDistributionMessage;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.SignalServiceMessageSender;
import su.sres.signalservice.api.crypto.ContentHint;
import su.sres.signalservice.api.crypto.UnidentifiedAccessPair;
import su.sres.signalservice.api.messages.SendMessageResult;
import su.sres.signalservice.api.push.DistributionId;
import su.sres.signalservice.api.push.SignalServiceAddress;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;
import su.sres.signalservice.internal.push.SignalServiceProtos.Content;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Resends a previously-sent message in response to receiving a retry receipt.
 *
 * Not for arbitrary retries due to network flakiness or something -- those should be handled within each individual job.
 */
public class ResendMessageJob extends BaseJob {

  public static final String KEY = "ResendMessageJob";

  private static final String TAG = Log.tag(ResendMessageJob.class);

  private final RecipientId    recipientId;
  private final long           sentTimestamp;
  private final Content        content;
  private final ContentHint    contentHint;
  private final GroupId.V2     groupId;
  private final DistributionId distributionId;

  private static final String KEY_RECIPIENT_ID    = "recipient_id";
  private static final String KEY_SENT_TIMESTAMP  = "sent_timestamp";
  private static final String KEY_CONTENT         = "content";
  private static final String KEY_CONTENT_HINT    = "content_hint";
  private static final String KEY_GROUP_ID        = "group_id";
  private static final String KEY_DISTRIBUTION_ID = "distribution_id";

  public ResendMessageJob(@NonNull RecipientId recipientId,
                          long sentTimestamp,
                          @NonNull Content content,
                          @NonNull ContentHint contentHint,
                          @Nullable GroupId.V2 groupId,
                          @Nullable DistributionId distributionId)
  {
    this(recipientId,
         sentTimestamp,
         content,
         contentHint,
         groupId,
         distributionId,
         new Parameters.Builder().setQueue(recipientId.toQueueKey())
                                 .setLifespan(TimeUnit.DAYS.toMillis(1))
                                 .setMaxAttempts(Parameters.UNLIMITED)
                                 .addConstraint(NetworkConstraint.KEY)
                                 .build());
  }

  private ResendMessageJob(@NonNull RecipientId recipientId,
                           long sentTimestamp,
                           @NonNull Content content,
                           @NonNull ContentHint contentHint,
                           @Nullable GroupId.V2 groupId,
                           @Nullable DistributionId distributionId,
                           @NonNull Parameters parameters)
  {
    super(parameters);

    this.recipientId    = recipientId;
    this.sentTimestamp  = sentTimestamp;
    this.content        = content;
    this.contentHint    = contentHint;
    this.groupId        = groupId;
    this.distributionId = distributionId;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder()
        .putString(KEY_RECIPIENT_ID, recipientId.serialize())
        .putLong(KEY_SENT_TIMESTAMP, sentTimestamp)
        .putBlobAsString(KEY_CONTENT, content.toByteArray())
        .putInt(KEY_CONTENT_HINT, contentHint.getType())
        .putBlobAsString(KEY_GROUP_ID, groupId != null ? groupId.getDecodedId() : null)
        .putString(KEY_DISTRIBUTION_ID, distributionId != null ? distributionId.toString() : null)
        .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  protected void onRun() throws Exception {
    if (SignalStore.internalValues().delayResends()) {
      Log.w(TAG, "Delaying resend by 10 sec because of an internal preference.");
      ThreadUtil.sleep(10000);
    }

    SignalServiceMessageSender messageSender = ApplicationDependencies.getSignalServiceMessageSender();
    Recipient                  recipient     = Recipient.resolved(recipientId);

    if (recipient.isUnregistered()) {
      Log.w(TAG, recipient.getId() + " is unregistered!");
      return;
    }

    SignalServiceAddress             address       = RecipientUtil.toSignalServiceAddress(context, recipient);
    Optional<UnidentifiedAccessPair> access        = UnidentifiedAccessUtil.getAccessFor(context, recipient);
    Content                          contentToSend = content;

    if (distributionId != null) {
      Optional<GroupDatabase.GroupRecord> groupRecord = ShadowDatabase.groups().getGroupByDistributionId(distributionId);

      if (!groupRecord.isPresent()) {
        Log.w(TAG, "Could not find a matching group for the distributionId! Skipping message send.");
        return;
      } else if (!groupRecord.get().getMembers().contains(recipientId)) {
        Log.w(TAG, "The target user is no longer in the group! Skipping message send.");
        return;
      }

      SenderKeyDistributionMessage senderKeyDistributionMessage = messageSender.getOrCreateNewGroupSession(distributionId);
      ByteString                   distributionBytes            = ByteString.copyFrom(senderKeyDistributionMessage.serialize());

      contentToSend = contentToSend.toBuilder().setSenderKeyDistributionMessage(distributionBytes).build();
    }

    SendMessageResult result = messageSender.resendContent(address, access, sentTimestamp, contentToSend, contentHint, Optional.fromNullable(groupId).transform(GroupId::getDecodedId));

    if (result.isSuccess() && distributionId != null) {
      List<SignalProtocolAddress> addresses = result.getSuccess()
                                                    .getDevices()
                                                    .stream()
                                                    .map(device -> new SignalProtocolAddress(recipient.requireServiceId(), device))
                                                    .collect(Collectors.toList());

      ApplicationDependencies.getSenderKeyStore().markSenderKeySharedWith(distributionId, addresses);
    }
  }

  @Override
  protected boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof PushNetworkException;
  }

  @Override
  public void onFailure() {
  }

  public static final class Factory implements Job.Factory<ResendMessageJob> {

    @Override
    public @NonNull ResendMessageJob create(@NonNull Parameters parameters, @NonNull Data data) {
      Content content;
      try {
        content = Content.parseFrom(data.getStringAsBlob(KEY_CONTENT));
      } catch (InvalidProtocolBufferException e) {
        throw new AssertionError(e);
      }

      byte[]     rawGroupId = data.getStringAsBlob(KEY_GROUP_ID);
      GroupId.V2 groupId    = rawGroupId != null ? GroupId.pushOrThrow(rawGroupId).requireV2() : null;

      String         rawDistributionId = data.getString(KEY_DISTRIBUTION_ID);
      DistributionId distributionId    = rawDistributionId != null ? DistributionId.from(rawDistributionId) : null;

      return new ResendMessageJob(RecipientId.from(data.getString(KEY_RECIPIENT_ID)),
                                  data.getLong(KEY_SENT_TIMESTAMP),
                                  content,
                                  ContentHint.fromType(data.getInt(KEY_CONTENT_HINT)),
                                  groupId,
                                  distributionId,
                                  parameters);
    }
  }
}
