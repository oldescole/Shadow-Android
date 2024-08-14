package su.sres.securesms.badges.self.featured

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import io.reactivex.rxjava3.kotlin.subscribeBy
import io.reactivex.rxjava3.subjects.PublishSubject
import su.sres.core.util.logging.Log
import su.sres.securesms.badges.BadgeRepository
import su.sres.securesms.badges.models.Badge
import su.sres.securesms.recipients.Recipient
import su.sres.securesms.util.livedata.Store

private val TAG = Log.tag(SelectFeaturedBadgeViewModel::class.java)

class SelectFeaturedBadgeViewModel(private val repository: BadgeRepository) : ViewModel() {

  private val store = Store(SelectFeaturedBadgeState())
  private val eventSubject = PublishSubject.create<SelectFeaturedBadgeEvent>()

  val state: LiveData<SelectFeaturedBadgeState> = store.stateLiveData
  val events: Observable<SelectFeaturedBadgeEvent> = eventSubject.observeOn(AndroidSchedulers.mainThread())

  private val disposables = CompositeDisposable()

  init {
    store.update(Recipient.live(Recipient.self().id).liveDataResolved) { recipient, state ->
      state.copy(
        stage = if (state.stage == SelectFeaturedBadgeState.Stage.INIT) SelectFeaturedBadgeState.Stage.READY else state.stage,
        selectedBadge = recipient.badges.firstOrNull(),
        allUnlockedBadges = recipient.badges
      )
    }
  }

  fun setSelectedBadge(badge: Badge) {
    store.update { it.copy(selectedBadge = badge) }
  }

  fun save() {
    val snapshot = store.state
    if (snapshot.selectedBadge == null) {
      eventSubject.onNext(SelectFeaturedBadgeEvent.NO_BADGE_SELECTED)
      return
    }

    store.update { it.copy(stage = SelectFeaturedBadgeState.Stage.SAVING) }
    disposables += repository.setFeaturedBadge(snapshot.selectedBadge).subscribeBy(
      onComplete = {
        eventSubject.onNext(SelectFeaturedBadgeEvent.SAVE_SUCCESSFUL)
      },
      onError = { error ->
        Log.e(TAG, "Failed to update profile.", error)
        eventSubject.onNext(SelectFeaturedBadgeEvent.FAILED_TO_UPDATE_PROFILE)
      }
    )
  }

  override fun onCleared() {
    disposables.clear()
  }

  class Factory(private val badgeRepository: BadgeRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
      return requireNotNull(modelClass.cast(SelectFeaturedBadgeViewModel(badgeRepository)))
    }
  }
}