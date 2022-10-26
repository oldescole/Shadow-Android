package su.sres.securesms.components.webrtc.participantslist;

import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import su.sres.securesms.R;
import su.sres.securesms.components.webrtc.CallParticipantsState;
import su.sres.securesms.components.webrtc.WebRtcCallViewModel;
import su.sres.securesms.events.CallParticipant;
import su.sres.securesms.util.BottomSheetUtil;
import su.sres.securesms.util.MappingModel;

import java.util.ArrayList;
import java.util.List;

public class CallParticipantsListDialog extends BottomSheetDialogFragment {

    private RecyclerView                participantList;
    private CallParticipantsListAdapter adapter;

    public static void show(@NonNull FragmentManager manager) {
        CallParticipantsListDialog fragment = new CallParticipantsListDialog();

        fragment.show(manager, BottomSheetUtil.STANDARD_BOTTOM_SHEET_FRAGMENT_TAG);
    }

    @Override
    public void show(@NonNull FragmentManager manager, @Nullable String tag) {
        BottomSheetUtil.show(manager, tag, this);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        setStyle(DialogFragment.STYLE_NORMAL, R.style.Theme_Signal_RoundedBottomSheet);
        super.onCreate(savedInstanceState);
    }

    @Override
    public @NonNull View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(inflater.getContext(), R.style.TextSecure_DarkTheme);
        LayoutInflater      themedInflater      = LayoutInflater.from(contextThemeWrapper);

        participantList = (RecyclerView) themedInflater.inflate(R.layout.call_participants_list_dialog, container, false);

        return participantList;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        final WebRtcCallViewModel viewModel = ViewModelProviders.of(requireActivity()).get(WebRtcCallViewModel.class);

        initializeList();

        viewModel.getCallParticipantsState().observe(getViewLifecycleOwner(), this::updateList);
    }

    private void initializeList() {
        adapter = new CallParticipantsListAdapter();

        participantList.setLayoutManager(new LinearLayoutManager(requireContext()));
        participantList.setAdapter(adapter);
    }

    private void updateList(@NonNull CallParticipantsState callParticipantsState) {
        List<MappingModel<?>> items = new ArrayList<>();

        items.add(new CallParticipantsListHeader(callParticipantsState.getAllRemoteParticipants().size() + 1));

        items.add(new CallParticipantViewState(callParticipantsState.getLocalParticipant()));
        for (CallParticipant callParticipant : callParticipantsState.getAllRemoteParticipants()) {
            items.add(new CallParticipantViewState(callParticipant));
        }

        adapter.submitList(items);
    }

}
