package su.sres.securesms.jobs;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.documentfile.provider.DocumentFile;

import su.sres.securesms.ApplicationPreferencesActivity;
import su.sres.securesms.R;
import su.sres.securesms.backup.BackupPassphrase;
import su.sres.securesms.backup.FullBackupExporter;
import su.sres.securesms.crypto.AttachmentSecretProvider;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.logging.Log;
import su.sres.securesms.notifications.NotificationChannels;
import su.sres.securesms.service.GenericForegroundService;
import su.sres.securesms.service.NotificationController;
import su.sres.securesms.util.BackupUtil;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Backup Job for installs requiring Scoped Storage.
 *
 * @see LocalBackupJob#enqueue(boolean)
 */
public final class LocalBackupJobApi29 extends BaseJob {

    public static final String KEY = "LocalBackupJobApi29";

    private static final String TAG = Log.tag(LocalBackupJobApi29.class);

    private static final short BACKUP_FAILED_ID = 31321;

    public static final String TEMP_BACKUP_FILE_PREFIX = ".backup";
    public static final String TEMP_BACKUP_FILE_SUFFIX = ".tmp";

    LocalBackupJobApi29(@NonNull Parameters parameters) {
        super(parameters);
    }

    @Override
    public @NonNull Data serialize() {
        return Data.EMPTY;
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    public void onRun() throws IOException {
        Log.i(TAG, "Executing backup job...");

        NotificationManagerCompat.from(context).cancel(BACKUP_FAILED_ID);

        if (!BackupUtil.isUserSelectionRequired(context)) {
            throw new IOException("Wrong backup job!");
        }

        Uri backupDirectoryUri = SignalStore.settings().getSignalBackupDirectory();
        if (backupDirectoryUri == null || backupDirectoryUri.getPath() == null) {
            throw new IOException("Backup Directory has not been selected!");
        }

        try (NotificationController notification = GenericForegroundService.startForegroundTask(context,
                context.getString(R.string.LocalBackupJob_creating_backup),
                NotificationChannels.BACKUPS,
                R.drawable.ic_signal_backup))
        {
            notification.setIndeterminateProgress();

            String       backupPassword  = BackupPassphrase.get(context);
            DocumentFile backupDirectory = DocumentFile.fromTreeUri(context, backupDirectoryUri);
            String       timestamp       = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date());
            String       fileName        = String.format("signal-%s.backup", timestamp);

            if (backupDirectory == null || !backupDirectory.canWrite()) {
                BackupUtil.disableBackups(context);
                postBackupsDisabledNotification();
                throw new IOException("Cannot write to backup directory location.");
            }

            deleteOldTemporaryBackups(backupDirectory);

            if (backupDirectory.findFile(fileName) != null) {
                throw new IOException("Backup file already exists!");
            }

            String       temporaryName = String.format(Locale.US, "%s%s%s", TEMP_BACKUP_FILE_PREFIX, UUID.randomUUID(), TEMP_BACKUP_FILE_SUFFIX);
            DocumentFile temporaryFile = backupDirectory.createFile("application/octet-stream", temporaryName);

            if (temporaryFile == null) {
                throw new IOException("Failed to create temporary backup file.");
            }

            if (backupPassword == null) {
                throw new IOException("Backup password is null");
            }

            try {
                FullBackupExporter.export(context,
                        AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret(),
                        DatabaseFactory.getBackupDatabase(context),
                        temporaryFile,
                        backupPassword);

                if (!temporaryFile.renameTo(fileName)) {
                    Log.w(TAG, "Failed to rename temp file");
                    throw new IOException("Renaming temporary backup file failed!");
                }
            } finally {
                DocumentFile fileToCleanUp = backupDirectory.findFile(temporaryName);
                if (fileToCleanUp != null) {
                    if (fileToCleanUp.delete()) {
                        Log.w(TAG, "Backup failed. Deleted temp file");
                    } else {
                        Log.w(TAG, "Backup failed. Failed to delete temp file " + temporaryName);
                    }
                }
            }

            BackupUtil.deleteOldBackups();
        }
    }

    private static void deleteOldTemporaryBackups(@NonNull DocumentFile backupDirectory) {
        for (DocumentFile file : backupDirectory.listFiles()) {
            if (file.isFile()) {
                String name = file.getName();
                if (name != null && name.startsWith(TEMP_BACKUP_FILE_PREFIX) && name.endsWith(TEMP_BACKUP_FILE_SUFFIX)) {
                    if (file.delete()) {
                        Log.w(TAG, "Deleted old temporary backup file");
                    } else {
                        Log.w(TAG, "Could not delete old temporary backup file");
                    }
                }
            }
        }
    }

    @Override
    public boolean onShouldRetry(@NonNull Exception e) {
        return false;
    }

    @Override
    public void onFailure() {
    }

    private void postBackupsDisabledNotification() {
        Intent intent = new Intent(context, ApplicationPreferencesActivity.class);

        intent.putExtra(ApplicationPreferencesActivity.LAUNCH_TO_BACKUPS_FRAGMENT, true);

        PendingIntent pendingIntent            = PendingIntent.getActivity(context, -1, intent, 0);
        Notification  backupFailedNotification = new NotificationCompat.Builder(context, NotificationChannels.BACKUPS)
                .setSmallIcon(R.drawable.ic_signal_backup)
                .setContentTitle(context.getString(R.string.LocalBackupJobApi29_backups_disabled))
                .setContentText(context.getString(R.string.LocalBackupJobApi29_your_backup_directory_has_been_deleted_or_moved))
                .setContentIntent(pendingIntent)
                .build();

        NotificationManagerCompat.from(context)
                .notify(BACKUP_FAILED_ID, backupFailedNotification);
    }

    public static class Factory implements Job.Factory<LocalBackupJobApi29> {
        @Override
        public @NonNull
        LocalBackupJobApi29 create(@NonNull Parameters parameters, @NonNull Data data) {
            return new LocalBackupJobApi29(parameters);
        }
    }
}