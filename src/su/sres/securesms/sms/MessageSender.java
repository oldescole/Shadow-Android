/*
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package su.sres.securesms.sms;

import android.content.Context;
import androidx.annotation.NonNull;

import com.annimon.stream.Stream;

import su.sres.securesms.attachments.AttachmentId;
import su.sres.securesms.attachments.DatabaseAttachment;
import su.sres.securesms.database.MessagingDatabase.SyncMessageId;
import su.sres.securesms.database.MmsSmsDatabase;
import su.sres.securesms.database.NoSuchMessageException;
import su.sres.securesms.database.model.SmsMessageRecord;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.jobs.AttachmentCopyJob;
import su.sres.securesms.jobs.AttachmentUploadJob;
import su.sres.securesms.logging.Log;

import su.sres.securesms.ApplicationContext;
import su.sres.securesms.attachments.Attachment;
import su.sres.securesms.database.AttachmentDatabase;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.MmsDatabase;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.database.SmsDatabase;
import su.sres.securesms.database.ThreadDatabase;
import su.sres.securesms.database.model.MessageRecord;
import su.sres.securesms.jobs.AttachmentCompressionJob;
import su.sres.securesms.jobs.MmsSendJob;
import su.sres.securesms.jobs.PushGroupSendJob;
import su.sres.securesms.jobs.PushMediaSendJob;
import su.sres.securesms.jobs.PushTextSendJob;
import su.sres.securesms.jobs.SmsSendJob;
import su.sres.securesms.mms.MmsException;
import su.sres.securesms.mms.OutgoingMediaMessage;
import su.sres.securesms.mms.OutgoingSecureMediaMessage;
import su.sres.securesms.push.AccountManagerFactory;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.service.ExpiringMessageManager;
import su.sres.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.SignalServiceAccountManager;
import su.sres.signalservice.api.push.ContactTokenDetails;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MessageSender {

  private static final String TAG = MessageSender.class.getSimpleName();

  public static long send(final Context context,
                          final OutgoingTextMessage message,
                          final long threadId,
                          final boolean forceSms,
                          final SmsDatabase.InsertListener insertListener)
  {
    SmsDatabase database    = DatabaseFactory.getSmsDatabase(context);
    Recipient   recipient   = message.getRecipient();
    boolean     keyExchange = message.isKeyExchange();

    long allocatedThreadId;

    if (threadId == -1) {
      allocatedThreadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
    } else {
      allocatedThreadId = threadId;
    }

    long messageId = database.insertMessageOutbox(allocatedThreadId, message, forceSms, System.currentTimeMillis(), insertListener);

    sendTextMessage(context, recipient, forceSms, keyExchange, messageId);

    return allocatedThreadId;
  }

  public static long send(final Context context,
                          final OutgoingMediaMessage message,
                          final long threadId,
                          final boolean forceSms,
                          final SmsDatabase.InsertListener insertListener)
  {
    try {
      ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);
      MmsDatabase    database       = DatabaseFactory.getMmsDatabase(context);

      long allocatedThreadId;

      if (threadId == -1) {
        allocatedThreadId = threadDatabase.getThreadIdFor(message.getRecipient(), message.getDistributionType());
      } else {
        allocatedThreadId = threadId;
      }

      Recipient recipient = message.getRecipient();
      long      messageId = database.insertMessageOutbox(message, allocatedThreadId, forceSms, insertListener);

      sendMediaMessage(context, recipient, forceSms, messageId, message.getExpiresIn());

      return allocatedThreadId;
    } catch (MmsException e) {
      Log.w(TAG, e);
      return threadId;
    }
  }

  public static void sendMediaBroadcast(@NonNull Context context, @NonNull List<OutgoingSecureMediaMessage> messages) {
    if (messages.isEmpty()) {
      Log.w(TAG, "sendMediaBroadcast() - No messages!");
      return;
    }

    if (!isValidBroadcastList(messages)) {
      Log.w(TAG, "sendMediaBroadcast() - Invalid message list!");
      return;
    }

    ThreadDatabase                 threadDatabase      = DatabaseFactory.getThreadDatabase(context);
    MmsDatabase                    mmsDatabase         = DatabaseFactory.getMmsDatabase(context);
    AttachmentDatabase             attachmentDatabase  = DatabaseFactory.getAttachmentDatabase(context);
    List<List<DatabaseAttachment>> databaseAttachments = new ArrayList<>(messages.get(0).getAttachments().size());
    List<Long>                     messageIds          = new ArrayList<>(messages.size());

    for (int i = 0; i < messages.get(0).getAttachments().size(); i++) {
      databaseAttachments.add(new ArrayList<>(messages.size()));
    }

    try {
      try {
        mmsDatabase.beginTransaction();

        for (OutgoingSecureMediaMessage message : messages) {
          long                     allocatedThreadId = threadDatabase.getThreadIdFor(message.getRecipient(), message.getDistributionType());
          long                     messageId         = mmsDatabase.insertMessageOutbox(message, allocatedThreadId, false, null);
          List<DatabaseAttachment> attachments       = attachmentDatabase.getAttachmentsForMessage(messageId);

          if (attachments.size() != databaseAttachments.size()) {
            Log.w(TAG, "Got back an attachment list that was a different size than expected. Expected: " + databaseAttachments.size() + "  Actual: "+ attachments.size());
            return;
          }

          for (int i = 0; i < attachments.size(); i++) {
            databaseAttachments.get(i).add(attachments.get(i));
          }

          messageIds.add(messageId);
        }

        mmsDatabase.setTransactionSuccessful();
      } finally {
        mmsDatabase.endTransaction();
      }

      List<Job> compressionJobs = new ArrayList<>(databaseAttachments.size());
      List<Job> uploadJobs      = new ArrayList<>(databaseAttachments.size());
      List<Job> copyJobs        = new ArrayList<>(databaseAttachments.size());
      List<Job> messageJobs     = new ArrayList<>(databaseAttachments.get(0).size());

      for (List<DatabaseAttachment> attachmentList : databaseAttachments) {
        DatabaseAttachment source = attachmentList.get(0);

        compressionJobs.add(AttachmentCompressionJob.fromAttachment(source, false, -1));

        uploadJobs.add(new AttachmentUploadJob(source.getAttachmentId()));

        if (attachmentList.size() > 1) {
          AttachmentId       sourceId       = source.getAttachmentId();
          List<AttachmentId> destinationIds = Stream.of(attachmentList.subList(1, attachmentList.size()))
                  .map(DatabaseAttachment::getAttachmentId)
                  .toList();

          copyJobs.add(new AttachmentCopyJob(sourceId, destinationIds));
        }
      }

      for (int i = 0; i < messageIds.size(); i++) {
        long                       messageId = messageIds.get(i);
        OutgoingSecureMediaMessage message   = messages.get(i);
        Recipient                  recipient = message.getRecipient();

        if (isLocalSelfSend(context, recipient, false)) {
          sendLocalMediaSelf(context, messageId);
        } else if (isGroupPushSend(recipient)) {
          messageJobs.add(new PushGroupSendJob(messageId, recipient.getId(), null));
        } else {
          messageJobs.add(new PushMediaSendJob(messageId, recipient));
        }
      }

      Log.i(TAG, String.format(Locale.ENGLISH, "sendMediaBroadcast() - Uploading %d attachment(s), copying %d of them, then sending %d messages.",
              uploadJobs.size(),
              copyJobs.size(),
              messageJobs.size()));

      JobManager.Chain chain = ApplicationContext.getInstance(context)
              .getJobManager()
              .startChain(compressionJobs)
              .then(uploadJobs);

      if (copyJobs.size() > 0) {
        chain = chain.then(copyJobs);
      }

      chain = chain.then(messageJobs);
      chain.enqueue();
    } catch (MmsException e) {
      Log.w(TAG, "sendMediaBroadcast() - Failed to send messages!", e);
    }
  }

  public static void resendGroupMessage(Context context, MessageRecord messageRecord, RecipientId filterRecipientId) {
    if (!messageRecord.isMms()) throw new AssertionError("Not Group");
    sendGroupPush(context, messageRecord.getRecipient(), messageRecord.getId(), filterRecipientId);
  }

  public static void resend(Context context, MessageRecord messageRecord) {
    long       messageId   = messageRecord.getId();
    boolean    forceSms    = messageRecord.isForcedSms();
    boolean    keyExchange = messageRecord.isKeyExchange();
    long       expiresIn   = messageRecord.getExpiresIn();
    Recipient  recipient   = messageRecord.getRecipient();

    if (messageRecord.isMms()) {
      sendMediaMessage(context, recipient, forceSms, messageId, expiresIn);
    } else {
      sendTextMessage(context, recipient, forceSms, keyExchange, messageId);
    }
  }

  private static void sendMediaMessage(Context context, Recipient recipient, boolean forceSms, long messageId, long expiresIn)
  {
    if (isLocalSelfSend(context, recipient, forceSms)) {
      sendLocalMediaSelf(context, messageId);
    } else if (isGroupPushSend(recipient)) {
      sendGroupPush(context, recipient, messageId, null);
    } else if (!forceSms && isPushMediaSend(context, recipient)) {
      sendMediaPush(context, recipient, messageId);
    } else {
      sendMms(context, messageId);
    }
  }

  private static void sendTextMessage(Context context, Recipient recipient,
                                      boolean forceSms, boolean keyExchange,
                                      long messageId)
  {
    if (isLocalSelfSend(context, recipient, forceSms)) {
      sendLocalTextSelf(context, messageId);
    } else if (!forceSms && isPushTextSend(context, recipient, keyExchange)) {
      sendTextPush(context, recipient, messageId);
    } else {
      sendSms(context, recipient, messageId);
    }
  }

  private static void sendTextPush(Context context, Recipient recipient, long messageId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    jobManager.add(new PushTextSendJob(messageId, recipient));
  }

  private static void sendMediaPush(Context context, Recipient recipient, long messageId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    PushMediaSendJob.enqueue(context, jobManager, messageId, recipient);
  }

  private static void sendGroupPush(Context context, Recipient recipient, long messageId, RecipientId filterRecipientId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    PushGroupSendJob.enqueue(context, jobManager, messageId, recipient.getId(), filterRecipientId);
  }

  private static void sendSms(Context context, Recipient recipient, long messageId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    jobManager.add(new SmsSendJob(context, messageId, recipient));
  }

  private static void sendMms(Context context, long messageId) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();
    MmsSendJob.enqueue(context, jobManager, messageId);
  }

  private static boolean isPushTextSend(Context context, Recipient recipient, boolean keyExchange) {
    if (!TextSecurePreferences.isPushRegistered(context)) {
      return false;
    }

    if (keyExchange) {
      return false;
    }

    return isPushDestination(context, recipient);
  }

  private static boolean isPushMediaSend(Context context, Recipient recipient) {
    if (!TextSecurePreferences.isPushRegistered(context)) {
      return false;
    }

    if (recipient.isGroup()) {
      return false;
    }

    return isPushDestination(context, recipient);
  }

  private static boolean isGroupPushSend(Recipient recipient) {
    return recipient.requireAddress().isGroup() &&
            !recipient.requireAddress().isMmsGroup();
  }

  private static boolean isPushDestination(Context context, Recipient destination) {
    if (destination.resolve().getRegistered() == RecipientDatabase.RegisteredState.REGISTERED) {
      return true;
    } else if (destination.resolve().getRegistered() == RecipientDatabase.RegisteredState.NOT_REGISTERED) {
      return false;
    } else {
      try {
        SignalServiceAccountManager   accountManager = AccountManagerFactory.createManager(context);
        Optional<ContactTokenDetails> registeredUser = accountManager.getContact(destination.requireAddress().serialize());

        if (!registeredUser.isPresent()) {
          DatabaseFactory.getRecipientDatabase(context).setRegistered(destination.getId(), RecipientDatabase.RegisteredState.NOT_REGISTERED);
          return false;
        } else {
          DatabaseFactory.getRecipientDatabase(context).setRegistered(destination.getId(), RecipientDatabase.RegisteredState.REGISTERED);
          return true;
        }
      } catch (IOException e1) {
        Log.w(TAG, e1);
        return false;
      }
    }
  }

  private static boolean isLocalSelfSend(@NonNull Context context, @NonNull Recipient recipient, boolean forceSms) {
    return recipient.isLocalNumber()                       &&
            !forceSms                                       &&
            TextSecurePreferences.isPushRegistered(context) &&
            !TextSecurePreferences.isMultiDevice(context);
  }

  private static void sendLocalMediaSelf(Context context, long messageId) {
    try {
      ExpiringMessageManager expirationManager  = ApplicationContext.getInstance(context).getExpiringMessageManager();
      AttachmentDatabase     attachmentDatabase = DatabaseFactory.getAttachmentDatabase(context);
      MmsDatabase            mmsDatabase        = DatabaseFactory.getMmsDatabase(context);
      MmsSmsDatabase         mmsSmsDatabase     = DatabaseFactory.getMmsSmsDatabase(context);
      OutgoingMediaMessage   message            = mmsDatabase.getOutgoingMessage(messageId);
      SyncMessageId          syncId             = new SyncMessageId(Recipient.self().getId(), message.getSentTimeMillis());

      for (Attachment attachment : message.getAttachments()) {
        attachmentDatabase.markAttachmentUploaded(messageId, attachment);
      }

      mmsDatabase.markAsSent(messageId, true);
      mmsDatabase.markUnidentified(messageId, true);

      mmsSmsDatabase.incrementDeliveryReceiptCount(syncId, System.currentTimeMillis());
      mmsSmsDatabase.incrementReadReceiptCount(syncId, System.currentTimeMillis());

      if (message.getExpiresIn() > 0 && !message.isExpirationUpdate()) {
        mmsDatabase.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(messageId, true, message.getExpiresIn());
      }
    } catch (NoSuchMessageException | MmsException e) {
      Log.w("Failed to update self-sent message.", e);
    }
  }

  private static void sendLocalTextSelf(Context context, long messageId) {
    try {
      ExpiringMessageManager expirationManager = ApplicationContext.getInstance(context).getExpiringMessageManager();
      SmsDatabase            smsDatabase       = DatabaseFactory.getSmsDatabase(context);
      MmsSmsDatabase         mmsSmsDatabase    = DatabaseFactory.getMmsSmsDatabase(context);
      SmsMessageRecord       message           = smsDatabase.getMessage(messageId);
      SyncMessageId          syncId            = new SyncMessageId(Recipient.self().getId(), message.getDateSent());

      smsDatabase.markAsSent(messageId, true);
      smsDatabase.markUnidentified(messageId, true);

      mmsSmsDatabase.incrementDeliveryReceiptCount(syncId, System.currentTimeMillis());
      mmsSmsDatabase.incrementReadReceiptCount(syncId, System.currentTimeMillis());

      if (message.getExpiresIn() > 0) {
        smsDatabase.markExpireStarted(messageId);
        expirationManager.scheduleDeletion(message.getId(), message.isMms(), message.getExpiresIn());
      }
    } catch (NoSuchMessageException e) {
      Log.w("Failed to update self-sent message.", e);
    }
  }


  private static boolean isValidBroadcastList(@NonNull List<OutgoingSecureMediaMessage> messages) {
    if (messages.isEmpty()) {
      return false;
    }

    int attachmentSize = messages.get(0).getAttachments().size();

    for (OutgoingSecureMediaMessage message : messages) {
      if (message.getAttachments().size() != attachmentSize) {
        return false;
      }
    }

    return true;
  }
}
