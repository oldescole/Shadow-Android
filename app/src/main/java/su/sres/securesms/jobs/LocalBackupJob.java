package su.sres.securesms.jobs;

import android.Manifest;

import androidx.annotation.NonNull;

import su.sres.securesms.backup.BackupFileIOError;
import su.sres.securesms.backup.BackupPassphrase;
import su.sres.securesms.database.ShadowDatabase;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.JobManager;
import su.sres.securesms.jobmanager.impl.ChargingConstraint;
import su.sres.core.util.logging.Log;

import su.sres.securesms.R;
import su.sres.securesms.backup.FullBackupExporter;
import su.sres.securesms.crypto.AttachmentSecretProvider;
import su.sres.securesms.database.NoExternalStorageException;
import su.sres.securesms.notifications.NotificationChannels;
import su.sres.securesms.permissions.Permissions;
import su.sres.securesms.service.GenericForegroundService;
import su.sres.securesms.service.NotificationController;
import su.sres.securesms.util.BackupUtil;
import su.sres.securesms.util.StorageUtil;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class LocalBackupJob extends BaseJob {

  public static final String KEY = "LocalBackupJob";

  private static final String TAG = Log.tag(LocalBackupJob.class);

  public static final String QUEUE = "__LOCAL_BACKUP__";

  public static final String TEMP_BACKUP_FILE_PREFIX = ".backup";
  public static final String TEMP_BACKUP_FILE_SUFFIX = ".tmp";

  public static void enqueue(boolean force) {
    JobManager jobManager = ApplicationDependencies.getJobManager();
    Parameters.Builder parameters = new Parameters.Builder()
        .setQueue(QUEUE)
        .setMaxInstancesForFactory(1)
        .setMaxAttempts(3);
    if (force) {
      jobManager.cancelAllInQueue(QUEUE);
    } else {
      parameters.addConstraint(ChargingConstraint.KEY);
    }

    if (BackupUtil.isUserSelectionRequired(ApplicationDependencies.getApplication())) {
      jobManager.add(new LocalBackupJobApi29(parameters.build()));
    } else {
      jobManager.add(new LocalBackupJob(parameters.build()));
    }
  }

  private LocalBackupJob(@NonNull Job.Parameters parameters) {
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
  public void onRun() throws NoExternalStorageException, IOException {
    Log.i(TAG, "Executing backup job...");

    BackupFileIOError.clearNotification(context);

    if (!Permissions.hasAll(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
      throw new IOException("No external storage permission!");
    }

    try (NotificationController notification = GenericForegroundService.startForegroundTask(context,
                                                                                            context.getString(R.string.LocalBackupJob_creating_backup),
                                                                                            NotificationChannels.BACKUPS,
                                                                                            R.drawable.ic_signal_backup))
    {
      notification.setIndeterminateProgress();
      String backupPassword  = BackupPassphrase.get(context);
      File   backupDirectory = StorageUtil.getOrCreateBackupDirectory();
      String timestamp       = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).format(new Date());
      String fileName        = String.format("shadow-%s.backup", timestamp);
      File   backupFile      = new File(backupDirectory, fileName);

      deleteOldTemporaryBackups(backupDirectory);

      if (backupFile.exists()) {
        throw new IOException("Backup file already exists?");
      }

      if (backupPassword == null) {
        throw new IOException("Backup password is null");
      }

      File tempFile = File.createTempFile(TEMP_BACKUP_FILE_PREFIX, TEMP_BACKUP_FILE_SUFFIX, backupDirectory);

      try {
        FullBackupExporter.export(context,
                                  AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret(),
                                  ShadowDatabase.getBackupDatabase(),
                                  tempFile,
                                  backupPassword,
                                  this::isCanceled);

        if (!tempFile.renameTo(backupFile)) {
          Log.w(TAG, "Failed to rename temp file");
          throw new IOException("Renaming temporary backup file failed!");
        }
      } catch (FullBackupExporter.BackupCanceledException e) {
        Log.w(TAG, "Backup cancelled");
        throw e;
      } catch (IOException e) {
        BackupFileIOError.postNotificationForException(context, e, getRunAttempt());
        throw e;
      } finally {
        if (tempFile.exists()) {
          if (tempFile.delete()) {
            Log.w(TAG, "Backup failed. Deleted temp file");
          } else {
            Log.w(TAG, "Backup failed. Failed to delete temp file " + tempFile);
          }
        }
      }

      BackupUtil.deleteOldBackups();
    }
  }

  private static void deleteOldTemporaryBackups(@NonNull File backupDirectory) {
    for (File file : backupDirectory.listFiles()) {
      if (file.isFile()) {
        String name = file.getName();
        if (name.startsWith(TEMP_BACKUP_FILE_PREFIX) && name.endsWith(TEMP_BACKUP_FILE_SUFFIX)) {
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
  public void onFailure() {}

  public static class Factory implements Job.Factory<LocalBackupJob> {
    @Override
    public @NonNull LocalBackupJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new LocalBackupJob(parameters);
    }
  }
}
