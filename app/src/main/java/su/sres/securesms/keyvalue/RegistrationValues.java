package su.sres.securesms.keyvalue;

import androidx.annotation.CheckResult;
import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

public final class RegistrationValues extends SignalStoreValues {

    private static final String REGISTRATION_COMPLETE = "registration.complete";
    private static final String HAS_UPLOADED_PROFILE  = "registration.has_uploaded_profile";
    private static final String SERVER_SET            = "registration.server_set";
    private static final String TRUSTSTORE_PASSWORD   = "registration.truststore_password";

    RegistrationValues(@NonNull KeyValueStore store) {
        super(store);
    }

    public synchronized void onFirstEverAppLaunch() {
        getStore().beginWrite()
                  .putBoolean(HAS_UPLOADED_PROFILE, false)
                  .putBoolean(REGISTRATION_COMPLETE, false)
                  .commit();
    }

    @Override
    @NonNull
    List<String> getKeysToIncludeInBackup() {
        return Collections.emptyList();
    }

    public synchronized void clearRegistrationComplete() {
        onFirstEverAppLaunch();
    }

    public synchronized void setRegistrationComplete() {
        getStore().beginWrite()
                  .putBoolean(REGISTRATION_COMPLETE, true)
                  .commit();
    }

    @CheckResult
    public synchronized boolean isRegistrationComplete() {
        return getStore().getBoolean(REGISTRATION_COMPLETE, true);
    }

    public String getStorePass() {
        return getString(TRUSTSTORE_PASSWORD, null);
    }

    public synchronized void setStorePass(String storePass) {
        putString(TRUSTSTORE_PASSWORD, storePass);
    }

    public synchronized void setServerSet(boolean flag) {
        putBoolean(SERVER_SET, flag);
    }

    public synchronized boolean isServerSet() {
        return getBoolean(SERVER_SET, false);
    }

    public boolean hasUploadedProfile() {
        return getBoolean(HAS_UPLOADED_PROFILE, true);
    }

    public void markHasUploadedProfile() {
        putBoolean(HAS_UPLOADED_PROFILE, true);
    }

    public void clearHasUploadedProfile() {
        putBoolean(HAS_UPLOADED_PROFILE, false);
    }
}