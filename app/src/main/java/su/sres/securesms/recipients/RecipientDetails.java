package su.sres.securesms.recipients;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import su.sres.securesms.badges.models.Badge;
import su.sres.securesms.conversation.colors.AvatarColor;
import su.sres.securesms.conversation.colors.ChatColors;
import su.sres.securesms.database.RecipientDatabase.InsightsBannerTier;
import su.sres.securesms.database.RecipientDatabase.MentionSetting;
import su.sres.securesms.database.RecipientDatabase.RecipientSettings;
import su.sres.securesms.database.RecipientDatabase.RegisteredState;
import su.sres.securesms.database.RecipientDatabase.UnidentifiedAccessMode;
import su.sres.securesms.database.RecipientDatabase.VibrateState;
import su.sres.securesms.groups.GroupId;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.profiles.ProfileName;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.securesms.util.Util;
import su.sres.securesms.wallpaper.ChatWallpaper;
import su.sres.signalservice.api.push.ACI;

import org.signal.zkgroup.profiles.ProfileKeyCredential;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class RecipientDetails {

  final ACI    aci;
  final String username;
  final String                     e164;
  final String                     email;
  final GroupId                    groupId;
  final String                     groupName;
  final String                     systemContactName;
  final String                     customLabel;
  final Uri                        systemContactPhoto;
  final Uri                        contactUri;
  final Optional<Long>             groupAvatarId;
  final Uri                        messageRingtone;
  final Uri                        callRingtone;
  final long                       mutedUntil;
  final VibrateState               messageVibrateState;
  final VibrateState               callVibrateState;
  final boolean                    blocked;
  final int                        expireMessages;
  final List<Recipient>            participants;
  final ProfileName                profileName;
  final Optional<Integer>          defaultSubscriptionId;
  final RegisteredState            registered;
  final byte[]                     profileKey;
  final ProfileKeyCredential       profileKeyCredential;
  final String                     profileAvatar;
  final boolean                    hasProfileImage;
  final boolean                    profileSharing;
  final long                       lastProfileFetch;
  final boolean                    systemContact;
  final boolean                    isSelf;
  final String                     notificationChannel;
  final UnidentifiedAccessMode     unidentifiedAccessMode;
  final boolean                    forceSmsSelection;
  final Recipient.Capability       groupsV2Capability;
  final Recipient.Capability       groupsV1MigrationCapability;
  final Recipient.Capability       senderKeyCapability;
  final Recipient.Capability       announcementGroupCapability;
  final Recipient.Capability       changeLoginCapability;
  final InsightsBannerTier         insightsBannerTier;
  final byte[]                     storageId;
  final MentionSetting             mentionSetting;
  final ChatWallpaper              wallpaper;
  final ChatColors                 chatColors;
  final AvatarColor                avatarColor;
  final String                     about;
  final String                     aboutEmoji;
  final ProfileName                systemProfileName;
  final Optional<Recipient.Extras> extras;
  final boolean     hasGroupsInCommon;
  final List<Badge> badges;

  public RecipientDetails(@Nullable String groupName,
                          @Nullable String systemContactName,
                          @NonNull Optional<Long> groupAvatarId,
                          boolean systemContact,
                          boolean isSelf,
                          @NonNull RegisteredState registeredState,
                          @NonNull RecipientSettings settings,
                          @Nullable List<Recipient> participants)
  {
    this.groupAvatarId               = groupAvatarId;
    this.systemContactPhoto          = Util.uri(settings.getSystemContactPhotoUri());
    this.customLabel                 = settings.getSystemPhoneLabel();
    this.contactUri = null;
    this.aci        = null;
    this.username   = null;
    this.e164                        = settings.getE164();
    this.email                       = settings.getEmail();
    this.groupId                     = settings.getGroupId();
    this.messageRingtone             = settings.getMessageRingtone();
    this.callRingtone                = settings.getCallRingtone();
    this.mutedUntil                  = settings.getMuteUntil();
    this.messageVibrateState         = settings.getMessageVibrateState();
    this.callVibrateState            = settings.getCallVibrateState();
    this.blocked                     = settings.isBlocked();
    this.expireMessages              = settings.getExpireMessages();
    this.participants                = participants == null ? new LinkedList<>() : participants;
    this.profileName                 = settings.getProfileName();
    this.defaultSubscriptionId       = settings.getDefaultSubscriptionId();
    this.registered                  = registeredState;
    this.profileKey                  = settings.getProfileKey();
    this.profileKeyCredential        = settings.getProfileKeyCredential();
    this.profileAvatar               = settings.getProfileAvatar();
    this.hasProfileImage             = settings.hasProfileImage();
    this.profileSharing              = settings.isProfileSharing();
    this.lastProfileFetch            = settings.getLastProfileFetch();
    this.systemContact               = systemContact;
    this.isSelf                      = isSelf;
    this.notificationChannel         = settings.getNotificationChannel();
    this.unidentifiedAccessMode      = settings.getUnidentifiedAccessMode();
    this.forceSmsSelection           = settings.isForceSmsSelection();
    this.groupsV2Capability          = settings.getGroupsV2Capability();
    this.groupsV1MigrationCapability = settings.getGroupsV1MigrationCapability();
    this.senderKeyCapability         = settings.getSenderKeyCapability();
    this.announcementGroupCapability = settings.getAnnouncementGroupCapability();
    this.changeLoginCapability       = settings.getChangeLoginCapability();
    this.insightsBannerTier          = settings.getInsightsBannerTier();
    this.storageId                   = settings.getStorageId();
    this.mentionSetting              = settings.getMentionSetting();
    this.wallpaper                   = settings.getWallpaper();
    this.chatColors                  = settings.getChatColors();
    this.avatarColor                 = settings.getAvatarColor();
    this.about                       = settings.getAbout();
    this.aboutEmoji                  = settings.getAboutEmoji();
    this.systemProfileName           = settings.getSystemProfileName();
    this.groupName                   = groupName;
    this.systemContactName           = systemContactName;
    this.extras                      = Optional.fromNullable(settings.getExtras());
    this.hasGroupsInCommon           = settings.hasGroupsInCommon();
    this.badges                      = settings.getBadges();
  }

  /**
   * Only used for {@link Recipient#UNKNOWN}.
   */
  RecipientDetails() {
    this.groupAvatarId               = null;
    this.systemContactPhoto          = null;
    this.customLabel                 = null;
    this.contactUri                  = null;
    this.aci                        = null;
    this.username                    = null;
    this.e164                        = null;
    this.email                       = null;
    this.groupId                     = null;
    this.messageRingtone             = null;
    this.callRingtone                = null;
    this.mutedUntil                  = 0;
    this.messageVibrateState         = VibrateState.DEFAULT;
    this.callVibrateState            = VibrateState.DEFAULT;
    this.blocked                     = false;
    this.expireMessages              = 0;
    this.participants                = new LinkedList<>();
    this.profileName                 = ProfileName.EMPTY;
    this.insightsBannerTier          = InsightsBannerTier.TIER_TWO;
    this.defaultSubscriptionId       = Optional.absent();
    this.registered                  = RegisteredState.UNKNOWN;
    this.profileKey                  = null;
    this.profileKeyCredential        = null;
    this.profileAvatar               = null;
    this.hasProfileImage             = false;
    this.profileSharing              = false;
    this.lastProfileFetch            = 0;
    this.systemContact               = true;
    this.isSelf                      = false;
    this.notificationChannel         = null;
    this.unidentifiedAccessMode      = UnidentifiedAccessMode.UNKNOWN;
    this.forceSmsSelection           = false;
    this.groupName                   = null;
    this.groupsV2Capability          = Recipient.Capability.UNKNOWN;
    this.groupsV1MigrationCapability = Recipient.Capability.UNKNOWN;
    this.senderKeyCapability         = Recipient.Capability.UNKNOWN;
    this.announcementGroupCapability = Recipient.Capability.UNKNOWN;
    this.changeLoginCapability       = Recipient.Capability.UNKNOWN;
    this.storageId                   = null;
    this.mentionSetting              = MentionSetting.ALWAYS_NOTIFY;
    this.wallpaper                   = null;
    this.chatColors                  = null;
    this.avatarColor                 = AvatarColor.UNKNOWN;
    this.about                       = null;
    this.aboutEmoji                  = null;
    this.systemProfileName           = ProfileName.EMPTY;
    this.systemContactName           = null;
    this.extras                      = Optional.absent();
    this.hasGroupsInCommon           = false;
    this.badges                      = Collections.emptyList();
  }

  public static @NonNull RecipientDetails forIndividual(@NonNull Context context, @NonNull RecipientSettings settings) {
    boolean systemContact = !settings.getSystemProfileName().isEmpty();
    boolean isSelf        = (settings.getE164() != null && settings.getE164().equals(SignalStore.account().getUserLogin())) ||
                            (settings.getAci() != null && settings.getAci().equals(SignalStore.account().getAci()));

    RegisteredState registeredState = settings.getRegistered();

    if (isSelf) {
      if (SignalStore.account().isRegistered() && !TextSecurePreferences.isUnauthorizedRecieved(context)) {
        registeredState = RegisteredState.REGISTERED;
      } else {
        registeredState = RegisteredState.NOT_REGISTERED;
      }
    }

    return new RecipientDetails(null, settings.getSystemDisplayName(), Optional.absent(), systemContact, isSelf, registeredState, settings, null);
  }
}