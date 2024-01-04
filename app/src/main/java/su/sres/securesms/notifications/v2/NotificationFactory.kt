package su.sres.securesms.notifications.v2

import android.annotation.TargetApi
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.TransactionTooLargeException
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import su.sres.core.util.concurrent.SignalExecutors
import su.sres.core.util.logging.Log
import su.sres.securesms.MainActivity
import su.sres.securesms.R
import su.sres.securesms.conversation.ConversationIntents
import su.sres.securesms.database.DatabaseFactory
import su.sres.securesms.notifications.DefaultMessageNotifier
import su.sres.securesms.notifications.NotificationChannels
import su.sres.securesms.notifications.NotificationIds
import su.sres.securesms.recipients.Recipient
import su.sres.securesms.util.BubbleUtil
import su.sres.securesms.util.ConversationUtil
import su.sres.securesms.util.FeatureFlags
import su.sres.securesms.util.ServiceUtil
import su.sres.securesms.util.TextSecurePreferences

private val TAG = Log.tag(NotificationFactory::class.java)

/**
 * Given a notification state consisting of conversations of messages, show appropriate system notifications.
 */
object NotificationFactory {

  fun notify(
    context: Context,
    state: NotificationStateV2,
    visibleThreadId: Long,
    targetThreadId: Long,
    defaultBubbleState: BubbleUtil.BubbleState,
    lastAudibleNotification: Long,
    notificationConfigurationChanged: Boolean,
    alertOverrides: Set<Long>,
    previousState: NotificationStateV2
  ): Set<Long> {
    if (state.isEmpty) {
      Log.d(TAG, "State is empty, bailing")
      return emptySet()
    }

    val nonVisibleThreadCount: Int = state.conversations.count { it.threadId != visibleThreadId }
    return if (Build.VERSION.SDK_INT < 24) {
      notify19(
        context = context,
        state = state,
        visibleThreadId = visibleThreadId,
        targetThreadId = targetThreadId,
        defaultBubbleState = defaultBubbleState,
        lastAudibleNotification = lastAudibleNotification,
        alertOverrides = alertOverrides,
        nonVisibleThreadCount = nonVisibleThreadCount
      )
    } else {
      notify24(
        context = context,
        state = state,
        visibleThreadId = visibleThreadId,
        targetThreadId = targetThreadId,
        defaultBubbleState = defaultBubbleState,
        lastAudibleNotification = lastAudibleNotification,
        notificationConfigurationChanged = notificationConfigurationChanged,
        alertOverrides = alertOverrides,
        nonVisibleThreadCount = nonVisibleThreadCount,
        previousState = previousState
      )
    }
  }

  private fun notify19(
    context: Context,
    state: NotificationStateV2,
    visibleThreadId: Long,
    targetThreadId: Long,
    defaultBubbleState: BubbleUtil.BubbleState,
    lastAudibleNotification: Long,
    alertOverrides: Set<Long>,
    nonVisibleThreadCount: Int
  ): Set<Long> {
    val threadsThatNewlyAlerted: MutableSet<Long> = mutableSetOf()

    state.conversations.find { it.threadId == visibleThreadId }?.let { conversation ->
      if (conversation.hasNewNotifications()) {
        Log.internal().i(TAG, "Thread is visible, notifying in thread. notificationId: ${conversation.notificationId}")
        notifyInThread(context, conversation.recipient, lastAudibleNotification)
      }
    }

    if (nonVisibleThreadCount == 1) {
      state.conversations.first { it.threadId != visibleThreadId }.let { conversation ->
        notifyForConversation(
          context = context,
          conversation = conversation,
          targetThreadId = targetThreadId,
          defaultBubbleState = defaultBubbleState,
          shouldAlert = (conversation.hasNewNotifications() || alertOverrides.contains(conversation.threadId)) && !conversation.mostRecentNotification.individualRecipient.isSelf
        )
        if (conversation.hasNewNotifications()) {
          threadsThatNewlyAlerted += conversation.threadId
        }
      }
    } else if (nonVisibleThreadCount > 1) {
      val nonVisibleConversations: List<NotificationConversation> = state.getNonVisibleConversation(visibleThreadId)
      threadsThatNewlyAlerted += nonVisibleConversations.filter { it.hasNewNotifications() }.map { it.threadId }
      notifySummary(context = context, state = state.copy(conversations = nonVisibleConversations))
    }

    return threadsThatNewlyAlerted
  }

  @TargetApi(24)
  private fun notify24(
    context: Context,
    state: NotificationStateV2,
    visibleThreadId: Long,
    targetThreadId: Long,
    defaultBubbleState: BubbleUtil.BubbleState,
    lastAudibleNotification: Long,
    notificationConfigurationChanged: Boolean,
    alertOverrides: Set<Long>,
    nonVisibleThreadCount: Int,
    previousState: NotificationStateV2
  ): Set<Long> {
    val threadsThatNewlyAlerted: MutableSet<Long> = mutableSetOf()

    state.conversations.forEach { conversation ->
      if (conversation.threadId == visibleThreadId && conversation.hasNewNotifications()) {
        Log.internal().i(TAG, "Thread is visible, notifying in thread. notificationId: ${conversation.notificationId}")
        notifyInThread(context, conversation.recipient, lastAudibleNotification)
      } else if (notificationConfigurationChanged || conversation.hasNewNotifications() || alertOverrides.contains(conversation.threadId) || !conversation.hasSameContent(previousState.getConversation(conversation.threadId))) {
        if (conversation.hasNewNotifications()) {
          threadsThatNewlyAlerted += conversation.threadId
        }

        notifyForConversation(
          context = context,
          conversation = conversation,
          targetThreadId = targetThreadId,
          defaultBubbleState = defaultBubbleState,
          shouldAlert = (conversation.hasNewNotifications() || alertOverrides.contains(conversation.threadId)) && !conversation.mostRecentNotification.individualRecipient.isSelf
        )
      }
    }

    if (nonVisibleThreadCount > 1 || ServiceUtil.getNotificationManager(context).isDisplayingSummaryNotification()) {
      notifySummary(context = context, state = state.copy(conversations = state.getNonVisibleConversation(visibleThreadId)))
    }

    return threadsThatNewlyAlerted
  }

  private fun notifyForConversation(
    context: Context,
    conversation: NotificationConversation,
    targetThreadId: Long,
    defaultBubbleState: BubbleUtil.BubbleState,
    shouldAlert: Boolean
  ) {
    if (conversation.notificationItems.isEmpty()) {
      return
    }
    val builder: NotificationBuilder = NotificationBuilder.create(context)

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setColor(ContextCompat.getColor(context, R.color.core_ultramarine))
      setCategory(NotificationCompat.CATEGORY_MESSAGE)
      setGroup(DefaultMessageNotifier.NOTIFICATION_GROUP)
      setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
      setChannelId(conversation.getChannelId(context))
      setContentTitle(conversation.getContentTitle(context))
      setLargeIcon(conversation.getContactLargeIcon(context).toLargeBitmap(context))
      addPerson(conversation.recipient)
      setShortcutId(ConversationUtil.getShortcutId(conversation.recipient))
      setContentInfo(conversation.messageCount.toString())
      setNumber(conversation.messageCount)
      setContentText(conversation.getContentText(context))
      setContentIntent(conversation.getPendingIntent(context))
      setDeleteIntent(conversation.getDeleteIntent(context))
      setSortKey(conversation.sortKey.toString())
      setWhen(conversation)
      addReplyActions(conversation)
      setOnlyAlertOnce(!shouldAlert)
      addMessages(conversation)
      setPriority(TextSecurePreferences.getNotificationPriority(context))
      setLights()
      setAlarms(conversation.recipient)
      setTicker(conversation.mostRecentNotification.getStyledPrimaryText(context, true))
      setBubbleMetadata(conversation, if (targetThreadId == conversation.threadId) defaultBubbleState else BubbleUtil.BubbleState.HIDDEN)
    }

    // if (conversation.messageCount == 1 && conversation.mostRecentNotification.isJoined) {
    //   builder.addTurnOffJoinedNotificationsAction(conversation.getTurnOffJoinedNotificationsIntent(context))
    // }

    val notificationId: Int = if (Build.VERSION.SDK_INT < 24) NotificationIds.MESSAGE_SUMMARY else conversation.notificationId

    NotificationManagerCompat.from(context).safelyNotify(context, conversation.recipient, notificationId, builder.build())
  }

  private fun notifySummary(context: Context, state: NotificationStateV2) {
    if (state.messageCount == 0) {
      return
    }
    val builder: NotificationBuilder = NotificationBuilder.create(context)

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setColor(ContextCompat.getColor(context, R.color.core_ultramarine))
      setCategory(NotificationCompat.CATEGORY_MESSAGE)
      setGroup(DefaultMessageNotifier.NOTIFICATION_GROUP)
      setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
      setChannelId(NotificationChannels.getMessagesChannel(context))
      setContentTitle(context.getString(R.string.app_name))
      setContentIntent(PendingIntent.getActivity(context, 0, MainActivity.clearTop(context), 0))
      setGroupSummary(true)
      setSubText(context.getString(R.string.MessageNotifier_d_new_messages_in_d_conversations, state.messageCount, state.threadCount))
      setContentInfo(state.messageCount.toString())
      setNumber(state.messageCount)
      setSummaryContentText(state.mostRecentSender)
      setDeleteIntent(state.getDeleteIntent(context))
      setWhen(state.mostRecentNotification)
      addMarkAsReadAction(state)
      addMessages(state)
      setOnlyAlertOnce(!state.notificationItems.any { it.isNewNotification })
      setPriority(TextSecurePreferences.getNotificationPriority(context))
      setLights()
      setAlarms(state.mostRecentSender)
      setTicker(state.mostRecentNotification?.getStyledPrimaryText(context, true))
    }

    Log.d(TAG, "showing summary notification")
    NotificationManagerCompat.from(context).safelyNotify(context, null, NotificationIds.MESSAGE_SUMMARY, builder.build())
  }

  private fun notifyInThread(context: Context, recipient: Recipient, lastAudibleNotification: Long) {
    if (!TextSecurePreferences.isInThreadNotifications(context) ||
      ServiceUtil.getAudioManager(context).ringerMode != AudioManager.RINGER_MODE_NORMAL ||
      (System.currentTimeMillis() - lastAudibleNotification) < DefaultMessageNotifier.MIN_AUDIBLE_PERIOD_MILLIS
    ) {
      return
    }

    val uri: Uri = if (NotificationChannels.supported()) {
      NotificationChannels.getMessageRingtone(context, recipient)
        ?: NotificationChannels.getMessageRingtone(context)
    } else {
      recipient.messageRingtone ?: TextSecurePreferences.getNotificationRingtone(context)
    }

    if (uri.toString().isEmpty()) {
      Log.d(TAG, "ringtone uri is empty")
      return
    }

    val ringtone = RingtoneManager.getRingtone(context, uri)

    if (ringtone == null) {
      Log.w(TAG, "ringtone is null")
      return
    }

    ringtone.audioAttributes = AudioAttributes.Builder()
      .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
      .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
      .build()

    ringtone.play()
  }

  fun notifyMessageDeliveryFailed(context: Context, recipient: Recipient, threadId: Long, visibleThread: Long) {
    if (threadId == visibleThread) {
      notifyInThread(context, recipient, 0)
      return
    }

    val intent: Intent = ConversationIntents.createBuilder(context, recipient.id, threadId)
      .build()
      .makeUniqueToPreventMerging()

    val builder: NotificationBuilder = NotificationBuilder.create(context)

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_action_warning_red))
      setContentTitle(context.getString(R.string.MessageNotifier_message_delivery_failed))
      setContentText(context.getString(R.string.MessageNotifier_failed_to_deliver_message))
      setTicker(context.getString(R.string.MessageNotifier_error_delivering_message))
      setContentIntent(PendingIntent.getActivity(context, 0, intent, 0))
      setAutoCancel(true)
      setAlarms(recipient)
      setChannelId(NotificationChannels.FAILURES)
    }

    NotificationManagerCompat.from(context).safelyNotify(context, recipient, threadId.toInt(), builder.build())
  }

  fun notifyProofRequired(context: Context, recipient: Recipient, threadId: Long, visibleThread: Long) {
    if (threadId == visibleThread) {
      notifyInThread(context, recipient, 0)
      return
    }

    val intent: Intent = ConversationIntents.createBuilder(context, recipient.id, threadId)
      .build()
      .makeUniqueToPreventMerging()

    val builder: NotificationBuilder = NotificationBuilder.create(context)

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_info_outline))
      setContentTitle(context.getString(R.string.MessageNotifier_message_delivery_paused))
      setContentText(context.getString(R.string.MessageNotifier_verify_to_continue_messaging_on_signal))
      setContentIntent(PendingIntent.getActivity(context, 0, intent, 0))
      setOnlyAlertOnce(true)
      setAutoCancel(true)
      setAlarms(recipient)
      setChannelId(NotificationChannels.FAILURES)
    }

    NotificationManagerCompat.from(context).safelyNotify(context, recipient, threadId.toInt(), builder.build())
  }

  private fun NotificationManagerCompat.safelyNotify(context: Context, threadRecipient: Recipient?, notificationId: Int, notification: Notification) {
    try {
      notify(notificationId, notification)
      Log.internal().i(TAG, "Posted notification: $notification")
    } catch (e: SecurityException) {
      Log.i(TAG, "Security exception when posting notification, clearing ringtone")
      if (threadRecipient != null) {
        SignalExecutors.BOUNDED.execute {
          DatabaseFactory.getRecipientDatabase(context).setMessageRingtone(threadRecipient.id, null)
          NotificationChannels.updateMessageRingtone(context, threadRecipient, null)
        }
      }
    } catch (runtimeException: RuntimeException) {
      if (runtimeException.cause is TransactionTooLargeException) {
        Log.e(TAG, "Transaction too large", runtimeException)
      } else {
        throw runtimeException
      }
    }
  }
}