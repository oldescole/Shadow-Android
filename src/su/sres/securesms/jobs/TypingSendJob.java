package su.sres.securesms.jobs;

import androidx.annotation.NonNull;

import com.annimon.stream.Stream;

import su.sres.securesms.crypto.UnidentifiedAccessUtil;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.dependencies.InjectableType;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.GroupUtil;
import su.sres.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.SignalServiceMessageSender;
import su.sres.signalservice.api.crypto.UnidentifiedAccessPair;
import su.sres.signalservice.api.messages.SignalServiceTypingMessage;
import su.sres.signalservice.api.messages.SignalServiceTypingMessage.Action;
import su.sres.signalservice.api.push.SignalServiceAddress;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

public class TypingSendJob extends BaseJob implements InjectableType {

    public static final String KEY = "TypingSendJob";

    private static final String TAG = TypingSendJob.class.getSimpleName();

    private static final String KEY_THREAD_ID = "thread_id";
    private static final String KEY_TYPING    = "typing";

    private long    threadId;
    private boolean typing;

    @Inject SignalServiceMessageSender messageSender;

    public TypingSendJob(long threadId, boolean typing) {
        this(new Job.Parameters.Builder()
                        .setQueue("TYPING_" + threadId)
                        .setMaxAttempts(1)
                        .setLifespan(TimeUnit.SECONDS.toMillis(5))
                        .build(),
                threadId,
                typing);
    }

    private TypingSendJob(@NonNull Job.Parameters parameters, long threadId, boolean typing) {
        super(parameters);

        this.threadId = threadId;
        this.typing   = typing;
    }

    @Override
    public @NonNull Data serialize() {
        return new Data.Builder().putLong(KEY_THREAD_ID, threadId)
                .putBoolean(KEY_TYPING, typing)
                .build();
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    public void onRun() throws Exception {
        if (!TextSecurePreferences.isTypingIndicatorsEnabled(context)) {
            return;
        }

        Log.d(TAG, "Sending typing " + (typing ? "started" : "stopped") + " for thread " + threadId);

        Recipient recipient = DatabaseFactory.getThreadDatabase(context).getRecipientForThreadId(threadId);

        if (recipient == null) {
            throw new IllegalStateException("Tried to send a typing indicator to a non-existent thread.");
        }

        List<Recipient>  recipients = Collections.singletonList(recipient);
        Optional<byte[]> groupId    = Optional.absent();

        if (recipient.isGroupRecipient()) {
            recipients = DatabaseFactory.getGroupDatabase(context).getGroupMembers(recipient.getAddress().toGroupString(), false);
            groupId    = Optional.of(GroupUtil.getDecodedId(recipient.getAddress().toGroupString()));
        }

        List<SignalServiceAddress>             addresses          = Stream.of(recipients).map(r -> new SignalServiceAddress(r.getAddress().serialize())).toList();
        List<Optional<UnidentifiedAccessPair>> unidentifiedAccess = Stream.of(recipients).map(r -> UnidentifiedAccessUtil.getAccessFor(context, r)).toList();
        SignalServiceTypingMessage             typingMessage      = new SignalServiceTypingMessage(typing ? Action.STARTED : Action.STOPPED, System.currentTimeMillis(), groupId);

        messageSender.sendTyping(addresses, unidentifiedAccess, typingMessage);
    }

    @Override
    public void onCanceled() {
    }

    @Override
    protected boolean onShouldRetry(@NonNull Exception exception) {
        return false;
    }

    public static final class Factory implements Job.Factory<TypingSendJob> {
        @Override
        public @NonNull TypingSendJob create(@NonNull Parameters parameters, @NonNull Data data) {
            return new TypingSendJob(parameters, data.getLong(KEY_THREAD_ID), data.getBoolean(KEY_TYPING));
        }
    }
}