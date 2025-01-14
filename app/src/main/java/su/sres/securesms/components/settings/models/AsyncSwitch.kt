package su.sres.securesms.components.settings.models

import android.view.View
import android.widget.ViewSwitcher
import com.google.android.material.switchmaterial.SwitchMaterial
import su.sres.securesms.R
import su.sres.securesms.components.settings.DSLSettingsText
import su.sres.securesms.components.settings.PreferenceModel
import su.sres.securesms.components.settings.PreferenceViewHolder
import su.sres.securesms.util.MappingAdapter

/**
 * Switch that will perform a long-running async operation (normally network) that requires a
 * progress spinner to replace the switch after a press.
 */
object AsyncSwitch {

  fun register(adapter: MappingAdapter) {
    adapter.registerFactory(Model::class.java, MappingAdapter.LayoutFactory(AsyncSwitch::ViewHolder, R.layout.dsl_async_switch_preference_item))
  }

  class Model(
    override val title: DSLSettingsText,
    override val isEnabled: Boolean,
    val isChecked: Boolean,
    val isProcessing: Boolean,
    val onClick: () -> Unit
  ) : PreferenceModel<Model>() {
    override fun areContentsTheSame(newItem: Model): Boolean {
      return super.areContentsTheSame(newItem) && isChecked == newItem.isChecked && isProcessing == newItem.isProcessing
    }
  }

  class ViewHolder(itemView: View) : PreferenceViewHolder<Model>(itemView) {
    private val switchWidget: SwitchMaterial = itemView.findViewById(R.id.switch_widget)
    private val switcher: ViewSwitcher = itemView.findViewById(R.id.switcher)

    override fun bind(model: Model) {
      super.bind(model)
      switchWidget.isEnabled = model.isEnabled
      switchWidget.isChecked = model.isChecked
      itemView.isEnabled = !model.isProcessing && model.isEnabled
      switcher.displayedChild = if (model.isProcessing) 1 else 0

      itemView.setOnClickListener {
        if (!model.isProcessing) {
          itemView.isEnabled = false
          switcher.displayedChild = 1
          model.onClick()
        }
      }
    }
  }
}