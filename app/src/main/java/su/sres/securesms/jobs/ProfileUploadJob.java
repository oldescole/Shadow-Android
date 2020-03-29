package su.sres.securesms.jobs;

import android.content.Context;

import androidx.annotation.NonNull;

import su.sres.securesms.push.SignalServiceNetworkAccess;
import su.sres.signalservice.internal.configuration.SignalServiceConfiguration;
import su.sres.zkgroup.profiles.ProfileKey;
import su.sres.securesms.crypto.ProfileKeyUtil;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.profiles.AvatarHelper;
import su.sres.securesms.profiles.ProfileName;
import su.sres.securesms.util.FeatureFlags;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.signalservice.api.SignalServiceAccountManager;
import su.sres.signalservice.api.util.StreamDetails;

public final class ProfileUploadJob extends BaseJob {

    public static final String KEY = "ProfileUploadJob";

    private final Context                     context;
    private final SignalServiceAccountManager accountManager;

    public ProfileUploadJob() {
        this(new Job.Parameters.Builder()
                .addConstraint(NetworkConstraint.KEY)
                .setQueue(KEY)
                .setLifespan(Parameters.IMMORTAL)
                .setMaxAttempts(Parameters.UNLIMITED)
                .setMaxInstances(1)
                .build());
    }

    private ProfileUploadJob(@NonNull Parameters parameters) {
        super(parameters);

        this.context        = ApplicationDependencies.getApplication();
        this.accountManager = ApplicationDependencies.getSignalServiceAccountManager();
    }

    @Override
    protected void onRun() throws Exception {
        ProfileKey  profileKey  = ProfileKeyUtil.getSelfProfileKey();
        ProfileName profileName = TextSecurePreferences.getProfileName(context);

        accountManager.updatePushServiceSocket(new SignalServiceNetworkAccess(context).getConfiguration(context));

        try (StreamDetails avatar = AvatarHelper.getSelfProfileAvatarStream(context)) {
            if (FeatureFlags.VERSIONED_PROFILES) {
                accountManager.setVersionedProfile(profileKey, profileName.serialize(), avatar);
            } else {
                accountManager.setProfileName(profileKey, profileName.serialize());
                accountManager.setProfileAvatar(profileKey, avatar);
            }
        }
    }

    @Override
    protected boolean onShouldRetry(@NonNull Exception e) {
        return true;
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
    public void onFailure() {
    }

    public static class Factory implements Job.Factory {

        @NonNull
        @Override
        public Job create(@NonNull Parameters parameters, @NonNull Data data) {
            return new ProfileUploadJob(parameters);
        }
    }
}