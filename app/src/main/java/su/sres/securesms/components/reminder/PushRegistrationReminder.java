package su.sres.securesms.components.reminder;

import android.content.Context;

import su.sres.securesms.R;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.registration.RegistrationNavigationActivity;

public class PushRegistrationReminder extends Reminder {

  public PushRegistrationReminder(final Context context) {
    super(context.getString(R.string.reminder_header_push_title),
          context.getString(R.string.reminder_header_push_text));

    setOkListener(v -> context.startActivity(RegistrationNavigationActivity.newIntentForReRegistration(context)));
  }

  @Override
  public boolean isDismissable() {
    return false;
  }

  public static boolean isEligible(Context context) {
    return !SignalStore.account().isRegistered();
  }
}
