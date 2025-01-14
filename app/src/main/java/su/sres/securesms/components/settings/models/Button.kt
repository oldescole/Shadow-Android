package su.sres.securesms.components.settings.models

import android.view.View
import com.google.android.material.button.MaterialButton
import su.sres.securesms.R
import su.sres.securesms.components.settings.DSLSettingsIcon
import su.sres.securesms.components.settings.DSLSettingsText
import su.sres.securesms.components.settings.PreferenceModel
import su.sres.securesms.util.MappingAdapter
import su.sres.securesms.util.MappingViewHolder

object Button {

  fun register(mappingAdapter: MappingAdapter) {
    mappingAdapter.registerFactory(Model.Primary::class.java, MappingAdapter.LayoutFactory({ ViewHolder(it) as MappingViewHolder<Model.Primary> }, R.layout.dsl_button_primary))
    mappingAdapter.registerFactory(Model.SecondaryNoOutline::class.java, MappingAdapter.LayoutFactory({ ViewHolder(it) as MappingViewHolder<Model.SecondaryNoOutline> }, R.layout.dsl_button_secondary))
  }

  sealed class Model<T : Model<T>>(
    title: DSLSettingsText?,
    icon: DSLSettingsIcon?,
    isEnabled: Boolean,
    val onClick: () -> Unit
  ) : PreferenceModel<T>(
    title = title,
    icon = icon,
    isEnabled = isEnabled
  ) {
    class Primary(
      title: DSLSettingsText?,
      icon: DSLSettingsIcon?,
      isEnabled: Boolean,
      onClick: () -> Unit
    ) : Model<Primary>(title, icon, isEnabled, onClick)

    class SecondaryNoOutline(
      title: DSLSettingsText?,
      icon: DSLSettingsIcon?,
      isEnabled: Boolean,
      onClick: () -> Unit
    ) : Model<SecondaryNoOutline>(title, icon, isEnabled, onClick)
  }

  class ViewHolder(itemView: View) : MappingViewHolder<Model<*>>(itemView) {

    private val button: MaterialButton = itemView as MaterialButton

    override fun bind(model: Model<*>) {
      button.text = model.title?.resolve(context)
      button.setOnClickListener {
        model.onClick()
      }
      button.icon = model.icon?.resolve(context)
      button.isEnabled = model.isEnabled
    }
  }
}