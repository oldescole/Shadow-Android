package su.sres.securesms.messagerequests;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.core.util.Consumer;

import su.sres.securesms.database.GroupDatabase;
import su.sres.securesms.database.MessageDatabase;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.database.ShadowDatabase;
import su.sres.securesms.database.ThreadDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.groups.GroupChangeException;
import su.sres.securesms.groups.GroupManager;
import su.sres.securesms.groups.ui.GroupChangeErrorCallback;
import su.sres.securesms.groups.ui.GroupChangeFailureReason;
import su.sres.securesms.jobs.MultiDeviceMessageRequestResponseJob;
import su.sres.securesms.jobs.ReportSpamJob;
import su.sres.securesms.jobs.SendViewedReceiptJob;
import su.sres.core.util.logging.Log;
import su.sres.securesms.notifications.MarkReadReceiver;
import su.sres.securesms.recipients.LiveRecipient;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.recipients.RecipientUtil;
import su.sres.securesms.sms.MessageSender;
import su.sres.securesms.util.FeatureFlags;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.core.util.concurrent.SignalExecutors;
import su.sres.signalservice.internal.push.exceptions.GroupPatchNotAcceptedException;
import su.sres.storageservice.protos.groups.local.DecryptedGroup;

import org.whispersystems.libsignal.util.guava.Optional;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

final class MessageRequestRepository {

  private static final String TAG = Log.tag(MessageRequestRepository.class);

  private final Context  context;
  private final Executor executor;

  MessageRequestRepository(@NonNull Context context) {
    this.context  = context.getApplicationContext();
    this.executor = SignalExecutors.BOUNDED;
  }

  void getGroups(@NonNull RecipientId recipientId, @NonNull Consumer<List<String>> onGroupsLoaded) {
    executor.execute(() -> {
      GroupDatabase groupDatabase = ShadowDatabase.groups();
      onGroupsLoaded.accept(groupDatabase.getPushGroupNamesContainingMember(recipientId));
    });
  }

  void getGroupInfo(@NonNull RecipientId recipientId, @NonNull Consumer<GroupInfo> onGroupInfoLoaded) {
    executor.execute(() -> {
      GroupDatabase                       groupDatabase = ShadowDatabase.groups();
      Optional<GroupDatabase.GroupRecord> groupRecord   = groupDatabase.getGroup(recipientId);
      onGroupInfoLoaded.accept(groupRecord.transform(record -> {
        if (record.isV2Group()) {
          DecryptedGroup decryptedGroup = record.requireV2GroupProperties().getDecryptedGroup();
          return new GroupInfo(decryptedGroup.getMembersCount(), decryptedGroup.getPendingMembersCount(), decryptedGroup.getDescription());
        } else {
          return new GroupInfo(record.getMembers().size(), 0, "");
        }
      }).or(GroupInfo.ZERO));
    });
  }

  @WorkerThread
  @NonNull MessageRequestState getMessageRequestState(@NonNull Recipient recipient, long threadId) {
    if (recipient.isBlocked()) {
      if (recipient.isGroup()) {
        return MessageRequestState.BLOCKED_GROUP;
      } else {
        return MessageRequestState.BLOCKED_INDIVIDUAL;
      }
    } else if (threadId <= 0) {
      return MessageRequestState.NONE;
    } else if (recipient.isPushV2Group()) {
      switch (getGroupMemberLevel(recipient.getId())) {
        case NOT_A_MEMBER:
          return MessageRequestState.NONE;
        case PENDING_MEMBER:
          return MessageRequestState.GROUP_V2_INVITE;
        default:
          if (RecipientUtil.isMessageRequestAccepted(context, threadId)) {
            return MessageRequestState.NONE;
          } else {
            return MessageRequestState.GROUP_V2_ADD;
          }
      }
    } else if (!RecipientUtil.isLegacyProfileSharingAccepted(recipient) && isLegacyThread(recipient)) {
      if (recipient.isGroup()) {
        return MessageRequestState.LEGACY_GROUP_V1;
      } else {
        return MessageRequestState.LEGACY_INDIVIDUAL;
      }
    } else if (recipient.isPushV1Group()) {
      if (RecipientUtil.isMessageRequestAccepted(context, threadId)) {
        if (recipient.getParticipants().size() > FeatureFlags.groupLimits().getHardLimit()) {
          return MessageRequestState.DEPRECATED_GROUP_V1_TOO_LARGE;
        } else {
          return MessageRequestState.DEPRECATED_GROUP_V1;
        }
      } else if (!recipient.isActiveGroup()) {
        return MessageRequestState.NONE;
      } else {
        return MessageRequestState.GROUP_V1;
      }
    } else {
      if (RecipientUtil.isMessageRequestAccepted(context, threadId)) {
        return MessageRequestState.NONE;
      } else {
        return MessageRequestState.INDIVIDUAL;
      }
    }
  }

  void acceptMessageRequest(@NonNull LiveRecipient liveRecipient,
                            long threadId,
                            @NonNull Runnable onMessageRequestAccepted,
                            @NonNull GroupChangeErrorCallback error)
  {
    executor.execute(() -> {
      if (liveRecipient.get().isPushV2Group()) {
        try {
          Log.i(TAG, "GV2 accepting invite");
          GroupManager.acceptInvite(context, liveRecipient.get().requireGroupId().requireV2());

          RecipientDatabase recipientDatabase = ShadowDatabase.recipients();
          recipientDatabase.setProfileSharing(liveRecipient.getId(), true);

          onMessageRequestAccepted.run();
        } catch (GroupChangeException | IOException e) {
          Log.w(TAG, e);
          error.onError(GroupChangeFailureReason.fromException(e));
        }
      } else {
        RecipientDatabase recipientDatabase = ShadowDatabase.recipients();
        recipientDatabase.setProfileSharing(liveRecipient.getId(), true);

        MessageSender.sendProfileKey(context, threadId);

        List<MessageDatabase.MarkedMessageInfo> messageIds = ShadowDatabase.threads()
                                                                            .setEntireThreadRead(threadId);
        ApplicationDependencies.getMessageNotifier().updateNotification(context);
        MarkReadReceiver.process(context, messageIds);

        List<MessageDatabase.MarkedMessageInfo> viewedInfos = ShadowDatabase.mms()
                                                                             .getViewedIncomingMessages(threadId);

        SendViewedReceiptJob.enqueue(threadId, liveRecipient.getId(), viewedInfos);

        if (TextSecurePreferences.isMultiDevice(context)) {
          ApplicationDependencies.getJobManager().add(MultiDeviceMessageRequestResponseJob.forAccept(liveRecipient.getId()));
        }

        onMessageRequestAccepted.run();
      }
    });
  }

  void deleteMessageRequest(@NonNull LiveRecipient recipient,
                            long threadId,
                            @NonNull Runnable onMessageRequestDeleted,
                            @NonNull GroupChangeErrorCallback error)
  {
    executor.execute(() -> {
      Recipient resolved = recipient.resolve();

      if (resolved.isGroup() && resolved.requireGroupId().isPush()) {
        try {
          GroupManager.leaveGroupFromBlockOrMessageRequest(context, resolved.requireGroupId().requirePush());
        } catch (GroupChangeException | GroupPatchNotAcceptedException e) {
          if (ShadowDatabase.groups().isCurrentMember(resolved.requireGroupId().requirePush(), Recipient.self().getId())) {
            Log.w(TAG, "Failed to leave group, and we're still a member.", e);
            error.onError(GroupChangeFailureReason.fromException(e));
            return;
          } else {
            Log.w(TAG, "Failed to leave group, but we're not a member, so ignoring.");
          }
        } catch (IOException e) {
          Log.w(TAG, e);
          error.onError(GroupChangeFailureReason.fromException(e));
          return;
        }
      }

      if (TextSecurePreferences.isMultiDevice(context)) {
        ApplicationDependencies.getJobManager().add(MultiDeviceMessageRequestResponseJob.forDelete(recipient.getId()));
      }

      ThreadDatabase threadDatabase = ShadowDatabase.threads();
      threadDatabase.deleteConversation(threadId);

      onMessageRequestDeleted.run();
    });
  }

  void blockMessageRequest(@NonNull LiveRecipient liveRecipient,
                           @NonNull Runnable onMessageRequestBlocked,
                           @NonNull GroupChangeErrorCallback error)
  {
    executor.execute(() -> {
      Recipient recipient = liveRecipient.resolve();
      try {
        RecipientUtil.block(context, recipient);
      } catch (GroupChangeException | IOException e) {
        Log.w(TAG, e);
        error.onError(GroupChangeFailureReason.fromException(e));
        return;
      }
      liveRecipient.refresh();

      if (TextSecurePreferences.isMultiDevice(context)) {
        ApplicationDependencies.getJobManager().add(MultiDeviceMessageRequestResponseJob.forBlock(liveRecipient.getId()));
      }

      onMessageRequestBlocked.run();
    });
  }

  void blockAndReportSpamMessageRequest(@NonNull LiveRecipient liveRecipient,
                                        long threadId,
                                        @NonNull Runnable onMessageRequestBlocked,
                                        @NonNull GroupChangeErrorCallback error)
  {
    executor.execute(() -> {
      Recipient recipient = liveRecipient.resolve();
      try {
        RecipientUtil.block(context, recipient);
      } catch (GroupChangeException | IOException e) {
        Log.w(TAG, e);
        error.onError(GroupChangeFailureReason.fromException(e));
        return;
      }
      liveRecipient.refresh();

      ApplicationDependencies.getJobManager().add(new ReportSpamJob(threadId, System.currentTimeMillis()));

      if (TextSecurePreferences.isMultiDevice(context)) {
        ApplicationDependencies.getJobManager().add(MultiDeviceMessageRequestResponseJob.forBlockAndReportSpam(liveRecipient.getId()));
      }

      onMessageRequestBlocked.run();
    });
  }

  void unblockAndAccept(@NonNull LiveRecipient liveRecipient, long threadId, @NonNull Runnable onMessageRequestUnblocked) {
    executor.execute(() -> {
      Recipient recipient = liveRecipient.resolve();

      RecipientUtil.unblock(context, recipient);

      List<MessageDatabase.MarkedMessageInfo> messageIds = ShadowDatabase.threads()
                                                                          .setEntireThreadRead(threadId);
      ApplicationDependencies.getMessageNotifier().updateNotification(context);
      MarkReadReceiver.process(context, messageIds);

      if (TextSecurePreferences.isMultiDevice(context)) {
        ApplicationDependencies.getJobManager().add(MultiDeviceMessageRequestResponseJob.forAccept(liveRecipient.getId()));
      }

      onMessageRequestUnblocked.run();
    });
  }

  private GroupDatabase.MemberLevel getGroupMemberLevel(@NonNull RecipientId recipientId) {
    return ShadowDatabase.groups()
                          .getGroup(recipientId)
                          .transform(g -> g.memberLevel(Recipient.self()))
                          .or(GroupDatabase.MemberLevel.NOT_A_MEMBER);
  }

  @WorkerThread
  private boolean isLegacyThread(@NonNull Recipient recipient) {
    Context context  = ApplicationDependencies.getApplication();
    Long    threadId = ShadowDatabase.threads().getThreadIdFor(recipient.getId());

    return threadId != null &&
           (RecipientUtil.hasSentMessageInThread(context, threadId) || RecipientUtil.isPreMessageRequestThread(context, threadId));
  }
}