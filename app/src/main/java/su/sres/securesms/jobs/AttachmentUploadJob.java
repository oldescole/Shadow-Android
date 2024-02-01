package su.sres.securesms.jobs;

import android.graphics.Bitmap;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.greenrobot.eventbus.EventBus;

import su.sres.securesms.R;
import su.sres.securesms.attachments.Attachment;
import su.sres.securesms.attachments.AttachmentId;
import su.sres.securesms.attachments.DatabaseAttachment;
import su.sres.securesms.attachments.PointerAttachment;
import su.sres.securesms.blurhash.BlurHashEncoder;
import su.sres.securesms.database.AttachmentDatabase;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.events.PartProgressEvent;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.core.util.logging.Log;
import su.sres.securesms.mms.PartAuthority;
import su.sres.securesms.net.NotPushRegisteredException;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.service.GenericForegroundService;
import su.sres.securesms.service.NotificationController;
import su.sres.securesms.util.MediaUtil;

import org.whispersystems.libsignal.util.guava.Optional;

import su.sres.signalservice.api.SignalServiceMessageSender;
import su.sres.signalservice.api.messages.SignalServiceAttachment;
import su.sres.signalservice.api.messages.SignalServiceAttachmentPointer;
import su.sres.signalservice.api.push.exceptions.ResumeLocationInvalidException;
import su.sres.signalservice.internal.push.http.ResumableUploadSpec;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
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

    private static final long UPLOAD_REUSE_THRESHOLD = TimeUnit.DAYS.toMillis(3);

    private static final String KEY_ROW_ID = "row_id";
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
    public @NonNull
    Data serialize() {
        return new Data.Builder().putLong(KEY_ROW_ID, attachmentId.getRowId())
                .putLong(KEY_UNIQUE_ID, attachmentId.getUniqueId())
                .build();
    }

    @Override
    public @NonNull
    String getFactoryKey() {
        return KEY;
    }

    @Override
    protected boolean shouldTrace() {
        return true;
    }

    @Override
    public void onRun() throws Exception {
        if (!Recipient.self().isRegistered()) {
            throw new NotPushRegisteredException();
        }

        Data inputData = getInputData();

        ResumableUploadSpec resumableUploadSpec;

        if (inputData != null && inputData.hasString(ResumableUploadSpecJob.KEY_RESUME_SPEC)) {
            Log.d(TAG, "Using attachments V3");

            resumableUploadSpec = ResumableUploadSpec.deserialize(inputData.getString(ResumableUploadSpecJob.KEY_RESUME_SPEC));
        } else {
            Log.d(TAG, "Using attachments V2");
            resumableUploadSpec = null;
        }

        SignalServiceMessageSender messageSender = ApplicationDependencies.getSignalServiceMessageSender();
        AttachmentDatabase database = DatabaseFactory.getAttachmentDatabase(context);
        DatabaseAttachment databaseAttachment = database.getAttachment(attachmentId);

        if (databaseAttachment == null) {
            throw new InvalidAttachmentException("Cannot find the specified attachment.");
        }

        long timeSinceUpload = System.currentTimeMillis() - databaseAttachment.getUploadTimestamp();
        if (timeSinceUpload < UPLOAD_REUSE_THRESHOLD && !TextUtils.isEmpty(databaseAttachment.getLocation())) {
            Log.i(TAG, "We can re-use an already-uploaded file. It was uploaded " + timeSinceUpload + " ms ago. Skipping.");
            return;
        } else if (databaseAttachment.getUploadTimestamp() > 0) {
            Log.i(TAG, "This file was previously-uploaded, but too long ago to be re-used. Age: " + timeSinceUpload + " ms");
        }

        Log.i(TAG, "Uploading attachment for message " + databaseAttachment.getMmsId() + " with ID " + databaseAttachment.getAttachmentId());

        try (NotificationController notification = getNotificationForAttachment(databaseAttachment)) {
            SignalServiceAttachment localAttachment = getAttachmentFor(databaseAttachment, notification, resumableUploadSpec);
            SignalServiceAttachmentPointer remoteAttachment = messageSender.uploadAttachment(localAttachment.asStream());
            Attachment attachment = PointerAttachment.forPointer(Optional.of(remoteAttachment), null, databaseAttachment.getFastPreflightId()).get();

            database.updateAttachmentAfterUpload(databaseAttachment.getAttachmentId(), attachment, remoteAttachment.getUploadTimestamp());
        }
    }

    private @Nullable
    NotificationController getNotificationForAttachment(@NonNull Attachment attachment) {
        if (attachment.getSize() >= FOREGROUND_LIMIT) {
            return GenericForegroundService.startForegroundTask(context, context.getString(R.string.AttachmentUploadJob_uploading_media));
        } else {
            return null;
        }
    }

    @Override
    public void onFailure() {
        if (isCanceled()) {
            DatabaseFactory.getAttachmentDatabase(context).deleteAttachment(attachmentId);
        }
    }

    @Override
    protected boolean onShouldRetry(@NonNull Exception exception) {
        if (exception instanceof ResumeLocationInvalidException) return false;

        return exception instanceof IOException && !(exception instanceof NotPushRegisteredException);
    }

    private @NonNull
    SignalServiceAttachment getAttachmentFor(Attachment attachment, @Nullable NotificationController notification, @Nullable ResumableUploadSpec resumableUploadSpec) throws InvalidAttachmentException {
        try {
            if (attachment.getUri() == null || attachment.getSize() == 0)
                throw new IOException("Assertion failed, outgoing attachment has no data!");
            InputStream is = PartAuthority.getAttachmentStream(context, attachment.getUri());
            SignalServiceAttachment.Builder builder = SignalServiceAttachment.newStreamBuilder()
                    .withStream(is)
                    .withContentType(attachment.getContentType())
                    .withLength(attachment.getSize())
                    .withFileName(attachment.getFileName())
                    .withVoiceNote(attachment.isVoiceNote())
                    .withBorderless(attachment.isBorderless())
                    .withGif(attachment.isVideoGif())
                    .withWidth(attachment.getWidth())
                    .withHeight(attachment.getHeight())
                    .withUploadTimestamp(System.currentTimeMillis())
                    .withCaption(attachment.getCaption())
                    .withCancelationSignal(this::isCanceled)
                    .withResumableUploadSpec(resumableUploadSpec)
                    .withListener((total, progress) -> {
                        EventBus.getDefault().postSticky(new PartProgressEvent(attachment, PartProgressEvent.Type.NETWORK, total, progress));
                        if (notification != null) {
                            notification.setProgress(total, progress);
                        }
                    });
            if (MediaUtil.isImageType(attachment.getContentType())) {
                return builder.withBlurHash(getImageBlurHash(attachment)).build();
            } else if (MediaUtil.isVideoType(attachment.getContentType())) {
                return builder.withBlurHash(getVideoBlurHash(attachment)).build();
            } else {
                return builder.build();
            }

        } catch (IOException ioe) {
            throw new InvalidAttachmentException(ioe);
        }
    }

    private @Nullable
    String getImageBlurHash(@NonNull Attachment attachment) throws IOException {
        if (attachment.getBlurHash() != null) return attachment.getBlurHash().getHash();
        if (attachment.getUri() == null) return null;

        return BlurHashEncoder.encode(PartAuthority.getAttachmentStream(context, attachment.getUri()));
    }

    private @Nullable
    String getVideoBlurHash(@NonNull Attachment attachment) throws IOException {
        if (attachment.getBlurHash() != null) {
            return attachment.getBlurHash().getHash();
        }

        if (Build.VERSION.SDK_INT < 23) {
            Log.w(TAG, "Video thumbnails not supported...");
            return null;
        }

        Bitmap bitmap = MediaUtil.getVideoThumbnail(context, Objects.requireNonNull(attachment.getUri()), 1000);

        if (bitmap != null) {
            Bitmap thumb = Bitmap.createScaledBitmap(bitmap, 100, 100, false);
            bitmap.recycle();

            Log.i(TAG, "Generated video thumbnail...");
            String hash = BlurHashEncoder.encode(thumb);
            thumb.recycle();

            return hash;
        } else {
            return null;
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
        public @NonNull
        AttachmentUploadJob create(@NonNull Job.Parameters parameters, @NonNull su.sres.securesms.jobmanager.Data data) {
            return new AttachmentUploadJob(parameters, new AttachmentId(data.getLong(KEY_ROW_ID), data.getLong(KEY_UNIQUE_ID)));
        }
    }
}