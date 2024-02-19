package su.sres.securesms.reactions.any;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import su.sres.securesms.components.emoji.EmojiPageModel;
import su.sres.securesms.components.emoji.EmojiPageViewGridAdapter;
import su.sres.securesms.components.emoji.RecentEmojiPageModel;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.emoji.EmojiCategory;
import su.sres.securesms.keyboard.emoji.EmojiKeyboardPageCategoryMappingModel;
import su.sres.securesms.keyboard.emoji.search.EmojiSearchRepository;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.reactions.ReactionsLoader;
import su.sres.securesms.util.MappingModelList;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.securesms.util.livedata.LiveDataUtil;

import java.util.List;
import java.util.stream.Collectors;

public final class ReactWithAnyEmojiViewModel extends ViewModel {

  private static final int SEARCH_LIMIT = 40;

  private final ReactWithAnyEmojiRepository repository;
  private final long                        messageId;
  private final boolean                     isMms;
  private final EmojiSearchRepository       emojiSearchRepository;

  private final LiveData<MappingModelList>         categories;
  private final LiveData<MappingModelList>         emojiList;
  private final MutableLiveData<EmojiSearchResult> searchResults;
  private final MutableLiveData<String>            selectedKey;

  private ReactWithAnyEmojiViewModel(@NonNull ReactionsLoader reactionsLoader,
                                     @NonNull ReactWithAnyEmojiRepository repository,
                                     long messageId,
                                     boolean isMms,
                                     @NonNull EmojiSearchRepository emojiSearchRepository)
  {
    this.repository            = repository;
    this.messageId             = messageId;
    this.isMms                 = isMms;
    this.emojiSearchRepository = emojiSearchRepository;
    this.searchResults         = new MutableLiveData<>(new EmojiSearchResult());
    this.selectedKey           = new MutableLiveData<>(getStartingKey());

    LiveData<List<ReactWithAnyEmojiPage>> emojiPages = Transformations.map(reactionsLoader.getReactions(), repository::getEmojiPageModels);

    LiveData<MappingModelList> emojiList = Transformations.map(emojiPages, (pages) -> {
      MappingModelList list = new MappingModelList();

      for (ReactWithAnyEmojiPage page : pages) {
        String key = page.getKey();
        for (ReactWithAnyEmojiPageBlock block : page.getPageBlocks()) {
          list.add(new EmojiPageViewGridAdapter.EmojiHeader(key, block.getLabel()));
          list.addAll(toMappingModels(block.getPageModel()));
        }
      }

      return list;
    });

    this.categories = LiveDataUtil.combineLatest(emojiPages, this.selectedKey, (pages, selectedKey) -> {
      MappingModelList list = new MappingModelList();
      list.add(new EmojiKeyboardPageCategoryMappingModel.RecentsMappingModel(RecentEmojiPageModel.KEY.equals(selectedKey)));
      list.addAll(pages.stream()
                       .filter(p -> !RecentEmojiPageModel.KEY.equals(p.getKey()))
                       .map(p -> {
                         EmojiCategory category = EmojiCategory.forKey(p.getKey());
                         return new EmojiKeyboardPageCategoryMappingModel.EmojiCategoryMappingModel(category, category.getKey().equals(selectedKey));
                       })
                       .collect(Collectors.toList()));
      return list;
    });

    this.emojiList = LiveDataUtil.combineLatest(emojiList, searchResults, (all, search) -> {
      if (TextUtils.isEmpty(search.query)) {
        return all;
      } else {
        if (search.model.getDisplayEmoji().isEmpty()) {
          return MappingModelList.singleton(new EmojiPageViewGridAdapter.EmojiNoResultsModel());
        }
        return toMappingModels(search.model);
      }
    });
  }

  LiveData<MappingModelList> getCategories() {
    return categories;
  }

  LiveData<String> getSelectedKey() {
    return selectedKey;
  }

  void onEmojiSelected(@NonNull String emoji) {
    if (messageId > 0) {
      SignalStore.emojiValues().setPreferredVariation(emoji);
      repository.addEmojiToMessage(emoji, messageId, isMms);
    }
  }

  LiveData<MappingModelList> getEmojiList() {
    return emojiList;
  }

  public void onQueryChanged(String query) {
    emojiSearchRepository.submitQuery(query, false, SEARCH_LIMIT, m -> searchResults.postValue(new EmojiSearchResult(query, m)));
  }

  public void selectPage(@NonNull String key) {
    if (key.equals(selectedKey.getValue())) {
      return;
    }

    selectedKey.setValue(key);
  }

  private static @NonNull MappingModelList toMappingModels(@NonNull EmojiPageModel model) {
    return model.getDisplayEmoji()
                .stream()
                .map(e -> new EmojiPageViewGridAdapter.EmojiModel(model.getKey(), e))
                .collect(MappingModelList.collect());
  }

  private static @NonNull String getStartingKey() {
    if (RecentEmojiPageModel.hasRecents(ApplicationDependencies.getApplication(), TextSecurePreferences.RECENT_STORAGE_KEY)) {
      return RecentEmojiPageModel.KEY;
    } else {
      return EmojiCategory.PEOPLE.getKey();
    }
  }

  private static class EmojiSearchResult {
    private final String         query;
    private final EmojiPageModel model;

    private EmojiSearchResult(@NonNull String query, @Nullable EmojiPageModel model) {
      this.query = query;
      this.model = model;
    }

    public EmojiSearchResult() {
      this("", null);
    }
  }

  static class Factory implements ViewModelProvider.Factory {

    private final ReactionsLoader             reactionsLoader;
    private final ReactWithAnyEmojiRepository repository;
    private final long                        messageId;
    private final boolean                     isMms;

    Factory(@NonNull ReactionsLoader reactionsLoader, @NonNull ReactWithAnyEmojiRepository repository, long messageId, boolean isMms) {
      this.reactionsLoader = reactionsLoader;
      this.repository      = repository;
      this.messageId       = messageId;
      this.isMms           = isMms;
    }

    @Override
    public @NonNull <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      //noinspection ConstantConditions
      return modelClass.cast(new ReactWithAnyEmojiViewModel(reactionsLoader, repository, messageId, isMms, new EmojiSearchRepository(ApplicationDependencies.getApplication())));
    }
  }
}