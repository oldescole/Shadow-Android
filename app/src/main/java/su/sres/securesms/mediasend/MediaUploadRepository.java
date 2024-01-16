package su.sres.securesms.mediasend;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import su.sres.securesms.attachments.Attachment;
import su.sres.securesms.attachments.AttachmentId;
import su.sres.securesms.database.AttachmentDatabase;
import su.sres.securesms.database.AttachmentDatabase.TransformProperties;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.core.util.logging.Log;
import su.sres.securesms.mms.GifSlide;
import su.sres.securesms.mms.ImageSlide;
import su.sres.securesms.mms.TextSlide;
import su.sres.securesms.mms.VideoSlide;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.sms.MessageSender;
import su.sres.securesms.sms.MessageSender.PreUploadResult;
import su.sres.securesms.util.MediaUtil;
import su.sres.core.util.concurrent.SignalExecutors;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Manages the proactive upload of media during the selection process. Upload/cancel operations
 * need to be serialized, because they're asynchronous operations that depend on ordered completion.
 *
 * For example, if we begin upload of a {@link Media) but then immediately cancel it (before it was
 * enqueued on the {@link JobManager}), we need to wait until we have the jobId to cancel. This
 * class manages everything by using a single thread executor.
 *
 * This also means that unlike most repositories, the class itself is stateful. Keep that in mind
 * when using it.
 */
class MediaUploadRepository {

    private static final String TAG = Log.tag(MediaUploadRepository.class);

    private final Context                               context;
    private final LinkedHashMap<Media, PreUploadResult> uploadResults;
    private final Executor                              executor;

    MediaUploadRepository(@NonNull Context context) {
        this.context       = context;
        this.uploadResults = new LinkedHashMap<>();
        this.executor      = SignalExecutors.newCachedSingleThreadExecutor("signal-MediaUpload");
    }

    void startUpload(@NonNull Media media, @Nullable Recipient recipient) {
        executor.execute(() -> uploadMediaInternal(media, recipient));
    }

    void startUpload(@NonNull Collection<Media> mediaItems, @Nullable Recipient recipient) {
        executor.execute(() -> {
            for (Media media : mediaItems) {
                cancelUploadInternal(media);
                uploadMediaInternal(media, recipient);
            }
        });
    }

    /**
     * Given a map of old->new, cancel medias that were changed and upload their replacements. Will
     * also upload any media in the map that wasn't yet uploaded.
     */
    void applyMediaUpdates(@NonNull Map<Media, Media> oldToNew, @Nullable Recipient recipient) {
        executor.execute(() -> {
            for (Map.Entry<Media, Media> entry : oldToNew.entrySet()) {
                Media oldMedia = entry.getKey();
                Media newMedia = entry.getValue();
                boolean same = oldMedia.equals(newMedia) && hasSameTransformProperties(oldMedia, newMedia);
                if (!same || !uploadResults.containsKey(newMedia)) {
                    cancelUploadInternal(oldMedia);
                    uploadMediaInternal(newMedia, recipient);
                }
            }
        });
    }

    private boolean hasSameTransformProperties(@NonNull Media oldMedia, @NonNull Media newMedia) {
        TransformProperties oldProperties = oldMedia.getTransformProperties().orNull();
        TransformProperties newProperties = newMedia.getTransformProperties().orNull();

        if (oldProperties == null || newProperties == null) {
            return oldProperties == newProperties;
        }

        return !newProperties.isVideoEdited() && oldProperties.getSentMediaQuality() == newProperties.getSentMediaQuality();
    }

    void cancelUpload(@NonNull Media media) {
        executor.execute(() -> cancelUploadInternal(media));
    }

    void cancelUpload(@NonNull Collection<Media> mediaItems) {
        executor.execute(() -> {
            for (Media media : mediaItems) {
                cancelUploadInternal(media);
            }
        });
    }

    void cancelAllUploads() {
        executor.execute(() -> {
            for (Media media : new HashSet<>(uploadResults.keySet())) {
                cancelUploadInternal(media);
            }
        });
    }

    void getPreUploadResults(@NonNull Callback<Collection<PreUploadResult>> callback) {
        executor.execute(() -> callback.onResult(uploadResults.values()));
    }

    void updateCaptions(@NonNull List<Media> updatedMedia) {
        executor.execute(() -> updateCaptionsInternal(updatedMedia));
    }

    void updateDisplayOrder(@NonNull List<Media> mediaInOrder) {
        executor.execute(() -> updateDisplayOrderInternal(mediaInOrder));
    }

    void deleteAbandonedAttachments() {
        executor.execute(() -> {
            int deleted = DatabaseFactory.getAttachmentDatabase(context).deleteAbandonedPreuploadedAttachments();
            Log.i(TAG, "Deleted " + deleted + " abandoned attachments.");
        });
    }

    @WorkerThread
    private void uploadMediaInternal(@NonNull Media media, @Nullable Recipient recipient) {
        Attachment      attachment = asAttachment(context, media);
        PreUploadResult result     = MessageSender.preUploadPushAttachment(context, attachment, recipient);

        if (result != null) {
            uploadResults.put(media, result);
        } else {
            Log.w(TAG, "Failed to upload media with URI: " + media.getUri());
        }
    }

    private void cancelUploadInternal(@NonNull Media media) {
        JobManager      jobManager = ApplicationDependencies.getJobManager();
        PreUploadResult result     = uploadResults.get(media);

        if (result != null) {
            Stream.of(result.getJobIds()).forEach(jobManager::cancel);
            uploadResults.remove(media);
        }
    }

    @WorkerThread
    private void updateCaptionsInternal(@NonNull List<Media> updatedMedia) {
        AttachmentDatabase db = DatabaseFactory.getAttachmentDatabase(context);

        for (Media updated : updatedMedia) {
            PreUploadResult result = uploadResults.get(updated);

            if (result != null) {
                db.updateAttachmentCaption(result.getAttachmentId(), updated.getCaption().orNull());
            } else {
                Log.w(TAG,"When updating captions, no pre-upload result could be found for media with URI: " + updated.getUri());
            }
        }
    }

    @WorkerThread
    private void updateDisplayOrderInternal(@NonNull List<Media> mediaInOrder) {
        Map<AttachmentId, Integer>  orderMap             = new HashMap<>();
        Map<Media, PreUploadResult> orderedUploadResults = new LinkedHashMap<>();

        for (int i = 0; i < mediaInOrder.size(); i++) {
            Media           media  = mediaInOrder.get(i);
            PreUploadResult result = uploadResults.get(media);

            if (result != null) {
                orderMap.put(result.getAttachmentId(), i);
                orderedUploadResults.put(media, result);
            } else {
                Log.w(TAG, "When updating display order, no pre-upload result could be found for media with URI: " + media.getUri());
            }
        }

        DatabaseFactory.getAttachmentDatabase(context).updateDisplayOrder(orderMap);

        if (orderedUploadResults.size() == uploadResults.size()) {
            uploadResults.clear();
            uploadResults.putAll(orderedUploadResults);
        }
    }

    public static @NonNull Attachment asAttachment(@NonNull Context context, @NonNull Media media) {
        if (MediaUtil.isVideoType(media.getMimeType())) {
            return new VideoSlide(context, media.getUri(), media.getSize(), media.isVideoGif(), media.getWidth(), media.getHeight(), media.getCaption().orNull(), media.getTransformProperties().orNull()).asAttachment();
        } else if (MediaUtil.isGif(media.getMimeType())) {
            return new GifSlide(context, media.getUri(), media.getSize(), media.getWidth(), media.getHeight(), media.isBorderless(), media.getCaption().orNull()).asAttachment();
        } else if (MediaUtil.isImageType(media.getMimeType())) {
            return new ImageSlide(context, media.getUri(), media.getMimeType(), media.getSize(), media.getWidth(), media.getHeight(), media.isBorderless(), media.getCaption().orNull(), null, media.getTransformProperties().orNull()).asAttachment();
        } else if (MediaUtil.isTextType(media.getMimeType())) {
            return new TextSlide(context, media.getUri(), null, media.getSize()).asAttachment();
        } else {
            throw new AssertionError("Unexpected mimeType: " + media.getMimeType());
        }
    }

    interface Callback<E> {
        void onResult(@NonNull E result);
    }
}