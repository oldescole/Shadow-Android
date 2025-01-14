package su.sres.signalservice.internal;

import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.libsignal.util.guava.Preconditions;

import io.reactivex.rxjava3.core.Single;
import su.sres.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;
import su.sres.signalservice.api.push.exceptions.PushNetworkException;
import su.sres.signalservice.internal.websocket.WebsocketResponse;

import java.util.concurrent.ExecutionException;

/**
 * Encapsulates a parsed APi response regardless of where it came from (WebSocket or REST). Not only
 * includes the success result but also any application errors encountered (404s, parsing, etc.) or
 * execution errors encountered (IOException, etc.).
 */
public final class ServiceResponse<Result> {

  private final int                 status;
  private final Optional<String>    body;
  private final Optional<Result>    result;
  private final Optional<Throwable> applicationError;
  private final Optional<Throwable> executionError;

  private ServiceResponse(Result result, WebsocketResponse response) {
    this(response.getStatus(), response.getBody(), result, null, null);
  }

  private ServiceResponse(Throwable applicationError, WebsocketResponse response) {
    this(response.getStatus(), response.getBody(), null, applicationError, null);
  }

  public ServiceResponse(int status,
                         String body,
                         Result result,
                         Throwable applicationError,
                         Throwable executionError)
  {
    if (result != null) {
      Preconditions.checkArgument(applicationError == null && executionError == null);
    } else {
      Preconditions.checkArgument(applicationError != null || executionError != null);
    }

    this.status           = status;
    this.body             = Optional.fromNullable(body);
    this.result           = Optional.fromNullable(result);
    this.applicationError = Optional.fromNullable(applicationError);
    this.executionError   = Optional.fromNullable(executionError);
  }

  public int getStatus() {
    return status;
  }

  public Optional<String> getBody() {
    return body;
  }

  public Optional<Result> getResult() {
    return result;
  }

  public Optional<Throwable> getApplicationError() {
    return applicationError;
  }

  public Optional<Throwable> getExecutionError() {
    return executionError;
  }

  public Single<Result> flattenResult() {
    if (result.isPresent()) {
      return Single.just(result.get());
    } else if (applicationError.isPresent()) {
      return Single.error(applicationError.get());
    } else if (executionError.isPresent()) {
      return Single.error(executionError.get());
    } else {
      return Single.error(new AssertionError("Should never get here."));
    }
  }

  public static <T> ServiceResponse<T> forResult(T result, WebsocketResponse response) {
    return new ServiceResponse<>(result, response);
  }

  public static <T> ServiceResponse<T> forResult(T result, int status, String body) {
    return new ServiceResponse<>(status, body, result, null, null);
  }

  public static <T> ServiceResponse<T> forApplicationError(Throwable throwable, WebsocketResponse response) {
    return new ServiceResponse<T>(throwable, response);
  }

  public static <T> ServiceResponse<T> forApplicationError(Throwable throwable, int status, String body) {
    return new ServiceResponse<>(status, body, null, throwable, null);
  }

  public static <T> ServiceResponse<T> forExecutionError(Throwable throwable) {
    return new ServiceResponse<>(0, null, null, null, throwable);
  }

  public static <T> ServiceResponse<T> forUnknownError(Throwable throwable) {
    if (throwable instanceof ExecutionException) {
      return forUnknownError(throwable.getCause());
    } else if (throwable instanceof NonSuccessfulResponseCodeException) {
      return forApplicationError(throwable, ((NonSuccessfulResponseCodeException) throwable).getCode(), null);
    } else if (throwable instanceof PushNetworkException && throwable.getCause() != null) {
      return forUnknownError(throwable.getCause());
    } else {
      return forExecutionError(throwable);
    }
  }

  public static <T, I> ServiceResponse<T> coerceError(ServiceResponse<I> response) {
    if (response.applicationError.isPresent()) {
      return ServiceResponse.forApplicationError(response.applicationError.get(), response.status, response.body.orNull());
    }
    return ServiceResponse.forExecutionError(response.executionError.orNull());
  }
}
