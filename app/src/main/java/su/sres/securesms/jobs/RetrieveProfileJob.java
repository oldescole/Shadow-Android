package su.sres.securesms.jobs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.text.TextUtils;

import org.signal.zkgroup.profiles.ProfileKey;
import org.signal.zkgroup.profiles.ProfileKeyCredential;
import su.sres.securesms.crypto.ProfileKeyUtil;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.GroupDatabase;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.database.RecipientDatabase.UnidentifiedAccessMode;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.logging.Log;
import su.sres.securesms.profiles.ProfileName;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.util.Base64;
import su.sres.securesms.util.FeatureFlags;
import su.sres.securesms.util.IdentityUtil;
import su.sres.securesms.util.ProfileUtil;
import su.sres.securesms.util.Util;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.crypto.InvalidCiphertextException;
import su.sres.signalservice.api.crypto.ProfileCipher;
import su.sres.signalservice.api.profiles.ProfileAndCredential;
import su.sres.signalservice.api.profiles.SignalServiceProfile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Retrieves a users profile and sets the appropriate local fields.
 * <p>
 * Recipient can be self if you use {@link #forRecipient} and it will delegate to {@link RefreshOwnProfileJob}.
 */
public class RetrieveProfileJob extends BaseJob  {

  public static final String KEY = "RetrieveProfileJob";

  private static final String TAG = RetrieveProfileJob.class.getSimpleName();

  private static final String KEY_RECIPIENT = "recipient";

  private final RecipientId recipientId;

  public static Job forRecipient(@NonNull Recipient recipient) {
    return forRecipient(recipient.getId());
  }

  public static Job forRecipient(@NonNull RecipientId recipientId) {
    if (Recipient.self().getId().equals(recipientId)) {
      return new RefreshOwnProfileJob();
    } else {
      return new RetrieveProfileJob(recipientId);
    }
  }

  private RetrieveProfileJob(@NonNull RecipientId recipientId) {
    this(new Job.Parameters.Builder()
                    .addConstraint(NetworkConstraint.KEY)
                    .setMaxAttempts(3)
                    .build(),
                    recipientId);
  }

  private RetrieveProfileJob(@NonNull Job.Parameters parameters, @NonNull RecipientId recipientId) {
    super(parameters);

    this.recipientId = recipientId;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putString(KEY_RECIPIENT, recipientId.serialize()).build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws IOException {
    Log.i(TAG, "Retrieving profile of " + recipientId);
    Recipient resolved = Recipient.resolved(recipientId);

    if (resolved.isGroup()) handleGroupRecipient(resolved);
    else                    handleIndividualRecipient(resolved);
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    return false;
  }

  @Override
  public void onFailure() {}

  private void handleIndividualRecipient(Recipient recipient) throws IOException {
    if (recipient.hasServiceIdentifier()) handlePhoneNumberRecipient(recipient);
    else                                  Log.w(TAG, "Skipping fetching profile of non-Signal recipient");
  }

  private void handlePhoneNumberRecipient(Recipient recipient) throws IOException {
    ProfileAndCredential profileAndCredential = ProfileUtil.retrieveProfile(context, recipient, getRequestType(recipient));
    SignalServiceProfile profile              = profileAndCredential.getProfile();
    ProfileKey           recipientProfileKey  = ProfileKeyUtil.profileKeyOrNull(recipient.getProfileKey());

    if (recipientProfileKey == null) {
      Log.i(TAG, "No profile key available for " + recipient.getId());
    } else {
      Log.i(TAG, "Profile key available for " + recipient.getId());
    }

 //   setUuid(recipient, profile.getUuid());
    setProfileName(recipient, profile.getName());
    setProfileAvatar(recipient, profile.getAvatar());
    if (FeatureFlags.usernames()) setUsername(recipient, profile.getUsername());
    setProfileCapabilities(recipient, profile.getCapabilities());
    setIdentityKey(recipient, profile.getIdentityKey());
    setUnidentifiedAccessMode(recipient, profile.getUnidentifiedAccess(), profile.isUnrestrictedUnidentifiedAccess());

    if (recipientProfileKey != null) {
      Optional<ProfileKeyCredential> profileKeyCredential = profileAndCredential.getProfileKeyCredential();
      if (profileKeyCredential.isPresent()) {
        setProfileKeyCredential(recipient, recipientProfileKey, profileKeyCredential.get());
      }
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
    return FeatureFlags.VERSIONED_PROFILES && !recipient.hasProfileKeyCredential()
            ? SignalServiceProfile.RequestType.PROFILE_AND_CREDENTIAL
            : SignalServiceProfile.RequestType.PROFILE;
  }

  private void handleGroupRecipient(Recipient group) throws IOException {
    List<Recipient> recipients = DatabaseFactory.getGroupDatabase(context).getGroupMembers(group.requireGroupId(), GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);

    for (Recipient recipient : recipients) {
      handleIndividualRecipient(recipient);
    }
  }

  private void setIdentityKey(Recipient recipient, String identityKeyValue) {
    try {
      if (TextUtils.isEmpty(identityKeyValue)) {
        Log.w(TAG, "Identity key is missing on profile!");
        return;
      }

      IdentityKey identityKey = new IdentityKey(Base64.decode(identityKeyValue), 0);

      if (!DatabaseFactory.getIdentityDatabase(context)
              .getIdentity(recipient.getId())
                          .isPresent())
      {
        Log.w(TAG, "Still first use...");
        return;
      }

      IdentityUtil.saveIdentity(context, recipient.requireServiceId(), identityKey);
    } catch (InvalidKeyException | IOException e) {
      Log.w(TAG, e);
    }
  }

  private void setUnidentifiedAccessMode(Recipient recipient, String unidentifiedAccessVerifier, boolean unrestrictedUnidentifiedAccess) {
    RecipientDatabase recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
    ProfileKey        profileKey        = ProfileKeyUtil.profileKeyOrNull(recipient.getProfileKey());

    if (unrestrictedUnidentifiedAccess && unidentifiedAccessVerifier != null) {
      if (recipient.getUnidentifiedAccessMode() != UnidentifiedAccessMode.UNRESTRICTED) {
        Log.i(TAG, "Marking recipient UD status as unrestricted.");
        recipientDatabase.setUnidentifiedAccessMode(recipient.getId(), UnidentifiedAccessMode.UNRESTRICTED);
      }
    } else if (profileKey == null || unidentifiedAccessVerifier == null) {
      if (recipient.getUnidentifiedAccessMode() != UnidentifiedAccessMode.DISABLED) {
        Log.i(TAG, "Marking recipient UD status as disabled.");
        recipientDatabase.setUnidentifiedAccessMode(recipient.getId(), UnidentifiedAccessMode.DISABLED);
      }
    } else {
      ProfileCipher profileCipher = new ProfileCipher(profileKey);
      boolean verifiedUnidentifiedAccess;

      try {
        verifiedUnidentifiedAccess = profileCipher.verifyUnidentifiedAccess(Base64.decode(unidentifiedAccessVerifier));
      } catch (IOException e) {
        Log.w(TAG, e);
        verifiedUnidentifiedAccess = false;
      }

      UnidentifiedAccessMode mode = verifiedUnidentifiedAccess ? UnidentifiedAccessMode.ENABLED : UnidentifiedAccessMode.DISABLED;

      if (recipient.getUnidentifiedAccessMode() != mode) {
        Log.i(TAG, "Marking recipient UD status as " + mode.name() + " after verification.");
        recipientDatabase.setUnidentifiedAccessMode(recipient.getId(), mode);
      }
    }
  }

  private void setProfileName(Recipient recipient, String profileName) {
    try {
      ProfileKey profileKey = ProfileKeyUtil.profileKeyOrNull(recipient.getProfileKey());
      if (profileKey == null) return;

      String plaintextProfileName = ProfileUtil.decryptName(profileKey, profileName);

      if (!Util.equals(plaintextProfileName, recipient.getProfileName().serialize())) {
        Log.i(TAG, "Profile name updated. Writing new value.");
        DatabaseFactory.getRecipientDatabase(context).setProfileName(recipient.getId(), ProfileName.fromSerialized(plaintextProfileName));
      }
      if (TextUtils.isEmpty(plaintextProfileName)) {
        Log.i
                (TAG, "No profile name set.");
      }

    } catch (InvalidCiphertextException | IOException e) {
      Log.w(TAG, e);
    }
  }

  private void setProfileAvatar(Recipient recipient, String profileAvatar) {
    if (recipient.getProfileKey() == null) return;

    if (!Util.equals(profileAvatar, recipient.getProfileAvatar())) {
      ApplicationDependencies.getJobManager().add(new RetrieveProfileAvatarJob(recipient, profileAvatar));
    } else {
      Log.d(TAG, "Skipping avatar fetch for " + recipient.getId());
    }
  }

  private void setUsername(Recipient recipient, @Nullable String username) {
    DatabaseFactory.getRecipientDatabase(context).setUsername(recipient.getId(), username);
  }

  // maybe later...
/*  private void setUuid(Recipient recipient, UUID uuid) {
    if (uuid !=null && !recipient.getUuid().isPresent()) {
      DatabaseFactory.getRecipientDatabase(context).setUuid(recipient.getId(), uuid);
    } else {

      if(uuid == null) {
        Log.i(TAG, "UUID is null");
      }

      if (recipient.getUuid().isPresent()) {
        Log.i(TAG, "Recipient UUID is present");
      }

    }
  } */

  private void setProfileCapabilities(@NonNull Recipient recipient, @Nullable SignalServiceProfile.Capabilities capabilities) {
    if (capabilities == null) {
      return;
    }

    DatabaseFactory.getRecipientDatabase(context).setCapabilities(recipient.getId(), capabilities);
  }

  public static final class Factory implements Job.Factory<RetrieveProfileJob> {

    @Override
    public @NonNull RetrieveProfileJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new RetrieveProfileJob(parameters, RecipientId.from(data.getString(KEY_RECIPIENT)));
    }
  }
}
