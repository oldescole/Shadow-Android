package su.sres.securesms.components.settings.app.data

import androidx.lifecycle.ViewModelProviders
import androidx.navigation.Navigation
import androidx.preference.PreferenceManager
import su.sres.securesms.R
import su.sres.securesms.components.settings.DSLConfiguration
import su.sres.securesms.components.settings.DSLSettingsAdapter
import su.sres.securesms.components.settings.DSLSettingsFragment
import su.sres.securesms.components.settings.DSLSettingsText
import su.sres.securesms.components.settings.configure
import su.sres.securesms.util.Util
import su.sres.securesms.webrtc.CallBandwidthMode
import kotlin.math.abs

class DataAndStorageSettingsFragment : DSLSettingsFragment(R.string.preferences__data_and_storage) {

  private val autoDownloadValues by lazy { resources.getStringArray(R.array.pref_media_download_entries) }
  private val autoDownloadLabels by lazy { resources.getStringArray(R.array.pref_media_download_values) }

  private val callBandwidthLabels by lazy { resources.getStringArray(R.array.pref_data_and_storage_call_bandwidth_values) }

  private lateinit var viewModel: DataAndStorageSettingsViewModel

  override fun onResume() {
    super.onResume()
    viewModel.refresh()
  }

  override fun bindAdapter(adapter: DSLSettingsAdapter) {
    val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
    val repository = DataAndStorageSettingsRepository()
    val factory = DataAndStorageSettingsViewModel.Factory(preferences, repository)
    viewModel = ViewModelProviders.of(this, factory)[DataAndStorageSettingsViewModel::class.java]

    viewModel.state.observe(viewLifecycleOwner) {
      adapter.submitList(getConfiguration(it).toMappingModelList())
    }
  }

  fun getConfiguration(state: DataAndStorageSettingsState): DSLConfiguration {
    return configure {
      clickPref(
        title = DSLSettingsText.from(R.string.preferences_data_and_storage__manage_storage),
        summary = DSLSettingsText.from(Util.getPrettyFileSize(state.totalStorageUse)),
        onClick = {
          Navigation.findNavController(requireView()).navigate(R.id.action_dataAndStorageSettingsFragment_to_storagePreferenceFragment)
        }
      )

      dividerPref()

      sectionHeaderPref(R.string.preferences_chats__media_auto_download)

      multiSelectPref(
        title = DSLSettingsText.from(R.string.preferences_chats__when_using_mobile_data),
        listItems = autoDownloadLabels,
        selected = autoDownloadValues.map { state.mobileAutoDownloadValues.contains(it) }.toBooleanArray(),
        onSelected = {
          val resultSet = it.mapIndexed { index, selected -> if (selected) autoDownloadValues[index] else null }.filterNotNull().toSet()
          viewModel.setMobileAutoDownloadValues(resultSet)
        }
      )

      multiSelectPref(
        title = DSLSettingsText.from(R.string.preferences_chats__when_using_wifi),
        listItems = autoDownloadLabels,
        selected = autoDownloadValues.map { state.wifiAutoDownloadValues.contains(it) }.toBooleanArray(),
        onSelected = {
          val resultSet = it.mapIndexed { index, selected -> if (selected) autoDownloadValues[index] else null }.filterNotNull().toSet()
          viewModel.setWifiAutoDownloadValues(resultSet)
        }
      )

      multiSelectPref(
        title = DSLSettingsText.from(R.string.preferences_chats__when_roaming),
        listItems = autoDownloadLabels,
        selected = autoDownloadValues.map { state.roamingAutoDownloadValues.contains(it) }.toBooleanArray(),
        onSelected = {
          val resultSet = it.mapIndexed { index, selected -> if (selected) autoDownloadValues[index] else null }.filterNotNull().toSet()
          viewModel.setRoamingAutoDownloadValues(resultSet)
        }
      )

      dividerPref()

      sectionHeaderPref(R.string.DataAndStorageSettingsFragment__calls)

      radioListPref(
        title = DSLSettingsText.from(R.string.preferences_data_and_storage__use_less_data_for_calls),
        listItems = callBandwidthLabels,
        selected = abs(state.callBandwidthMode.code - 2),
        onSelected = {
          viewModel.setCallBandwidthMode(CallBandwidthMode.fromCode(abs(it - 2)))
        }
      )

      textPref(
        summary = DSLSettingsText.from(R.string.preference_data_and_storage__using_less_data_may_improve_calls_on_bad_networks)
      )

      dividerPref()

      sectionHeaderPref(R.string.preferences_proxy)

      clickPref(
        title = DSLSettingsText.from(R.string.preferences_use_proxy),
        summary = DSLSettingsText.from(if (state.isProxyEnabled) R.string.preferences_on else R.string.preferences_off),
        onClick = {
          Navigation.findNavController(requireView()).navigate(R.id.action_dataAndStorageSettingsFragment_to_editProxyFragment)
        }
      )

      dividerPref()

      sectionHeaderPref(R.string.preferences_updates)

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__update_in_roaming),
        summary = DSLSettingsText.from(R.string.preferences__allow_shadow_update_in_roaming),
        isChecked = state.updateInRoaming,
        onClick = {
          viewModel.setUpdateInRoamingEnabled(!state.updateInRoaming)
        }
      )

    }
  }
}