package su.sres.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.greenrobot.eventbus.EventBus;
import su.sres.securesms.R;
import su.sres.securesms.attachments.Attachment;
import su.sres.securesms.attachments.AttachmentId;
import su.sres.securesms.attachments.DatabaseAttachment;
import su.sres.securesms.attachments.PointerAttachment;
import su.sres.securesms.database.AttachmentDatabase;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.events.PartProgressEvent;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.logging.Log;
import su.sres.securesms.mms.PartAuthority;
import su.sres.securesms.service.GenericForegroundService;
import su.sres.securesms.service.NotificationController;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.SignalServiceMessageSender;
import su.sres.signalservice.api.messages.SignalServiceAttachment;
import su.sres.signalservice.api.messages.SignalServiceAttachmentPointer;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Uploads an attachment without alteration.
 * <p>
 * Queue {@link AttachmentCompressionJob} before to compress.
 */
public final class AttachmentUploadJob extends BaseJob {

    public static final String KEY = "AttachmentUploadJobV2";

    @SuppressWarnings("unused")
    private static final String TAG = Log.tag(AttachmentUploadJob.class);

    private static final String KEY_ROW_ID    = "row_id";
    private static final String KEY_UNIQUE_ID = "unique_id";

    /**
     * Foreground notification shows while uploading attachments above this.
     */
    private static final int FOREGROUND_LIMIT = 10 * 1024 * 1024;

    private final AttachmentId attachmentId;

    public AttachmentUploadJob(AttachmentId attachmentId) {
        this(new Job.Parameters.Builder()
                        .addConstraint(NetworkConstraint.KEY)
                        .setLifespan(TimeUnit.DAYS.toMillis(1))
                        .setMaxAttempts(Parameters.UNLIMITED)
                        .build(),
                attachmentId);
    }

    private AttachmentUploadJob(@NonNull Job.Parameters parameters, @NonNull AttachmentId attachmentId) {
        super(parameters);

        this.attachmentId = attachmentId;
    }

    @Override
    public @NonNull Data serialize() {
        return new Data.Builder().putLong(KEY_ROW_ID, attachmentId.getRowId())
                .putLong(KEY_UNIQUE_ID, attachmentId.getUniqueId())
                .build();
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    public void onRun() throws Exception {
        SignalServiceMessageSender messageSender      = ApplicationDependencies.getSignalServiceMessageSender();
        AttachmentDatabase         database           = DatabaseFactory.getAttachmentDatabase(context);
        DatabaseAttachment         databaseAttachment = database.getAttachment(attachmentId);

        if (databaseAttachment == null) {
            throw new InvalidAttachmentException("Cannot find the specified attachment.");
        }

        try (NotificationController notification = getNotificationForAttachment(databaseAttachment)) {
            SignalServiceAttachment        localAttachment  = getAttachmentFor(databaseAttachment, notification);
            SignalServiceAttachmentPointer remoteAttachment = messageSender.uploadAttachment(localAttachment.asStream(), databaseAttachment.isSticker());
            Attachment                     attachment       = PointerAttachment.forPointer(Optional.of(remoteAttachment), null, databaseAttachment.getFastPreflightId()).get();

            database.updateAttachmentAfterUpload(databaseAttachment.getAttachmentId(), attachment);
        }
    }

    private @Nullable NotificationController getNotificationForAttachment(@NonNull Attachment attachment) {
        if (attachment.getSize() >= FOREGROUND_LIMIT) {
            return GenericForegroundService.startForegroundTask(context, context.getString(R.string.AttachmentUploadJob_uploading_media));
        } else {
            return null;
        }
    }

    @Override
    public void onCanceled() { }

    @Override
    protected boolean onShouldRetry(@NonNull Exception exception) {
        return exception instanceof IOException;
    }

    private @NonNull SignalServiceAttachment getAttachmentFor(Attachment attachment, @Nullable NotificationController notification) throws InvalidAttachmentException {
        try {
            if (attachment.getDataUri() == null || attachment.getSize() == 0) throw new IOException("Assertion failed, outgoing attachment has no data!");
            InputStream is = PartAuthority.getAttachmentStream(context, attachment.getDataUri());
            return SignalServiceAttachment.newStreamBuilder()
                    .withStream(is)
                    .withContentType(attachment.getContentType())
                    .withLength(attachment.getSize())
                    .withFileName(attachment.getFileName())
                    .withVoiceNote(attachment.isVoiceNote())
                    .withWidth(attachment.getWidth())
                    .withHeight(attachment.getHeight())
                    .withCaption(attachment.getCaption())
                    .withListener((total, progress) -> {
                        EventBus.getDefault().postSticky(new PartProgressEvent(attachment, PartProgressEvent.Type.NETWORK, total, progress));
                        if (notification != null) {
                            notification.setProgress(total, progress);
                        }
                    })
                    .build();
        } catch (IOException ioe) {
            throw new InvalidAttachmentException(ioe);
        }
    }

    private class InvalidAttachmentException extends Exception {
        InvalidAttachmentException(String message) {
            super(message);
        }

        InvalidAttachmentException(Exception e) {
            super(e);
        }
    }

    public static final class Factory implements Job.Factory<AttachmentUploadJob> {
        @Override
        public @NonNull AttachmentUploadJob create(@NonNull Parameters parameters, @NonNull su.sres.securesms.jobmanager.Data data) {
            return new AttachmentUploadJob(parameters, new AttachmentId(data.getLong(KEY_ROW_ID), data.getLong(KEY_UNIQUE_ID)));
        }
    }
}