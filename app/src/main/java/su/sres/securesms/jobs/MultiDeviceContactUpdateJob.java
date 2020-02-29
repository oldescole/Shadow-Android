package su.sres.securesms.jobs;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import su.sres.securesms.ApplicationContext;
import su.sres.securesms.crypto.ProfileKeyUtil;
import su.sres.securesms.crypto.UnidentifiedAccessUtil;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.IdentityDatabase;
import su.sres.securesms.database.ThreadDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.recipients.RecipientUtil;
import su.sres.securesms.util.TextSecurePreferences;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.util.guava.Optional;

import su.sres.signalservice.api.SignalServiceMessageSender;
import su.sres.signalservice.api.crypto.UntrustedIdentityException;
import su.sres.signalservice.api.messages.SignalServiceAttachment;
import su.sres.signalservice.api.messages.SignalServiceAttachmentStream;
import su.sres.signalservice.api.messages.multidevice.ContactsMessage;
import su.sres.signalservice.api.messages.multidevice.DeviceContact;
import su.sres.signalservice.api.messages.multidevice.DeviceContactsOutputStream;
import su.sres.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import su.sres.signalservice.api.messages.multidevice.VerifiedMessage;
import su.sres.signalservice.api.push.SignalServiceAddress;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;
import su.sres.signalservice.api.util.InvalidNumberException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class MultiDeviceContactUpdateJob extends BaseJob {

    public static final String KEY = "MultiDeviceContactUpdateJob";

    private static final String TAG = MultiDeviceContactUpdateJob.class.getSimpleName();

    private static final long FULL_SYNC_TIME = TimeUnit.HOURS.toMillis(6);

    private static final String KEY_RECIPIENT = "recipient";
    private static final String KEY_FORCE_SYNC = "force_sync";

    private @Nullable
    RecipientId recipientId;

    private boolean forceSync;

    public MultiDeviceContactUpdateJob() {
        this(false);
    }

    public MultiDeviceContactUpdateJob(boolean forceSync) {
        this(null, forceSync);
    }

    public MultiDeviceContactUpdateJob(@Nullable RecipientId recipientId) {
        this(recipientId, true);
    }

    public MultiDeviceContactUpdateJob(@Nullable RecipientId recipientId, boolean forceSync) {
        this(new Job.Parameters.Builder()
                        .addConstraint(NetworkConstraint.KEY)
                        .setQueue("MultiDeviceContactUpdateJob")
                        .setLifespan(TimeUnit.DAYS.toMillis(1))
                        .setMaxAttempts(Parameters.UNLIMITED)
                        .build(),
                recipientId,
                forceSync);
    }

    private MultiDeviceContactUpdateJob(@NonNull Job.Parameters parameters, @Nullable RecipientId recipientId, boolean forceSync) {
        super(parameters);

        this.recipientId = recipientId;
        this.forceSync = forceSync;
    }

    @Override
    public @NonNull
    Data serialize() {
        return new Data.Builder().putString(KEY_RECIPIENT, recipientId != null ? recipientId.serialize() : null)
                .putBoolean(KEY_FORCE_SYNC, forceSync)
                .build();
    }

    @Override
    public @NonNull
    String getFactoryKey() {
        return KEY;
    }

    @Override
    public void onRun()
            throws IOException, UntrustedIdentityException, NetworkException {
        if (!TextSecurePreferences.isMultiDevice(context)) {
            Log.i(TAG, "Not multi device, aborting...");
            return;
        }

        if (recipientId == null) generateFullContactUpdate();
        else generateSingleContactUpdate(recipientId);
    }

    private void generateSingleContactUpdate(@NonNull RecipientId recipientId)
            throws IOException, UntrustedIdentityException, NetworkException {
        File contactDataFile = createTempFile("multidevice-contact-update");

        try {
            DeviceContactsOutputStream out = new DeviceContactsOutputStream(new FileOutputStream(contactDataFile));
            Recipient recipient = Recipient.resolved(recipientId);
            Optional<IdentityDatabase.IdentityRecord> identityRecord = DatabaseFactory.getIdentityDatabase(context).getIdentity(recipient.getId());
            Optional<VerifiedMessage> verifiedMessage = getVerifiedMessage(recipient, identityRecord);
            Map<RecipientId, Integer> inboxPositions = DatabaseFactory.getThreadDatabase(context).getInboxPositions();
            Set<RecipientId> archived = DatabaseFactory.getThreadDatabase(context).getArchivedRecipients();

            out.write(new DeviceContact(RecipientUtil.toSignalServiceAddress(context, recipient),
                    Optional.of(recipient.getDisplayName(context)),
                    getSystemAvatar(recipient.getContactUri()),
                    Optional.fromNullable(recipient.getColor().serialize()),
                    verifiedMessage,
                    Optional.fromNullable(recipient.getProfileKey()),
                    recipient.isBlocked(),
                    recipient.getExpireMessages() > 0 ? Optional.of(recipient.getExpireMessages())
                            : Optional.absent(),
                    Optional.fromNullable(inboxPositions.get(recipientId)),
                    archived.contains(recipientId)));

            out.close();
            sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), contactDataFile, false);

        } catch (InvalidNumberException e) {
            Log.w(TAG, e);
        } finally {
            if (contactDataFile != null) contactDataFile.delete();
        }
    }

    private void generateFullContactUpdate()
            throws IOException, UntrustedIdentityException, NetworkException {
        boolean isAppVisible = ApplicationContext.getInstance(context).isAppVisible();
        long timeSinceLastSync = System.currentTimeMillis() - TextSecurePreferences.getLastFullContactSyncTime(context);

        Log.d(TAG, "Requesting a full contact sync. forced = " + forceSync + ", appVisible = " + isAppVisible + ", timeSinceLastSync = " + timeSinceLastSync + " ms");

        if (!forceSync && !isAppVisible && timeSinceLastSync < FULL_SYNC_TIME) {
            Log.i(TAG, "App is backgrounded and the last contact sync was too soon (" + timeSinceLastSync + " ms ago). Marking that we need a sync. Skipping multi-device contact update...");
            TextSecurePreferences.setNeedsFullContactSync(context, true);
            return;
        }

        TextSecurePreferences.setLastFullContactSyncTime(context, System.currentTimeMillis());
        TextSecurePreferences.setNeedsFullContactSync(context, false);

        File contactDataFile = createTempFile("multidevice-contact-update");

        try {
            DeviceContactsOutputStream out = new DeviceContactsOutputStream(new FileOutputStream(contactDataFile));
            List<Recipient> recipients = DatabaseFactory.getRecipientDatabase(context).getRecipientsForMultiDeviceSync();
            Map<RecipientId, Integer> inboxPositions = DatabaseFactory.getThreadDatabase(context).getInboxPositions();
            Set<RecipientId> archived = DatabaseFactory.getThreadDatabase(context).getArchivedRecipients();

            for (Recipient recipient : recipients) {
                Optional<IdentityDatabase.IdentityRecord> identity = DatabaseFactory.getIdentityDatabase(context).getIdentity(recipient.getId());
                Optional<VerifiedMessage> verified = getVerifiedMessage(recipient, identity);
                Optional<String> name = Optional.fromNullable(recipient.getName(context));
                Optional<String> color = Optional.of(recipient.getColor().serialize());
                Optional<byte[]> profileKey = Optional.fromNullable(recipient.getProfileKey());
                boolean blocked = recipient.isBlocked();
                Optional<Integer> expireTimer = recipient.getExpireMessages() > 0 ? Optional.of(recipient.getExpireMessages()) : Optional.absent();
                Optional<Integer> inboxPosition = Optional.fromNullable(inboxPositions.get(recipient.getId()));

                out.write(new DeviceContact(RecipientUtil.toSignalServiceAddress(context, recipient),
                        name,
                        getSystemAvatar(recipient.getContactUri()),
                        color,
                        verified,
                        profileKey,
                        blocked,
                        expireTimer,
                        inboxPosition,
                        archived.contains(recipient.getId())));
            }

            if (ProfileKeyUtil.hasProfileKey(context)) {
                Recipient self = Recipient.self();
                out.write(new DeviceContact(RecipientUtil.toSignalServiceAddress(context, self),
                        Optional.absent(),
                        Optional.absent(),
                        Optional.of(self.getColor().serialize()),
                        Optional.absent(),
                        Optional.of(ProfileKeyUtil.getProfileKey(context)),
                        false,
                        self.getExpireMessages() > 0 ? Optional.of(self.getExpireMessages()) : Optional.absent(),
                        Optional.fromNullable(inboxPositions.get(self.getId())),
                        archived.contains(self.getId())));
            }

            out.close();
            sendUpdate(ApplicationDependencies.getSignalServiceMessageSender(), contactDataFile, true);
        } catch (InvalidNumberException e) {
            Log.w(TAG, e);
        } finally {
            if (contactDataFile != null) contactDataFile.delete();
        }
    }

    @Override
    public boolean onShouldRetry(@NonNull Exception exception) {
        if (exception instanceof PushNetworkException) return true;
        return false;
    }


    @Override
    public void onCanceled() {

    }

    private void sendUpdate(SignalServiceMessageSender messageSender, File contactsFile, boolean complete)
            throws IOException, UntrustedIdentityException, NetworkException {
        if (contactsFile.length() > 0) {
            FileInputStream contactsFileStream = new FileInputStream(contactsFile);
            SignalServiceAttachmentStream attachmentStream = SignalServiceAttachment.newStreamBuilder()
                    .withStream(contactsFileStream)
                    .withContentType("application/octet-stream")
                    .withLength(contactsFile.length())
                    .build();

            try {
                messageSender.sendMessage(SignalServiceSyncMessage.forContacts(new ContactsMessage(attachmentStream, complete)),
                        UnidentifiedAccessUtil.getAccessForSync(context));
            } catch (IOException ioe) {
                throw new NetworkException(ioe);
            }
        }
    }

    private Optional<SignalServiceAttachmentStream> getSystemAvatar(@Nullable Uri uri) {
        if (uri == null) {
            return Optional.absent();
        }

        Uri displayPhotoUri = Uri.withAppendedPath(uri, ContactsContract.Contacts.Photo.DISPLAY_PHOTO);

        try {
            AssetFileDescriptor fd = context.getContentResolver().openAssetFileDescriptor(displayPhotoUri, "r");

            if (fd == null) {
                return Optional.absent();
            }

            return Optional.of(SignalServiceAttachment.newStreamBuilder()
                    .withStream(fd.createInputStream())
                    .withContentType("image/*")
                    .withLength(fd.getLength())
                    .build());
        } catch (IOException e) {
            Log.i(TAG, "Could not find avatar for URI: " + displayPhotoUri);
        }

        Uri photoUri = Uri.withAppendedPath(uri, ContactsContract.Contacts.Photo.CONTENT_DIRECTORY);

        if (photoUri == null) {
            return Optional.absent();
        }

        Cursor cursor = context.getContentResolver().query(photoUri,
                new String[]{
                        ContactsContract.CommonDataKinds.Photo.PHOTO,
                        ContactsContract.CommonDataKinds.Phone.MIMETYPE
                }, null, null, null);

        try {
            if (cursor != null && cursor.moveToNext()) {
                byte[] data = cursor.getBlob(0);

                if (data != null) {
                    return Optional.of(SignalServiceAttachment.newStreamBuilder()
                            .withStream(new ByteArrayInputStream(data))
                            .withContentType("image/*")
                            .withLength(data.length)
                            .build());
                }
            }

            return Optional.absent();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private Optional<VerifiedMessage> getVerifiedMessage(Recipient recipient, Optional<IdentityDatabase.IdentityRecord> identity) throws InvalidNumberException {
        if (!identity.isPresent()) return Optional.absent();

        SignalServiceAddress destination = RecipientUtil.toSignalServiceAddress(context, recipient);
        IdentityKey identityKey = identity.get().getIdentityKey();

        VerifiedMessage.VerifiedState state;

        switch (identity.get().getVerifiedStatus()) {
            case VERIFIED:
                state = VerifiedMessage.VerifiedState.VERIFIED;
                break;
            case UNVERIFIED:
                state = VerifiedMessage.VerifiedState.UNVERIFIED;
                break;
            case DEFAULT:
                state = VerifiedMessage.VerifiedState.DEFAULT;
                break;
            default:
                throw new AssertionError("Unknown state: " + identity.get().getVerifiedStatus());
        }

        return Optional.of(new VerifiedMessage(destination, identityKey, state, System.currentTimeMillis()));
    }

    private File createTempFile(String prefix) throws IOException {
        File file = File.createTempFile(prefix, "tmp", context.getCacheDir());
        file.deleteOnExit();

        return file;
    }

    private static class NetworkException extends Exception {

        public NetworkException(Exception ioe) {
            super(ioe);
        }
    }

    public static final class Factory implements Job.Factory<MultiDeviceContactUpdateJob> {
        @Override
        public @NonNull
        MultiDeviceContactUpdateJob create(@NonNull Parameters parameters, @NonNull Data data) {
            String serialized = data.getString(KEY_RECIPIENT);
            RecipientId address = serialized != null ? RecipientId.from(serialized) : null;

            return new MultiDeviceContactUpdateJob(parameters, address, data.getBoolean(KEY_FORCE_SYNC));
        }
    }
}