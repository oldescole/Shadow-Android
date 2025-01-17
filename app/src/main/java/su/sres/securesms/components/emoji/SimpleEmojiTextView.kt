package su.sres.securesms.components.emoji

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import su.sres.securesms.keyvalue.SignalStore
import org.whispersystems.libsignal.util.guava.Optional
import su.sres.securesms.util.ThrottledDebouncer

open class SimpleEmojiTextView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

  private var bufferType: BufferType? = null
  private val sizeChangeDebouncer: ThrottledDebouncer = ThrottledDebouncer(200)

  override fun setText(text: CharSequence?, type: BufferType?) {
    bufferType = type
    val candidates = if (isInEditMode) null else EmojiProvider.getCandidates(text)
    if (SignalStore.settings().isPreferSystemEmoji || candidates == null || candidates.size() == 0) {
      super.setText(Optional.fromNullable(text).or(""), type)
    } else {
      val startDrawableSize: Int = compoundDrawables[0]?.let { it.intrinsicWidth + compoundDrawablePadding } ?: 0
      val endDrawableSize: Int = compoundDrawables[1]?.let { it.intrinsicWidth + compoundDrawablePadding } ?: 0
      val adjustedWidth: Int = width - startDrawableSize - endDrawableSize

      val newContent = if (width == 0 || maxLines == -1) {
        text
      } else {
        TextUtils.ellipsize(text, paint, (adjustedWidth * maxLines).toFloat(), TextUtils.TruncateAt.END, false, null)
      }

      val newCandidates = if (isInEditMode) null else EmojiProvider.getCandidates(newContent)
      val newText = if (newCandidates == null || newCandidates.size() == 0) {
        newContent
      } else {
        EmojiProvider.emojify(newCandidates, newContent, this)
      }
      bufferType = BufferType.SPANNABLE
      super.setText(newText, type)
    }
  }

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    super.onSizeChanged(width, height, oldWidth, oldHeight)
    sizeChangeDebouncer.publish {
      if (width > 0 && oldWidth != width) {
        setText(text, bufferType ?: BufferType.SPANNABLE)
      }
    }
  }
}