package su.sres.securesms.components.settings.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.navigation.NavDirections
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.Subject
import su.sres.securesms.MainActivity
import su.sres.securesms.R
import su.sres.securesms.components.settings.DSLSettingsActivity
import su.sres.securesms.components.settings.app.subscription.DonationPaymentComponent
import su.sres.securesms.components.settings.app.subscription.DonationPaymentRepository
import su.sres.securesms.components.settings.app.subscription.SubscriptionsRepository
import su.sres.securesms.components.settings.app.subscription.boost.BoostRepository
import su.sres.securesms.components.settings.app.subscription.boost.BoostViewModel
import su.sres.securesms.components.settings.app.subscription.subscribe.SubscribeViewModel
import su.sres.securesms.dependencies.ApplicationDependencies
import su.sres.securesms.help.HelpFragment
import su.sres.securesms.keyvalue.SettingsValues
import su.sres.securesms.keyvalue.SignalStore
import su.sres.securesms.service.KeyCachingService
import su.sres.securesms.util.CachedInflater
import su.sres.securesms.util.DynamicTheme
import su.sres.securesms.util.FeatureFlags

private const val START_LOCATION = "app.settings.start.location"
private const val NOTIFICATION_CATEGORY = "android.intent.category.NOTIFICATION_PREFERENCES"
private const val STATE_WAS_CONFIGURATION_UPDATED = "app.settings.state.configuration.updated"

class AppSettingsActivity : DSLSettingsActivity(), DonationPaymentComponent {

  private var wasConfigurationUpdated = false

  override val donationPaymentRepository: DonationPaymentRepository by lazy { DonationPaymentRepository(this) }
  override val googlePayResultPublisher: Subject<DonationPaymentComponent.GooglePayResult> = PublishSubject.create()

  override fun onCreate(savedInstanceState: Bundle?, ready: Boolean) {

    if (intent?.hasExtra(ARG_NAV_GRAPH) != true) {
      intent?.putExtra(ARG_NAV_GRAPH, R.navigation.app_settings)
    }

    super.onCreate(savedInstanceState, ready)

    val startingAction: NavDirections? = if (intent?.categories?.contains(NOTIFICATION_CATEGORY) == true) {
      AppSettingsFragmentDirections.actionDirectToNotificationsSettingsFragment()
    } else {
      when (StartLocation.fromCode(intent?.getIntExtra(START_LOCATION, StartLocation.HOME.code))) {
        StartLocation.HOME -> null
        StartLocation.BACKUPS -> AppSettingsFragmentDirections.actionDirectToBackupsPreferenceFragment()
        StartLocation.HELP -> AppSettingsFragmentDirections.actionDirectToHelpFragment()
          .setStartCategoryIndex(intent.getIntExtra(HelpFragment.START_CATEGORY_INDEX, 0))
        StartLocation.PROXY -> AppSettingsFragmentDirections.actionDirectToEditProxyFragment()
        StartLocation.NOTIFICATIONS -> AppSettingsFragmentDirections.actionDirectToNotificationsSettingsFragment()
        StartLocation.CHANGE_USER_LOGIN -> AppSettingsFragmentDirections.actionDirectToChangeUserLoginFragment()
        StartLocation.SUBSCRIPTIONS -> AppSettingsFragmentDirections.actionDirectToSubscriptions()
        StartLocation.BOOST -> AppSettingsFragmentDirections.actionAppSettingsFragmentToBoostsFragment()
        StartLocation.MANAGE_SUBSCRIPTIONS -> AppSettingsFragmentDirections.actionDirectToManageDonations()
      }
    }

    if (startingAction == null && savedInstanceState != null) {
      wasConfigurationUpdated = savedInstanceState.getBoolean(STATE_WAS_CONFIGURATION_UPDATED)
    }

    startingAction?.let {
      navController.navigate(it)
    }

    SignalStore.settings().onConfigurationSettingChanged.observe(this) { key ->
      if (key == SettingsValues.THEME) {
        DynamicTheme.setDefaultDayNightMode(this)
        recreate()
      } else if (key == SettingsValues.LANGUAGE) {
        CachedInflater.from(this).clear()
        wasConfigurationUpdated = true
        recreate()
        val intent = Intent(this, KeyCachingService::class.java)
        intent.action = KeyCachingService.LOCALE_CHANGE_EVENT
        startService(intent)
      }
    }
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    finish()
    startActivity(intent)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.putBoolean(STATE_WAS_CONFIGURATION_UPDATED, wasConfigurationUpdated)
  }

  override fun onWillFinish() {
    if (wasConfigurationUpdated) {
      setResult(MainActivity.RESULT_CONFIG_CHANGED)
    } else {
      setResult(RESULT_OK)
    }
  }

  override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    googlePayResultPublisher.onNext(DonationPaymentComponent.GooglePayResult(requestCode, resultCode, data))
  }

  companion object {

    @JvmStatic
    fun home(context: Context): Intent = getIntentForStartLocation(context, StartLocation.HOME)

    @JvmStatic
    fun backups(context: Context): Intent = getIntentForStartLocation(context, StartLocation.BACKUPS)

    @JvmStatic
    fun help(context: Context, startCategoryIndex: Int = 0): Intent {
      return getIntentForStartLocation(context, StartLocation.HELP)
        .putExtra(HelpFragment.START_CATEGORY_INDEX, startCategoryIndex)
    }

    @JvmStatic
    fun proxy(context: Context): Intent = getIntentForStartLocation(context, StartLocation.PROXY)

    @JvmStatic
    fun notifications(context: Context): Intent = getIntentForStartLocation(context, StartLocation.NOTIFICATIONS)

    @JvmStatic
    fun changeUserLogin(context: Context): Intent = getIntentForStartLocation(context, StartLocation.CHANGE_USER_LOGIN)

    @JvmStatic
    fun subscriptions(context: Context): Intent = getIntentForStartLocation(context, StartLocation.SUBSCRIPTIONS)

    @JvmStatic
    fun boost(context: Context): Intent = getIntentForStartLocation(context, StartLocation.BOOST)

    @JvmStatic
    fun manageSubscriptions(context: Context): Intent = getIntentForStartLocation(context, StartLocation.MANAGE_SUBSCRIPTIONS)

    private fun getIntentForStartLocation(context: Context, startLocation: StartLocation): Intent {
      return Intent(context, AppSettingsActivity::class.java)
        .putExtra(ARG_NAV_GRAPH, R.navigation.app_settings)
        .putExtra(START_LOCATION, startLocation.code)
    }
  }

  private enum class StartLocation(val code: Int) {
    HOME(0),
    BACKUPS(1),
    HELP(2),
    PROXY(3),
    NOTIFICATIONS(4),
    CHANGE_USER_LOGIN(5),
    SUBSCRIPTIONS(6),
    BOOST(7),
    MANAGE_SUBSCRIPTIONS(8);

    companion object {
      fun fromCode(code: Int?): StartLocation {
        return values().find { code == it.code } ?: HOME
      }
    }
  }
}