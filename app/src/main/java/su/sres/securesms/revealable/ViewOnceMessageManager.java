package su.sres.securesms.revealable;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import su.sres.securesms.ApplicationContext;
import su.sres.securesms.database.AttachmentDatabase;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.MessageDatabase;
import su.sres.core.util.logging.Log;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.service.TimedEventManager;

/**
 * Manages clearing removable message content after they're opened.
 */
public class ViewOnceMessageManager extends TimedEventManager<ViewOnceExpirationInfo> {

    private static final String TAG = Log.tag(ViewOnceMessageManager.class);

    private final MessageDatabase mmsDatabase;
    private final AttachmentDatabase attachmentDatabase;

    public ViewOnceMessageManager(@NonNull Application application) {
        super(application, "ViewOnceMessageManager");

        this.mmsDatabase        = DatabaseFactory.getMmsDatabase(application);
        this.attachmentDatabase = DatabaseFactory.getAttachmentDatabase(application);

        scheduleIfNecessary();
    }

    @WorkerThread
    @Override
    protected @Nullable ViewOnceExpirationInfo getNextClosestEvent() {
        ViewOnceExpirationInfo expirationInfo = mmsDatabase.getNearestExpiringViewOnceMessage();

        if (expirationInfo != null) {
            Log.i(TAG, "Next closest expiration is in " + getDelayForEvent(expirationInfo) + " ms for messsage " + expirationInfo.getMessageId() + ".");
        } else {
            Log.i(TAG, "No messages to schedule.");
        }

        return expirationInfo;
    }

    @WorkerThread
    @Override
    protected void executeEvent(@NonNull ViewOnceExpirationInfo event) {
        Log.i(TAG, "Deleting attachments for message " + event.getMessageId());
        attachmentDatabase.deleteAttachmentFilesForViewOnceMessage(event.getMessageId());
    }

    @WorkerThread
    @Override
    protected long getDelayForEvent(@NonNull ViewOnceExpirationInfo event) {
        long expiresAt = event.getReceiveTime() + ViewOnceUtil.MAX_LIFESPAN;
        long timeLeft  = expiresAt - System.currentTimeMillis();

        return Math.max(0, timeLeft);
    }

    @AnyThread
    @Override
    protected void scheduleAlarm(@NonNull Application application, long delay) {
        setAlarm(application, delay, ViewOnceAlarm.class);
    }

    public static class ViewOnceAlarm extends BroadcastReceiver {

        private static final String TAG = Log.tag(ViewOnceAlarm.class);

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive()");
            ApplicationDependencies.getViewOnceMessageManager().scheduleIfNecessary();
        }
    }
}