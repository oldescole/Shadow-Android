package su.sres.securesms.jobs;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.zkgroup.profiles.ProfileKey;
import org.signal.zkgroup.profiles.ProfileKeyCredential;
import su.sres.securesms.crypto.ProfileKeyUtil;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.core.util.logging.Log;
import su.sres.securesms.profiles.ProfileName;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.ProfileUtil;
import su.sres.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;

import su.sres.securesms.util.Util;
import su.sres.signalservice.api.crypto.InvalidCiphertextException;
import su.sres.signalservice.api.profiles.ProfileAndCredential;
import su.sres.signalservice.api.profiles.SignalServiceProfile;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;


/**
 * Refreshes the profile of the local user. Different from {@link RetrieveProfileJob} in that we
 * have to sometimes look at/set different data stores, and we will *always* do the fetch regardless
 * of caching.
 */
public class RefreshOwnProfileJob extends BaseJob {

    public static final String KEY = "RefreshOwnProfileJob";

    private static final String TAG = Log.tag(RefreshOwnProfileJob.class);

    public RefreshOwnProfileJob() {
        this(new Parameters.Builder()
                .addConstraint(NetworkConstraint.KEY)
                .setQueue(ProfileUploadJob.QUEUE)
                .setMaxInstancesForFactory(1)
                .setMaxAttempts(10)
                .build());
    }


    private RefreshOwnProfileJob(@NonNull Parameters parameters) {
        super(parameters);
    }

    @Override
    public @NonNull Data serialize() {
        return Data.EMPTY;
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    protected void onRun() throws Exception {
        if (!TextSecurePreferences.isPushRegistered(context) || TextUtils.isEmpty(TextSecurePreferences.getLocalNumber(context))) {
            Log.w(TAG, "Not yet registered!");
            return;
        }

        Recipient            self                 = Recipient.self();
        ProfileAndCredential profileAndCredential = ProfileUtil.retrieveProfileSync(context, self, getRequestType(self));
        SignalServiceProfile profile              = profileAndCredential.getProfile();

        setProfileName(profile.getName());
        setProfileAbout(profile.getAbout(), profile.getAboutEmoji());
        setProfileAvatar(profile.getAvatar());
        setProfileCapabilities(profile.getCapabilities());
        Optional<ProfileKeyCredential> profileKeyCredential = profileAndCredential.getProfileKeyCredential();
        if (profileKeyCredential.isPresent()) {
            setProfileKeyCredential(self, ProfileKeyUtil.getSelfProfileKey(), profileKeyCredential.get());
        }
    }

    private void setProfileKeyCredential(@NonNull Recipient recipient,
                                         @NonNull ProfileKey recipientProfileKey,
                                         @NonNull ProfileKeyCredential credential)
    {
        RecipientDatabase recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
        recipientDatabase.setProfileKeyCredential(recipient.getId(), recipientProfileKey, credential);
    }

    private static SignalServiceProfile.RequestType getRequestType(@NonNull Recipient recipient) {
        return !recipient.hasProfileKeyCredential()
                ? SignalServiceProfile.RequestType.PROFILE_AND_CREDENTIAL
                : SignalServiceProfile.RequestType.PROFILE;
    }

    @Override
    protected boolean onShouldRetry(@NonNull Exception e) {
        return e instanceof PushNetworkException;
    }

    @Override
    public void onFailure() { }

    private void setProfileName(@Nullable String encryptedName) {
        try {
            ProfileKey  profileKey    = ProfileKeyUtil.getSelfProfileKey();
            String      plaintextName = ProfileUtil.decryptString(profileKey, encryptedName);
            ProfileName profileName   = ProfileName.fromSerialized(plaintextName);

            DatabaseFactory.getRecipientDatabase(context).setProfileName(Recipient.self().getId(), profileName);
        } catch (InvalidCiphertextException | IOException e) {
            Log.w(TAG, e);
        }
    }

    private void setProfileAbout(@Nullable String encryptedAbout, @Nullable String encryptedEmoji) {
        try {
            ProfileKey  profileKey     = ProfileKeyUtil.getSelfProfileKey();
            String      plaintextAbout = ProfileUtil.decryptString(profileKey, encryptedAbout);
            String      plaintextEmoji = ProfileUtil.decryptString(profileKey, encryptedEmoji);

            Log.d(TAG, "Saving " + (!Util.isEmpty(plaintextAbout) ? "non-" : "") + "empty about.");
            Log.d(TAG, "Saving " + (!Util.isEmpty(plaintextEmoji) ? "non-" : "") + "empty emoji.");

            DatabaseFactory.getRecipientDatabase(context).setAbout(Recipient.self().getId(), plaintextAbout, plaintextEmoji);
        } catch (InvalidCiphertextException | IOException e) {
            Log.w(TAG, e);
        }
    }

    private static void setProfileAvatar(@Nullable String avatar) {
        ApplicationDependencies.getJobManager().add(new RetrieveProfileAvatarJob(Recipient.self(), avatar));
    }

    private void setProfileCapabilities(@Nullable SignalServiceProfile.Capabilities capabilities) {
        if (capabilities == null) {
            return;
        }

        DatabaseFactory.getRecipientDatabase(context).setCapabilities(Recipient.self().getId(), capabilities);
    }

    public static final class Factory implements Job.Factory<RefreshOwnProfileJob> {

        @Override
        public @NonNull RefreshOwnProfileJob create(@NonNull Parameters parameters, @NonNull Data data) {
            return new RefreshOwnProfileJob(parameters);
        }
    }
}