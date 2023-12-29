package su.sres.securesms.notifications.v2

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.text.SpannableStringBuilder
import androidx.core.app.TaskStackBuilder
import su.sres.securesms.R
import su.sres.securesms.contacts.avatars.ContactColors
import su.sres.securesms.contacts.avatars.GeneratedContactPhoto
import su.sres.securesms.conversation.ConversationIntents
import su.sres.securesms.notifications.DeleteNotificationReceiver
import su.sres.securesms.notifications.MarkReadReceiver
import su.sres.securesms.notifications.NotificationChannels
import su.sres.securesms.notifications.NotificationIds
import su.sres.securesms.notifications.RemoteReplyReceiver
import su.sres.securesms.notifications.ReplyMethod
import su.sres.securesms.preferences.widgets.NotificationPrivacyPreference
import su.sres.securesms.recipients.Recipient
import su.sres.securesms.service.KeyCachingService
import su.sres.securesms.util.TextSecurePreferences
import su.sres.securesms.util.Util

private const val LARGE_ICON_DIMEN = 250

/**
 * Encapsulate all the notifications for a given conversation (thread) and the top
 * level information about said conversation.
 */
class NotificationConversation(
  val recipient: Recipient,
  val threadId: Long,
  val notificationItems: List<NotificationItemV2>
) {

  val mostRecentNotification: NotificationItemV2 = notificationItems.last()
  val notificationId: Int = NotificationIds.getNotificationIdForThread(threadId)
  val sortKey: Long = Long.MAX_VALUE - mostRecentNotification.timestamp
  val messageCount: Int = notificationItems.size
  val isGroup: Boolean = recipient.isGroup

  fun getContentTitle(context: Context): CharSequence {
    return if (TextSecurePreferences.getNotificationPrivacy(context).isDisplayContact) {
      recipient.getDisplayName(context)
    } else {
      context.getString(R.string.SingleRecipientNotificationBuilder_signal)
    }
  }

  fun getLargeIcon(context: Context): Bitmap? {
    if (TextSecurePreferences.getNotificationPrivacy(context).isDisplayMessage) {
      val largeIconUri: Uri? = getSlideLargeIcon()
      if (largeIconUri != null) {
        return largeIconUri.toBitmap(context, LARGE_ICON_DIMEN)
      }
    }

    return getContactLargeIcon(context).toLargeBitmap(context)
  }

  private fun getContactLargeIcon(context: Context): Drawable? {
    return if (TextSecurePreferences.getNotificationPrivacy(context).isDisplayContact) {
      recipient.getContactDrawable(context)
    } else {
      GeneratedContactPhoto("Unknown", R.drawable.ic_profile_outline_40).asDrawable(context, ContactColors.UNKNOWN_COLOR.toConversationColor(context))
    }
  }

  fun getContactUri(context: Context): String? {
    return if (TextSecurePreferences.getNotificationPrivacy(context).isDisplayContact) {
      recipient.contactUri?.toString()
    } else {
      null
    }
  }

  private fun getSlideLargeIcon(): Uri? {
    return if (notificationItems.size == 1) mostRecentNotification.getLargeIconUri() else null
  }

  fun getSlideBigPictureUri(context: Context): Uri? {
    return if (notificationItems.size == 1 && TextSecurePreferences.getNotificationPrivacy(context).isDisplayMessage && !KeyCachingService.isLocked(context)) {
      mostRecentNotification.getBigPictureUri()
    } else {
      null
    }
  }

  fun getContentText(context: Context): CharSequence? {
    val privacy: NotificationPrivacyPreference = TextSecurePreferences.getNotificationPrivacy(context)
    val stringBuilder = SpannableStringBuilder()

    if (privacy.isDisplayContact && recipient.isGroup) {
      stringBuilder.append(Util.getBoldedString(mostRecentNotification.individualRecipient.getDisplayName(context) + ": "))
    }

    return if (privacy.isDisplayMessage) {
      stringBuilder.append(mostRecentNotification.getPrimaryText(context))
    } else {
      stringBuilder.append(context.getString(R.string.SingleRecipientNotificationBuilder_new_message))
    }
  }

  fun getConversationTitle(context: Context): CharSequence? {
    if (isGroup) {
      return if (TextSecurePreferences.getNotificationPrivacy(context).isDisplayContact) {
        recipient.getDisplayName(context)
      } else {
        context.getString(R.string.SingleRecipientNotificationBuilder_signal)
      }
    }
    return null
  }

  fun getWhen(): Long {
    return mostRecentNotification.timestamp
  }

  fun hasNewNotifications(): Boolean {
    return notificationItems.any { it.isNewNotification }
  }

  fun getChannelId(context: Context): String {
    return recipient.notificationChannel ?: NotificationChannels.getMessagesChannel(context)
  }

  fun getPendingIntent(context: Context): PendingIntent {
    val intent: Intent = ConversationIntents.createBuilder(context, recipient.id, threadId)
      .withStartingPosition(mostRecentNotification.getStartingPosition(context))
      .build()
      .makeUniqueToPreventMerging()

    return TaskStackBuilder.create(context)
      .addNextIntentWithParentStack(intent)
      .getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)!!
  }

  fun getDeleteIntent(context: Context): PendingIntent? {
    val ids = LongArray(notificationItems.size)
    val mms = BooleanArray(ids.size)
    notificationItems.forEachIndexed { index, notificationItem ->
      ids[index] = notificationItem.id
      mms[index] = notificationItem.isMms
    }

    val intent = Intent(context, DeleteNotificationReceiver::class.java)
      .setAction(DeleteNotificationReceiver.DELETE_NOTIFICATION_ACTION)
      .putExtra(DeleteNotificationReceiver.EXTRA_IDS, ids)
      .putExtra(DeleteNotificationReceiver.EXTRA_MMS, mms)
      .putExtra(DeleteNotificationReceiver.EXTRA_THREAD_IDS, longArrayOf(threadId))
      .makeUniqueToPreventMerging()

    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
  }

  fun getMarkAsReadIntent(context: Context): PendingIntent {
    val intent = Intent(context, MarkReadReceiver::class.java).setAction(MarkReadReceiver.CLEAR_ACTION)
      .putExtra(MarkReadReceiver.THREAD_IDS_EXTRA, longArrayOf(mostRecentNotification.threadId))
      .putExtra(MarkReadReceiver.NOTIFICATION_ID_EXTRA, notificationId)
      .makeUniqueToPreventMerging()

    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
  }

  fun getQuickReplyIntent(context: Context): PendingIntent {
    val intent: Intent = ConversationIntents.createPopUpBuilder(context, recipient.id, mostRecentNotification.threadId)
      .build()
      .makeUniqueToPreventMerging()

    return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
  }

  fun getRemoteReplyIntent(context: Context, replyMethod: ReplyMethod): PendingIntent {
    val intent = Intent(context, RemoteReplyReceiver::class.java)
      .setAction(RemoteReplyReceiver.REPLY_ACTION)
      .putExtra(RemoteReplyReceiver.RECIPIENT_EXTRA, recipient.id)
      .putExtra(RemoteReplyReceiver.REPLY_METHOD, replyMethod)
      .putExtra(RemoteReplyReceiver.EARLIEST_TIMESTAMP, notificationItems.first().timestamp)
      .setPackage(context.packageName)
      .makeUniqueToPreventMerging()

    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT)
  }

  /* fun getTurnOffJoinedNotificationsIntent(context: Context): PendingIntent {
    return PendingIntent.getActivity(
      context,
      0,
      TurnOffContactJoinedNotificationsActivity.newIntent(context, threadId),
      PendingIntent.FLAG_UPDATE_CURRENT
    )
  } */

  override fun toString(): String {
    return "NotificationConversation(threadId=$threadId, notificationItems=$notificationItems, messageCount=$messageCount, hasNewNotifications=${hasNewNotifications()})"
  }
}