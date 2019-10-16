package su.sres.securesms.jobs;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import android.text.TextUtils;
import android.util.Pair;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.signal.libsignal.metadata.InvalidMetadataMessageException;
import org.signal.libsignal.metadata.InvalidMetadataVersionException;
import org.signal.libsignal.metadata.ProtocolDuplicateMessageException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyException;
import org.signal.libsignal.metadata.ProtocolInvalidKeyIdException;
import org.signal.libsignal.metadata.ProtocolInvalidMessageException;
import org.signal.libsignal.metadata.ProtocolInvalidVersionException;
import org.signal.libsignal.metadata.ProtocolLegacyMessageException;
import org.signal.libsignal.metadata.ProtocolNoSessionException;
import org.signal.libsignal.metadata.ProtocolUntrustedIdentityException;
import org.signal.libsignal.metadata.SelfSendException;
import su.sres.securesms.ApplicationContext;
import su.sres.securesms.ConversationListActivity;
import su.sres.securesms.R;
import su.sres.securesms.attachments.Attachment;
import su.sres.securesms.attachments.DatabaseAttachment;
import su.sres.securesms.attachments.PointerAttachment;
import su.sres.securesms.attachments.TombstoneAttachment;
import su.sres.securesms.attachments.UriAttachment;
import su.sres.securesms.contactshare.Contact;
import su.sres.securesms.contactshare.ContactModelMapper;
import su.sres.securesms.crypto.IdentityKeyUtil;
import su.sres.securesms.crypto.SecurityEvent;
import su.sres.securesms.crypto.UnidentifiedAccessUtil;
import su.sres.securesms.crypto.storage.SignalProtocolStoreImpl;
import su.sres.securesms.crypto.storage.TextSecureSessionStore;
import su.sres.securesms.database.Address;
import su.sres.securesms.database.AttachmentDatabase;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.GroupDatabase;
import su.sres.securesms.database.GroupReceiptDatabase;
import su.sres.securesms.database.GroupReceiptDatabase.GroupReceiptInfo;
import su.sres.securesms.database.MessagingDatabase;
import su.sres.securesms.database.MessagingDatabase.InsertResult;
import su.sres.securesms.database.MessagingDatabase.SyncMessageId;
import su.sres.securesms.database.MmsDatabase;
import su.sres.securesms.database.MmsSmsDatabase;
import su.sres.securesms.database.NoSuchMessageException;
import su.sres.securesms.database.PushDatabase;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.database.SmsDatabase;
import su.sres.securesms.database.StickerDatabase;
import su.sres.securesms.database.ThreadDatabase;
import su.sres.securesms.database.model.MessageRecord;
import su.sres.securesms.database.model.MmsMessageRecord;
import su.sres.securesms.database.model.StickerRecord;
import su.sres.securesms.groups.GroupMessageProcessor;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.linkpreview.Link;
import su.sres.securesms.linkpreview.LinkPreview;
import su.sres.securesms.linkpreview.LinkPreviewUtil;
import su.sres.securesms.logging.Log;
import su.sres.securesms.mms.IncomingMediaMessage;
import su.sres.securesms.mms.MmsException;
import su.sres.securesms.mms.OutgoingExpirationUpdateMessage;
import su.sres.securesms.mms.OutgoingMediaMessage;
import su.sres.securesms.mms.OutgoingSecureMediaMessage;
import su.sres.securesms.mms.QuoteModel;
import su.sres.securesms.mms.SlideDeck;
import su.sres.securesms.mms.StickerSlide;
import su.sres.securesms.notifications.MessageNotifier;
import su.sres.securesms.notifications.NotificationChannels;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.service.WebRtcCallService;
import su.sres.securesms.sms.IncomingEncryptedMessage;
import su.sres.securesms.sms.IncomingEndSessionMessage;
import su.sres.securesms.sms.IncomingTextMessage;
import su.sres.securesms.sms.OutgoingEncryptedMessage;
import su.sres.securesms.sms.OutgoingEndSessionMessage;
import su.sres.securesms.sms.OutgoingTextMessage;
import su.sres.securesms.stickers.StickerLocator;
import su.sres.securesms.transport.RetryLaterException;
import su.sres.securesms.util.GroupUtil;
import su.sres.securesms.util.Hex;
import su.sres.securesms.util.IdentityUtil;
import su.sres.securesms.util.MediaUtil;
import su.sres.securesms.util.TextSecurePreferences;
import org.whispersystems.libsignal.state.SessionStore;
import org.whispersystems.libsignal.state.SignalProtocolStore;
import org.whispersystems.libsignal.util.guava.Optional;
import su.sres.signalservice.api.crypto.SignalServiceCipher;
import su.sres.signalservice.api.messages.SignalServiceContent;
import su.sres.signalservice.api.messages.SignalServiceDataMessage;
import su.sres.signalservice.api.messages.SignalServiceDataMessage.Preview;
import su.sres.signalservice.api.messages.SignalServiceEnvelope;
import su.sres.signalservice.api.messages.SignalServiceGroup;
import su.sres.signalservice.api.messages.SignalServiceReceiptMessage;
import su.sres.signalservice.api.messages.SignalServiceTypingMessage;
import su.sres.signalservice.api.messages.calls.AnswerMessage;
import su.sres.signalservice.api.messages.calls.BusyMessage;
import su.sres.signalservice.api.messages.calls.HangupMessage;
import su.sres.signalservice.api.messages.calls.IceUpdateMessage;
import su.sres.signalservice.api.messages.calls.OfferMessage;
import su.sres.signalservice.api.messages.calls.SignalServiceCallMessage;
import su.sres.signalservice.api.messages.multidevice.ReadMessage;
import su.sres.signalservice.api.messages.multidevice.RequestMessage;
import su.sres.signalservice.api.messages.multidevice.SentTranscriptMessage;
import su.sres.signalservice.api.messages.multidevice.SignalServiceSyncMessage;
import su.sres.signalservice.api.messages.multidevice.StickerPackOperationMessage;
import su.sres.signalservice.api.messages.multidevice.VerifiedMessage;
import su.sres.signalservice.api.messages.multidevice.ViewOnceOpenMessage;
import su.sres.signalservice.api.messages.shared.SharedContact;
import su.sres.signalservice.api.push.SignalServiceAddress;
import su.sres.signalservice.internal.push.UnsupportedDataMessageException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class PushDecryptJob extends BaseJob {

  public static final String KEY = "PushDecryptJob";

  public static final String TAG = PushDecryptJob.class.getSimpleName();

  private static final String KEY_MESSAGE_ID     = "message_id";
  private static final String KEY_SMS_MESSAGE_ID = "sms_message_id";

  private long messageId;
  private long smsMessageId;

  public PushDecryptJob(Context context, long pushMessageId) {
    this(context, pushMessageId, -1);
  }

  public PushDecryptJob(Context context, long pushMessageId, long smsMessageId) {
    this(new Job.Parameters.Builder()
                    .setQueue("__PUSH_DECRYPT_JOB__")
                    .setMaxAttempts(Parameters.UNLIMITED)
                    .build(),
            pushMessageId,
            smsMessageId);
    setContext(context);
  }

  private PushDecryptJob(@NonNull Job.Parameters parameters, long pushMessageId, long smsMessageId) {
    super(parameters);

    this.messageId    = pushMessageId;
    this.smsMessageId = smsMessageId;
  }

  @Override
  public @NonNull Data serialize() {
    return new Data.Builder().putLong(KEY_MESSAGE_ID, messageId)
            .putLong(KEY_SMS_MESSAGE_ID, smsMessageId)
            .build();
  }

  @Override
  public @NonNull String getFactoryKey() {
    return KEY;
  }

  @Override
  public void onRun() throws NoSuchMessageException, RetryLaterException {
    if (needsMigration()) {
      Log.w(TAG, "Migration is still needed.");
      postMigrationNotification();
      throw new RetryLaterException();
    }
      PushDatabase          database             = DatabaseFactory.getPushDatabase(context);
      SignalServiceEnvelope envelope             = database.get(messageId);
      Optional<Long>        optionalSmsMessageId = smsMessageId > 0 ? Optional.of(smsMessageId) : Optional.absent();

    handleMessage(envelope, optionalSmsMessageId);
    database.delete(messageId);
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception exception) {
    return exception instanceof RetryLaterException;
  }

  @Override
  public void onCanceled() {

  }

  private boolean needsMigration() {
    return !IdentityKeyUtil.hasIdentityKey(context) || TextSecurePreferences.getNeedsSqlCipherMigration(context);
  }

  private void postMigrationNotification() {
    NotificationManagerCompat.from(context).notify(494949,
            new NotificationCompat.Builder(context, NotificationChannels.getMessagesChannel(context))
                    .setSmallIcon(R.drawable.icon_notification)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentTitle(context.getString(R.string.PushDecryptJob_new_locked_message))
                    .setContentText(context.getString(R.string.PushDecryptJob_unlock_to_view_pending_messages))
                    .setContentIntent(PendingIntent.getActivity(context, 0, new Intent(context, ConversationListActivity.class), 0))
                    .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE)
                    .build());

  }

  private void handleMessage(@NonNull SignalServiceEnvelope envelope, @NonNull Optional<Long> smsMessageId) {
    try {
      GroupDatabase        groupDatabase = DatabaseFactory.getGroupDatabase(context);
      SignalProtocolStore  axolotlStore  = new SignalProtocolStoreImpl(context);
      SignalServiceAddress localAddress  = new SignalServiceAddress(TextSecurePreferences.getLocalNumber(context));
      SignalServiceCipher  cipher        = new SignalServiceCipher(localAddress, axolotlStore, UnidentifiedAccessUtil.getCertificateValidator());

      SignalServiceContent content = cipher.decrypt(envelope);

      if (shouldIgnore(content)) {
        Log.i(TAG, "Ignoring message.");
        return;
      }


      if (content.getDataMessage().isPresent()) {
        SignalServiceDataMessage message        = content.getDataMessage().get();
        boolean                  isMediaMessage = message.getAttachments().isPresent() || message.getQuote().isPresent() || message.getSharedContacts().isPresent() || message.getPreviews().isPresent() || message.getSticker().isPresent();

        if      (isInvalidMessage(message))     handleInvalidMessage(content.getSender(), content.getSenderDevice(), message.getGroupInfo(), content.getTimestamp(), smsMessageId);
        else if (message.isEndSession())        handleEndSessionMessage(content, smsMessageId);
        else if (message.isGroupUpdate())       handleGroupMessage(content, message, smsMessageId);
        else if (message.isExpirationUpdate())  handleExpirationUpdate(content, message, smsMessageId);
        else if (isMediaMessage)                handleMediaMessage(content, message, smsMessageId);
        else if (message.getBody().isPresent()) handleTextMessage(content, message, smsMessageId);

        if (message.getGroupInfo().isPresent() && groupDatabase.isUnknownGroup(GroupUtil.getEncodedId(message.getGroupInfo().get().getGroupId(), false))) {
          handleUnknownGroupMessage(content, message.getGroupInfo().get());
        }

        if (message.getProfileKey().isPresent() && message.getProfileKey().get().length == 32) {
          handleProfileKey(content, message);
        }

        if (content.isNeedsReceipt()) {
          handleNeedsDeliveryReceipt(content, message);
        }
      } else if (content.getSyncMessage().isPresent()) {
        TextSecurePreferences.setMultiDevice(context, true);

        SignalServiceSyncMessage syncMessage = content.getSyncMessage().get();

        if      (syncMessage.getSent().isPresent())                  handleSynchronizeSentMessage(content, syncMessage.getSent().get());
        else if (syncMessage.getRequest().isPresent())               handleSynchronizeRequestMessage(syncMessage.getRequest().get());
        else if (syncMessage.getRead().isPresent())                  handleSynchronizeReadMessage(syncMessage.getRead().get(), content.getTimestamp());
        else if (syncMessage.getViewOnceOpen().isPresent())          handleSynchronizeViewOnceOpenMessage(syncMessage.getViewOnceOpen().get(), content.getTimestamp());
        else if (syncMessage.getVerified().isPresent())              handleSynchronizeVerifiedMessage(syncMessage.getVerified().get());
        else if (syncMessage.getStickerPackOperations().isPresent()) handleSynchronizeStickerPackOperation(syncMessage.getStickerPackOperations().get());
        else                                                         Log.w(TAG, "Contains no known sync types...");
      } else if (content.getCallMessage().isPresent()) {
        Log.i(TAG, "Got call message...");
        SignalServiceCallMessage message = content.getCallMessage().get();

        if      (message.getOfferMessage().isPresent())      handleCallOfferMessage(content, message.getOfferMessage().get(), smsMessageId);
        else if (message.getAnswerMessage().isPresent())     handleCallAnswerMessage(content, message.getAnswerMessage().get());
        else if (message.getIceUpdateMessages().isPresent()) handleCallIceUpdateMessage(content, message.getIceUpdateMessages().get());
        else if (message.getHangupMessage().isPresent())     handleCallHangupMessage(content, message.getHangupMessage().get(), smsMessageId);
        else if (message.getBusyMessage().isPresent())       handleCallBusyMessage(content, message.getBusyMessage().get());
      } else if (content.getReceiptMessage().isPresent()) {
        SignalServiceReceiptMessage message = content.getReceiptMessage().get();

        if      (message.isReadReceipt())     handleReadReceipt(content, message);
        else if (message.isDeliveryReceipt()) handleDeliveryReceipt(content, message);
      } else if (content.getTypingMessage().isPresent()) {
        handleTypingMessage(content, content.getTypingMessage().get());
      } else {
        Log.w(TAG, "Got unrecognized message...");
      }

      resetRecipientToPush(Recipient.external(context, content.getSender()));

      if (envelope.isPreKeySignalMessage()) {
        ApplicationContext.getInstance(context).getJobManager().add(new RefreshPreKeysJob());
      }
    } catch (ProtocolInvalidVersionException e) {
      Log.w(TAG, e);
      handleInvalidVersionMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolInvalidMessageException  e) {
      Log.w(TAG, e);
      handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    }
    catch (ProtocolInvalidKeyIdException | ProtocolInvalidKeyException | ProtocolUntrustedIdentityException e) {
      Log.w(TAG, e);
      handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (StorageFailedException e) {
      Log.w(TAG, e);
      handleCorruptMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolNoSessionException e) {
      Log.w(TAG, e);
      handleNoSessionMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolLegacyMessageException e) {
      Log.w(TAG, e);
      handleLegacyMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (ProtocolDuplicateMessageException e) {
      Log.w(TAG, e);
      handleDuplicateMessage(e.getSender(), e.getSenderDevice(), envelope.getTimestamp(), smsMessageId);
    } catch (InvalidMetadataVersionException | InvalidMetadataMessageException e) {
      Log.w(TAG, e);
    } catch (SelfSendException e) {
      Log.i(TAG, "Dropping UD message from self.");
    } catch (UnsupportedDataMessageException e) {
      Log.w(TAG, e);
      handleUnsupportedDataMessage(e.getSender(), e.getSenderDevice(), e.getGroup(), envelope.getTimestamp(), smsMessageId);
    }
  }

  private void handleCallOfferMessage(@NonNull SignalServiceContent content,
                                      @NonNull OfferMessage message,
                                      @NonNull Optional<Long> smsMessageId)
  {
    Log.w(TAG, "handleCallOfferMessage...");

    if (smsMessageId.isPresent()) {
      SmsDatabase database = DatabaseFactory.getSmsDatabase(context);
      database.markAsMissedCall(smsMessageId.get());
    } else {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_INCOMING_CALL);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_RECIPIENT, Recipient.external(context, content.getSender()).getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_DESCRIPTION, message.getDescription());
      intent.putExtra(WebRtcCallService.EXTRA_TIMESTAMP, content.getTimestamp());

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
      else                                                context.startService(intent);
    }
  }

  private void handleCallAnswerMessage(@NonNull SignalServiceContent content,
                                       @NonNull AnswerMessage message)
  {
    Log.i(TAG, "handleCallAnswerMessage...");
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_RESPONSE_MESSAGE);
    intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_RECIPIENT, Recipient.external(context, content.getSender()).getId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_DESCRIPTION, message.getDescription());

    context.startService(intent);
  }

  private void handleCallIceUpdateMessage(@NonNull SignalServiceContent content,
                                          @NonNull List<IceUpdateMessage> messages)
  {
    Log.w(TAG, "handleCallIceUpdateMessage... " + messages.size());
    for (IceUpdateMessage message : messages) {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_ICE_MESSAGE);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_RECIPIENT, Recipient.external(context, content.getSender()).getId());
      intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP, message.getSdp());
      intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP_MID, message.getSdpMid());
      intent.putExtra(WebRtcCallService.EXTRA_ICE_SDP_LINE_INDEX, message.getSdpMLineIndex());

      context.startService(intent);
    }
  }

  private void handleCallHangupMessage(@NonNull SignalServiceContent content,
                                       @NonNull HangupMessage message,
                                       @NonNull Optional<Long> smsMessageId)
  {
    Log.i(TAG, "handleCallHangupMessage");
    if (smsMessageId.isPresent()) {
      DatabaseFactory.getSmsDatabase(context).markAsMissedCall(smsMessageId.get());
    } else {
      Intent intent = new Intent(context, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_REMOTE_HANGUP);
      intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
      intent.putExtra(WebRtcCallService.EXTRA_REMOTE_RECIPIENT, Recipient.external(context, content.getSender()).getId());

      context.startService(intent);
    }
  }

  private void handleCallBusyMessage(@NonNull SignalServiceContent content,
                                     @NonNull BusyMessage message)
  {
    Intent intent = new Intent(context, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_REMOTE_BUSY);
    intent.putExtra(WebRtcCallService.EXTRA_CALL_ID, message.getId());
    intent.putExtra(WebRtcCallService.EXTRA_REMOTE_RECIPIENT, Recipient.external(context, content.getSender()).getId());

    context.startService(intent);
  }

  private void handleEndSessionMessage(@NonNull SignalServiceContent content,
                                       @NonNull Optional<Long>       smsMessageId)
  {
    SmsDatabase         smsDatabase         = DatabaseFactory.getSmsDatabase(context);
    IncomingTextMessage incomingTextMessage = new IncomingTextMessage(Recipient.external(context, content.getSender()).getId(),
            content.getSenderDevice(),
            content.getTimestamp(),
            "", Optional.absent(), 0,
            content.isNeedsReceipt());

    Long threadId;

    if (!smsMessageId.isPresent()) {
      IncomingEndSessionMessage incomingEndSessionMessage = new IncomingEndSessionMessage(incomingTextMessage);
      Optional<InsertResult>    insertResult              = smsDatabase.insertMessageInbox(incomingEndSessionMessage);

      if (insertResult.isPresent()) threadId = insertResult.get().getThreadId();
      else                          threadId = null;
    } else {
      smsDatabase.markAsEndSession(smsMessageId.get());
      threadId = smsDatabase.getThreadIdForMessage(smsMessageId.get());
    }

    if (threadId != null) {
      SessionStore sessionStore = new TextSecureSessionStore(context);
      sessionStore.deleteAllSessions(content.getSender());

      SecurityEvent.broadcastSecurityUpdateEvent(context);
      MessageNotifier.updateNotification(context, threadId);
    }
  }

  private long handleSynchronizeSentEndSessionMessage(@NonNull SentTranscriptMessage message)
  {
    SmsDatabase               database                  = DatabaseFactory.getSmsDatabase(context);
    Recipient                 recipient                 = getSyncMessageDestination(message);
    OutgoingTextMessage       outgoingTextMessage       = new OutgoingTextMessage(recipient, "", -1);
    OutgoingEndSessionMessage outgoingEndSessionMessage = new OutgoingEndSessionMessage(outgoingTextMessage);

    long threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);

    if (!recipient.isGroup()) {
      SessionStore sessionStore = new TextSecureSessionStore(context);
      sessionStore.deleteAllSessions(recipient.requireAddress().toPhoneString());

      SecurityEvent.broadcastSecurityUpdateEvent(context);

      long messageId = database.insertMessageOutbox(threadId, outgoingEndSessionMessage,
                                                    false, message.getTimestamp(),
                                                    null);
      database.markAsSent(messageId, true);
    }

    return threadId;
  }

  private void handleGroupMessage(@NonNull SignalServiceContent content,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Optional<Long> smsMessageId)
          throws StorageFailedException
  {
    GroupMessageProcessor.process(context, content, message, false);

    if (message.getExpiresInSeconds() != 0 && message.getExpiresInSeconds() != getMessageDestination(content, message).getExpireMessages()) {
      handleExpirationUpdate(content, message, Optional.absent());
    }

    if (smsMessageId.isPresent()) {
      DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
    }
  }

  private void handleUnknownGroupMessage(@NonNull SignalServiceContent content,
                                         @NonNull SignalServiceGroup group)
  {
    ApplicationContext.getInstance(context)
                      .getJobManager()
            .add(new RequestGroupInfoJob(Recipient.external(context, content.getSender()).getId(), group.getGroupId()));
  }

  private void handleExpirationUpdate(@NonNull SignalServiceContent content,
                                      @NonNull SignalServiceDataMessage message,
                                      @NonNull Optional<Long> smsMessageId)
          throws StorageFailedException
  {
    try {
      MmsDatabase          database     = DatabaseFactory.getMmsDatabase(context);
      Recipient            recipient    = getMessageDestination(content, message);
      IncomingMediaMessage mediaMessage = new IncomingMediaMessage(recipient.getId(),
              message.getTimestamp(), -1,
              message.getExpiresInSeconds() * 1000L, true,
              false,
              content.isNeedsReceipt(),
              Optional.absent(),
              message.getGroupInfo(),
              Optional.absent(),
              Optional.absent(),
              Optional.absent(),
              Optional.absent(),
              Optional.absent());

      database.insertSecureDecryptedMessageInbox(mediaMessage, -1);

      DatabaseFactory.getRecipientDatabase(context).setExpireMessages(recipient.getId(), message.getExpiresInSeconds());

      if (smsMessageId.isPresent()) {
        DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
      }
    } catch (MmsException e) {
      throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
    }
  }

  private void handleSynchronizeVerifiedMessage(@NonNull VerifiedMessage verifiedMessage) {
    IdentityUtil.processVerifiedMessage(context, verifiedMessage);
  }

  private void handleSynchronizeStickerPackOperation(@NonNull List<StickerPackOperationMessage> stickerPackOperations) {
    JobManager jobManager = ApplicationContext.getInstance(context).getJobManager();

    for (StickerPackOperationMessage operation : stickerPackOperations) {
      if (operation.getPackId().isPresent() && operation.getPackKey().isPresent() && operation.getType().isPresent()) {
        String packId  = Hex.toStringCondensed(operation.getPackId().get());
        String packKey = Hex.toStringCondensed(operation.getPackKey().get());

        switch (operation.getType().get()) {
          case INSTALL:
            jobManager.add(new StickerPackDownloadJob(packId, packKey, false));
            break;
          case REMOVE:
            DatabaseFactory.getStickerDatabase(context).uninstallPack(packId);
            break;
        }
      } else {
        Log.w(TAG, "Received incomplete sticker pack operation sync.");
      }
    }
  }

  private void handleSynchronizeSentMessage(@NonNull SignalServiceContent content,
                                            @NonNull SentTranscriptMessage message)
          throws StorageFailedException

  {
    try {
      GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);

      Long threadId = null;

      if (message.isRecipientUpdate()) {
        handleGroupRecipientUpdate(message);
      } else if (message.getMessage().isEndSession()) {
        threadId = handleSynchronizeSentEndSessionMessage(message);
      } else if (message.getMessage().isGroupUpdate()) {
        threadId = GroupMessageProcessor.process(context, content, message.getMessage(), true);
      } else if (message.getMessage().isExpirationUpdate()) {
        threadId = handleSynchronizeSentExpirationUpdate(message);
      } else if (message.getMessage().getAttachments().isPresent() || message.getMessage().getQuote().isPresent() || message.getMessage().getPreviews().isPresent() || message.getMessage().getSticker().isPresent()) {
        threadId = handleSynchronizeSentMediaMessage(message);
      } else {
        threadId = handleSynchronizeSentTextMessage(message);
      }

      if (message.getMessage().getGroupInfo().isPresent() && groupDatabase.isUnknownGroup(GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get().getGroupId(), false))) {
        handleUnknownGroupMessage(content, message.getMessage().getGroupInfo().get());
      }

      if (message.getMessage().getProfileKey().isPresent()) {
        Recipient recipient = null;

        if      (message.getDestination().isPresent())            recipient = Recipient.external(context, message.getDestination().get());
        else if (message.getMessage().getGroupInfo().isPresent()) recipient = Recipient.external(context, GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get().getGroupId(), false));

        if (recipient != null && !recipient.isSystemContact() && !recipient.isProfileSharing()) {
          DatabaseFactory.getRecipientDatabase(context).setProfileSharing(recipient.getId(), true);
        }
      }


      if (threadId != null) {
        DatabaseFactory.getThreadDatabase(context).setRead(threadId, true);
        MessageNotifier.updateNotification(context);
      }

      MessageNotifier.setLastDesktopActivityTimestamp(message.getTimestamp());
    } catch (MmsException e) {
      throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
    }
  }

  private void handleSynchronizeRequestMessage(@NonNull RequestMessage message)
  {
    if (message.isContactsRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
              .add(new MultiDeviceContactUpdateJob(true));

      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new RefreshUnidentifiedDeliveryAbilityJob());
    }

    if (message.isGroupsRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
              .add(new MultiDeviceGroupUpdateJob());
    }

    if (message.isBlockedListRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
              .add(new MultiDeviceBlockedUpdateJob());
    }

    if (message.isConfigurationRequest()) {
      ApplicationContext.getInstance(context)
                        .getJobManager()
              .add(new MultiDeviceConfigurationUpdateJob(TextSecurePreferences.isReadReceiptsEnabled(context),
                      TextSecurePreferences.isTypingIndicatorsEnabled(context),
                      TextSecurePreferences.isShowUnidentifiedDeliveryIndicatorsEnabled(context),
                      TextSecurePreferences.isLinkPreviewsEnabled(context)));

      ApplicationContext.getInstance(context)
              .getJobManager()
              .add(new MultiDeviceStickerPackSyncJob());
    }
  }

  private void handleSynchronizeReadMessage(@NonNull List<ReadMessage> readMessages, long envelopeTimestamp)
  {
    for (ReadMessage readMessage : readMessages) {
      List<Pair<Long, Long>> expiringText = DatabaseFactory.getSmsDatabase(context).setTimestampRead(new SyncMessageId(Recipient.external(context, readMessage.getSender()).getId(), readMessage.getTimestamp()), envelopeTimestamp);
      List<Pair<Long, Long>> expiringMedia = DatabaseFactory.getMmsDatabase(context).setTimestampRead(new SyncMessageId(Recipient.external(context, readMessage.getSender()).getId(), readMessage.getTimestamp()), envelopeTimestamp);

      for (Pair<Long, Long> expiringMessage : expiringText) {
        ApplicationContext.getInstance(context)
                          .getExpiringMessageManager()
                          .scheduleDeletion(expiringMessage.first, false, envelopeTimestamp, expiringMessage.second);
      }

      for (Pair<Long, Long> expiringMessage : expiringMedia) {
        ApplicationContext.getInstance(context)
                          .getExpiringMessageManager()
                          .scheduleDeletion(expiringMessage.first, true, envelopeTimestamp, expiringMessage.second);
      }
    }

    MessageNotifier.setLastDesktopActivityTimestamp(envelopeTimestamp);
    MessageNotifier.cancelDelayedNotifications();
    MessageNotifier.updateNotification(context);
  }

  private void handleSynchronizeViewOnceOpenMessage(@NonNull ViewOnceOpenMessage openMessage, long envelopeTimestamp) {
    RecipientId   author    = Recipient.external(context, openMessage.getSender()).getId();
    long          timestamp = openMessage.getTimestamp();
    MessageRecord record    = DatabaseFactory.getMmsSmsDatabase(context).getMessageFor(timestamp, author);

    if (record != null && record.isMms()) {
      DatabaseFactory.getAttachmentDatabase(context).deleteAttachmentFilesForMessage(record.getId());
    }

    MessageNotifier.setLastDesktopActivityTimestamp(envelopeTimestamp);
    MessageNotifier.cancelDelayedNotifications();
    MessageNotifier.updateNotification(context);
  }

  private void handleMediaMessage(@NonNull SignalServiceContent content,
                                  @NonNull SignalServiceDataMessage message,
                                  @NonNull Optional<Long> smsMessageId)
          throws StorageFailedException
  {
    notifyTypingStoppedFromIncomingMessage(getMessageDestination(content, message), content.getSender(), content.getSenderDevice());

    Optional<InsertResult> insertResult;

    MmsDatabase database = DatabaseFactory.getMmsDatabase(context);
    database.beginTransaction();

    try {
      Optional<QuoteModel>        quote          = getValidatedQuote(message.getQuote());
      Optional<List<Contact>>     sharedContacts = getContacts(message.getSharedContacts());
      Optional<List<LinkPreview>> linkPreviews   = getLinkPreviews(message.getPreviews(), message.getBody().or(""));
      Optional<Attachment>        sticker        = getStickerAttachment(message.getSticker());
      IncomingMediaMessage        mediaMessage   = new IncomingMediaMessage(Recipient.external(context, content.getSender()).getId(),
              message.getTimestamp(), -1,
              message.getExpiresInSeconds() * 1000L, false,
              message.isViewOnce(),
              content.isNeedsReceipt(),
              message.getBody(),
              message.getGroupInfo(),
              message.getAttachments(),
              quote,
              sharedContacts,
              linkPreviews,
              sticker);

      insertResult = database.insertSecureDecryptedMessageInbox(mediaMessage, -1);

      if (insertResult.isPresent()) {
        List<DatabaseAttachment> allAttachments     = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(insertResult.get().getMessageId());
        List<DatabaseAttachment> stickerAttachments = Stream.of(allAttachments).filter(Attachment::isSticker).toList();
        List<DatabaseAttachment> attachments        = Stream.of(allAttachments).filterNot(Attachment::isSticker).toList();

        forceStickerDownloadIfNecessary(stickerAttachments);

        for (DatabaseAttachment attachment : attachments) {
          ApplicationContext.getInstance(context)
                  .getJobManager()
                  .add(new AttachmentDownloadJob(insertResult.get().getMessageId(), attachment.getAttachmentId(), false));
        }
        if (smsMessageId.isPresent()) {
          DatabaseFactory.getSmsDatabase(context).deleteMessage(smsMessageId.get());
        }

        database.setTransactionSuccessful();
      }

    } catch (MmsException e) {
      throw new StorageFailedException(e, content.getSender(), content.getSenderDevice());
    } finally {
      database.endTransaction();
    }

    if (insertResult.isPresent()) {
      MessageNotifier.updateNotification(context, insertResult.get().getThreadId());

      if (message.isViewOnce()) {
        ApplicationContext.getInstance(context).getViewOnceMessageManager().scheduleIfNecessary();
      }
    }
  }

  private long handleSynchronizeSentExpirationUpdate(@NonNull SentTranscriptMessage message) throws MmsException {
    MmsDatabase database   = DatabaseFactory.getMmsDatabase(context);
    Recipient   recipient  = getSyncMessageDestination(message);

    OutgoingExpirationUpdateMessage expirationUpdateMessage = new OutgoingExpirationUpdateMessage(recipient,
                                                                                                  message.getTimestamp(),
                                                                                                  message.getMessage().getExpiresInSeconds() * 1000L);

    long threadId  = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
    long messageId = database.insertMessageOutbox(expirationUpdateMessage, threadId, false, null);

    database.markAsSent(messageId, true);

    DatabaseFactory.getRecipientDatabase(context).setExpireMessages(recipient.getId(), message.getMessage().getExpiresInSeconds());

    return threadId;
  }

  private long handleSynchronizeSentMediaMessage(@NonNull SentTranscriptMessage message)
      throws MmsException
  {
    MmsDatabase                 database        = DatabaseFactory.getMmsDatabase(context);
    Recipient                   recipients      = getSyncMessageDestination(message);
    Optional<QuoteModel>        quote           = getValidatedQuote(message.getMessage().getQuote());
    Optional<Attachment>        sticker         = getStickerAttachment(message.getMessage().getSticker());
    Optional<List<Contact>>     sharedContacts  = getContacts(message.getMessage().getSharedContacts());
    Optional<List<LinkPreview>> previews        = getLinkPreviews(message.getMessage().getPreviews(), message.getMessage().getBody().or(""));
    boolean                     viewOnce        = message.getMessage().isViewOnce();
    List<Attachment>            syncAttachments = viewOnce ? Collections.emptyList() : PointerAttachment.forPointers(message.getMessage().getAttachments());

    if (sticker.isPresent()) {
      syncAttachments.add(sticker.get());
    }

    OutgoingMediaMessage mediaMessage = new OutgoingMediaMessage(recipients, message.getMessage().getBody().orNull(),
            syncAttachments,
            message.getTimestamp(), -1,
            message.getMessage().getExpiresInSeconds() * 1000,
            viewOnce,
            ThreadDatabase.DistributionTypes.DEFAULT, quote.orNull(),
            sharedContacts.or(Collections.emptyList()),
            previews.or(Collections.emptyList()),
            Collections.emptyList(), Collections.emptyList());

    mediaMessage = new OutgoingSecureMediaMessage(mediaMessage);

    if (recipients.getExpireMessages() != message.getMessage().getExpiresInSeconds()) {
      handleSynchronizeSentExpirationUpdate(message);
    }

    long threadId  = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipients);

    database.beginTransaction();

    try {
      long messageId = database.insertMessageOutbox(mediaMessage, threadId, false, GroupReceiptDatabase.STATUS_UNKNOWN, null);

      if (recipients.requireAddress().isGroup()) {
        updateGroupReceiptStatus(message, messageId, recipients.requireAddress().toGroupString());
      }

    database.markAsSent(messageId, true);
      database.markUnidentified(messageId, message.isUnidentified(recipients.requireAddress().serialize()));

      List<DatabaseAttachment> allAttachments     = DatabaseFactory.getAttachmentDatabase(context).getAttachmentsForMessage(messageId);
      List<DatabaseAttachment> stickerAttachments = Stream.of(allAttachments).filter(Attachment::isSticker).toList();
      List<DatabaseAttachment> attachments        = Stream.of(allAttachments).filterNot(Attachment::isSticker).toList();

      forceStickerDownloadIfNecessary(stickerAttachments);

      for (DatabaseAttachment attachment : attachments) {
        ApplicationContext.getInstance(context)
                .getJobManager()
                .add(new AttachmentDownloadJob(messageId, attachment.getAttachmentId(), false));
      }

      if (message.getMessage().getExpiresInSeconds() > 0) {
        database.markExpireStarted(messageId, message.getExpirationStartTimestamp());
        ApplicationContext.getInstance(context)
                .getExpiringMessageManager()
                .scheduleDeletion(messageId, true,
                        message.getExpirationStartTimestamp(),
                        message.getMessage().getExpiresInSeconds() * 1000L);
      }

      if (recipients.isLocalNumber()) {
        SyncMessageId id = new SyncMessageId(recipients.getId(), message.getTimestamp());
        DatabaseFactory.getMmsSmsDatabase(context).incrementDeliveryReceiptCount(id, System.currentTimeMillis());
        DatabaseFactory.getMmsSmsDatabase(context).incrementReadReceiptCount(id, System.currentTimeMillis());
      }

      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }

    return threadId;
  }

  private void handleGroupRecipientUpdate(@NonNull SentTranscriptMessage message) {
    Recipient recipient = getSyncMessageDestination(message);

    if (!recipient.isGroup()) {
      Log.w(TAG, "Got recipient update for a non-group message! Skipping.");
      return;
    }

    MmsSmsDatabase database = DatabaseFactory.getMmsSmsDatabase(context);
    MessageRecord  record   = database.getMessageFor(message.getTimestamp(), Recipient.self().getId());

    if (record == null) {
      Log.w(TAG, "Got recipient update for non-existing message! Skipping.");
      return;
    }

    if (!record.isMms()) {
      Log.w(TAG, "Recipient update matched a non-MMS message! Skipping.");
      return;
    }

    updateGroupReceiptStatus(message, record.getId(), recipient.requireAddress().toGroupString());
  }

  private void updateGroupReceiptStatus(@NonNull SentTranscriptMessage message, long messageId, @NonNull String groupString) {
    GroupReceiptDatabase      receiptDatabase   = DatabaseFactory.getGroupReceiptDatabase(context);
    List<Recipient>           messageRecipients = Stream.of(message.getRecipients()).map(address -> Recipient.external(context, address)).toList();
    List<Recipient>           members           = DatabaseFactory.getGroupDatabase(context).getGroupMembers(groupString, false);
    Map<RecipientId, Integer> localReceipts     = Stream.of(receiptDatabase.getGroupReceiptInfo(messageId))
            .collect(Collectors.toMap(GroupReceiptInfo::getRecipientId, GroupReceiptInfo::getStatus));

    for (Recipient messageRecipient : messageRecipients) {
      //noinspection ConstantConditions
      if (localReceipts.containsKey(messageRecipient.getId()) && localReceipts.get(messageRecipient.getId()) < GroupReceiptDatabase.STATUS_UNDELIVERED) {
        receiptDatabase.update(messageRecipient.getId(), messageId, GroupReceiptDatabase.STATUS_UNDELIVERED, message.getTimestamp());
      } else if (!localReceipts.containsKey(messageRecipient.getId())) {
        receiptDatabase.insert(Collections.singletonList(messageRecipient.getId()), messageId, GroupReceiptDatabase.STATUS_UNDELIVERED, message.getTimestamp());
      }
    }

    for (Recipient member : members) {
      receiptDatabase.setUnidentified(member.getId(), messageId, message.isUnidentified(member.requireAddress().serialize()));
    }
  }

  private void handleTextMessage(@NonNull SignalServiceContent content,
                                 @NonNull SignalServiceDataMessage message,
                                 @NonNull Optional<Long> smsMessageId)
          throws StorageFailedException
  {
    SmsDatabase database  = DatabaseFactory.getSmsDatabase(context);
    String      body      = message.getBody().isPresent() ? message.getBody().get() : "";
    Recipient   recipient = getMessageDestination(content, message);

    if (message.getExpiresInSeconds() != recipient.getExpireMessages()) {
      handleExpirationUpdate(content, message, Optional.absent());
    }

    Long threadId;

    if (smsMessageId.isPresent() && !message.getGroupInfo().isPresent()) {
      threadId = database.updateBundleMessageBody(smsMessageId.get(), body).second;
    } else {
      notifyTypingStoppedFromIncomingMessage(recipient, content.getSender(), content.getSenderDevice());

      IncomingTextMessage textMessage = new IncomingTextMessage(Recipient.external(context, content.getSender()).getId(),
                                                                content.getSenderDevice(),
                                                                message.getTimestamp(), body,
                                                                message.getGroupInfo(),
                                                                message.getExpiresInSeconds() * 1000L,
                                                                content.isNeedsReceipt());

      textMessage = new IncomingEncryptedMessage(textMessage, body);
      Optional<InsertResult> insertResult = database.insertMessageInbox(textMessage);

      if (insertResult.isPresent()) threadId = insertResult.get().getThreadId();
      else                          threadId = null;

      if (smsMessageId.isPresent()) database.deleteMessage(smsMessageId.get());
    }

    if (threadId != null) {
      MessageNotifier.updateNotification(context, threadId);
    }
  }

  private long handleSynchronizeSentTextMessage(@NonNull SentTranscriptMessage message)
      throws MmsException
  {

    Recipient recipient       = getSyncMessageDestination(message);
    String    body            = message.getMessage().getBody().or("");
    long      expiresInMillis = message.getMessage().getExpiresInSeconds() * 1000L;

    if (recipient.getExpireMessages() != message.getMessage().getExpiresInSeconds()) {
      handleSynchronizeSentExpirationUpdate(message);
    }

    long    threadId  = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(recipient);
    boolean isGroup   = recipient.requireAddress().isGroup();

    MessagingDatabase database;
    long              messageId;

    if (isGroup) {
      OutgoingMediaMessage outgoingMediaMessage = new OutgoingMediaMessage(recipient, new SlideDeck(), body, message.getTimestamp(), -1, expiresInMillis, false, ThreadDatabase.DistributionTypes.DEFAULT, null, Collections.emptyList(), Collections.emptyList());
      outgoingMediaMessage = new OutgoingSecureMediaMessage(outgoingMediaMessage);

      messageId = DatabaseFactory.getMmsDatabase(context).insertMessageOutbox(outgoingMediaMessage, threadId, false, GroupReceiptDatabase.STATUS_UNKNOWN, null);
      database  = DatabaseFactory.getMmsDatabase(context);

      updateGroupReceiptStatus(message, messageId, recipient.requireAddress().toGroupString());

    } else {
      OutgoingTextMessage outgoingTextMessage = new OutgoingEncryptedMessage(recipient, body, expiresInMillis);

      messageId = DatabaseFactory.getSmsDatabase(context).insertMessageOutbox(threadId, outgoingTextMessage, false, message.getTimestamp(), null);
      database  = DatabaseFactory.getSmsDatabase(context);
      database.markUnidentified(messageId, message.isUnidentified(recipient.requireAddress().serialize()));
    }

    database.markAsSent(messageId, true);

    if (expiresInMillis > 0) {
      database.markExpireStarted(messageId, message.getExpirationStartTimestamp());
      ApplicationContext.getInstance(context)
                        .getExpiringMessageManager()
                        .scheduleDeletion(messageId, isGroup, message.getExpirationStartTimestamp(), expiresInMillis);
    }

    if (recipient.isLocalNumber()) {
      SyncMessageId id = new SyncMessageId(recipient.getId(), message.getTimestamp());
      DatabaseFactory.getMmsSmsDatabase(context).incrementDeliveryReceiptCount(id, System.currentTimeMillis());
      DatabaseFactory.getMmsSmsDatabase(context).incrementReadReceiptCount(id, System.currentTimeMillis());
    }

    return threadId;
  }

  private void handleInvalidVersionMessage(@NonNull String sender, int senderDevice, long timestamp,
                                           @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsInvalidVersionKeyExchange(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsInvalidVersionKeyExchange(smsMessageId.get());
    }
  }

  private void handleCorruptMessage(@NonNull String sender, int senderDevice, long timestamp,
                                    @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsDecryptFailed(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsDecryptFailed(smsMessageId.get());
    }
  }

  private void handleNoSessionMessage(@NonNull String sender, int senderDevice, long timestamp,
                                      @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsNoSession(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsNoSession(smsMessageId.get());
    }
  }

  private void handleUnsupportedDataMessage(@NonNull String sender,
                                            int senderDevice,
                                            @NonNull Optional<SignalServiceGroup> group,
                                            long timestamp,
                                            @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp, group);

      if (insertResult.isPresent()) {
        smsDatabase.markAsUnsupportedProtocolVersion(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsNoSession(smsMessageId.get());
    }
  }

  private void handleInvalidMessage(@NonNull String sender,
                                    int senderDevice,
                                    @NonNull Optional<SignalServiceGroup> group,
                                    long timestamp,
                                    @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp, group);

      if (insertResult.isPresent()) {
        smsDatabase.markAsInvalidMessage(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsNoSession(smsMessageId.get());
    }
  }

  private void handleLegacyMessage(@NonNull String sender, int senderDevice, long timestamp,
                                   @NonNull Optional<Long> smsMessageId)
  {
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);

    if (!smsMessageId.isPresent()) {
      Optional<InsertResult> insertResult = insertPlaceholder(sender, senderDevice, timestamp);

      if (insertResult.isPresent()) {
        smsDatabase.markAsLegacyVersion(insertResult.get().getMessageId());
        MessageNotifier.updateNotification(context, insertResult.get().getThreadId());
      }
    } else {
      smsDatabase.markAsLegacyVersion(smsMessageId.get());
    }
  }

  @SuppressWarnings("unused")
  private void handleDuplicateMessage(@NonNull String sender, int senderDeviceId, long timestamp,
                                      @NonNull Optional<Long> smsMessageId)
  {
    // Let's start ignoring these now
//    SmsDatabase smsDatabase = DatabaseFactory.getEncryptingSmsDatabase(context);
//
//    if (smsMessageId <= 0) {
//      Pair<Long, Long> messageAndThreadId = insertPlaceholder(masterSecret, envelope);
//      smsDatabase.markAsDecryptDuplicate(messageAndThreadId.first);
//      MessageNotifier.updateNotification(context, masterSecret, messageAndThreadId.second);
//    } else {
//      smsDatabase.markAsDecryptDuplicate(smsMessageId);
//    }
  }

  private void handleProfileKey(@NonNull SignalServiceContent content,
                                @NonNull SignalServiceDataMessage message)
  {
    RecipientDatabase database  = DatabaseFactory.getRecipientDatabase(context);
    Recipient         recipient = Recipient.external(context, content.getSender());

    if (recipient.getProfileKey() == null || !MessageDigest.isEqual(recipient.getProfileKey(), message.getProfileKey().get())) {
      database.setProfileKey(recipient.getId(), message.getProfileKey().get());
      database.setUnidentifiedAccessMode(recipient.getId(), RecipientDatabase.UnidentifiedAccessMode.UNKNOWN);
      ApplicationContext.getInstance(context).getJobManager().add(new RetrieveProfileJob(recipient));
    }
  }

  private void handleNeedsDeliveryReceipt(@NonNull SignalServiceContent content,
                                          @NonNull SignalServiceDataMessage message)
  {
    ApplicationContext.getInstance(context)
            .getJobManager()
            .add(new SendDeliveryReceiptJob(Recipient.external(context, content.getSender()).getId(), message.getTimestamp()));
  }

  @SuppressLint("DefaultLocale")
  private void handleDeliveryReceipt(@NonNull SignalServiceContent content,
                                     @NonNull SignalServiceReceiptMessage message)
  {
    for (long timestamp : message.getTimestamps()) {
      Log.i(TAG, String.format("Received encrypted delivery receipt: (XXXXX, %d)", timestamp));
      DatabaseFactory.getMmsSmsDatabase(context)
              .incrementDeliveryReceiptCount(new SyncMessageId(Recipient.external(context, content.getSender()).getId(), timestamp), System.currentTimeMillis());
    }
  }

  @SuppressLint("DefaultLocale")
  private void handleReadReceipt(@NonNull SignalServiceContent content,
                                 @NonNull SignalServiceReceiptMessage message)
  {
    if (TextSecurePreferences.isReadReceiptsEnabled(context)) {
      for (long timestamp : message.getTimestamps()) {
        Log.i(TAG, String.format("Received encrypted read receipt: (XXXXX, %d)", timestamp));

        DatabaseFactory.getMmsSmsDatabase(context)
                .incrementReadReceiptCount(new SyncMessageId(Recipient.external(context, content.getSender()).getId(), timestamp), content.getTimestamp());
      }
    }
  }

  private void handleTypingMessage(@NonNull SignalServiceContent content,
                                   @NonNull SignalServiceTypingMessage typingMessage)
  {
    if (!TextSecurePreferences.isTypingIndicatorsEnabled(context)) {
      return;
    }

    Recipient author = Recipient.external(context, content.getSender());

    long threadId;

    if (typingMessage.getGroupId().isPresent()) {
      RecipientId recipientId  = DatabaseFactory.getRecipientDatabase(context).getOrInsertFromGroupId(GroupUtil.getEncodedId(typingMessage.getGroupId().get(), false));
      Recipient groupRecipient = Recipient.resolved(recipientId);

      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(groupRecipient);
    } else {
      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(author);
    }

    if (threadId <= 0) {
      Log.w(TAG, "Couldn't find a matching thread for a typing message.");
      return;
    }

    if (typingMessage.isTypingStarted()) {
      Log.d(TAG, "Typing started on thread " + threadId);
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStarted(context,threadId, author, content.getSenderDevice());
    } else {
      Log.d(TAG, "Typing stopped on thread " + threadId);
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStopped(context, threadId, author, content.getSenderDevice(), false);
    }
  }

  private boolean isInvalidMessage(@NonNull SignalServiceDataMessage message) {
    if (message.isViewOnce()) {
      return !message.getAttachments().isPresent()       ||
              message.getAttachments().get().size() != 1  ||
              !MediaUtil.isImageType(message.getAttachments().get().get(0).getContentType().toLowerCase());

    }

    return false;
  }

  private Optional<QuoteModel> getValidatedQuote(Optional<SignalServiceDataMessage.Quote> quote) {
    if (!quote.isPresent()) return Optional.absent();

    if (quote.get().getId() <= 0) {
      Log.w(TAG, "Received quote without an ID! Ignoring...");
      return Optional.absent();
    }

    if (quote.get().getAuthor() == null) {
      Log.w(TAG, "Received quote without an author! Ignoring...");
      return Optional.absent();
    }

    RecipientId   author  = Recipient.external(context, quote.get().getAuthor().getNumber()).getId();
    MessageRecord message = DatabaseFactory.getMmsSmsDatabase(context).getMessageFor(quote.get().getId(), author);

    if (message != null) {
      Log.i(TAG, "Found matching message record...");

      List<Attachment> attachments = new LinkedList<>();

      if (message.isMms()) {
        MmsMessageRecord mmsMessage = (MmsMessageRecord) message;

        if (!mmsMessage.isViewOnce()) {
          attachments = mmsMessage.getSlideDeck().asAttachments();

          if (attachments.isEmpty()) {
            attachments.addAll(Stream.of(mmsMessage.getLinkPreviews())
                    .filter(lp -> lp.getThumbnail().isPresent())
                    .map(lp -> lp.getThumbnail().get())
                    .toList());
          }
        } else if (quote.get().getAttachments().size() > 0) {
          attachments.add(new TombstoneAttachment(quote.get().getAttachments().get(0).getContentType(), true));
        }
      }

      return Optional.of(new QuoteModel(quote.get().getId(), author, message.getBody(), false, attachments));
    }

    Log.w(TAG, "Didn't find matching message record...");
    return Optional.of(new QuoteModel(quote.get().getId(),
                                      author,
                                      quote.get().getText(),
                                      true,
                                      PointerAttachment.forPointers(quote.get().getAttachments())));
  }

  private Optional<Attachment> getStickerAttachment(Optional<SignalServiceDataMessage.Sticker> sticker) {
    if (!sticker.isPresent()) {
      return Optional.absent();
    }

    if (sticker.get().getPackId() == null || sticker.get().getPackKey() == null || sticker.get().getAttachment() == null) {
      Log.w(TAG, "Malformed sticker!");
      return Optional.absent();
    }

    String          packId          = Hex.toStringCondensed(sticker.get().getPackId());
    String          packKey         = Hex.toStringCondensed(sticker.get().getPackKey());
    int             stickerId       = sticker.get().getStickerId();
    StickerLocator  stickerLocator  = new StickerLocator(packId, packKey, stickerId);
    StickerDatabase stickerDatabase = DatabaseFactory.getStickerDatabase(context);
    StickerRecord   stickerRecord   = stickerDatabase.getSticker(stickerLocator.getPackId(), stickerLocator.getStickerId(), false);

    if (stickerRecord != null) {
      return Optional.of(new UriAttachment(stickerRecord.getUri(),
              stickerRecord.getUri(),
              MediaUtil.IMAGE_WEBP,
              AttachmentDatabase.TRANSFER_PROGRESS_DONE,
              stickerRecord.getSize(),
              StickerSlide.WIDTH,
              StickerSlide.HEIGHT,
              null,
              String.valueOf(new SecureRandom().nextLong()),
              false,
              false,
              null,
              stickerLocator));
    } else {
      return Optional.of(PointerAttachment.forPointer(Optional.of(sticker.get().getAttachment()), stickerLocator).get());
    }
  }

  private Optional<List<Contact>> getContacts(Optional<List<SharedContact>> sharedContacts) {
    if (!sharedContacts.isPresent()) return Optional.absent();

    List<Contact> contacts = new ArrayList<>(sharedContacts.get().size());

    for (SharedContact sharedContact : sharedContacts.get()) {
      contacts.add(ContactModelMapper.remoteToLocal(sharedContact));
    }

    return Optional.of(contacts);
  }

  private Optional<List<LinkPreview>> getLinkPreviews(Optional<List<Preview>> previews, @NonNull String message) {
    if (!previews.isPresent()) return Optional.absent();

    List<LinkPreview> linkPreviews = new ArrayList<>(previews.get().size());

    for (Preview preview : previews.get()) {
      Optional<Attachment> thumbnail     = PointerAttachment.forPointer(preview.getImage());
      Optional<String>     url           = Optional.fromNullable(preview.getUrl());
      Optional<String>     title         = Optional.fromNullable(preview.getTitle());
      boolean              hasContent    = !TextUtils.isEmpty(title.or("")) || thumbnail.isPresent();
      boolean              presentInBody = url.isPresent() && Stream.of(LinkPreviewUtil.findWhitelistedUrls(message)).map(Link::getUrl).collect(Collectors.toSet()).contains(url.get());
      boolean              validDomain   = url.isPresent() && LinkPreviewUtil.isWhitelistedLinkUrl(url.get());

      if (hasContent && presentInBody && validDomain) {
        LinkPreview linkPreview = new LinkPreview(url.get(), title.or(""), thumbnail);
        linkPreviews.add(linkPreview);
      } else {
        Log.w(TAG, String.format("Discarding an invalid link preview. hasContent: %b presentInBody: %b validDomain: %b", hasContent, presentInBody, validDomain));
      }
    }

    return Optional.of(linkPreviews);
  }

  private Optional<InsertResult> insertPlaceholder(@NonNull String sender, int senderDevice, long timestamp) {
    return insertPlaceholder(sender, senderDevice, timestamp, Optional.absent());
  }

  private Optional<InsertResult> insertPlaceholder(@NonNull String sender, int senderDevice, long timestamp, Optional<SignalServiceGroup> group) {
    SmsDatabase         database    = DatabaseFactory.getSmsDatabase(context);
    IncomingTextMessage textMessage = new IncomingTextMessage(Recipient.external(context, sender).getId(),
            senderDevice, timestamp, "",
            group, 0, false);

    textMessage = new IncomingEncryptedMessage(textMessage, "");
    return database.insertMessageInbox(textMessage);
  }

  private Recipient getSyncMessageDestination(SentTranscriptMessage message) {
    if (message.getMessage().getGroupInfo().isPresent()) {
      return Recipient.external(context, GroupUtil.getEncodedId(message.getMessage().getGroupInfo().get().getGroupId(), false));
    } else {
      return Recipient.external(context, message.getDestination().get());
    }
  }

  private Recipient getMessageDestination(SignalServiceContent content, SignalServiceDataMessage message) {
    if (message.getGroupInfo().isPresent()) {
      return Recipient.external(context, GroupUtil.getEncodedId(message.getGroupInfo().get().getGroupId(), false));
    } else {
      return Recipient.external(context, content.getSender());
    }
  }

  private void notifyTypingStoppedFromIncomingMessage(@NonNull Recipient conversationRecipient, @NonNull String sender, int device) {
    Recipient author   = Recipient.external(context, sender);
    long      threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(conversationRecipient);

    if (threadId > 0) {
      Log.d(TAG, "Typing stopped on thread " + threadId + " due to an incoming message.");
      ApplicationContext.getInstance(context).getTypingStatusRepository().onTypingStopped(context, threadId, author, device, true);
    }
  }

  private boolean shouldIgnore(@Nullable SignalServiceContent content) {
    if (content == null) {
      Log.w(TAG, "Got a message with null content.");
      return true;
    }

    Recipient sender = Recipient.external(context, content.getSender());

    if (content.getDataMessage().isPresent()) {
      SignalServiceDataMessage message      = content.getDataMessage().get();
      Recipient                conversation = getMessageDestination(content, message);

      if (conversation.isGroup() && conversation.isBlocked()) {
        return true;
      } else if (conversation.isGroup()) {
        GroupDatabase    groupDatabase = DatabaseFactory.getGroupDatabase(context);
        Optional<String> groupId       = message.getGroupInfo().isPresent() ? Optional.of(GroupUtil.getEncodedId(message.getGroupInfo().get().getGroupId(), false))
                : Optional.absent();
        if (groupId.isPresent() && groupDatabase.isUnknownGroup(groupId.get())) {
          return false;
        }

        boolean isTextMessage    = message.getBody().isPresent();
        boolean isMediaMessage   = message.getAttachments().isPresent() || message.getQuote().isPresent() || message.getSharedContacts().isPresent();
        boolean isExpireMessage  = message.isExpirationUpdate();
        boolean isContentMessage = !message.isGroupUpdate() && (isTextMessage || isMediaMessage || isExpireMessage);
        boolean isGroupActive    = groupId.isPresent() && groupDatabase.isActive(groupId.get());
        boolean isLeaveMessage   = message.getGroupInfo().isPresent() && message.getGroupInfo().get().getType() == SignalServiceGroup.Type.QUIT;

        return (isContentMessage && !isGroupActive) || (sender.isBlocked() && !isLeaveMessage);
      } else {
        return sender.isBlocked();
      }
    } else if (content.getCallMessage().isPresent() || content.getTypingMessage().isPresent()) {
      return sender.isBlocked();
    }

    return false;
  }

  private void resetRecipientToPush(@NonNull Recipient recipient) {
    if (recipient.isForceSmsSelection()) {
      DatabaseFactory.getRecipientDatabase(context).setForceSmsSelection(recipient.getId(), false);
    }
  }

  private void forceStickerDownloadIfNecessary(List<DatabaseAttachment> stickerAttachments) {
    if (stickerAttachments.isEmpty()) return;

    DatabaseAttachment stickerAttachment = stickerAttachments.get(0);

    if (stickerAttachment.getTransferState() != AttachmentDatabase.TRANSFER_PROGRESS_DONE) {
      AttachmentDownloadJob downloadJob = new AttachmentDownloadJob(messageId, stickerAttachment.getAttachmentId(), true);

      try {
        downloadJob.setContext(context);
        downloadJob.doWork();
      } catch (Exception e) {
        Log.w(TAG, "Failed to download sticker inline. Scheduling.");
        ApplicationContext.getInstance(context).getJobManager().add(downloadJob);
      }
    }
  }

  @SuppressWarnings("WeakerAccess")
  private static class StorageFailedException extends Exception {
    private final String sender;
    private final int senderDevice;

    private StorageFailedException(Exception e, String sender, int senderDevice) {
      super(e);
      this.sender       = sender;
      this.senderDevice = senderDevice;
    }

    public String getSender() {
      return sender;
    }

    public int getSenderDevice() {
      return senderDevice;
    }
  }

  public static final class Factory implements Job.Factory<PushDecryptJob> {
    @Override
    public @NonNull PushDecryptJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new PushDecryptJob(parameters, data.getLong(KEY_MESSAGE_ID), data.getLong(KEY_SMS_MESSAGE_ID));
    }
  }
}
