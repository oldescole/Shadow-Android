/**
 * Copyright (C) 2014-2016 Open Whisper Systems
 * <p>
 * Licensed according to the LICENSE file in this repository.
 */

package su.sres.signalservice.api;

import com.google.protobuf.ByteString;

import su.sres.signalservice.api.messages.calls.SystemCertificates;
import su.sres.zkgroup.profiles.ProfileKey;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.logging.Log;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.FeatureFlags;

import su.sres.signalservice.api.crypto.ProfileCipher;
import su.sres.signalservice.api.crypto.ProfileCipherOutputStream;
import su.sres.signalservice.api.push.exceptions.NoContentException;
import su.sres.signalservice.api.storage.StorageKey;
import su.sres.signalservice.api.messages.calls.ConfigurationInfo;
import su.sres.signalservice.api.messages.calls.TurnServerInfo;
import su.sres.signalservice.api.messages.multidevice.DeviceInfo;
import su.sres.signalservice.api.profiles.SignalServiceProfile;
import su.sres.signalservice.api.profiles.SignalServiceProfileWrite;
import su.sres.signalservice.api.push.ContactTokenDetails;
import su.sres.signalservice.api.push.SignedPreKeyEntity;
import su.sres.signalservice.api.push.exceptions.NotFoundException;
import su.sres.signalservice.api.storage.SignalStorageCipher;
import su.sres.signalservice.api.storage.SignalStorageModels;
import su.sres.signalservice.api.storage.SignalStorageRecord;
import su.sres.signalservice.api.storage.StorageManifestKey;
import su.sres.signalservice.api.storage.SignalStorageManifest;
import su.sres.signalservice.api.util.CredentialsProvider;
import su.sres.signalservice.api.util.StreamDetails;
import su.sres.signalservice.internal.configuration.SignalServiceConfiguration;
import su.sres.signalservice.internal.crypto.ProvisioningCipher;
import su.sres.signalservice.internal.push.ProfileAvatarData;
import su.sres.signalservice.internal.push.PushServiceSocket;
import su.sres.signalservice.internal.push.RemoteConfigResponse;
import su.sres.signalservice.internal.push.http.ProfileCipherOutputStreamFactory;
import su.sres.signalservice.internal.storage.protos.ManifestRecord;
import su.sres.signalservice.internal.storage.protos.ReadOperation;
import su.sres.signalservice.internal.storage.protos.StorageItem;
import su.sres.signalservice.internal.storage.protos.StorageItems;
import su.sres.signalservice.internal.storage.protos.StorageManifest;
import su.sres.signalservice.internal.storage.protos.WriteOperation;
import su.sres.signalservice.internal.util.StaticCredentialsProvider;
import su.sres.signalservice.internal.util.Util;

import su.sres.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static su.sres.signalservice.internal.push.ProvisioningProtos.ProvisionMessage;
import static su.sres.signalservice.internal.push.ProvisioningProtos.ProvisioningVersion;

/**
 * The main interface for creating, registering, and
 * managing a Signal Service account.
 *
 * @author Moxie Marlinspike
 */
public class SignalServiceAccountManager {

    private static final String TAG = SignalServiceAccountManager.class.getSimpleName();

    private final PushServiceSocket pushServiceSocket;
    private final CredentialsProvider credentials;
    private final String userAgent;

    /**
     * Construct a SignalServiceAccountManager.
     *
     * @param configuration The URL for the Signal Service.
     * @param uuid The Signal Service UUID.
     * @param e164 The Signal Service phone number.
     * @param password A Signal Service password.
     * @param signalAgent A string which identifies the client software.
     */
    public SignalServiceAccountManager(SignalServiceConfiguration configuration,
                                       UUID uuid, String e164, String password,
                                       String signalAgent)
    {
        this(configuration, new StaticCredentialsProvider(uuid, e164, password, null), signalAgent);
    }

    public SignalServiceAccountManager(SignalServiceConfiguration configuration,
                                       CredentialsProvider credentialsProvider,
                                       String signalAgent)
    {
        this.pushServiceSocket = new PushServiceSocket(configuration, credentialsProvider, signalAgent);
        this.credentials = credentialsProvider;
        this.userAgent         = signalAgent;
    }

    public byte[] getSenderCertificate() throws IOException {
        return this.pushServiceSocket.getSenderCertificate();
    }

    public byte[] getSenderCertificateLegacy() throws IOException {
        return this.pushServiceSocket.getSenderCertificateLegacy();
    }

    public void setPin(Optional<String> pin) throws IOException {
        if (pin.isPresent()) {
            this.pushServiceSocket.setPin(pin.get());
        } else {
            this.pushServiceSocket.removePin();
        }
    }

    public UUID getOwnUuid() throws IOException {
        return this.pushServiceSocket.getOwnUuid();
    }

    /**
     * Register/Unregister a Google Cloud Messaging registration ID.
     *
     * @param gcmRegistrationId The GCM id to register.  A call with an absent value will unregister.
     * @throws IOException
     */
    public void setGcmId(Optional<String> gcmRegistrationId) throws IOException {
        if (gcmRegistrationId.isPresent()) {
            this.pushServiceSocket.registerGcmId(gcmRegistrationId.get());
        } else {
            this.pushServiceSocket.unregisterGcmId();
        }
    }

    /**
     * Request a push challenge. A number will be pushed to the GCM (FCM) id. This can then be used
     * during SMS/call requests to bypass the CAPTCHA.
     *
     * @param gcmRegistrationId The GCM (FCM) id to use.
     * @param e164number        The number to associate it with.
     * @throws IOException
     */
    public void requestPushChallenge(String gcmRegistrationId, String e164number) throws IOException {
        this.pushServiceSocket.requestPushChallenge(gcmRegistrationId, e164number);
    }

    /**
     * Request an SMS verification code.  On success, the server will send
     * an SMS verification code to this Signal user.
     *
     * @param androidSmsRetrieverSupported
     * @param captchaToken                 If the user has done a CAPTCHA, include this.
     * @param challenge                    If present, it can bypass the CAPTCHA.
     * @throws IOException
     */
    public void requestSmsVerificationCode(boolean androidSmsRetrieverSupported, Optional<String> captchaToken, Optional<String> challenge) throws IOException {
        this.pushServiceSocket.requestSmsVerificationCode(androidSmsRetrieverSupported, captchaToken, challenge);
    }

    /**
     * Request a Voice verification code.  On success, the server will
     * make a voice call to this Signal user.
     *
     * @param locale
     * @param captchaToken If the user has done a CAPTCHA, include this.
     * @param challenge    If present, it can bypass the CAPTCHA.
     * @throws IOException
     */
    public void requestVoiceVerificationCode(Locale locale, Optional<String> captchaToken, Optional<String> challenge) throws IOException {
        this.pushServiceSocket.requestVoiceVerificationCode(locale, captchaToken, challenge);
    }

    /**
     * Verify a Signal Service account with a received SMS or voice verification code.
     *
     * @param verificationCode The verification code received via SMS or Voice
     *                         (see {@link #requestSmsVerificationCode} and
     *                         {@link #requestVoiceVerificationCode}).
     * @param signalingKey 52 random bytes.  A 32 byte AES key and a 20 byte Hmac256 key,
     *                     concatenated.
     * @param signalProtocolRegistrationId A random 14-bit number that identifies this Signal install.
     *                                     This value should remain consistent across registrations for the
     *                                     same install, but probabilistically differ across registrations
     *                                     for separate installs.
     *
     * @return The UUID of the user that was registered.
     * @throws IOException
     */
    public UUID verifyAccountWithCode(String verificationCode, String signalingKey, int signalProtocolRegistrationId, boolean fetchesMessages, String pin,
                                      byte[] unidentifiedAccessKey, boolean unrestrictedUnidentifiedAccess,
                                      SignalServiceProfile.Capabilities capabilities)
            throws IOException {
        return this.pushServiceSocket.verifyAccountCode(verificationCode, signalingKey,
                signalProtocolRegistrationId,
                fetchesMessages, pin,
                unidentifiedAccessKey,
                unrestrictedUnidentifiedAccess,
                capabilities);
    }

    /**
     * Refresh account attributes with server.
     *
     * @param signalingKey 52 random bytes.  A 32 byte AES key and a 20 byte Hmac256 key, concatenated.
     * @param signalProtocolRegistrationId A random 14-bit number that identifies this Signal install.
     *                                     This value should remain consistent across registrations for the same
     *                                     install, but probabilistically differ across registrations for
     *                                     separate installs.
     *
     * @throws IOException
     */
    public void setAccountAttributes(String signalingKey, int signalProtocolRegistrationId, boolean fetchesMessages, String pin,
                                     byte[] unidentifiedAccessKey, boolean unrestrictedUnidentifiedAccess,
                                     SignalServiceProfile.Capabilities capabilities)
            throws IOException {
        this.pushServiceSocket.setAccountAttributes(signalingKey, signalProtocolRegistrationId, fetchesMessages, pin,
                unidentifiedAccessKey, unrestrictedUnidentifiedAccess,
                capabilities);
    }

    /**
     * Register an identity key, signed prekey, and list of one time prekeys
     * with the server.
     *
     * @param identityKey The client's long-term identity keypair.
     * @param signedPreKey The client's signed prekey.
     * @param oneTimePreKeys The client's list of one-time prekeys.
     *
     * @throws IOException
     */
    public void setPreKeys(IdentityKey identityKey, SignedPreKeyRecord signedPreKey, List<PreKeyRecord> oneTimePreKeys)
            throws IOException {
        this.pushServiceSocket.registerPreKeys(identityKey, signedPreKey, oneTimePreKeys);
    }

    /**
     * @return The server's count of currently available (eg. unused) prekeys for this user.
     * @throws IOException
     */
    public int getPreKeysCount() throws IOException {
        return this.pushServiceSocket.getAvailablePreKeys();
    }

    /**
     * Set the client's signed prekey.
     *
     * @param signedPreKey The client's new signed prekey.
     * @throws IOException
     */
    public void setSignedPreKey(SignedPreKeyRecord signedPreKey) throws IOException {
        this.pushServiceSocket.setCurrentSignedPreKey(signedPreKey);
    }

    /**
     * @return The server's view of the client's current signed prekey.
     * @throws IOException
     */
    public SignedPreKeyEntity getSignedPreKey() throws IOException {
        return this.pushServiceSocket.getCurrentSignedPreKey();
    }

    /**
     * Checks whether a contact is currently registered with the server.
     *
     * @param e164number The contact to check.
     * @return An optional ContactTokenDetails, present if registered, absent if not.
     * @throws IOException
     */
    public Optional<ContactTokenDetails> getContact(String e164number) throws IOException {
        String contactToken = createDirectoryServerToken(e164number, true);
        ContactTokenDetails contactTokenDetails = this.pushServiceSocket.getContactTokenDetails(contactToken);

        if (contactTokenDetails != null) {
            contactTokenDetails.setNumber(e164number);
        }

        return Optional.fromNullable(contactTokenDetails);
    }

    /**
     * Checks which contacts in a set are registered with the server.
     *
     * @param e164numbers The contacts to check.
     * @return A list of ContactTokenDetails for the registered users.
     * @throws IOException
     */
    public List<ContactTokenDetails> getContacts(Set<String> e164numbers)
            throws IOException {
        Map<String, String> contactTokensMap = createDirectoryServerTokenMap(e164numbers);
        List<ContactTokenDetails> activeTokens = this.pushServiceSocket.retrieveDirectory(contactTokensMap.keySet());

        for (ContactTokenDetails activeToken : activeTokens) {
            activeToken.setNumber(contactTokensMap.get(activeToken.getToken()));
        }

        return activeTokens;
    }

    public long getStorageManifestVersion() throws IOException {
        try {
            String authToken = this.pushServiceSocket.getStorageAuth();
            StorageManifest storageManifest = this.pushServiceSocket.getStorageManifest(authToken);

            return storageManifest.getVersion();
        } catch (NotFoundException e) {
            return 0;
        }
    }

    public Optional<SignalStorageManifest> getStorageManifestIfDifferentVersion(StorageKey storageKey, long manifestVersion) throws IOException, InvalidKeyException {
        try {
            String          authToken       = this.pushServiceSocket.getStorageAuth();
            StorageManifest storageManifest = this.pushServiceSocket.getStorageManifestIfDifferentVersion(authToken, manifestVersion);

            if (storageManifest.getValue().isEmpty()) {
                Log.w(TAG, "Got an empty storage manifest!");
                return Optional.absent();
            }

            byte[]         rawRecord      = SignalStorageCipher.decrypt(storageKey.deriveManifestKey(storageManifest.getVersion()), storageManifest.getValue().toByteArray());
            ManifestRecord manifestRecord = ManifestRecord.parseFrom(rawRecord);
            List<byte[]>   keys           = new ArrayList<>(manifestRecord.getKeysCount());

            for (ByteString key : manifestRecord.getKeysList()) {
                keys.add(key.toByteArray());
            }

            return Optional.of(new SignalStorageManifest(manifestRecord.getVersion(), keys));
        } catch (NoContentException e) {
            return Optional.absent();
        }
    }

    public List<SignalStorageRecord> readStorageRecords(StorageKey storageKey, List<byte[]> storageKeys) throws IOException, InvalidKeyException {
        ReadOperation.Builder operation = ReadOperation.newBuilder();

        for (byte[] key : storageKeys) {
            operation.addReadKey(ByteString.copyFrom(key));
        }

        String                    authToken = this.pushServiceSocket.getStorageAuth();
        StorageItems              items     = this.pushServiceSocket.readStorageItems(authToken, operation.build());
        List<SignalStorageRecord> result    = new ArrayList<>(items.getItemsCount());

        if (items.getItemsCount() != storageKeys.size()) {
            Log.w(TAG, "Failed to find all remote keys! Requested: " + storageKeys.size() + ", Found: " + items.getItemsCount());
        }

        for (StorageItem item : items.getItemsList()) {
            if (item.hasKey()) {
                result.add(SignalStorageModels.remoteToLocalStorageRecord(item, storageKey));
            } else {
                Log.w(TAG, "Encountered a StorageItem with no key! Skipping.");
            }
        }

        return result;
    }

    /**
     * @return If there was a conflict, the latest {@link SignalStorageManifest}. Otherwise absent.
     */
    public Optional<SignalStorageManifest> resetStorageRecords(StorageKey storageKey,
                                                               SignalStorageManifest manifest,
                                                               List<SignalStorageRecord> allRecords)
            throws IOException, InvalidKeyException
    {
        return writeStorageRecords(storageKey, manifest, allRecords, Collections.<byte[]>emptyList(), true);
    }

    /**
     * @return If there was a conflict, the latest {@link SignalStorageManifest}. Otherwise absent.
     */
    public Optional<SignalStorageManifest> writeStorageRecords(StorageKey storageKey,
                                                               SignalStorageManifest manifest,
                                                               List<SignalStorageRecord> inserts,
                                                               List<byte[]> deletes)
            throws IOException, InvalidKeyException
        {
            return writeStorageRecords(storageKey, manifest, inserts, deletes, false);
        }

        /**
         * @return If there was a conflict, the latest {@link SignalStorageManifest}. Otherwise absent.
         */
        private Optional<SignalStorageManifest> writeStorageRecords(StorageKey storageKey,
                SignalStorageManifest manifest,
                List<SignalStorageRecord> inserts,
                List<byte[]> deletes,
        boolean clearAll)
      throws IOException, InvalidKeyException
        {
        ManifestRecord.Builder manifestRecordBuilder = ManifestRecord.newBuilder().setVersion(manifest.getVersion());

        for (byte[] key : manifest.getStorageKeys()) {
            manifestRecordBuilder.addKeys(ByteString.copyFrom(key));
        }

            String             authToken       = this.pushServiceSocket.getStorageAuth();
            StorageManifestKey manifestKey     = storageKey.deriveManifestKey(manifest.getVersion());
            byte[]             encryptedRecord = SignalStorageCipher.encrypt(manifestKey, manifestRecordBuilder.build().toByteArray());
            StorageManifest    storageManifest = StorageManifest.newBuilder()
                .setVersion(manifest.getVersion())
                .setValue(ByteString.copyFrom(encryptedRecord))
                .build();
        WriteOperation.Builder writeBuilder = WriteOperation.newBuilder().setManifest(storageManifest);

        for (SignalStorageRecord insert : inserts) {
            writeBuilder.addInsertItem(SignalStorageModels.localToRemoteStorageRecord(insert, storageKey));
        }

            if (clearAll) {
                writeBuilder.setClearAll(true);
            } else {
                for (byte[] delete : deletes) {
                    writeBuilder.addDeleteKey(ByteString.copyFrom(delete));
                }
        }

        Optional<StorageManifest> conflict = this.pushServiceSocket.writeStorageContacts(authToken, writeBuilder.build());

        if (conflict.isPresent()) {
            StorageManifestKey conflictKey       = storageKey.deriveManifestKey(conflict.get().getVersion());
            byte[]             rawManifestRecord = SignalStorageCipher.decrypt(conflictKey, conflict.get().getValue().toByteArray());
            ManifestRecord     record            = ManifestRecord.parseFrom(rawManifestRecord);
            List<byte[]>       keys              = new ArrayList<>(record.getKeysCount());

            for (ByteString key : record.getKeysList()) {
                keys.add(key.toByteArray());
            }

            SignalStorageManifest conflictManifest = new SignalStorageManifest(record.getVersion(), keys);

            return Optional.of(conflictManifest);
        } else {
            return Optional.absent();
        }
    }

    public Map<String, Boolean> getRemoteConfig() throws IOException {
        RemoteConfigResponse response = this.pushServiceSocket.getRemoteConfig();
        Map<String, Boolean> out      = new HashMap<>();

        for (RemoteConfigResponse.Config config : response.getConfig()) {
            out.put(config.getName(), config.isEnabled());
        }

        return out;
    }

    public String getNewDeviceVerificationCode() throws IOException {
        return this.pushServiceSocket.getNewDeviceVerificationCode();
    }

    public void addDevice(String deviceIdentifier,
                          ECPublicKey deviceKey,
                          IdentityKeyPair identityKeyPair,
                          Optional<byte[]> profileKey,
                          String code)
            throws InvalidKeyException, IOException {
        ProvisioningCipher cipher = new ProvisioningCipher(deviceKey);
        ProvisionMessage.Builder message = ProvisionMessage.newBuilder()
                .setIdentityKeyPublic(ByteString.copyFrom(identityKeyPair.getPublicKey().serialize()))
                .setIdentityKeyPrivate(ByteString.copyFrom(identityKeyPair.getPrivateKey().serialize()))
                .setProvisioningCode(code)
                .setProvisioningVersion(ProvisioningVersion.CURRENT_VALUE);
        String e164 = credentials.getE164();
        UUID uuid = credentials.getUuid();

        if (e164 != null) {
            message.setNumber(e164);
        } else {
            throw new AssertionError("Missing phone number!");
        }

        if (uuid != null) {
            message.setUuid(uuid.toString());
        } else {
            Log.w(TAG, "[addDevice] Missing UUID.");
        }

        if (profileKey.isPresent()) {
            message.setProfileKey(ByteString.copyFrom(profileKey.get()));
        }

        byte[] ciphertext = cipher.encrypt(message.build());
        this.pushServiceSocket.sendProvisioningMessage(deviceIdentifier, ciphertext);
    }

    public List<DeviceInfo> getDevices() throws IOException {
        return this.pushServiceSocket.getDevices();
    }

    public void removeDevice(long deviceId) throws IOException {
        this.pushServiceSocket.removeDevice(deviceId);
    }

    public TurnServerInfo getTurnServerInfo() throws IOException {
        return this.pushServiceSocket.getTurnServerInfo();
    }

    public ConfigurationInfo getConfigurationInfo() throws IOException {
        return this.pushServiceSocket.getConfigurationInfo();
    }

    public SystemCertificates getSystemCerts() throws IOException {
        return this.pushServiceSocket.getSystemCerts();
    }

    public void setProfileName(ProfileKey key, String name)
            throws IOException {
        if (FeatureFlags.VERSIONED_PROFILES) {
            throw new AssertionError();
        }
        if (name == null) name = "";

        String ciphertextName = Base64.encodeBytesWithoutPadding(new ProfileCipher(key).encryptName(name.getBytes(StandardCharsets.UTF_8), ProfileCipher.NAME_PADDED_LENGTH));

        this.pushServiceSocket.setProfileName(ciphertextName);
    }

    public void setProfileAvatar(ProfileKey key, StreamDetails avatar)
            throws IOException {
        if (FeatureFlags.VERSIONED_PROFILES) {
            throw new AssertionError();
        }
        ProfileAvatarData profileAvatarData = null;

        if (avatar != null) {
            profileAvatarData = new ProfileAvatarData(avatar.getStream(),
                    ProfileCipherOutputStream.getCiphertextLength(avatar.getLength()),
                    avatar.getContentType(),
                    new ProfileCipherOutputStreamFactory(key));
        }

        this.pushServiceSocket.setProfileAvatar(profileAvatarData);
    }

    public void setVersionedProfile(ProfileKey profileKey, String name, StreamDetails avatar)
            throws IOException
    {
        if (!FeatureFlags.VERSIONED_PROFILES) {
            throw new AssertionError();
        }

        if (name == null) name = "";

        byte[]            ciphertextName    = new ProfileCipher(profileKey).encryptName(name.getBytes(StandardCharsets.UTF_8), ProfileCipher.NAME_PADDED_LENGTH);
        boolean           hasAvatar         = avatar != null;
        ProfileAvatarData profileAvatarData = null;

        if (hasAvatar) {
            profileAvatarData = new ProfileAvatarData(avatar.getStream(),
                    ProfileCipherOutputStream.getCiphertextLength(avatar.getLength()),
                    avatar.getContentType(),
                    new ProfileCipherOutputStreamFactory(profileKey));
        }

        this.pushServiceSocket.writeProfile(new SignalServiceProfileWrite(profileKey.getProfileKeyVersion().serialize(),
                        ciphertextName,
                        hasAvatar,
                        profileKey.getCommitment().serialize()),
                profileAvatarData);
    }

    public void setUsername(String username) throws IOException {
        this.pushServiceSocket.setUsername(username);
    }

    public void deleteUsername() throws IOException {
        this.pushServiceSocket.deleteUsername();
    }

    public void setSoTimeoutMillis(long soTimeoutMillis) {
        this.pushServiceSocket.setSoTimeoutMillis(soTimeoutMillis);
    }

    public void cancelInFlightRequests() {
        this.pushServiceSocket.cancelInFlightRequests();
    }

    private String createDirectoryServerToken(String e164number, boolean urlSafe) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA1");
            byte[] token = Util.trim(digest.digest(e164number.getBytes()), 10);
            String encoded = Base64.encodeBytesWithoutPadding(token);

            if (urlSafe) return encoded.replace('+', '-').replace('/', '_');
            else return encoded;
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private Map<String, String> createDirectoryServerTokenMap(Collection<String> e164numbers) {
        Map<String, String> tokenMap = new HashMap<>(e164numbers.size());

        for (String number : e164numbers) {
            tokenMap.put(createDirectoryServerToken(number, false), number);
        }

        return tokenMap;
    }

    public void updatePushServiceSocket(SignalServiceConfiguration configuration) {
        this.pushServiceSocket.renewNetworkConfiguration(configuration);
    }

}
