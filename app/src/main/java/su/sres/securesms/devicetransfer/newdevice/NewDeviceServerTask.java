package su.sres.securesms.devicetransfer.newdevice;

import android.content.Context;

import androidx.annotation.NonNull;

import net.sqlcipher.database.SQLiteDatabase;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import su.sres.core.util.logging.Log;
import su.sres.devicetransfer.ServerTask;
import su.sres.securesms.AppInitialization;
import su.sres.securesms.backup.BackupPassphrase;
import su.sres.securesms.backup.FullBackupBase;
import su.sres.securesms.backup.FullBackupImporter;
import su.sres.securesms.crypto.AttachmentSecretProvider;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.notifications.NotificationChannels;

import java.io.IOException;
import java.io.InputStream;

/**
 * Performs the restore with the backup data coming in over the input stream. Used in
 * conjunction with {@link su.sres.devicetransfer.DeviceToDeviceTransferService}.
 */
final class NewDeviceServerTask implements ServerTask {

    private static final String TAG = Log.tag(NewDeviceServerTask.class);

    @Override
    public void run(@NonNull Context context, @NonNull InputStream inputStream) {
        long start = System.currentTimeMillis();

        Log.i(TAG, "Starting backup restore.");

        EventBus.getDefault().register(this);
        try {
            SQLiteDatabase database = DatabaseFactory.getBackupDatabase(context);

            String passphrase = "deadbeef";

            BackupPassphrase.set(context, passphrase);
            FullBackupImporter.importFile(context,
                    AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret(),
                    database,
                    inputStream,
                    passphrase);

            DatabaseFactory.upgradeRestored(context, database);
            NotificationChannels.restoreContactNotificationChannels(context);

            AppInitialization.onPostBackupRestore(context);

            Log.i(TAG, "Backup restore complete.");
        } catch (FullBackupImporter.DatabaseDowngradeException e) {
            Log.w(TAG, "Failed due to the backup being from a newer version of Signal.", e);
            EventBus.getDefault().post(new Status(0, Status.State.FAILURE_VERSION_DOWNGRADE));
        } catch (IOException e) {
            Log.w(TAG, e);
            EventBus.getDefault().post(new Status(0, Status.State.FAILURE_UNKNOWN));
        } finally {
            EventBus.getDefault().unregister(this);
        }

        long end = System.currentTimeMillis();
        Log.i(TAG, "Receive took: " + (end - start));
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    public void onEvent(FullBackupBase.BackupEvent event) {
        if (event.getType() == FullBackupBase.BackupEvent.Type.PROGRESS) {
            EventBus.getDefault().post(new Status(event.getCount(), Status.State.IN_PROGRESS));
        } else if (event.getType() == FullBackupBase.BackupEvent.Type.FINISHED) {
            EventBus.getDefault().post(new Status(event.getCount(), Status.State.SUCCESS));
        }
    }

    public static final class Status {
        private final long  messageCount;
        private final State state;

        public Status(long messageCount, State state) {
            this.messageCount = messageCount;
            this.state        = state;
        }

        public long getMessageCount() {
            return messageCount;
        }

        public @NonNull State getState() {
            return state;
        }

        public enum State {
            IN_PROGRESS,
            SUCCESS,
            FAILURE_VERSION_DOWNGRADE,
            FAILURE_UNKNOWN
        }
    }
}
