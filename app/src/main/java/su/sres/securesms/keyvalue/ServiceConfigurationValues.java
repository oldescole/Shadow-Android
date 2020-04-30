package su.sres.securesms.keyvalue;

import androidx.annotation.CheckResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class ServiceConfigurationValues {

    private static final String SHADOW_SERVICE_URL = "service_confifuration.shadow_service_url";
    private static final String CLOUD_URL = "service_configuration.cloud_url";
    private static final String STORAGE_URL = "service_configuration.storage_url";
    private static final String STATUS_URL = "service_configuration.status_url";
    private static final String UNIDENTIFIED_ACCESS_CA_PUBLIC_KEY = "service_configuration.unidentified_access_ca_public_key";
//    private static final String SERVER_CERT_PUBLIC_KEY = "service_configuration.server_cert_public_key";

    public static final String EXAMPLE_URI = "https://example.com";

    private final KeyValueStore store;

    ServiceConfigurationValues(@NonNull KeyValueStore store) {
        this.store = store;
    }

    public synchronized void setShadowUrl(String shadowUrl) {
        store.beginWrite()
                .putString(SHADOW_SERVICE_URL, shadowUrl)
                .commit();
    }

    public synchronized void setCloudUrl(String cloudUrl) {
        store.beginWrite()
                .putString(CLOUD_URL, cloudUrl)
                .commit();
    }

    public synchronized void setStorageUrl(String storageUrl) {
        store.beginWrite()
                .putString(STORAGE_URL, storageUrl)
                .commit();
    }

    public synchronized void setStatusUrl(String statusUrl) {
        store.beginWrite()
                .putString(STATUS_URL, statusUrl)
                .commit();
    }

    public synchronized void setUnidentifiedAccessCaPublicKey(byte[] unidentifiedAccessCaPublicKey) {
        store.beginWrite()
                .putBlob(UNIDENTIFIED_ACCESS_CA_PUBLIC_KEY, unidentifiedAccessCaPublicKey)
                .commit();
    }

//    public synchronized void setServerCaPublicKey(byte[] serverCaPublicKey) {
//        store.beginWrite()
//                .putBlob(SERVER_CERT_PUBLIC_KEY, serverCaPublicKey)
//                .commit();
//    }

    public @Nullable
    String getShadowUrl() {
        return store.getString(SHADOW_SERVICE_URL, EXAMPLE_URI);
    }

    public @Nullable
    String getCloudUrl() {
        return store.getString(CLOUD_URL, EXAMPLE_URI);
    }

    public @Nullable
    String getStorageUrl() {
        return store.getString(STORAGE_URL, EXAMPLE_URI);
    }

    public @Nullable
    String getStatusUrl() {
        return store.getString(STATUS_URL, EXAMPLE_URI);
    }

    public @Nullable
    byte[] getUnidentifiedAccessCaPublicKey() {
        return store.getBlob(UNIDENTIFIED_ACCESS_CA_PUBLIC_KEY, null);
    }

//    public @Nullable
//    byte[] getServerCaPublicKey() {
//        return store.getBlob(SERVER_CERT_PUBLIC_KEY, null);
//    }
}