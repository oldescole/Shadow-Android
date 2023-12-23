package su.sres.securesms.notifications.v2

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import com.bumptech.glide.load.engine.DiskCacheStrategy
import su.sres.securesms.R
import su.sres.securesms.contacts.avatars.ContactPhoto
import su.sres.securesms.contacts.avatars.FallbackContactPhoto
import su.sres.securesms.contacts.avatars.GeneratedContactPhoto
import su.sres.securesms.contacts.avatars.ProfileContactPhoto
import su.sres.securesms.mms.DecryptableStreamUriLoader.DecryptableUri
import su.sres.securesms.mms.GlideApp
import su.sres.securesms.recipients.Recipient
import su.sres.securesms.util.BitmapUtil
import java.util.concurrent.ExecutionException

fun Drawable?.toLargeBitmap(context: Context): Bitmap? {
  if (this == null) {
    return null
  }

  val largeIconTargetSize: Int = context.resources.getDimensionPixelSize(R.dimen.contact_photo_target_size)

  return BitmapUtil.createFromDrawable(this, largeIconTargetSize, largeIconTargetSize)
}

fun Recipient.getContactDrawable(context: Context): Drawable? {
  val contactPhoto: ContactPhoto? = if (isSelf) ProfileContactPhoto(this, profileAvatar) else contactPhoto
  val fallbackContactPhoto: FallbackContactPhoto = if (isSelf) getFallback(context) else fallbackContactPhoto
  return if (contactPhoto != null) {
    try {
      GlideApp.with(context.applicationContext)
        .load(contactPhoto)
        .diskCacheStrategy(DiskCacheStrategy.ALL)
        .circleCrop()
        .submit(
          context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width),
          context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
        )
        .get()
    } catch (e: InterruptedException) {
      fallbackContactPhoto.asDrawable(context, color.toConversationColor(context))
    } catch (e: ExecutionException) {
      fallbackContactPhoto.asDrawable(context, color.toConversationColor(context))
    }
  } else {
    fallbackContactPhoto.asDrawable(context, color.toConversationColor(context))
  }
}

fun Uri.toBitmap(context: Context, dimension: Int): Bitmap {
  return try {
    GlideApp.with(context.applicationContext)
      .asBitmap()
      .load(DecryptableUri(this))
      .diskCacheStrategy(DiskCacheStrategy.NONE)
      .submit(dimension, dimension)
      .get()
  } catch (e: InterruptedException) {
    Bitmap.createBitmap(dimension, dimension, Bitmap.Config.RGB_565)
  } catch (e: ExecutionException) {
    Bitmap.createBitmap(dimension, dimension, Bitmap.Config.RGB_565)
  }
}

fun Intent.makeUniqueToPreventMerging(): Intent {
  return setData((Uri.parse("custom://" + System.currentTimeMillis())))
}

fun Recipient.getFallback(context: Context): FallbackContactPhoto {
  return GeneratedContactPhoto(getDisplayName(context), R.drawable.ic_profile_outline_40)
}