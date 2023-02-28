package su.sres.securesms.conversationlist;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import android.app.Application;
import android.database.ContentObserver;
import android.os.Handler;
import androidx.annotation.NonNull;
import androidx.paging.DataSource;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;

import android.text.TextUtils;

import java.util.Objects;

import su.sres.securesms.conversationlist.model.Conversation;
import su.sres.securesms.conversationlist.model.SearchResult;
import su.sres.securesms.database.DatabaseContentProviders;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.DatabaseObserver;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.core.util.logging.Log;
import su.sres.securesms.megaphone.Megaphone;
import su.sres.securesms.megaphone.MegaphoneRepository;
import su.sres.securesms.megaphone.Megaphones;
import su.sres.securesms.search.SearchRepository;
import su.sres.securesms.util.Debouncer;
import su.sres.securesms.util.Util;
import su.sres.core.util.concurrent.SignalExecutors;
import su.sres.securesms.util.paging.Invalidator;

class ConversationListViewModel extends ViewModel {

    private static final String TAG = Log.tag(ConversationListViewModel.class);

    private final MutableLiveData<Megaphone>        megaphone;
    private final MutableLiveData<SearchResult>     searchResult;
    private final LiveData<ConversationList>        conversationList;
    private final SearchRepository                  searchRepository;
    private final MegaphoneRepository               megaphoneRepository;
    private final Debouncer                         debouncer;
    private final DatabaseObserver.Observer observer;
    private final Invalidator                       invalidator;

    private String lastQuery;

    private ConversationListViewModel(@NonNull Application application, @NonNull SearchRepository searchRepository, boolean isArchived) {
        this.megaphone           = new MutableLiveData<>();
        this.searchResult        = new MutableLiveData<>();
        this.searchRepository    = searchRepository;
        this.megaphoneRepository = ApplicationDependencies.getMegaphoneRepository();
        this.debouncer           = new Debouncer(300);
        this.invalidator         = new Invalidator();
        this.observer            = () -> {
            if (!TextUtils.isEmpty(getLastQuery())) {
                searchRepository.query(getLastQuery(), searchResult::postValue);
            }
        };

        DataSource.Factory<Integer, Conversation> factory = new ConversationListDataSource.Factory(application, invalidator, isArchived);
        PagedList.Config                          config  = new PagedList.Config.Builder()
                .setPageSize(15)
                .setInitialLoadSizeHint(30)
                .setEnablePlaceholders(true)
                .build();

        LiveData<PagedList<Conversation>> conversationList = new LivePagedListBuilder<>(factory, config).setFetchExecutor(ConversationListDataSource.EXECUTOR)
                .setInitialLoadKey(0)
                .build();

        ApplicationDependencies.getDatabaseObserver().registerConversationListObserver(observer);

        this.conversationList = Transformations.switchMap(conversationList, conversation -> {
            if (conversation.getDataSource().isInvalid()) {
                Log.w(TAG, "Received an invalid conversation list. Ignoring.");
                return new MutableLiveData<>();
            }

            MutableLiveData<ConversationList> updated = new MutableLiveData<>();

            if (isArchived) {
                updated.postValue(new ConversationList(conversation, 0, 0));
            } else {
                SignalExecutors.BOUNDED.execute(() -> {
                    int archiveCount = DatabaseFactory.getThreadDatabase(application).getArchivedConversationListCount();
                    int pinnedCount  = DatabaseFactory.getThreadDatabase(application).getPinnedConversationListCount();
                    updated.postValue(new ConversationList(conversation, archiveCount, pinnedCount));
                });
            }

            return updated;
        });
    }

    public LiveData<Boolean> hasNoConversations() {
        return Transformations.map(getConversationList(), ConversationList::isEmpty);
    }

    @NonNull LiveData<SearchResult> getSearchResult() {
        return searchResult;
    }

    @NonNull LiveData<Megaphone> getMegaphone() {
        return megaphone;
    }

    @NonNull LiveData<ConversationList> getConversationList() {
        return conversationList;
    }

    public int getPinnedCount() {
        return Objects.requireNonNull(getConversationList().getValue()).pinnedCount;
    }

    void onVisible() {
        megaphoneRepository.getNextMegaphone(megaphone::postValue);
        ApplicationDependencies.getDatabaseObserver().notifyConversationListListeners();
    }

    void onMegaphoneCompleted(@NonNull Megaphones.Event event) {
        megaphone.postValue(null);
        megaphoneRepository.markFinished(event);
    }

    void onMegaphoneSnoozed(@NonNull Megaphones.Event event) {
        megaphoneRepository.markSeen(event);
        megaphone.postValue(null);
    }

    void onMegaphoneVisible(@NonNull Megaphone visible) {
        megaphoneRepository.markVisible(visible.getEvent());
    }

    void updateQuery(String query) {
        lastQuery = query;
        debouncer.publish(() -> searchRepository.query(query, result -> {
            Util.runOnMain(() -> {
                if (query.equals(lastQuery)) {
                    searchResult.setValue(result);
                }
            });
        }));
    }

    private @NonNull String getLastQuery() {
        return lastQuery == null ? "" : lastQuery;
    }

    @Override
    protected void onCleared() {
        invalidator.invalidate();
        debouncer.clear();
        ApplicationDependencies.getDatabaseObserver().unregisterObserver(observer);
    }

    public static class Factory extends ViewModelProvider.NewInstanceFactory {

        private final boolean isArchived;

        public Factory(boolean isArchived) {
            this.isArchived = isArchived;
        }

        @Override
        public @NonNull<T extends ViewModel> T create(@NonNull Class<T> modelClass) {
            //noinspection ConstantConditions
            return modelClass.cast(new ConversationListViewModel(ApplicationDependencies.getApplication(), new SearchRepository(), isArchived));
        }
    }

    final static class ConversationList {
        private final PagedList<Conversation> conversations;
        private final int                     archivedCount;
        private final int                     pinnedCount;

        ConversationList(PagedList<Conversation> conversations, int archivedCount, int pinnedCount) {
            this.conversations = conversations;
            this.archivedCount = archivedCount;
            this.pinnedCount   = pinnedCount;
        }

        PagedList<Conversation> getConversations() {
            return conversations;
        }

        int getArchivedCount() {
            return archivedCount;
        }

        public int getPinnedCount() {
            return pinnedCount;
        }

        boolean isEmpty() {
            return conversations.isEmpty() && archivedCount == 0;
        }
    }
}