package su.sres.securesms.keyvalue;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceDataStore;

import java.util.ArrayList;
import java.util.List;

import su.sres.securesms.database.KeyValueDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.util.SignalUncaughtExceptionHandler;

/**
 * Simple, encrypted key-value store.
 */
public final class SignalStore {

  private KeyValueStore store;

  private final AccountValues            accountValues;
  private final KbsValues                  kbsValues;
  private final RegistrationValues         registrationValues;
  private final RemoteConfigValues         remoteConfigValues;
  private final ServiceConfigurationValues serviceConfigValues;
  private final StorageServiceValues       storageServiceValues;
  private final UiHints                    uiHints;
  private final TooltipValues              tooltipValues;
  private final MiscellaneousValues        misc;
  private final InternalValues             internalValues;
  private final EmojiValues                emojiValues;
  private final SettingsValues             settingsValues;
  private final CertificateValues          certificateValues;
  private final UserLoginPrivacyValues     userLoginPrivacyValues;
  private final OnboardingValues           onboardingValues;
  private final WallpaperValues            wallpaperValues;
  private final PaymentsValues             paymentsValues;
  private final DonationsValues          donationsValues;
  private final ProxyValues                proxyValues;
  private final RateLimitValues            rateLimitValues;
  private final ChatColorsValues           chatColorsValues;
  private final ImageEditorValues        imageEditorValues;

  private static volatile SignalStore instance;

  private static @NonNull SignalStore getInstance() {
    if (instance == null) {
      synchronized (SignalStore.class) {
        if (instance == null) {
          instance = new SignalStore(new KeyValueStore(KeyValueDatabase.getInstance(ApplicationDependencies.getApplication())));
        }
      }
    }

    return instance;
  }

  private SignalStore(@NonNull KeyValueStore store) {
    this.store                    = store;
    this.accountValues            = new AccountValues(store);
    this.kbsValues              = new KbsValues(store);
    this.registrationValues     = new RegistrationValues(store);
    this.remoteConfigValues     = new RemoteConfigValues(store);
    this.serviceConfigValues    = new ServiceConfigurationValues(store);
    this.storageServiceValues   = new StorageServiceValues(store);
    this.uiHints                = new UiHints(store);
    this.tooltipValues          = new TooltipValues(store);
    this.misc                   = new MiscellaneousValues(store);
    this.internalValues         = new InternalValues(store);
    this.emojiValues            = new EmojiValues(store);
    this.settingsValues         = new SettingsValues(store);
    this.certificateValues      = new CertificateValues(store);
    this.userLoginPrivacyValues = new UserLoginPrivacyValues(store);
    this.onboardingValues       = new OnboardingValues(store);
    this.wallpaperValues        = new WallpaperValues(store);
    this.paymentsValues         = new PaymentsValues(store);
    this.donationsValues          = new DonationsValues(store);
    this.proxyValues            = new ProxyValues(store);
    this.rateLimitValues        = new RateLimitValues(store);
    this.chatColorsValues       = new ChatColorsValues(store);
    this.imageEditorValues        = new ImageEditorValues(store);
  }

  public static void onFirstEverAppLaunch() {
    account().onFirstEverAppLaunch();
    kbsValues().onFirstEverAppLaunch();
    registrationValues().onFirstEverAppLaunch();
    remoteConfigValues().onFirstEverAppLaunch();
    serviceConfigurationValues().onFirstEverAppLaunch();
    storageService().onFirstEverAppLaunch();
    uiHints().onFirstEverAppLaunch();
    tooltips().onFirstEverAppLaunch();
    misc().onFirstEverAppLaunch();
    internalValues().onFirstEverAppLaunch();
    emojiValues().onFirstEverAppLaunch();
    settings().onFirstEverAppLaunch();
    certificateValues().onFirstEverAppLaunch();
    userLoginPrivacy().onFirstEverAppLaunch();
    onboarding().onFirstEverAppLaunch();
    wallpaper().onFirstEverAppLaunch();
    paymentsValues().onFirstEverAppLaunch();
    donationsValues().onFirstEverAppLaunch();
    proxy().onFirstEverAppLaunch();
    rateLimit().onFirstEverAppLaunch();
    chatColorsValues().onFirstEverAppLaunch();
    imageEditorValues().onFirstEverAppLaunch();
  }

  public static List<String> getKeysToIncludeInBackup() {
    List<String> keys = new ArrayList<>();
    keys.addAll(account().getKeysToIncludeInBackup());
    keys.addAll(kbsValues().getKeysToIncludeInBackup());
    keys.addAll(registrationValues().getKeysToIncludeInBackup());
    keys.addAll(remoteConfigValues().getKeysToIncludeInBackup());
    keys.addAll(storageService().getKeysToIncludeInBackup());
    keys.addAll(uiHints().getKeysToIncludeInBackup());
    keys.addAll(tooltips().getKeysToIncludeInBackup());
    keys.addAll(misc().getKeysToIncludeInBackup());
    keys.addAll(internalValues().getKeysToIncludeInBackup());
    keys.addAll(emojiValues().getKeysToIncludeInBackup());
    keys.addAll(settings().getKeysToIncludeInBackup());
    keys.addAll(certificateValues().getKeysToIncludeInBackup());
    keys.addAll(userLoginPrivacy().getKeysToIncludeInBackup());
    keys.addAll(onboarding().getKeysToIncludeInBackup());
    keys.addAll(wallpaper().getKeysToIncludeInBackup());
    keys.addAll(paymentsValues().getKeysToIncludeInBackup());
    keys.addAll(donationsValues().getKeysToIncludeInBackup());
    keys.addAll(proxy().getKeysToIncludeInBackup());
    keys.addAll(rateLimit().getKeysToIncludeInBackup());
    keys.addAll(chatColorsValues().getKeysToIncludeInBackup());
    keys.addAll(serviceConfigurationValues().getKeysToIncludeInBackup());
    keys.addAll(imageEditorValues().getKeysToIncludeInBackup());
    return keys;
  }

  /**
   * Forces the store to re-fetch all of it's data from the database.
   * Should only be used for testing!
   */
  @VisibleForTesting
  public static void resetCache() {
    getInstance().store.resetCache();
  }

  public static @NonNull AccountValues account() {
    return getInstance().accountValues;
  }

  public static @NonNull
  KbsValues kbsValues() {
    return getInstance().kbsValues;
  }

  public static @NonNull
  RegistrationValues registrationValues() {
    return getInstance().registrationValues;
  }

  public static @NonNull
  ServiceConfigurationValues serviceConfigurationValues() {
    return getInstance().serviceConfigValues;
  }

  public static @NonNull
  RemoteConfigValues remoteConfigValues() {
    return getInstance().remoteConfigValues;
  }

  public static @NonNull StorageServiceValues storageService() {
    return getInstance().storageServiceValues;
  }

  public static @NonNull
  UiHints uiHints() {
    return getInstance().uiHints;
  }

  public static @NonNull
  TooltipValues tooltips() {
    return getInstance().tooltipValues;
  }

  public static @NonNull
  MiscellaneousValues misc() {
    return getInstance().misc;
  }

  public static @NonNull
  InternalValues internalValues() {
    return getInstance().internalValues;
  }

  public static @NonNull
  EmojiValues emojiValues() {
    return getInstance().emojiValues;
  }

  public static @NonNull
  SettingsValues settings() {
    return getInstance().settingsValues;
  }

  public static @NonNull
  CertificateValues certificateValues() {
    return getInstance().certificateValues;
  }

  public static @NonNull
  UserLoginPrivacyValues userLoginPrivacy() {
    return getInstance().userLoginPrivacyValues;
  }

  public static @NonNull OnboardingValues onboarding() {
    return getInstance().onboardingValues;
  }

  public static @NonNull WallpaperValues wallpaper() {
    return getInstance().wallpaperValues;
  }

  public static @NonNull PaymentsValues paymentsValues() {
    return getInstance().paymentsValues;
  }

  public static @NonNull DonationsValues donationsValues() {
    return getInstance().donationsValues;
  }

  public static @NonNull ProxyValues proxy() {
    return getInstance().proxyValues;
  }

  public static @NonNull RateLimitValues rateLimit() {
    return getInstance().rateLimitValues;
  }

  public static @NonNull ChatColorsValues chatColorsValues() {
    return getInstance().chatColorsValues;
  }

  public static @NonNull ImageEditorValues imageEditorValues() {
    return getInstance().imageEditorValues;
  }

  public static @NonNull
  GroupsV2AuthorizationSignalStoreCache groupsV2AuthorizationCache() {
    return new GroupsV2AuthorizationSignalStoreCache(getStore());
  }

  public static @NonNull
  PreferenceDataStore getPreferenceDataStore() {
    return new SignalPreferenceDataStore(getStore());
  }

  /**
   * Ensures any pending writes are finished. Only intended to be called by
   * {@link SignalUncaughtExceptionHandler}.
   */
  public static void blockUntilAllWritesFinished() {
    getStore().blockUntilAllWritesFinished();
  }

  private static @NonNull
  KeyValueStore getStore() {
    return getInstance().store;
  }

  /**
   * Allows you to set a custom KeyValueStore to read from. Only for testing!
   */
  @VisibleForTesting
  public static void inject(@NonNull KeyValueStore store) {
    instance = new SignalStore(store);
  }
}