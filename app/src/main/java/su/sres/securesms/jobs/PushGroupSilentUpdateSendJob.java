package su.sres.securesms.jobs;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;
import com.google.protobuf.InvalidProtocolBufferException;

import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.net.NotPushRegisteredException;
import su.sres.signalservice.api.push.exceptions.ServerRejectedException;
import su.sres.storageservice.protos.groups.local.DecryptedGroup;
import su.sres.securesms.crypto.UnidentifiedAccessUtil;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.groups.GroupId;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.core.util.logging.Log;
import su.sres.securesms.mms.MessageGroupContext;
import su.sres.securesms.mms.OutgoingGroupUpdateMessage;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.recipients.RecipientUtil;
import su.sres.securesms.transport.RetryLaterException;
import su.sres.securesms.util.Base64;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.SignalServiceMessageSender;
import su.sres.signalservice.api.crypto.UnidentifiedAccessPair;
import su.sres.signalservice.api.crypto.UntrustedIdentityException;
import su.sres.signalservice.api.groupsv2.DecryptedGroupUtil;
import su.sres.signalservice.api.messages.SendMessageResult;
import su.sres.signalservice.api.messages.SignalServiceDataMessage;
import su.sres.signalservice.api.messages.SignalServiceGroupV2;
import su.sres.signalservice.api.push.SignalServiceAddress;
import su.sres.signalservice.api.util.UuidUtil;
import su.sres.signalservice.internal.push.SignalServiceProtos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Sends an update to a group without inserting a change message locally.
 * <p>
 * An example usage would be to update a group with a profile key change.
 */
public final class PushGroupSilentUpdateSendJob extends BaseJob {

    public static final String KEY = "PushGroupSilentSendJob";

    private static final String TAG = Log.tag(PushGroupSilentUpdateSendJob.class);

    private static final String KEY_RECIPIENTS              = "recipients";
    private static final String KEY_INITIAL_RECIPIENT_COUNT = "initial_recipient_count";
    private static final String KEY_TIMESTAMP               = "timestamp";
    private static final String KEY_GROUP_CONTEXT_V2        = "group_context_v2";

    private final List<RecipientId>                  recipients;
    private final int                                initialRecipientCount;
    private final SignalServiceProtos.GroupContextV2 groupContextV2;
    private final long                               timestamp;

    @WorkerThread
    public static @NonNull Job create(@NonNull Context context,
                                      @NonNull GroupId.V2 groupId,
                                      @NonNull DecryptedGroup decryptedGroup,
                                      @NonNull OutgoingGroupUpdateMessage groupMessage)
    {
        List<UUID> memberUuids  = DecryptedGroupUtil.toUuidList(decryptedGroup.getMembersList());
        List<UUID> pendingUuids = DecryptedGroupUtil.pendingToUuidList(decryptedGroup.getPendingMembersList());

        Set<RecipientId> recipients = Stream.concat(Stream.of(memberUuids), Stream.of(pendingUuids))
                .filter(uuid -> !UuidUtil.UNKNOWN_UUID.equals(uuid))
                .filter(uuid -> !Recipient.self().getUuid().get().equals(uuid))
                .map(uuid -> Recipient.externalPush(context, uuid, null, false))
                .filter(recipient -> recipient.getRegistered() != RecipientDatabase.RegisteredState.NOT_REGISTERED)
                .map(Recipient::getId)
                .collect(Collectors.toSet());

        MessageGroupContext.GroupV2Properties properties   = groupMessage.requireGroupV2Properties();
        SignalServiceProtos.GroupContextV2    groupContext = properties.getGroupContext();

        String queue = Recipient.externalGroupExact(context, groupId).getId().toQueueKey();

        return new PushGroupSilentUpdateSendJob(new ArrayList<>(recipients),
                recipients.size(),
                groupMessage.getSentTimeMillis(),
                groupContext,
                new Parameters.Builder()
                        .setQueue(queue)
                        .setLifespan(TimeUnit.DAYS.toMillis(1))
                        .setMaxAttempts(Parameters.UNLIMITED)
                        .build());
    }

    private PushGroupSilentUpdateSendJob(@NonNull List<RecipientId> recipients,
                                         int initialRecipientCount,
                                         long timestamp,
                                         @NonNull SignalServiceProtos.GroupContextV2 groupContextV2,
                                         @NonNull Parameters parameters)
    {
        super(parameters);

        this.recipients            = recipients;
        this.initialRecipientCount = initialRecipientCount;
        this.groupContextV2        = groupContextV2;
        this.timestamp             = timestamp;
    }

    @Override
    public @NonNull Data serialize() {
        return new Data.Builder().putString(KEY_RECIPIENTS, RecipientId.toSerializedList(recipients))
                .putInt(KEY_INITIAL_RECIPIENT_COUNT, initialRecipientCount)
                .putLong(KEY_TIMESTAMP, timestamp)
                .putString(KEY_GROUP_CONTEXT_V2, Base64.encodeBytes(groupContextV2.toByteArray()))
                .build();
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    protected void onRun() throws Exception {
        if (!Recipient.self().isRegistered()) {
            throw new NotPushRegisteredException();
        }

        List<Recipient> destinations = Stream.of(recipients).map(Recipient::resolved).toList();
        List<Recipient> completions  = deliver(destinations);

        for (Recipient completion : completions) {
            recipients.remove(completion.getId());
        }

        Log.i(TAG, "Completed now: " + completions.size() + ", Remaining: " + recipients.size());

        if (!recipients.isEmpty()) {
            Log.w(TAG, "Still need to send to " + recipients.size() + " recipients. Retrying.");
            throw new RetryLaterException();
        }
    }

    @Override
    protected boolean onShouldRetry(@NonNull Exception e) {
        if (e instanceof ServerRejectedException) return false;
        if (e instanceof NotPushRegisteredException) return false;
        return e instanceof IOException ||
                e instanceof RetryLaterException;
    }

    @Override
    public void onFailure() {
        Log.w(TAG, "Failed to send remote delete to all recipients! (" + (initialRecipientCount - recipients.size() + "/" + initialRecipientCount + ")") );
    }

    private @NonNull List<Recipient> deliver(@NonNull List<Recipient> destinations)
            throws IOException, UntrustedIdentityException
    {
        SignalServiceMessageSender             messageSender      = ApplicationDependencies.getSignalServiceMessageSender();
        List<SignalServiceAddress>             addresses          = RecipientUtil.toSignalServiceAddressesFromResolved(context, destinations);
        List<Optional<UnidentifiedAccessPair>> unidentifiedAccess = UnidentifiedAccessUtil.getAccessFor(context, destinations);

        SignalServiceGroupV2     group            = SignalServiceGroupV2.fromProtobuf(groupContextV2);
        SignalServiceDataMessage groupDataMessage = SignalServiceDataMessage.newBuilder()
                .withTimestamp(timestamp)
                .asGroupMessage(group)
                .build();

        List<SendMessageResult> results = messageSender.sendMessage(addresses, unidentifiedAccess, false, groupDataMessage);

        return GroupSendJobHelper.getCompletedSends(context, results);
    }

    public static class Factory implements Job.Factory<PushGroupSilentUpdateSendJob> {
        @Override
        public @NonNull
        PushGroupSilentUpdateSendJob create(@NonNull Parameters parameters, @NonNull Data data) {
            List<RecipientId> recipients            = RecipientId.fromSerializedList(data.getString(KEY_RECIPIENTS));
            int               initialRecipientCount = data.getInt(KEY_INITIAL_RECIPIENT_COUNT);
            long              timestamp             = data.getLong(KEY_TIMESTAMP);
            byte[]            contextBytes          = Base64.decodeOrThrow(data.getString(KEY_GROUP_CONTEXT_V2));

            SignalServiceProtos.GroupContextV2 groupContextV2;
            try {
                groupContextV2 = SignalServiceProtos.GroupContextV2.parseFrom(contextBytes);
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError(e);
            }

            return new PushGroupSilentUpdateSendJob(recipients, initialRecipientCount, timestamp, groupContextV2, parameters);
        }
    }
}