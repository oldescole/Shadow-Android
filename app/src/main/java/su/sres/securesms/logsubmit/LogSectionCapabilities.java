package su.sres.securesms.logsubmit;

import android.content.Context;

import androidx.annotation.NonNull;

import su.sres.securesms.AppCapabilities;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.recipients.Recipient;
import su.sres.signalservice.api.account.AccountAttributes;

public final class LogSectionCapabilities implements LogSection {

  @Override
  public @NonNull String getTitle() {
    return "CAPABILITIES";
  }

  @Override
  public @NonNull CharSequence getContent(@NonNull Context context) {
    if (!SignalStore.account().isRegistered()) {
      return "Unregistered";
    }

    if (SignalStore.account().getUserLogin() == null || SignalStore.account().getAci() == null) {
      return "Self not yet available!";
    }

    Recipient self = Recipient.self();

    AccountAttributes.Capabilities capabilities = AppCapabilities.getCapabilities(false);

    return new StringBuilder().append("-- Local").append("\n")
                              .append("GV2                : ").append(capabilities.isGv2()).append("\n")
                              .append("GV1 Migration      : ").append(capabilities.isGv1Migration()).append("\n")
                              .append("Sender Key         : ").append(capabilities.isSenderKey()).append("\n")
                              .append("Announcement Groups: ").append(capabilities.isAnnouncementGroup()).append("\n")
                              .append("Change Login      : ").append(capabilities.isChangeLogin()).append("\n")
                              .append("\n")
                              .append("-- Global").append("\n")
                              .append("GV2                : ").append(self.getGroupsV2Capability()).append("\n")
                              .append("GV1 Migration      : ").append(self.getGroupsV1MigrationCapability()).append("\n")
                              .append("Sender Key         : ").append(self.getSenderKeyCapability()).append("\n")
                              .append("Announcement Groups: ").append(self.getAnnouncementGroupCapability()).append("\n")
                              .append("Change Login      : ").append(self.getChangeLoginCapability()).append("\n");
  }
}