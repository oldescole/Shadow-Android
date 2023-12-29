package su.sres.securesms.giph.mp4;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;

import su.sres.core.util.logging.Log;
import su.sres.securesms.R;
import su.sres.securesms.components.CornerMask;

/**
 * Video Player class specifically created for the GiphyMp4Fragment.
 */
public final class GiphyMp4VideoPlayer extends FrameLayout implements DefaultLifecycleObserver {

    @SuppressWarnings("unused")
    private static final String TAG = Log.tag(GiphyMp4VideoPlayer.class);

    private final PlayerView exoView;
    private       ExoPlayer  exoPlayer;
    private CornerMask cornerMask;

    public GiphyMp4VideoPlayer(Context context) {
        this(context, null);
    }

    public GiphyMp4VideoPlayer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GiphyMp4VideoPlayer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        inflate(context, R.layout.gif_player, this);

        this.exoView = findViewById(R.id.video_view);
    }

    @Override
    protected void onDetachedFromWindow() {
        Log.d(TAG, "onDetachedFromWindow");
        super.onDetachedFromWindow();
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);

        if (cornerMask != null) {
            cornerMask.mask(canvas);
        }
    }

    void setExoPlayer(@NonNull ExoPlayer exoPlayer) {
        exoView.setPlayer(exoPlayer);
        this.exoPlayer = exoPlayer;
    }

    void setVideoSource(@NonNull MediaSource mediaSource) {
        exoPlayer.prepare(mediaSource);
    }

    void setCornerMask(@Nullable CornerMask cornerMask) {
        this.cornerMask = new CornerMask(this, cornerMask);
        invalidate();
    }

    void play() {
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(true);
        }
    }

    void stop() {
        if (exoPlayer != null) {
            exoPlayer.stop(true);
        }
    }

    long getDuration() {
        if (exoPlayer != null) {
            return exoPlayer.getDuration();
        } else {
            return C.LENGTH_UNSET;
        }
    }

    void setResizeMode(@AspectRatioFrameLayout.ResizeMode int resizeMode) {
        exoView.setResizeMode(resizeMode);
    }

    @Override public void onDestroy(@NonNull LifecycleOwner owner) {
        if (exoPlayer != null) {
            exoPlayer.release();
        }
    }
}