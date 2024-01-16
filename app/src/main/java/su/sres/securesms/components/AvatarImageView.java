package su.sres.securesms.components;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.fragment.app.FragmentActivity;

import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.Transformation;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.bitmap.CircleCrop;
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;

import su.sres.core.util.logging.Log;
import su.sres.securesms.R;
import su.sres.securesms.color.MaterialColor;
import su.sres.securesms.contacts.avatars.ContactColors;
import su.sres.securesms.contacts.avatars.ContactPhoto;
import su.sres.securesms.contacts.avatars.ProfileContactPhoto;
import su.sres.securesms.contacts.avatars.ResourceContactPhoto;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.groups.ui.managegroup.ManageGroupActivity;
import su.sres.securesms.mms.GlideApp;
import su.sres.securesms.mms.GlideRequests;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.ui.bottomsheet.RecipientBottomSheetDialogFragment;
import su.sres.securesms.recipients.ui.managerecipient.ManageRecipientActivity;
import su.sres.securesms.util.AvatarUtil;
import su.sres.securesms.util.BlurTransformation;
import su.sres.securesms.util.ThemeUtil;
import su.sres.securesms.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class AvatarImageView extends AppCompatImageView {

    private static final int SIZE_LARGE = 1;
    private static final int SIZE_SMALL = 2;

    @SuppressWarnings("unused")
    private static final String TAG = Log.tag(AvatarImageView.class);

    private static final Paint LIGHT_THEME_OUTLINE_PAINT = new Paint();
    private static final Paint DARK_THEME_OUTLINE_PAINT = new Paint();

    static {
        LIGHT_THEME_OUTLINE_PAINT.setColor(Color.argb((int) (255 * 0.2), 0, 0, 0));
        LIGHT_THEME_OUTLINE_PAINT.setStyle(Paint.Style.STROKE);
        LIGHT_THEME_OUTLINE_PAINT.setStrokeWidth(1);
        LIGHT_THEME_OUTLINE_PAINT.setAntiAlias(true);

        DARK_THEME_OUTLINE_PAINT.setColor(Color.argb((int) (255 * 0.2), 255, 255, 255));
        DARK_THEME_OUTLINE_PAINT.setStyle(Paint.Style.STROKE);
        DARK_THEME_OUTLINE_PAINT.setStrokeWidth(1);
        DARK_THEME_OUTLINE_PAINT.setAntiAlias(true);
    }

    private int size;
    private boolean inverted;
    private Paint outlinePaint;
    private OnClickListener listener;
    private Recipient.FallbackPhotoProvider fallbackPhotoProvider;
    private boolean                         blurred;

    private @Nullable RecipientContactPhoto recipientContactPhoto;
    private @NonNull Drawable unknownRecipientDrawable;

    public AvatarImageView(Context context) {
        super(context);
        initialize(context, null);
    }

    public AvatarImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize(context, attrs);
    }

    private void initialize(@NonNull Context context, @Nullable AttributeSet attrs) {
        setScaleType(ScaleType.CENTER_CROP);

        if (attrs != null) {
            TypedArray typedArray = context.getTheme().obtainStyledAttributes(attrs, R.styleable.AvatarImageView, 0, 0);
            inverted = typedArray.getBoolean(R.styleable.AvatarImageView_inverted, false);
            size = typedArray.getInt(R.styleable.AvatarImageView_fallbackImageSize, SIZE_LARGE);
            typedArray.recycle();
        }

        outlinePaint = ThemeUtil.isDarkTheme(getContext()) ? DARK_THEME_OUTLINE_PAINT : LIGHT_THEME_OUTLINE_PAINT;

        unknownRecipientDrawable = new ResourceContactPhoto(R.drawable.ic_profile_outline_40, R.drawable.ic_profile_outline_20).asDrawable(getContext(), ContactColors.UNKNOWN_COLOR.toConversationColor(getContext()), inverted);
        blurred                  = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth() - getPaddingRight() - getPaddingLeft();
        float height = getHeight() - getPaddingBottom() - getPaddingTop();
        float cx = width / 2f;
        float cy = height / 2f;
        float radius = Math.min(cx, cy) - (outlinePaint.getStrokeWidth() / 2f);

        canvas.translate(getPaddingLeft(), getPaddingTop());
        canvas.drawCircle(cx, cy, radius, outlinePaint);
    }

    @Override
    public void setOnClickListener(OnClickListener listener) {
        this.listener = listener;
        super.setOnClickListener(listener);
    }

    public void setFallbackPhotoProvider(Recipient.FallbackPhotoProvider fallbackPhotoProvider) {
        this.fallbackPhotoProvider = fallbackPhotoProvider;
    }

    /**
     * Shows self as the actual profile picture.
     */
    public void setRecipient(@NonNull Recipient recipient) {
        setRecipient(recipient, false);
    }

    /**
     * Shows self as the actual profile picture.
     */
    public void setRecipient(@NonNull Recipient recipient, boolean quickContactEnabled) {
        if (recipient.isSelf()) {
            setAvatar(GlideApp.with(this), null, quickContactEnabled);
            AvatarUtil.loadIconIntoImageView(recipient, this);
        } else {
            setAvatar(GlideApp.with(this), recipient, quickContactEnabled);
        }
    }

    /**
     * Shows self as the note to self icon.
     */
    public void setAvatar(@Nullable Recipient recipient) {
        setAvatar(GlideApp.with(this), recipient, false);
    }

    /**
     * Shows self as the profile avatar.
     */
    public void setAvatarUsingProfile(@Nullable Recipient recipient) {
        setAvatar(GlideApp.with(this), recipient, false, true);
    }

    public void setAvatar(@NonNull GlideRequests requestManager, @Nullable Recipient recipient, boolean quickContactEnabled) {
        setAvatar(requestManager, recipient, quickContactEnabled, false);
    }

    public void setAvatar(@NonNull GlideRequests requestManager, @Nullable Recipient recipient, boolean quickContactEnabled, boolean useSelfProfileAvatar) {
        if (recipient != null) {
            RecipientContactPhoto photo = (recipient.isSelf() && useSelfProfileAvatar) ? new RecipientContactPhoto(recipient,
                    new ProfileContactPhoto(Recipient.self(),
                            Recipient.self().getProfileAvatar()))
                    : new RecipientContactPhoto(recipient);

            boolean shouldBlur = recipient.shouldBlurAvatar();

            if (!photo.equals(recipientContactPhoto) || shouldBlur != blurred) {
                requestManager.clear(this);
                recipientContactPhoto = photo;

                Drawable fallbackContactPhotoDrawable = size == SIZE_SMALL ? photo.recipient.getSmallFallbackContactPhotoDrawable(getContext(), inverted, fallbackPhotoProvider)
                        : photo.recipient.getFallbackContactPhotoDrawable(getContext(), inverted, fallbackPhotoProvider);

                if (photo.contactPhoto != null) {

                    List<Transformation<Bitmap>> transforms = new ArrayList<>();
                    if (shouldBlur) {
                        transforms.add(new BlurTransformation(ApplicationDependencies.getApplication(), 0.25f, BlurTransformation.MAX_RADIUS));
                    }
                    transforms.add(new CircleCrop());
                    blurred = shouldBlur;

                    requestManager.load(photo.contactPhoto)
                            .fallback(fallbackContactPhotoDrawable)
                            .error(fallbackContactPhotoDrawable)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .downsample(DownsampleStrategy.CENTER_INSIDE)
                            .transform(new MultiTransformation<>(transforms))
                            .into(this);
                } else {
                    setImageDrawable(fallbackContactPhotoDrawable);
                }
            }

            setAvatarClickHandler(recipient, quickContactEnabled);
        } else {
            recipientContactPhoto = null;
            requestManager.clear(this);
            if (fallbackPhotoProvider != null) {
                setImageDrawable(fallbackPhotoProvider.getPhotoForRecipientWithoutName()
                        .asDrawable(getContext(), MaterialColor.STEEL.toAvatarColor(getContext()), inverted));
            } else {
                setImageDrawable(unknownRecipientDrawable);
            }

            disableQuickContact();
        }
    }

    private void setAvatarClickHandler(@NonNull final Recipient recipient,
                                       boolean quickContactEnabled) {
        if (quickContactEnabled) {
            super.setOnClickListener(v -> {
                Context context = getContext();
                if (recipient.isPushGroup()) {
                    context.startActivity(ManageGroupActivity.newIntent(context, recipient.requireGroupId().requirePush()),
                            ManageGroupActivity.createTransitionBundle(context, this));
                } else {
                    if (context instanceof FragmentActivity) {
                        RecipientBottomSheetDialogFragment.create(recipient.getId(), null)
                                .show(((FragmentActivity) context).getSupportFragmentManager(), "BOTTOM");
                    } else {
                        context.startActivity(ManageRecipientActivity.newIntent(context, recipient.getId()),
                                ManageRecipientActivity.createTransitionBundle(context, this));
                    }
                }
            });
        } else {
            disableQuickContact();
        }
    }

    public void setImageBytesForGroup(@Nullable byte[] avatarBytes,
                                      @Nullable Recipient.FallbackPhotoProvider fallbackPhotoProvider,
                                      @NonNull MaterialColor color) {
        Drawable fallback = Util.firstNonNull(fallbackPhotoProvider, Recipient.DEFAULT_FALLBACK_PHOTO_PROVIDER)
                .getPhotoForGroup()
                .asDrawable(getContext(), color.toAvatarColor(getContext()));

        GlideApp.with(this)
                .load(avatarBytes)
                .fallback(fallback)
                .error(fallback)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .circleCrop()
                .into(this);
    }

    public void setNonAvatarImageResource(@DrawableRes int imageResource) {
        recipientContactPhoto = null;
        setImageResource(imageResource);
    }

    public void disableQuickContact() {
        super.setOnClickListener(listener);
        setClickable(listener != null);
    }

    private static class RecipientContactPhoto {
        private final @NonNull Recipient recipient;
        private final @Nullable ContactPhoto contactPhoto;
        private final boolean ready;

        RecipientContactPhoto(@NonNull Recipient recipient) {
            this(recipient, recipient.getContactPhoto());
        }

        RecipientContactPhoto(@NonNull Recipient recipient, @Nullable ContactPhoto contactPhoto) {
            this.recipient = recipient;
            this.ready = !recipient.isResolving();
            this.contactPhoto = contactPhoto;
        }

        public boolean equals(@Nullable RecipientContactPhoto other) {
            if (other == null) return false;
            return other.recipient.equals(recipient) &&
                    other.recipient.getColor().equals(recipient.getColor()) &&
                    other.ready == ready &&
                    Objects.equals(other.contactPhoto, contactPhoto);
        }
    }
}
