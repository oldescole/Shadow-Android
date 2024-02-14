package su.sres.securesms.components.identity;

import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import su.sres.securesms.R;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.IdentityDatabase;
import su.sres.securesms.database.IdentityDatabase.IdentityRecord;
import su.sres.signalservice.api.SignalSessionLock;

import java.util.List;

public class UnverifiedSendDialog extends AlertDialog.Builder implements DialogInterface.OnClickListener {

  private final List<IdentityRecord> untrustedRecords;
  private final ResendListener       resendListener;

  public UnverifiedSendDialog(@NonNull Context context,
                              @NonNull String message,
                              @NonNull List<IdentityRecord> untrustedRecords,
                              @NonNull ResendListener resendListener)
  {
    super(context);
    this.untrustedRecords = untrustedRecords;
    this.resendListener   = resendListener;

    setTitle(R.string.UnverifiedSendDialog_send_message);
    setIcon(R.drawable.ic_warning);
    setMessage(message);
    setPositiveButton(R.string.UnverifiedSendDialog_send, this);
    setNegativeButton(android.R.string.cancel, null);
  }

  @Override
  public void onClick(DialogInterface dialog, int which) {
    final IdentityDatabase identityDatabase = DatabaseFactory.getIdentityDatabase(getContext());

    new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... params) {
        try(SignalSessionLock.Lock unused = ReentrantSessionLock.INSTANCE.acquire()) {
          for (IdentityRecord identityRecord : untrustedRecords) {
            identityDatabase.setVerified(identityRecord.getRecipientId(),
                                         identityRecord.getIdentityKey(),
                                         IdentityDatabase.VerifiedStatus.DEFAULT);
          }
        }

        return null;
      }

      @Override
      protected void onPostExecute(Void result) {
        resendListener.onResendMessage();
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  public interface ResendListener {
    public void onResendMessage();
  }
}
