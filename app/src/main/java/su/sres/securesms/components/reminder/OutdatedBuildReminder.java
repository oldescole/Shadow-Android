package su.sres.securesms.components.reminder;

import android.content.Context;

import java.util.concurrent.TimeUnit;

import su.sres.securesms.R;
import su.sres.securesms.util.PlayStoreUtil;
import su.sres.securesms.util.Util;

/**
 * Reminder that is shown when a build is getting close to expiry (either because of the
 * compile-time constant, or remote deprecation).
 */
public class OutdatedBuildReminder extends Reminder {

  public OutdatedBuildReminder(final Context context) {
    super(null, getPluralsText(context));
    setOkListener(v -> PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(context));
    addAction(new Action(context.getString(R.string.OutdatedBuildReminder_update_now), R.id.reminder_action_update_now));
  }

  private static CharSequence getPluralsText(final Context context) {
    int days = getDaysUntilExpiry() - 1;
    return context.getResources().getQuantityString(R.plurals.OutdatedBuildReminder_your_version_of_signal_will_expire_in_n_days, days, days);
  }

  @Override
  public boolean isDismissable() {
    return false;
  }

  public static boolean isEligible() {
    return getDaysUntilExpiry() <= 10;
  }

  private static int getDaysUntilExpiry() {
    return (int) TimeUnit.MILLISECONDS.toDays(Util.getTimeUntilBuildExpiry());
  }

}
