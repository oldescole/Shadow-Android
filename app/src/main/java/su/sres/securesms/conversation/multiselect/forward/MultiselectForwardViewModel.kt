package su.sres.securesms.conversation.multiselect.forward

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.reactivex.rxjava3.core.Single
import su.sres.securesms.recipients.RecipientId
import su.sres.securesms.sharing.MultiShareArgs
import su.sres.securesms.sharing.ShareContact
import su.sres.securesms.sharing.ShareSelectionMappingModel
import su.sres.securesms.util.livedata.Store
import org.whispersystems.libsignal.util.guava.Optional
import su.sres.securesms.keyvalue.SignalStore

class MultiselectForwardViewModel(
  private val records: List<MultiShareArgs>,
  private val repository: MultiselectForwardRepository
) : ViewModel() {

  private val store = Store(MultiselectForwardState())

  val state: LiveData<MultiselectForwardState> = store.stateLiveData

  val shareContactMappingModels: LiveData<List<ShareSelectionMappingModel>> = Transformations.map(state) { s -> s.selectedContacts.mapIndexed { i, c -> ShareSelectionMappingModel(c, i == 0) } }

  fun addSelectedContact(recipientId: Optional<RecipientId>, number: String?): Single<Boolean> {
    return repository
      .canSelectRecipient(recipientId)
      .doOnSuccess { allowed ->
        if (allowed) {
          store.update { it.copy(selectedContacts = it.selectedContacts + ShareContact(recipientId, number)) }
        }
      }
  }

  fun removeSelectedContact(recipientId: Optional<RecipientId>, number: String?) {
    store.update { it.copy(selectedContacts = it.selectedContacts - ShareContact(recipientId, number)) }
  }

  fun send(additionalMessage: String) {
    if (SignalStore.tooltips().showMultiForwardDialog()) {
      SignalStore.tooltips().markMultiForwardDialogSeen()
      store.update { it.copy(stage = MultiselectForwardState.Stage.FirstConfirmation) }
    } else {
      store.update { it.copy(stage = MultiselectForwardState.Stage.LoadingIdentities) }
      repository.checkForBadIdentityRecords(store.state.selectedContacts) { identityRecords ->
        if (identityRecords.isEmpty()) {
          performSend(additionalMessage)
        } else {
          store.update { it.copy(stage = MultiselectForwardState.Stage.SafetyConfirmation(identityRecords)) }
        }
      }
    }
  }

  fun confirmFirstSend(additionalMessage: String) {
    send(additionalMessage)
  }

  fun confirmSafetySend(additionalMessage: String) {
    send(additionalMessage)
  }

  fun cancelSend() {
    store.update { it.copy(stage = MultiselectForwardState.Stage.Selection) }
  }

  private fun performSend(additionalMessage: String) {
    store.update { it.copy(stage = MultiselectForwardState.Stage.SendPending) }
    repository.send(
      additionalMessage = additionalMessage,
      multiShareArgs = records,
      shareContacts = store.state.selectedContacts,
      MultiselectForwardRepository.MultiselectForwardResultHandlers(
        onAllMessageSentSuccessfully = { store.update { it.copy(stage = MultiselectForwardState.Stage.Success) } },
        onAllMessagesFailed = { store.update { it.copy(stage = MultiselectForwardState.Stage.AllFailed) } },
        onSomeMessagesFailed = { store.update { it.copy(stage = MultiselectForwardState.Stage.SomeFailed) } }
      )
    )
  }

  class Factory(
    private val records: List<MultiShareArgs>,
    private val repository: MultiselectForwardRepository
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
      return requireNotNull(modelClass.cast(MultiselectForwardViewModel(records, repository)))
    }
  }
}