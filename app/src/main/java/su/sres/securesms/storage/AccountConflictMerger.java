package su.sres.securesms.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;


import su.sres.core.util.logging.Log;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.storage.SignalAccountRecord;
import su.sres.signalservice.api.storage.SignalAccountRecord.PinnedConversation;
import su.sres.signalservice.internal.storage.protos.AccountRecord;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

class AccountConflictMerger implements StorageSyncHelper.ConflictMerger<SignalAccountRecord> {

    private static final String TAG = Log.tag(AccountConflictMerger.class);

    private final Optional<SignalAccountRecord> local;

    AccountConflictMerger(Optional<SignalAccountRecord> local) {
        this.local = local;
    }

    @Override
    public @NonNull Optional<SignalAccountRecord> getMatching(@NonNull SignalAccountRecord record) {
        return local;
    }

    @Override
    public @NonNull Collection<SignalAccountRecord> getInvalidEntries(@NonNull Collection<SignalAccountRecord> remoteRecords) {
        Set<SignalAccountRecord> invalid = new HashSet<>(remoteRecords);
        if (remoteRecords.size() > 0) {
            invalid.remove(remoteRecords.iterator().next());
        }

        if (invalid.size() > 0) {
            Log.w(TAG, "Found invalid account entries! Count: " + invalid.size());
        }

        return invalid;
    }

    @Override
    public @NonNull SignalAccountRecord merge(@NonNull SignalAccountRecord remote, @NonNull SignalAccountRecord local, @NonNull StorageSyncHelper.KeyGenerator keyGenerator) {
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
        List<PinnedConversation> pinnedConversations    = remote.getPinnedConversations();
        AccountRecord.UserLoginSharingMode   userLoginSharingMode = remote.getUserLoginSharingMode();
        boolean                              paymentsEnabled        = remote.getPayments().isEnabled();
        byte[]                               paymentsEntropy        = remote.getPayments().getEntropy().or(local.getPayments().getEntropy()).orNull();
        boolean                              matchesRemote          = doParamsMatch(remote, unknownFields, givenName, familyName, avatarUrlPath, profileKey, noteToSelfArchived, noteToSelfForcedUnread, readReceipts, typingIndicators, sealedSenderIndicators, linkPreviews, userLoginSharingMode, unlisted, pinnedConversations, paymentsEnabled, paymentsEntropy);
        boolean                              matchesLocal           = doParamsMatch(local, unknownFields, givenName, familyName, avatarUrlPath, profileKey, noteToSelfArchived, noteToSelfForcedUnread, readReceipts, typingIndicators, sealedSenderIndicators, linkPreviews, userLoginSharingMode, unlisted, pinnedConversations, paymentsEnabled, paymentsEntropy);

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
                    .setPayments(paymentsEnabled, paymentsEntropy)
                    .build();
        }
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
                                         boolean unlistedUserLogin,
                                         @NonNull List<PinnedConversation> pinnedConversations,
                                         boolean paymentsEnabled,
                                         @Nullable byte[] paymentsEntropy)
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
                contact.isUserLoginUnlisted() == unlistedUserLogin              &&
                Objects.equals(contact.getPinnedConversations(), pinnedConversations) &&
                contact.getPayments().isEnabled() == paymentsEnabled                  &&
                Arrays.equals(contact.getPayments().getEntropy().orNull(), paymentsEntropy);
    }
}