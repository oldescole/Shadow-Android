package su.sres.securesms.conversation.ui.mentions;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import su.sres.securesms.conversation.ui.mentions.MentionViewHolder.MentionEventsListener;
import su.sres.securesms.util.MappingAdapter;
import su.sres.securesms.util.MappingModel;

public class MentionsPickerAdapter extends MappingAdapter {
    private final Runnable currentListChangedListener;

    public MentionsPickerAdapter(@Nullable MentionEventsListener mentionEventsListener, @NonNull Runnable currentListChangedListener) {
        this.currentListChangedListener = currentListChangedListener;
        registerFactory(MentionViewState.class, MentionViewHolder.createFactory(mentionEventsListener));
    }

    @Override
    public void onCurrentListChanged(@NonNull List<MappingModel<?>> previousList, @NonNull List<MappingModel<?>> currentList) {
        super.onCurrentListChanged(previousList, currentList);
        currentListChangedListener.run();
    }
}