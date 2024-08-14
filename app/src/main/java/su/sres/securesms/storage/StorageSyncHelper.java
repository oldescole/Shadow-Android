package su.sres.securesms.storage;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.database.RecipientDatabase.RecipientSettings;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobs.RetrieveProfileAvatarJob;
import su.sres.securesms.jobs.StorageSyncJob;
import su.sres.securesms.keyvalue.UserLoginPrivacyValues;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.core.util.logging.Log;
import su.sres.securesms.payments.Entropy;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.Base64;
import su.sres.securesms.util.SetUtil;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.storage.SignalAccountRecord;
import su.sres.signalservice.api.storage.SignalContactRecord;
import su.sres.signalservice.api.storage.SignalStorageManifest;
import su.sres.signalservice.api.storage.SignalStorageRecord;
import su.sres.signalservice.api.storage.StorageId;
import su.sres.signalservice.api.util.OptionalUtil;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class StorageSyncHelper {

    private static final String TAG = Log.tag(StorageSyncHelper.class);

    public static final StorageKeyGenerator KEY_GENERATOR = () -> Util.getSecretBytes(16);

    private static StorageKeyGenerator keyGenerator = KEY_GENERATOR;

    private static final long REFRESH_INTERVAL = TimeUnit.HOURS.toMillis(2);

    /**
     * Given a list of all the local and remote keys you know about, this will return a result telling
     * you which keys are exclusively remote and which are exclusively local.
     *
     * @param remoteIds All remote keys available.
     * @param localIds All local keys available.
     *
     * @return An object describing which keys are exclusive to the remote data set and which keys are
     *         exclusive to the local data set.
     */
    public static @NonNull IdDifferenceResult findIdDifference(@NonNull Collection<StorageId> remoteIds,
                                                               @NonNull Collection<StorageId> localIds)
    {
        Map<String, StorageId> remoteByRawId = Stream.of(remoteIds).collect(Collectors.toMap(id -> Base64.encodeBytes(id.getRaw()), id -> id));
        Map<String, StorageId> localByRawId  = Stream.of(localIds).collect(Collectors.toMap(id -> Base64.encodeBytes(id.getRaw()), id -> id));

        boolean hasTypeMismatch = remoteByRawId.size() != remoteIds.size() || localByRawId.size() != localIds.size();

        Set<String> remoteOnlyRawIds = SetUtil.difference(remoteByRawId.keySet(), localByRawId.keySet());
        Set<String> localOnlyRawIds  = SetUtil.difference(localByRawId.keySet(), remoteByRawId.keySet());
        Set<String> sharedRawIds     = SetUtil.intersection(localByRawId.keySet(), remoteByRawId.keySet());

        for (String rawId : sharedRawIds) {
            StorageId remote = Objects.requireNonNull(remoteByRawId.get(rawId));
            StorageId local  = Objects.requireNonNull(localByRawId.get(rawId));

            if (remote.getType() != local.getType()) {
                remoteOnlyRawIds.remove(rawId);
                localOnlyRawIds.remove(rawId);
                hasTypeMismatch = true;
                Log.w(TAG, "Remote type " + remote.getType() + " did not match local type " + local.getType() + "!");
            }
        }

        List<StorageId> remoteOnlyKeys = Stream.of(remoteOnlyRawIds).map(remoteByRawId::get).toList();
        List<StorageId> localOnlyKeys  = Stream.of(localOnlyRawIds).map(localByRawId::get).toList();

        return new IdDifferenceResult(remoteOnlyKeys, localOnlyKeys, hasTypeMismatch);
    }

    public static @NonNull byte[] generateKey() {
        return keyGenerator.generate();
    }

    @VisibleForTesting
    static void setTestKeyGenerator(@Nullable StorageKeyGenerator testKeyGenerator) {
        keyGenerator = testKeyGenerator != null ? testKeyGenerator : KEY_GENERATOR;
    }

    public static boolean profileKeyChanged(StorageRecordUpdate<SignalContactRecord> update) {
        return !OptionalUtil.byteArrayEquals(update.getOld().getProfileKey(), update.getNew().getProfileKey());
    }

    public static SignalStorageRecord buildAccountRecord(@NonNull Context context, @NonNull Recipient self) {

        RecipientDatabase       recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
        RecipientSettings       settings          = recipientDatabase.getRecipientSettingsForSync(self.getId());
        List<RecipientSettings> pinned            = Stream.of(DatabaseFactory.getThreadDatabase(context).getPinnedRecipientIds())
                .map(recipientDatabase::getRecipientSettingsForSync)
                .toList();

        SignalAccountRecord account = new SignalAccountRecord.Builder(self.getStorageServiceId())
                .setUnknownFields(settings != null ? settings.getSyncExtras().getStorageProto() : null)
                .setProfileKey(self.getProfileKey())
                .setGivenName(self.getProfileName().getGivenName())
                .setFamilyName(self.getProfileName().getFamilyName())
                .setAvatarUrlPath(self.getProfileAvatar())
                .setNoteToSelfArchived(settings != null && settings.getSyncExtras().isArchived())
                .setNoteToSelfForcedUnread(settings != null && settings.getSyncExtras().isForcedUnread())
                .setTypingIndicatorsEnabled(TextSecurePreferences.isTypingIndicatorsEnabled(context))
                .setReadReceiptsEnabled(TextSecurePreferences.isReadReceiptsEnabled(context))
                .setSealedSenderIndicatorsEnabled(TextSecurePreferences.isShowUnidentifiedDeliveryIndicatorsEnabled(context))
                .setLinkPreviewsEnabled(SignalStore.settings().isLinkPreviewsEnabled())
                .setUnlistedUserLogin(SignalStore.userLoginPrivacy().getUserLoginListingMode().isUnlisted())
                .setUserLoginSharingMode(StorageSyncModels.localToRemoteUserLoginSharingMode(SignalStore.userLoginPrivacy().getUserLoginSharingMode()))
                .setPinnedConversations(StorageSyncModels.localToRemotePinnedConversations(pinned))
                .setPayments(SignalStore.paymentsValues().mobileCoinPaymentsEnabled(), Optional.fromNullable(SignalStore.paymentsValues().getPaymentsEntropy()).transform(Entropy::getBytes).orNull())
                .setUniversalExpireTimer(SignalStore.settings().getUniversalExpireTimer())
                .setUserLogin(TextSecurePreferences.getLocalNumber(context))
                .setDefaultReactions(SignalStore.emojiValues().getReactions())
                .build();

        return SignalStorageRecord.forAccount(account);
    }

    public static void applyAccountStorageSyncUpdates(@NonNull Context context, @NonNull Recipient self, @NonNull SignalAccountRecord updatedRecord, boolean fetchProfile) {
        SignalAccountRecord localRecord = buildAccountRecord(context, self).getAccount().get();
        applyAccountStorageSyncUpdates(context, self, new StorageRecordUpdate<>(localRecord, updatedRecord), fetchProfile);
    }

    public static void applyAccountStorageSyncUpdates(@NonNull Context context, @NonNull Recipient self, @NonNull StorageRecordUpdate<SignalAccountRecord> update, boolean fetchProfile) {
        DatabaseFactory.getRecipientDatabase(context).applyStorageSyncAccountUpdate(update);

        TextSecurePreferences.setReadReceiptsEnabled(context, update.getNew().isReadReceiptsEnabled());
        TextSecurePreferences.setTypingIndicatorsEnabled(context, update.getNew().isTypingIndicatorsEnabled());
        TextSecurePreferences.setShowUnidentifiedDeliveryIndicatorsEnabled(context, update.getNew().isSealedSenderIndicatorsEnabled());
        SignalStore.settings().setLinkPreviewsEnabled(update.getNew().isLinkPreviewsEnabled());
        SignalStore.userLoginPrivacy().setUserLoginListingMode(update.getNew().isUserLoginUnlisted() ? UserLoginPrivacyValues.UserLoginListingMode.UNLISTED : UserLoginPrivacyValues.UserLoginListingMode.LISTED);
        SignalStore.userLoginPrivacy().setUserLoginSharingMode(StorageSyncModels.remoteToLocalUserLoginSharingMode(update.getNew().getUserLoginSharingMode()));
        SignalStore.paymentsValues().setEnabledAndEntropy(update.getNew().getPayments().isEnabled(), Entropy.fromBytes(update.getNew().getPayments().getEntropy().orNull()));
        SignalStore.settings().setUniversalExpireTimer(update.getNew().getUniversalExpireTimer());
        SignalStore.emojiValues().setReactions(update.getNew().getDefaultReactions());

        if (fetchProfile && update.getNew().getAvatarUrlPath().isPresent()) {
            ApplicationDependencies.getJobManager().add(new RetrieveProfileAvatarJob(self, update.getNew().getAvatarUrlPath().get()));
        }
    }

    public static void scheduleSyncForDataChange() {
        if (!SignalStore.registrationValues().isRegistrationComplete()) {
            Log.d(TAG, "Registration still ongoing. Ignore sync request.");
            return;
        }
        ApplicationDependencies.getJobManager().add(new StorageSyncJob());
    }

    public static void scheduleRoutineSync() {
        long timeSinceLastSync = System.currentTimeMillis() - SignalStore.storageService().getLastSyncTime();

        if (timeSinceLastSync > REFRESH_INTERVAL) {
            Log.d(TAG, "Scheduling a sync. Last sync was " + timeSinceLastSync + " ms ago.");
            scheduleSyncForDataChange();
        } else {
            Log.d(TAG, "No need for sync. Last sync was " + timeSinceLastSync + " ms ago.");
        }
    }

    public static final class IdDifferenceResult {
        private final List<StorageId> remoteOnlyIds;
        private final List<StorageId> localOnlyIds;
        private final boolean         hasTypeMismatches;

        private IdDifferenceResult(@NonNull List<StorageId> remoteOnlyIds,
                                   @NonNull List<StorageId> localOnlyIds,
                                   boolean hasTypeMismatches)
        {
            this.remoteOnlyIds     = remoteOnlyIds;
            this.localOnlyIds      = localOnlyIds;
            this.hasTypeMismatches = hasTypeMismatches;
        }

        public @NonNull List<StorageId> getRemoteOnlyIds() {
            return remoteOnlyIds;
        }

        public @NonNull List<StorageId> getLocalOnlyIds() {
            return localOnlyIds;
        }

        /**
         * @return True if there exist some keys that have matching raw ID's but different types,
         *         otherwise false.
         */
        public boolean hasTypeMismatches() {
            return hasTypeMismatches;
        }

        public boolean isEmpty() {
            return remoteOnlyIds.isEmpty() && localOnlyIds.isEmpty();
        }

        @Override
        public @NonNull String toString() {
            return "remoteOnly: " + remoteOnlyIds.size() + ", localOnly: " + localOnlyIds.size() + ", hasTypeMismatches: " + hasTypeMismatches;
        }
    }

    public static final class WriteOperationResult {
        private final SignalStorageManifest     manifest;
        private final List<SignalStorageRecord> inserts;
        private final List<byte[]>              deletes;

        public WriteOperationResult(@NonNull SignalStorageManifest manifest,
                                    @NonNull List<SignalStorageRecord> inserts,
                                    @NonNull List<byte[]> deletes)
        {
            this.manifest = manifest;
            this.inserts  = inserts;
            this.deletes  = deletes;
        }

        public @NonNull SignalStorageManifest getManifest() {
            return manifest;
        }

        public @NonNull List<SignalStorageRecord> getInserts() {
            return inserts;
        }

        public @NonNull List<byte[]> getDeletes() {
            return deletes;
        }

        public boolean isEmpty() {
            return inserts.isEmpty() && deletes.isEmpty();
        }

        @Override
        public @NonNull String toString() {
            if (isEmpty()) {
                return "Empty";
            } else {
                return String.format(Locale.ENGLISH,
                        "ManifestVersion: %d, Total Keys: %d, Inserts: %d, Deletes: %d",
                        manifest.getVersion(),
                        manifest.getStorageIds().size(),
                        inserts.size(),
                        deletes.size());
            }
        }
    }
}