package su.sres.securesms.giph.mp4;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.core.util.Consumer;

import su.sres.core.util.concurrent.SignalExecutors;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.giph.model.GiphyImage;
import su.sres.securesms.net.ContentProxySelector;
import su.sres.securesms.net.StandardUserAgentInterceptor;
import su.sres.securesms.providers.BlobProvider;
import su.sres.securesms.push.SignalServiceNetworkAccess;
import su.sres.securesms.util.MediaUtil;

import java.io.IOException;
import java.util.concurrent.Executor;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Repository responsible for downloading gifs selected by the user in the appropriate format.
 */
final class GiphyMp4Repository {

    private static final Executor EXECUTOR = SignalExecutors.BOUNDED;

    private final OkHttpClient client;

    GiphyMp4Repository() {
        this.client = new OkHttpClient.Builder().proxySelector(new ContentProxySelector())
                .addInterceptor(new StandardUserAgentInterceptor())
                .dns(SignalServiceNetworkAccess.DNS)
                .build();
    }

    void saveToBlob(@NonNull GiphyImage giphyImage, boolean isForMms, @NonNull Consumer<GiphyMp4SaveResult> resultConsumer) {
        EXECUTOR.execute(() -> {
            try {
                Uri blob = saveToBlobInternal(giphyImage, isForMms);
                resultConsumer.accept(new GiphyMp4SaveResult.Success(blob, giphyImage));
            } catch (IOException e) {
                resultConsumer.accept(new GiphyMp4SaveResult.Error(e));
            }
        });
    }

    @WorkerThread
    private @NonNull Uri saveToBlobInternal(@NonNull GiphyImage giphyImage, boolean isForMms) throws IOException {
        String  url;
        String  mime;

        if (isForMms) {
            url  = giphyImage.getGifMmsUrl();
            mime = MediaUtil.IMAGE_GIF;
        } else {
            url  = giphyImage.getMp4Url();
            mime = MediaUtil.VIDEO_MP4;
        }

        Request request = new Request.Builder().url(url).build();

        try (Response response = client.newCall(request).execute()) {
            if (response.code() >= 200 && response.code() < 300) {
                return BlobProvider.getInstance()
                        .forData(response.body().byteStream(), response.body().contentLength())
                        .withMimeType(mime)
                        .createForSingleSessionOnDisk(ApplicationDependencies.getApplication());
            } else {
                throw new IOException("Unexpected response code: " + response.code());
            }
        }
    }
}
