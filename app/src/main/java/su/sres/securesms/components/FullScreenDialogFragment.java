package su.sres.securesms.components;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.DialogFragment;

import su.sres.securesms.R;
import su.sres.securesms.util.ThemeUtil;

/**
 * Base dialog fragment for rendering as a full screen dialog with animation
 * transitions.
 */
public abstract class FullScreenDialogFragment extends DialogFragment {

    protected Toolbar toolbar;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setStyle(STYLE_NO_FRAME, ThemeUtil.isDarkTheme(requireActivity()) ? R.style.TextSecure_DarkTheme_FullScreenDialog
                : R.style.TextSecure_LightTheme_FullScreenDialog);
    }

    @Override
    public @NonNull View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.full_screen_dialog_fragment, container, false);
        inflater.inflate(getDialogLayoutResource(), view.findViewById(R.id.full_screen_dialog_content), true);
        toolbar = view.findViewById(R.id.full_screen_dialog_toolbar);
        toolbar.setTitle(getTitle());
        toolbar.setNavigationOnClickListener(v -> onNavigateUp());
        return view;
    }

    protected void onNavigateUp() {
        dismissAllowingStateLoss();
    }

    protected abstract @StringRes int getTitle();

    protected abstract @LayoutRes int getDialogLayoutResource();
}
