package su.sres.securesms.profiles.manage;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProviders;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.dd.CircularProgressButton;

import su.sres.core.util.BreakIteratorCompat;
import su.sres.core.util.EditTextUtil;
import su.sres.securesms.R;
import su.sres.securesms.components.emoji.EmojiUtil;

import su.sres.securesms.reactions.any.ReactWithAnyEmojiBottomSheetDialogFragment;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.util.StringUtil;
import su.sres.securesms.util.ViewUtil;
import su.sres.securesms.util.adapter.AlwaysChangedDiffUtil;
import su.sres.securesms.util.text.AfterTextChanged;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.Arrays;
import java.util.List;

import su.sres.signalservice.api.crypto.ProfileCipher;

/**
 * Lets you edit the 'About' section of your profile.
 */
public class EditAboutFragment extends Fragment implements ManageProfileActivity.EmojiController {

    public static final int ABOUT_MAX_GLYPHS              = 140;
    public static final int ABOUT_LIMIT_DISPLAY_THRESHOLD = 120;

    private static final String KEY_SELECTED_EMOJI = "selected_emoji";

    private static final List<AboutPreset> PRESETS = Arrays.asList(
            new AboutPreset("\uD83D\uDC4B", R.string.EditAboutFragment_speak_freely),
            new AboutPreset("\uD83E\uDD10", R.string.EditAboutFragment_encrypted),
            new AboutPreset("\uD83D\uDE4F", R.string.EditAboutFragment_be_kind),
            new AboutPreset("☕",            R.string.EditAboutFragment_coffee_lover),
            new AboutPreset("\uD83D\uDC4D", R.string.EditAboutFragment_free_to_chat),
            new AboutPreset("\uD83D\uDCF5", R.string.EditAboutFragment_taking_a_break),
            new AboutPreset("\uD83D\uDE80", R.string.EditAboutFragment_working_on_something_new),
            new AboutPreset("", R.string.EditAboutFragment_busy),
            new AboutPreset("", R.string.EditAboutFragment_DND)
    );

    private ImageView              emojiView;
    private EditText               bodyView;
    private TextView               countView;
    private CircularProgressButton saveButton;
    private EditAboutViewModel     viewModel;

    private String selectedEmoji;

    @Override
    public @NonNull View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.edit_about_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        this.emojiView  = view.findViewById(R.id.edit_about_emoji);
        this.bodyView   = view.findViewById(R.id.edit_about_body);
        this.countView  = view.findViewById(R.id.edit_about_count);
        this.saveButton = view.findViewById(R.id.edit_about_save);

        initializeViewModel();

        view.<Toolbar>findViewById(R.id.toolbar)
                .setNavigationOnClickListener(v -> Navigation.findNavController(view)
                        .popBackStack());

        EditTextUtil.addGraphemeClusterLimitFilter(bodyView, ABOUT_MAX_GLYPHS);
        this.bodyView.addTextChangedListener(new AfterTextChanged(editable -> {
            trimFieldToMaxByteLength(editable);
            presentCount(editable.toString());
        }));

        this.emojiView.setOnClickListener(v -> {
            ReactWithAnyEmojiBottomSheetDialogFragment.createForAboutSelection()
                    .show(requireFragmentManager(), "BOTTOM");
        });

        view.findViewById(R.id.edit_about_clear).setOnClickListener(v -> onClearClicked());

        saveButton.setOnClickListener(v -> viewModel.onSaveClicked(requireContext(),
                bodyView.getText().toString(),
                selectedEmoji));

        RecyclerView  presetList    = view.findViewById(R.id.edit_about_presets);
        PresetAdapter presetAdapter = new PresetAdapter();

        presetList.setAdapter(presetAdapter);
        presetList.setLayoutManager(new LinearLayoutManager(requireContext()));

        presetAdapter.submitList(PRESETS);

        if (savedInstanceState != null && savedInstanceState.containsKey(KEY_SELECTED_EMOJI)) {
            onEmojiSelectedInternal(savedInstanceState.getString(KEY_SELECTED_EMOJI, ""));
        } else {
            this.bodyView.setText(Recipient.self().getAbout());
            onEmojiSelectedInternal(Optional.fromNullable(Recipient.self().getAboutEmoji()).or(""));
        }

        ViewUtil.focusAndMoveCursorToEndAndOpenKeyboard(bodyView);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString(KEY_SELECTED_EMOJI, selectedEmoji);
    }

    @Override
    public void onEmojiSelected(@NonNull String emoji) {
        onEmojiSelectedInternal(emoji);
        ViewUtil.focusAndMoveCursorToEndAndOpenKeyboard(bodyView);
    }

    private void onEmojiSelectedInternal(@NonNull String emoji) {
        Drawable drawable = EmojiUtil.convertToDrawable(requireContext(), emoji);
        if (drawable != null) {
            this.emojiView.setImageDrawable(drawable);
            this.selectedEmoji = emoji;
        } else {
            this.emojiView.setImageResource(R.drawable.ic_add_emoji);
            this.selectedEmoji = "";
        }
    }

    private void initializeViewModel() {
        this.viewModel = ViewModelProviders.of(this).get(EditAboutViewModel.class);

        viewModel.getSaveState().observe(getViewLifecycleOwner(), this::presentSaveState);
        viewModel.getEvents().observe(getViewLifecycleOwner(), this::presentEvent);
    }

    private void presentCount(@NonNull String aboutBody) {
        BreakIteratorCompat breakIterator = BreakIteratorCompat.getInstance();
        breakIterator.setText(aboutBody);
        int glyphCount = breakIterator.countBreaks();

        if (glyphCount >= ABOUT_LIMIT_DISPLAY_THRESHOLD) {
            this.countView.setVisibility(View.VISIBLE);
            this.countView.setText(getResources().getString(R.string.EditAboutFragment_count, glyphCount, ABOUT_MAX_GLYPHS));
        } else {
            this.countView.setVisibility(View.GONE);
        }
    }

    private void presentSaveState(@NonNull EditAboutViewModel.SaveState state) {
        switch (state) {
            case IDLE:
                saveButton.setClickable(true);
                saveButton.setIndeterminateProgressMode(false);
                saveButton.setProgress(0);
                break;
            case IN_PROGRESS:
                saveButton.setClickable(false);
                saveButton.setIndeterminateProgressMode(true);
                saveButton.setProgress(50);
                break;
            case DONE:
                saveButton.setClickable(false);
                Navigation.findNavController(requireView()).popBackStack();
                break;
        }
    }

    private void presentEvent(@NonNull EditAboutViewModel.Event event) {
        if (event == EditAboutViewModel.Event.NETWORK_FAILURE) {
            Toast.makeText(requireContext(), R.string.EditProfileNameFragment_failed_to_save_due_to_network_issues_try_again_later, Toast.LENGTH_SHORT).show();
        }
    }

    private void onClearClicked() {
        bodyView.setText("");
        onEmojiSelectedInternal("");
    }

    private static void trimFieldToMaxByteLength(Editable s) {
        int trimmedLength = StringUtil.trimToFit(s.toString(), ProfileCipher.MAX_POSSIBLE_ABOUT_LENGTH).length();

        if (s.length() > trimmedLength) {
            s.delete(trimmedLength, s.length());
        }
    }

    private void onPresetSelected(@NonNull AboutPreset preset) {
        onEmojiSelectedInternal(preset.getEmoji());
        bodyView.setText(requireContext().getString(preset.getBodyRes()));
        bodyView.setSelection(bodyView.length(), bodyView.length());
    }

    private final class PresetAdapter extends ListAdapter<AboutPreset, PresetViewHolder> {

        protected PresetAdapter() {
            super(new AlwaysChangedDiffUtil<>());
        }

        @Override
        public @NonNull PresetViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PresetViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.about_preset_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull PresetViewHolder holder, int position) {
            AboutPreset preset = getItem(position);

            holder.bind(preset);
            holder.itemView.setOnClickListener(v -> onPresetSelected(preset));
        }
    }

    private final class PresetViewHolder extends RecyclerView.ViewHolder {

        private final ImageView emoji;
        private final TextView  body;

        public PresetViewHolder(@NonNull View itemView) {
            super(itemView);

            this.emoji = itemView.findViewById(R.id.about_preset_emoji);
            this.body  = itemView.findViewById(R.id.about_preset_body);
        }

        public void bind(@NonNull AboutPreset preset) {
            this.emoji.setImageDrawable(EmojiUtil.convertToDrawable(requireContext(), preset.getEmoji()));
            this.body.setText(preset.getBodyRes());
        }
    }

    private static final class AboutPreset {
        private final String emoji;
        private final int    bodyRes;

        private AboutPreset(@NonNull String emoji, @StringRes int bodyRes) {
            this.emoji   = emoji;
            this.bodyRes = bodyRes;
        }

        public @NonNull String getEmoji() {
            return emoji;
        }

        public @StringRes int getBodyRes() {
            return bodyRes;
        }
    }
}