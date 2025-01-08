package su.sres.securesms.jobs;

import androidx.annotation.NonNull;

import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Data;
import su.sres.securesms.jobmanager.Job;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.securesms.keyvalue.CertificateType;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.core.util.logging.Log;
import su.sres.signalservice.api.SignalServiceAccountManager;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;

import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

public final class RotateCertificateJob extends BaseJob {

  public static final String KEY = "RotateCertificateJob";

  private static final String TAG = Log.tag(RotateCertificateJob.class);

  public RotateCertificateJob() {
    this(new Job.Parameters.Builder()
             .setQueue("__ROTATE_SENDER_CERTIFICATE__")
             .addConstraint(NetworkConstraint.KEY)
             .setLifespan(TimeUnit.DAYS.toMillis(1))
             .setMaxAttempts(Parameters.UNLIMITED)
             .build());
  }

  private RotateCertificateJob(@NonNull Job.Parameters parameters) {
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
  public void onAdded() {}

  @Override
  public void onRun() throws IOException {
    if (!SignalStore.account().isRegistered()) {
      Log.w(TAG, "Not yet registered. Ignoring.");
      return;
    }

    synchronized (RotateCertificateJob.class) {
      SignalServiceAccountManager accountManager = ApplicationDependencies.getSignalServiceAccountManager();
      Collection<CertificateType> certificateTypes = SignalStore.userLoginPrivacy()
                                                                .getAllCertificateTypes();

      Log.i(TAG, "Rotating these certificates " + certificateTypes);

      for (CertificateType certificateType : certificateTypes) {
        byte[] certificate;

        switch (certificateType) {
          case UUID_AND_E164:
            certificate = accountManager.getSenderCertificate(); break;
          case UUID_ONLY:
            certificate = accountManager.getSenderCertificateForUserLoginPrivacy(); break;
          default:
            throw new AssertionError();
        }

        Log.i(TAG, String.format("Successfully got %s certificate", certificateType));
        SignalStore.certificateValues()
                   .setUnidentifiedAccessCertificate(certificateType, certificate);
      }
    }
  }

  @Override
  public boolean onShouldRetry(@NonNull Exception e) {
    return e instanceof PushNetworkException;
  }

  @Override
  public void onFailure() {
    Log.w(TAG, "Failed to rotate sender certificate!");
  }

  public static final class Factory implements Job.Factory<RotateCertificateJob> {
    @Override
    public @NonNull RotateCertificateJob create(@NonNull Parameters parameters, @NonNull Data data) {
      return new RotateCertificateJob(parameters);
    }
  }
}