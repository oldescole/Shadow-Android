package su.sres.securesms.groups.ui.invitesandrequests.joining;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import su.sres.securesms.R;
import su.sres.securesms.util.BottomSheetUtil;
import su.sres.securesms.util.PlayStoreUtil;
import su.sres.securesms.util.ThemeUtil;

public final class GroupJoinUpdateRequiredBottomSheetDialogFragment extends BottomSheetDialogFragment {

    public static void show(@NonNull FragmentManager manager) {
        new GroupJoinUpdateRequiredBottomSheetDialogFragment().show(manager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        setStyle(DialogFragment.STYLE_NORMAL,
                ThemeUtil.isDarkTheme(requireContext()) ? R.style.Theme_Signal_RoundedBottomSheet
                        : R.style.Theme_Signal_RoundedBottomSheet_Light);

        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.group_join_update_needed_bottom_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        view.findViewById(R.id.group_join_update_button)
                .setOnClickListener(v -> {
                    PlayStoreUtil.openPlayStoreOrOurApkDownloadPage(requireContext());
                    dismiss();
                });
    }

    @Override
    public void show(@NonNull FragmentManager manager, @Nullable String tag) {
        BottomSheetUtil.show(manager, tag, this);
    }
}