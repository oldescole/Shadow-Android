package su.sres.securesms.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import su.sres.core.util.concurrent.SignalExecutors;
import su.sres.securesms.R;
import su.sres.securesms.crypto.ReentrantSessionLock;
import su.sres.securesms.crypto.storage.TextSecureIdentityKeyStore;
import su.sres.securesms.database.GroupDatabase;
import su.sres.securesms.database.IdentityDatabase;
import su.sres.securesms.database.ShadowDatabase;
import su.sres.securesms.database.model.IdentityRecord;
import su.sres.securesms.database.MessageDatabase;
import su.sres.securesms.database.MessageDatabase.InsertResult;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.core.util.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.sms.IncomingIdentityDefaultMessage;
import su.sres.securesms.sms.IncomingIdentityUpdateMessage;
import su.sres.securesms.sms.IncomingIdentityVerifiedMessage;
import su.sres.securesms.sms.IncomingTextMessage;
import su.sres.securesms.sms.OutgoingIdentityDefaultMessage;
import su.sres.securesms.sms.OutgoingIdentityVerifiedMessage;
import su.sres.securesms.sms.OutgoingTextMessage;
import su.sres.securesms.util.concurrent.ListenableFuture;
import su.sres.securesms.util.concurrent.SettableFuture;

import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.libsignal.state.SessionRecord;
import org.whispersystems.libsignal.state.SessionStore;
import org.whispersystems.libsignal.util.guava.Optional;

import su.sres.securesms.util.concurrent.SimpleTask;
import su.sres.signalservice.api.SignalSessionLock;
import su.sres.signalservice.api.messages.multidevice.VerifiedMessage;

import java.util.List;

public final class IdentityUtil {

  private IdentityUtil() {}

  private static final String TAG = Log.tag(IdentityUtil.class);

  public static ListenableFuture<Optional<IdentityRecord>> getRemoteIdentityKey(final Context context, final Recipient recipient) {
    final SettableFuture<Optional<IdentityRecord>> future      = new SettableFuture<>();
    final RecipientId                              recipientId = recipient.getId();

    SimpleTask.run(SignalExecutors.BOUNDED,
                   () -> ApplicationDependencies.getIdentityStore().getIdentityRecord(recipientId),
                   future::set);

    return future;
  }

  public static void markIdentityVerified(Context context, Recipient recipient, boolean verified, boolean remote)
  {
    long            time          = System.currentTimeMillis();
    MessageDatabase smsDatabase   = ShadowDatabase.sms();
    GroupDatabase   groupDatabase = ShadowDatabase.groups();

    try (GroupDatabase.Reader reader = groupDatabase.getGroups()) {

      GroupDatabase.GroupRecord groupRecord;

      while ((groupRecord = reader.getNext()) != null) {
        if (groupRecord.getMembers().contains(recipient.getId()) && groupRecord.isActive() && !groupRecord.isMms()) {

          if (remote) {
            IncomingTextMessage incoming = new IncomingTextMessage(recipient.getId(), 1, time, -1, time, null, Optional.of(groupRecord.getId()), 0, false, null);

            if (verified) incoming = new IncomingIdentityVerifiedMessage(incoming);
            else incoming = new IncomingIdentityDefaultMessage(incoming);

            smsDatabase.insertMessageInbox(incoming);
          } else {
            RecipientId         recipientId    = ShadowDatabase.recipients().getOrInsertFromGroupId(groupRecord.getId());
            Recipient           groupRecipient = Recipient.resolved(recipientId);
            long                threadId       = ShadowDatabase.threads().getOrCreateThreadIdFor(groupRecipient);
            OutgoingTextMessage outgoing;

            if (verified) outgoing = new OutgoingIdentityVerifiedMessage(recipient);
            else outgoing = new OutgoingIdentityDefaultMessage(recipient);

            ShadowDatabase.sms().insertMessageOutbox(threadId, outgoing, false, time, null);
            ShadowDatabase.threads().update(threadId, true);
          }
        }
      }
    }

    if (remote) {
      IncomingTextMessage incoming = new IncomingTextMessage(recipient.getId(), 1, time, -1, time, null, Optional.absent(), 0, false, null);

      if (verified) incoming = new IncomingIdentityVerifiedMessage(incoming);
      else incoming = new IncomingIdentityDefaultMessage(incoming);

      smsDatabase.insertMessageInbox(incoming);
    } else {
      OutgoingTextMessage outgoing;

      if (verified) outgoing = new OutgoingIdentityVerifiedMessage(recipient);
      else outgoing = new OutgoingIdentityDefaultMessage(recipient);

      long threadId = ShadowDatabase.threads().getOrCreateThreadIdFor(recipient);

      Log.i(TAG, "Inserting verified outbox...");
      ShadowDatabase.sms().insertMessageOutbox(threadId, outgoing, false, time, null);
      ShadowDatabase.threads().update(threadId, true);
    }
  }

  public static void markIdentityUpdate(@NonNull Context context, @NonNull RecipientId recipientId) {
    long            time          = System.currentTimeMillis();
    MessageDatabase smsDatabase   = ShadowDatabase.sms();
    GroupDatabase   groupDatabase = ShadowDatabase.groups();

    try (GroupDatabase.Reader reader = groupDatabase.getGroups()) {
      GroupDatabase.GroupRecord groupRecord;

      while ((groupRecord = reader.getNext()) != null) {
        if (groupRecord.getMembers().contains(recipientId) && groupRecord.isActive()) {
          IncomingTextMessage           incoming    = new IncomingTextMessage(recipientId, 1, time, time, time, null, Optional.of(groupRecord.getId()), 0, false, null);
          IncomingIdentityUpdateMessage groupUpdate = new IncomingIdentityUpdateMessage(incoming);

          smsDatabase.insertMessageInbox(groupUpdate);
        }
      }
    }

    IncomingTextMessage           incoming         = new IncomingTextMessage(recipientId, 1, time, -1, time, null, Optional.absent(), 0, false, null);
    IncomingIdentityUpdateMessage individualUpdate = new IncomingIdentityUpdateMessage(incoming);
    Optional<InsertResult>        insertResult     = smsDatabase.insertMessageInbox(individualUpdate);

    if (insertResult.isPresent()) {
      ApplicationDependencies.getMessageNotifier().updateNotification(context, insertResult.get().getThreadId());
    }
  }

  public static void saveIdentity(String user, IdentityKey identityKey) {
    try (SignalSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      SessionStore          sessionStore = ApplicationDependencies.getSessionStore();
      SignalProtocolAddress address      = new SignalProtocolAddress(user, 1);

      if (ApplicationDependencies.getIdentityStore().saveIdentity(address, identityKey)) {
        if (sessionStore.containsSession(address)) {
          SessionRecord sessionRecord = sessionStore.loadSession(address);
          sessionRecord.archiveCurrentState();

          sessionStore.storeSession(address, sessionRecord);
        }
      }
    }
  }

  public static void processVerifiedMessage(Context context, VerifiedMessage verifiedMessage) {
    try (SignalSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
      TextSecureIdentityKeyStore identityStore  = ApplicationDependencies.getIdentityStore();
      Recipient                  recipient      = Recipient.externalPush(context, verifiedMessage.getDestination());
      Optional<IdentityRecord>   identityRecord = identityStore.getIdentityRecord(recipient.getId());

      if (!identityRecord.isPresent() && verifiedMessage.getVerified() == VerifiedMessage.VerifiedState.DEFAULT) {
        Log.w(TAG, "No existing record for default status");
        return;
      }

      if (verifiedMessage.getVerified() == VerifiedMessage.VerifiedState.DEFAULT &&
          identityRecord.isPresent() &&
          identityRecord.get().getIdentityKey().equals(verifiedMessage.getIdentityKey()) &&
          identityRecord.get().getVerifiedStatus() != IdentityDatabase.VerifiedStatus.DEFAULT)
      {
        identityStore.setVerified(recipient.getId(), identityRecord.get().getIdentityKey(), IdentityDatabase.VerifiedStatus.DEFAULT);
        markIdentityVerified(context, recipient, false, true);
      }

      if (verifiedMessage.getVerified() == VerifiedMessage.VerifiedState.VERIFIED &&
          (!identityRecord.isPresent() ||
           (identityRecord.isPresent() && !identityRecord.get().getIdentityKey().equals(verifiedMessage.getIdentityKey())) ||
           (identityRecord.isPresent() && identityRecord.get().getVerifiedStatus() != IdentityDatabase.VerifiedStatus.VERIFIED)))
      {
        saveIdentity(verifiedMessage.getDestination().getIdentifier(), verifiedMessage.getIdentityKey());
        identityStore.setVerified(recipient.getId(), verifiedMessage.getIdentityKey(), IdentityDatabase.VerifiedStatus.VERIFIED);
        markIdentityVerified(context, recipient, true, true);
      }
    }
  }


  public static @Nullable String getUnverifiedBannerDescription(@NonNull Context context,
                                                                @NonNull List<Recipient> unverified)
  {
    return getPluralizedIdentityDescription(context, unverified,
                                            R.string.IdentityUtil_unverified_banner_one,
                                            R.string.IdentityUtil_unverified_banner_two,
                                            R.string.IdentityUtil_unverified_banner_many);
  }

  public static @Nullable String getUnverifiedSendDialogDescription(@NonNull Context context,
                                                                    @NonNull List<Recipient> unverified)
  {
    return getPluralizedIdentityDescription(context, unverified,
                                            R.string.IdentityUtil_unverified_dialog_one,
                                            R.string.IdentityUtil_unverified_dialog_two,
                                            R.string.IdentityUtil_unverified_dialog_many);
  }

  public static @Nullable String getUntrustedSendDialogDescription(@NonNull Context context,
                                                                   @NonNull List<Recipient> untrusted)
  {
    return getPluralizedIdentityDescription(context, untrusted,
                                            R.string.IdentityUtil_untrusted_dialog_one,
                                            R.string.IdentityUtil_untrusted_dialog_two,
                                            R.string.IdentityUtil_untrusted_dialog_many);
  }

  private static @Nullable String getPluralizedIdentityDescription(@NonNull Context context,
                                                                   @NonNull List<Recipient> recipients,
                                                                   @StringRes int resourceOne,
                                                                   @StringRes int resourceTwo,
                                                                   @StringRes int resourceMany)
  {
    if (recipients.isEmpty()) return null;

    if (recipients.size() == 1) {
      String name = recipients.get(0).getDisplayName(context);
      return context.getString(resourceOne, name);
    } else {
      String firstName  = recipients.get(0).getDisplayName(context);
      String secondName = recipients.get(1).getDisplayName(context);

      if (recipients.size() == 2) {
        return context.getString(resourceTwo, firstName, secondName);
      } else {
        int    othersCount = recipients.size() - 2;
        String nMore       = context.getResources().getQuantityString(R.plurals.identity_others, othersCount, othersCount);

        return context.getString(resourceMany, firstName, secondName, nMore);
      }
    }
  }
}
