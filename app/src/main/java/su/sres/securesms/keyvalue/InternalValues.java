package su.sres.securesms.keyvalue;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

import su.sres.securesms.util.FeatureFlags;

public final class InternalValues extends SignalStoreValues {

  public static final String GV2_DO_NOT_CREATE_GV2                = "internal.gv2.do_not_create_gv2";
  public static final String GV2_FORCE_INVITES                    = "internal.gv2.force_invites";
  public static final String GV2_IGNORE_SERVER_CHANGES            = "internal.gv2.ignore_server_changes";
  public static final String GV2_IGNORE_P2P_CHANGES               = "internal.gv2.ignore_p2p_changes";
  public static final String GV2_DISABLE_AUTOMIGRATE_INITIATION   = "internal.gv2.disable_automigrate_initiation";
  public static final String GV2_DISABLE_AUTOMIGRATE_NOTIFICATION = "internal.gv2.disable_automigrate_notification";
  public static final String RECIPIENT_DETAILS                    = "internal.recipient_details";
  public static final String FORCE_BUILT_IN_EMOJI                 = "internal.force_built_in_emoji";
  public static final String REMOVE_SENDER_KEY_MINIMUM            = "internal.remove_sender_key_minimum";
  public static final String DELAY_RESENDS                        = "internal.delay_resends";
  public static final String SHAKE_TO_REPORT                      = "internal.shake_to_report";

  InternalValues(KeyValueStore store) {
    super(store);
  }

  @Override
  void onFirstEverAppLaunch() {
  }

  @Override
  @NonNull
  List<String> getKeysToIncludeInBackup() {
    return Collections.emptyList();
  }

  /**
   * Do not attempt to create GV2 groups, i.e. will force creation of GV1 or MMS groups.
   */
  public synchronized boolean gv2DoNotCreateGv2Groups() {
    return FeatureFlags.internalUser() && getBoolean(GV2_DO_NOT_CREATE_GV2, false);
  }

  /**
   * Members will not be added directly to a GV2 even if they could be.
   */
  public synchronized boolean gv2ForceInvites() {
    return FeatureFlags.internalUser() && getBoolean(GV2_FORCE_INVITES, false);
  }

  /**
   * The Server will leave out changes that can only be described by a future protocol level that
   * an older client cannot understand. Ignoring those changes by nulling them out simulates that
   * scenario for testing.
   * <p>
   * In conjunction with {@link #gv2IgnoreP2PChanges()} it means no group changes are coming into
   * the client and it will generate changes by group state comparison, and those changes will not
   * have an editor and so will be in the passive voice.
   */
  public synchronized boolean gv2IgnoreServerChanges() {
    return FeatureFlags.internalUser() && getBoolean(GV2_IGNORE_SERVER_CHANGES, false);
  }

  /**
   * Signed group changes are sent P2P, if the client ignores them, it will then ask the server
   * directly which allows testing of certain testing scenarios.
   */
  public synchronized boolean gv2IgnoreP2PChanges() {
    return FeatureFlags.internalUser() && getBoolean(GV2_IGNORE_P2P_CHANGES, false);
  }

  /**
   * Show detailed recipient info in the {@link su.sres.securesms.recipients.ui.managerecipient.ManageRecipientFragment}.
   */
  public synchronized boolean recipientDetails() {
    return FeatureFlags.internalUser() && getBoolean(RECIPIENT_DETAILS, false);
  }

  /**
   * Force the app to use the emoji that ship with the app, as opposed to the ones that were downloaded.
   */
  public synchronized boolean forceBuiltInEmoji() {
    return FeatureFlags.internalUser() && getBoolean(FORCE_BUILT_IN_EMOJI, false);
  }

  /**
   * Remove the requirement that there must be two sender-key-capable recipients to use sender key
   */
  public synchronized boolean removeSenderKeyMinimum() {
    return FeatureFlags.internalUser() && getBoolean(REMOVE_SENDER_KEY_MINIMUM, false);
  }

  /**
   * Delay resending messages in response to retry receipts by 10 seconds.
   */
  public synchronized boolean delayResends() {
    return FeatureFlags.internalUser() && getBoolean(DELAY_RESENDS, false);
  }

  /**
   * Disable initiating a GV1->GV2 auto-migration. You can still recognize a group has been
   * auto-migrated.
   */
  public synchronized boolean disableGv1AutoMigrateInitiation() {
    return FeatureFlags.internalUser() && getBoolean(GV2_DISABLE_AUTOMIGRATE_INITIATION, false);
  }

  /**
   * Disable sending a group update after an automigration. This will force other group members to
   * have to discover the migration on their own.
   */
  public synchronized boolean disableGv1AutoMigrateNotification() {
    return FeatureFlags.internalUser() && getBoolean(GV2_DISABLE_AUTOMIGRATE_NOTIFICATION, false);
  }

  /**
   * Whether or not "shake to report" is enabled.
   */
  public synchronized boolean shakeToReport() {
    return FeatureFlags.internalUser() && getBoolean(SHAKE_TO_REPORT, true);
  }
}