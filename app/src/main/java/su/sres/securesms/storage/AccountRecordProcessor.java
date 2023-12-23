package su.sres.securesms.storage;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import su.sres.core.util.logging.Log;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.recipients.Recipient;

import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.storage.SignalAccountRecord;
import su.sres.signalservice.api.storage.SignalAccountRecord.PinnedConversation;
import su.sres.signalservice.internal.storage.protos.AccountRecord;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Processes {@link SignalAccountRecord}s. Unlike some other {@link StorageRecordProcessor}s, this
 * one has some statefulness in order to reject all but one account record (since we should have
 * exactly one account record).
 */
public class AccountRecordProcessor extends DefaultStorageRecordProcessor<SignalAccountRecord> {

    private static final String TAG = Log.tag(AccountRecordProcessor.class);

    private final Context             context;
    private final RecipientDatabase   recipientDatabase;
    private final SignalAccountRecord localAccountRecord;
    private final Recipient           self;

    private boolean foundAccountRecord = false;

    public AccountRecordProcessor(@NonNull Context context, @NonNull Recipient self) {
        this(context, self, StorageSyncHelper.buildAccountRecord(context, self).getAccount().get(), DatabaseFactory.getRecipientDatabase(context));
    }

    AccountRecordProcessor(@NonNull Context context, @NonNull Recipient self, @NonNull SignalAccountRecord localAccountRecord, @NonNull RecipientDatabase recipientDatabase) {
        this.context            = context;
        this.self               = self;
        this.recipientDatabase  = recipientDatabase;
        this.localAccountRecord = localAccountRecord;
    }

    /**
     * We want to catch:
     * - Multiple account records
     */
    @Override
    boolean isInvalid(@NonNull SignalAccountRecord remote) {
        if (foundAccountRecord) {
            Log.w(TAG, "Found an additional account record! Considering it invalid.");
            return true;
        }

        foundAccountRecord = true;
        return false;
    }

    @Override
    public @NonNull Optional<SignalAccountRecord> getMatching(@NonNull SignalAccountRecord record, @NonNull StorageKeyGenerator keyGenerator) {
        return Optional.of(localAccountRecord);
    }

    @Override
    public @NonNull SignalAccountRecord merge(@NonNull SignalAccountRecord remote, @NonNull SignalAccountRecord local, @NonNull StorageKeyGenerator keyGenerator) {
        String givenName;
        String familyName;

        if (remote.getGivenName().isPresent() || remote.getFamilyName().isPresent()) {
            givenName  = remote.getGivenName().or("");
            familyName = remote.getFamilyName().or("");
        } else {
            givenName  = local.getGivenName().or("");
            familyName = local.getFamilyName().or("");
        }

        byte[]                               unknownFields          = remote.serializeUnknownFields();
        String                               avatarUrlPath          = remote.getAvatarUrlPath().or(local.getAvatarUrlPath()).or("");
        byte[]                               profileKey             = remote.getProfileKey().or(local.getProfileKey()).orNull();
        boolean                              noteToSelfArchived     = remote.isNoteToSelfArchived();
        boolean                              noteToSelfForcedUnread = remote.isNoteToSelfForcedUnread();
        boolean                              readReceipts           = remote.isReadReceiptsEnabled();
        boolean                              typingIndicators       = remote.isTypingIndicatorsEnabled();
        boolean                              sealedSenderIndicators = remote.isSealedSenderIndicatorsEnabled();
        boolean                              linkPreviews           = remote.isLinkPreviewsEnabled();
        boolean                              unlisted               = remote.isUserLoginUnlisted();
        List<PinnedConversation>             pinnedConversations    = remote.getPinnedConversations();
        AccountRecord.UserLoginSharingMode userLoginSharingMode = remote.getUserLoginSharingMode();
        boolean                              matchesRemote          = doParamsMatch(remote, unknownFields, givenName, familyName, avatarUrlPath, profileKey, noteToSelfArchived, noteToSelfForcedUnread, readReceipts, typingIndicators, sealedSenderIndicators, linkPreviews, userLoginSharingMode, unlisted, pinnedConversations);
        boolean                              matchesLocal           = doParamsMatch(local, unknownFields, givenName, familyName, avatarUrlPath, profileKey, noteToSelfArchived, noteToSelfForcedUnread, readReceipts, typingIndicators, sealedSenderIndicators, linkPreviews, userLoginSharingMode, unlisted, pinnedConversations);

        if (matchesRemote) {
            return remote;
        } else if (matchesLocal) {
            return local;
        } else {
            return new SignalAccountRecord.Builder(keyGenerator.generate())
                    .setUnknownFields(unknownFields)
                    .setGivenName(givenName)
                    .setFamilyName(familyName)
                    .setAvatarUrlPath(avatarUrlPath)
                    .setProfileKey(profileKey)
                    .setNoteToSelfArchived(noteToSelfArchived)
                    .setNoteToSelfForcedUnread(noteToSelfForcedUnread)
                    .setReadReceiptsEnabled(readReceipts)
                    .setTypingIndicatorsEnabled(typingIndicators)
                    .setSealedSenderIndicatorsEnabled(sealedSenderIndicators)
                    .setLinkPreviewsEnabled(linkPreviews)
                    .setUnlistedUserLogin(unlisted)
                    .setUserLoginSharingMode(userLoginSharingMode)
                    .setUnlistedUserLogin(unlisted)
                    .setPinnedConversations(pinnedConversations)

                    .build();
        }
    }

    @Override
    void insertLocal(@NonNull SignalAccountRecord record) {
        throw new UnsupportedOperationException("We should always have a local AccountRecord, so we should never been inserting a new one.");
    }

    @Override
    void updateLocal(@NonNull StorageRecordUpdate<SignalAccountRecord> update) {
        StorageSyncHelper.applyAccountStorageSyncUpdates(context, self, update.getNew(), true);
    }

    @Override
    public int compare(@NonNull SignalAccountRecord lhs, @NonNull SignalAccountRecord rhs) {
        return 0;
    }

    private static boolean doParamsMatch(@NonNull SignalAccountRecord contact,
                                         @Nullable byte[] unknownFields,
                                         @NonNull String givenName,
                                         @NonNull String familyName,
                                         @NonNull String avatarUrlPath,
                                         @Nullable byte[] profileKey,
                                         boolean noteToSelfArchived,
                                         boolean noteToSelfForcedUnread,
                                         boolean readReceipts,
                                         boolean typingIndicators,
                                         boolean sealedSenderIndicators,
                                         boolean linkPreviewsEnabled,
                                         AccountRecord.UserLoginSharingMode userLoginSharingMode,
                                         boolean unlistedPhoneNumber,
                                         @NonNull List<PinnedConversation> pinnedConversations)
    {
        return Arrays.equals(contact.serializeUnknownFields(), unknownFields)      &&
                Objects.equals(contact.getGivenName().or(""), givenName)            &&
                Objects.equals(contact.getFamilyName().or(""), familyName)          &&
                Objects.equals(contact.getAvatarUrlPath().or(""), avatarUrlPath)    &&
                Arrays.equals(contact.getProfileKey().orNull(), profileKey)         &&
                contact.isNoteToSelfArchived() == noteToSelfArchived                &&
                contact.isNoteToSelfForcedUnread() == noteToSelfForcedUnread        &&
                contact.isReadReceiptsEnabled() == readReceipts                     &&
                contact.isTypingIndicatorsEnabled() == typingIndicators             &&
                contact.isSealedSenderIndicatorsEnabled() == sealedSenderIndicators &&
                contact.isLinkPreviewsEnabled() == linkPreviewsEnabled              &&
                contact.getUserLoginSharingMode() == userLoginSharingMode       &&
                contact.isUserLoginUnlisted() == unlistedPhoneNumber              &&
                Objects.equals(contact.getPinnedConversations(), pinnedConversations);
    }
}
