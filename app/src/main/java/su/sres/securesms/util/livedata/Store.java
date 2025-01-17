package su.sres.securesms.util.livedata;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import com.annimon.stream.function.Function;

import su.sres.core.util.concurrent.SignalExecutors;
import su.sres.securesms.util.concurrent.SerialExecutor;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Manages a state to be updated from a view model and provide direct and live access. Updates
 * occur serially on the same executor to allow updating in a thread safe way. While not
 * every state update is guaranteed to be emitted, no update action will be dropped and state
 * that is emitted will be accurate.
 */
public class Store<State> {
  private final LiveDataStore liveStore;

  public Store(@NonNull State state) {
    this.liveStore = new LiveDataStore(state);
  }

  public @NonNull LiveData<State> getStateLiveData() {
    return liveStore;
  }

  public @NonNull State getState() {
    return liveStore.getState();
  }

  @AnyThread
  public void update(@NonNull Function<State, State> updater) {
    liveStore.update(updater);
  }

  @MainThread
  public <Input> void update(@NonNull LiveData<Input> source, @NonNull Action<Input, State> action) {
    liveStore.update(source, action);
  }

  @MainThread
  public void clear() {
    liveStore.clear();
  }

  @SuppressWarnings({ "rawtypes", "unchecked" })
  private final class LiveDataStore extends MediatorLiveData<State> {
    private       State    state;
    private final Executor stateUpdater;

    private final Set<LiveData> sources;

    LiveDataStore(@NonNull State state) {
      this.stateUpdater = new SerialExecutor(SignalExecutors.BOUNDED);
      this.sources      = new HashSet<>();
      setState(state);
    }

    synchronized @NonNull State getState() {
      return state;
    }

    private synchronized void setState(@NonNull State state) {
      this.state = state;
      postValue(this.state);
    }

    <Input> void update(@NonNull LiveData<Input> source, @NonNull Action<Input, State> action) {
      sources.add(source);
      addSource(source, input -> stateUpdater.execute(() -> setState(action.apply(input, getState()))));
    }

    void update(@NonNull Function<State, State> updater) {
      stateUpdater.execute(() -> setState(updater.apply(getState())));
    }

    void clear() {
      for (LiveData source : sources) {
        removeSource(source);
      }
      sources.clear();
    }
  }

  public interface Action<Input, State> {
    State apply(Input input, State current);
  }
}
