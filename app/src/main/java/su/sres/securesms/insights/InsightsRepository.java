package su.sres.securesms.insights;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.util.Consumer;

import com.annimon.stream.Stream;

import su.sres.securesms.R;
import su.sres.securesms.contacts.avatars.GeneratedContactPhoto;
import su.sres.securesms.contacts.avatars.ProfileContactPhoto;
import su.sres.securesms.database.MmsSmsDatabase;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.database.ShadowDatabase;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.sms.MessageSender;
import su.sres.securesms.sms.OutgoingTextMessage;
import su.sres.securesms.util.Util;
import su.sres.securesms.util.concurrent.SimpleTask;

import org.whispersystems.libsignal.util.guava.Optional;

import java.util.List;

public class InsightsRepository implements InsightsDashboardViewModel.Repository, InsightsModalViewModel.Repository {

  private final Context context;

  public InsightsRepository(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public void getInsightsData(@NonNull Consumer<InsightsData> insightsDataConsumer) {
    SimpleTask.run(() -> {
      MmsSmsDatabase mmsSmsDatabase = ShadowDatabase.mmsSms();
      int            insecure       = mmsSmsDatabase.getInsecureMessageCountForInsights();
      int            secure         = mmsSmsDatabase.getSecureMessageCountForInsights();

      if (insecure + secure == 0) {
        return new InsightsData(false, 0);
      } else {
        return new InsightsData(true, Util.clamp((int) Math.ceil((insecure * 100f) / (insecure + secure)), 0, 100));
      }
    }, insightsDataConsumer::accept);
  }

  @Override
  public void getInsecureRecipients(@NonNull Consumer<List<Recipient>> insecureRecipientsConsumer) {
    SimpleTask.run(() -> {
                     RecipientDatabase recipientDatabase      = ShadowDatabase.recipients();
                     List<RecipientId> unregisteredRecipients = recipientDatabase.getUninvitedRecipientsForInsights();

                     return Stream.of(unregisteredRecipients)
                                  .map(Recipient::resolved)
                                  .toList();
                   },
                   insecureRecipientsConsumer::accept);
  }

  @Override
  public void getUserAvatar(@NonNull Consumer<InsightsUserAvatar> avatarConsumer) {
    SimpleTask.run(() -> {
      Recipient self = Recipient.self().resolve();
      String    name = Optional.fromNullable(self.getDisplayName(context)).or("");

      return new InsightsUserAvatar(new ProfileContactPhoto(self, self.getProfileAvatar()),
                                    self.getAvatarColor(),
                                    new GeneratedContactPhoto(name, R.drawable.ic_profile_outline_40));
    }, avatarConsumer::accept);
  }

  @Override
  public void sendSmsInvite(@NonNull Recipient recipient, Runnable onSmsMessageSent) {
    SimpleTask.run(() -> {
      Recipient resolved       = recipient.resolve();
      int       subscriptionId = resolved.getDefaultSubscriptionId().or(-1);
      String    message        = context.getString(R.string.InviteActivity_lets_switch_to_signal, context.getString(R.string.install_url));

      MessageSender.send(context, new OutgoingTextMessage(resolved, message, subscriptionId), -1L, true, null, null);

      RecipientDatabase database = ShadowDatabase.recipients();
      database.setHasSentInvite(recipient.getId());

      return null;
    }, v -> onSmsMessageSent.run());
  }
}