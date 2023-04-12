package su.sres.securesms.migrations;

import androidx.annotation.NonNull;

import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobs.StickerPackDownloadJob;
import su.sres.securesms.stickers.BlessedPacks;

/**
 * Installs Day by Day blessed pack.
 */
public class StickerDayByDayMigrationJob extends MigrationJob {

    public static final String KEY = "StickerDayByDayMigrationJob";

    StickerDayByDayMigrationJob() {
        this(new Parameters.Builder().build());
    }

    private StickerDayByDayMigrationJob(@NonNull Parameters parameters) {
        super(parameters);
    }

    @Override
    public boolean isUiBlocking() {
        return false;
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @Override
    public void performMigration() {
        ApplicationDependencies.getJobManager().add(StickerPackDownloadJob.forInstall(BlessedPacks.DAY_BY_DAY.getPackId(), BlessedPacks.DAY_BY_DAY.getPackKey(), false));
    }

    @Override
    boolean shouldRetry(@NonNull Exception e) {
        return false;
    }

    public static class Factory implements Job.Factory<StickerDayByDayMigrationJob> {
        @Override
        public @NonNull StickerDayByDayMigrationJob create(@NonNull Parameters parameters, @NonNull Data data) {
            return new StickerDayByDayMigrationJob(parameters);
        }
    }
}
