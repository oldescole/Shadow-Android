package su.sres.securesms.gcm;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Locale;

import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobs.FcmRefreshJob;
import su.sres.core.util.logging.Log;
import su.sres.securesms.registration.PushChallengeRequest;
import su.sres.securesms.util.TextSecurePreferences;

public class FcmReceiveService extends FirebaseMessagingService {

  private static final String TAG = Log.tag(FcmReceiveService.class);

  @Override
  public void onMessageReceived(RemoteMessage remoteMessage) {

    Log.i(TAG, String.format(Locale.US,
            "onMessageReceived() ID: %s, Delay: %d, Priority: %d, Original Priority: %d",
            remoteMessage.getMessageId(),
            (System.currentTimeMillis() - remoteMessage.getSentTime()),
            remoteMessage.getPriority(),
            remoteMessage.getOriginalPriority()));

    String challenge = remoteMessage.getData().get("challenge");
    if (challenge != null) {
      handlePushChallenge(challenge);
    } else {

      handleReceivedNotification(ApplicationDependencies.getApplication());
    }
  }

  @Override
  public void onDeletedMessages() {
    Log.w(TAG, "onDeleteMessages() -- Messages may have been dropped. Doing a normal message fetch.");
    handleReceivedNotification(ApplicationDependencies.getApplication());
  }

  @Override
  public void onNewToken(String token) {
    Log.i(TAG, "onNewToken()");

    if (!TextSecurePreferences.isPushRegistered(ApplicationDependencies.getApplication())) {
      Log.i(TAG, "Got a new FCM token, but the user isn't registered.");
      return;
    }

    ApplicationDependencies.getJobManager().add(new FcmRefreshJob());
  }

  @Override
  public void onMessageSent(@NonNull String s) {
    Log.i(TAG, "onMessageSent()" + s);
  }

  @Override
  public void onSendError(@NonNull String s, @NonNull Exception e) {
    Log.w(TAG, "onSendError()", e);
  }

  private static void handleReceivedNotification(Context context) {
    try {
      context.startService(new Intent(context, FcmFetchService.class));
    } catch (Exception e) {
      Log.w(TAG, "Failed to start service. Falling back to legacy approach.");
      FcmFetchService.retrieveMessages(context);
    }
  }

  private static void handlePushChallenge(@NonNull String challenge) {
    Log.d(TAG, String.format("Got a push challenge \"%s\"", challenge));

    PushChallengeRequest.postChallengeResponse(challenge);
  }
}