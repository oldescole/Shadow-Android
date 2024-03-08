package su.sres.securesms.components.settings.app.internal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.widget.Toast
import androidx.lifecycle.ViewModelProviders
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import su.sres.core.util.concurrent.SignalExecutors
import su.sres.securesms.R
import su.sres.securesms.components.settings.DSLConfiguration
import su.sres.securesms.components.settings.DSLSettingsAdapter
import su.sres.securesms.components.settings.DSLSettingsFragment
import su.sres.securesms.components.settings.DSLSettingsText
import su.sres.securesms.components.settings.configure
import su.sres.securesms.database.DatabaseFactory
import su.sres.securesms.database.LocalMetricsDatabase
import su.sres.securesms.dependencies.ApplicationDependencies
import su.sres.securesms.jobs.RefreshAttributesJob
import su.sres.securesms.jobs.RefreshOwnProfileJob
import su.sres.securesms.jobs.RemoteConfigRefreshJob
import su.sres.securesms.jobs.RotateProfileKeyJob
import su.sres.securesms.jobs.StorageForcePushJob
import su.sres.securesms.payments.DataExportUtil
import su.sres.securesms.util.ConversationUtil
import su.sres.securesms.util.concurrent.SimpleTask

class InternalSettingsFragment : DSLSettingsFragment(R.string.preferences__internal_preferences) {

  private lateinit var viewModel: InternalSettingsViewModel

  override fun bindAdapter(adapter: DSLSettingsAdapter) {
    val repository = InternalSettingsRepository(requireContext())
    val factory = InternalSettingsViewModel.Factory(repository)
    viewModel = ViewModelProviders.of(this, factory)[InternalSettingsViewModel::class.java]

    viewModel.state.observe(viewLifecycleOwner) {
      adapter.submitList(getConfiguration(it).toMappingModelList())
    }
  }

  private fun getConfiguration(state: InternalSettingsState): DSLConfiguration {
    return configure {
      sectionHeaderPref(R.string.preferences__internal_payments)

      clickPref(
        title = DSLSettingsText.from(R.string.preferences__internal_payment_copy_data),
        summary = DSLSettingsText.from(R.string.preferences__internal_payment_copy_data_description),
        onClick = {
          copyPaymentsDataToClipboard()
        }
      )

      dividerPref()

      sectionHeaderPref(R.string.preferences__internal_account)

      clickPref(
        title = DSLSettingsText.from(R.string.preferences__internal_refresh_attributes),
        summary = DSLSettingsText.from(R.string.preferences__internal_refresh_attributes_description),
        onClick = {
          refreshAttributes()
        }
      )

      clickPref(
        title = DSLSettingsText.from(R.string.preferences__internal_rotate_profile_key),
        summary = DSLSettingsText.from(R.string.preferences__internal_rotate_profile_key_description),
        onClick = {
          rotateProfileKey()
        }
      )

      dividerPref()

      sectionHeaderPref(R.string.preferences__internal_display)

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__internal_user_details),
        summary = DSLSettingsText.from(R.string.preferences__internal_user_details_description),
        isChecked = state.seeMoreUserDetails,
        onClick = {
          viewModel.setSeeMoreUserDetails(!state.seeMoreUserDetails)
        }
      )

      dividerPref()

      sectionHeaderPref(R.string.preferences__internal_preferences_groups_v2)

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__internal_do_not_create_gv2),
        summary = DSLSettingsText.from(R.string.preferences__internal_do_not_create_gv2_description),
        isChecked = state.gv2doNotCreateGv2Groups,
        onClick = {
          viewModel.setGv2DoNotCreateGv2Groups(!state.gv2doNotCreateGv2Groups)
        }
      )

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__internal_force_gv2_invites),
        summary = DSLSettingsText.from(R.string.preferences__internal_force_gv2_invites_description),
        isChecked = state.gv2forceInvites,
        onClick = {
          viewModel.setGv2ForceInvites(!state.gv2forceInvites)
        }
      )

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__internal_ignore_gv2_server_changes),
        summary = DSLSettingsText.from(R.string.preferences__internal_ignore_gv2_server_changes_description),
        isChecked = state.gv2ignoreServerChanges,
        onClick = {
          viewModel.setGv2IgnoreServerChanges(!state.gv2ignoreServerChanges)
        }
      )

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__internal_ignore_gv2_p2p_changes),
        summary = DSLSettingsText.from(R.string.preferences__internal_ignore_gv2_server_changes_description),
        isChecked = state.gv2ignoreP2PChanges,
        onClick = {
          viewModel.setGv2IgnoreP2PChanges(!state.gv2ignoreP2PChanges)
        }
      )

      dividerPref()

      sectionHeaderPref(R.string.preferences__internal_preferences_groups_v1_migration)

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__internal_do_not_initiate_automigrate),
        summary = DSLSettingsText.from(R.string.preferences__internal_do_not_initiate_automigrate_description),
        isChecked = state.disableAutoMigrationInitiation,
        onClick = {
          viewModel.setDisableAutoMigrationInitiation(!state.disableAutoMigrationInitiation)
        }
      )

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__internal_do_not_notify_automigrate),
        summary = DSLSettingsText.from(R.string.preferences__internal_do_not_notify_automigrate_description),
        isChecked = state.disableAutoMigrationNotification,
        onClick = {
          viewModel.setDisableAutoMigrationNotification(!state.disableAutoMigrationNotification)
        }
      )

      dividerPref()

      sectionHeaderPref(R.string.preferences__internal_conversations_and_shortcuts)

      clickPref(
        title = DSLSettingsText.from(R.string.preferences__internal_delete_all_dynamic_shortcuts),
        summary = DSLSettingsText.from(R.string.preferences__internal_click_to_delete_all_dynamic_shortcuts),
        onClick = {
          deleteAllDynamicShortcuts()
        }
      )

      dividerPref()

      sectionHeaderPref(R.string.preferences__internal_emoji)

      val emojiSummary = if (state.emojiVersion == null) {
        getString(R.string.preferences__internal_use_built_in_emoji_set)
      } else {
        getString(
          R.string.preferences__internal_current_version_d_at_density_s,
          state.emojiVersion.version,
          state.emojiVersion.density
        )
      }

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__internal_use_built_in_emoji_set),
        summary = DSLSettingsText.from(emojiSummary),
        isChecked = state.useBuiltInEmojiSet,
        onClick = {
          viewModel.setDisableAutoMigrationNotification(!state.useBuiltInEmojiSet)
        }
      )

      dividerPref()

      sectionHeaderPref(R.string.preferences__internal_sender_key)

      clickPref(
        title = DSLSettingsText.from(R.string.preferences__internal_clear_all_state),
        summary = DSLSettingsText.from(R.string.preferences__internal_click_to_delete_all_sender_key_state),
        onClick = {
          clearAllSenderKeyState()
        }
      )

      clickPref(
        title = DSLSettingsText.from(R.string.preferences__internal_clear_shared_state),
        summary = DSLSettingsText.from(R.string.preferences__internal_click_to_delete_all_sharing_state),
        onClick = {
          clearAllSenderKeySharedState()
        }
      )

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__internal_remove_two_person_minimum),
        summary = DSLSettingsText.from(R.string.preferences__internal_remove_the_requirement_that_you_need),
        isChecked = state.removeSenderKeyMinimium,
        onClick = {
          viewModel.setRemoveSenderKeyMinimum(!state.removeSenderKeyMinimium)
        }
      )

      switchPref(
        title = DSLSettingsText.from(R.string.preferences__internal_delay_resends),
        summary = DSLSettingsText.from(R.string.preferences__internal_delay_resending_messages_in_response_to_retry_receipts),
        isChecked = state.delayResends,
        onClick = {
          viewModel.setDelayResends(!state.delayResends)
        }
      )

      dividerPref()

      sectionHeaderPref(R.string.preferences__internal_local_metrics)

      clickPref(
        title = DSLSettingsText.from(R.string.preferences__internal_clear_local_metrics),
        summary = DSLSettingsText.from(R.string.preferences__internal_click_to_clear_all_local_metrics_state),
        onClick = {
          clearAllLocalMetricsState()
        }
      )
    }
  }

  private fun copyPaymentsDataToClipboard() {
    MaterialAlertDialogBuilder(requireContext())
      .setMessage(
        """
    Local payments history will be copied to the clipboard.
    It may therefore compromise privacy.
    However, no private keys will be copied.
        """.trimIndent()
      )
      .setPositiveButton(
        "Copy"
      ) { _: DialogInterface?, _: Int ->
        SimpleTask.run<Any?>(
          SignalExecutors.UNBOUNDED,
          {
            val context: Context = ApplicationDependencies.getApplication()
            val clipboard =
              context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val tsv = DataExportUtil.createTsv()
            val clip = ClipData.newPlainText(context.getString(R.string.app_name), tsv)
            clipboard.setPrimaryClip(clip)
            null
          },
          {
            Toast.makeText(
              context,
              "Payments have been copied",
              Toast.LENGTH_SHORT
            ).show()
          }
        )
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun refreshAttributes() {
    ApplicationDependencies.getJobManager()
      .startChain(RefreshAttributesJob())
      .then(RefreshOwnProfileJob())
      .enqueue()
    Toast.makeText(context, "Scheduled attribute refresh", Toast.LENGTH_SHORT).show()
  }

  private fun rotateProfileKey() {
    ApplicationDependencies.getJobManager().add(RotateProfileKeyJob())
    Toast.makeText(context, "Scheduled profile key rotation", Toast.LENGTH_SHORT).show()
  }

  private fun refreshRemoteValues() {
    ApplicationDependencies.getJobManager().add(RemoteConfigRefreshJob())
    Toast.makeText(context, "Scheduled remote config refresh", Toast.LENGTH_SHORT).show()
  }

  private fun forceStorageServiceSync() {
    ApplicationDependencies.getJobManager().add(StorageForcePushJob())
    Toast.makeText(context, "Scheduled storage force push", Toast.LENGTH_SHORT).show()
  }

  private fun deleteAllDynamicShortcuts() {
    ConversationUtil.clearAllShortcuts(requireContext())
    Toast.makeText(context, "Deleted all dynamic shortcuts.", Toast.LENGTH_SHORT).show()
  }

  private fun clearAllSenderKeyState() {
    DatabaseFactory.getSenderKeyDatabase(requireContext()).deleteAll()
    DatabaseFactory.getSenderKeySharedDatabase(requireContext()).deleteAll()
    Toast.makeText(context, "Deleted all sender key state.", Toast.LENGTH_SHORT).show()
  }

  private fun clearAllSenderKeySharedState() {
    DatabaseFactory.getSenderKeySharedDatabase(requireContext()).deleteAll()
    Toast.makeText(context, "Deleted all sender key shared state.", Toast.LENGTH_SHORT).show()
  }

  private fun clearAllLocalMetricsState() {
    LocalMetricsDatabase.getInstance(ApplicationDependencies.getApplication()).clear()
    Toast.makeText(context, "Cleared all local metrics state.", Toast.LENGTH_SHORT).show()
  }
}