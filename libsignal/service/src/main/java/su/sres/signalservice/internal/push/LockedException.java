package su.sres.signalservice.internal.push;


import su.sres.signalservice.api.push.exceptions.NonSuccessfulResponseCodeException;

public class LockedException extends NonSuccessfulResponseCodeException {

  private int  length;
  private long timeRemaining;

  public LockedException(int length, long timeRemaining) {
    super(423);
    this.length        = length;
    this.timeRemaining = timeRemaining;
  }

  public int getLength() {
    return length;
  }

  public long getTimeRemaining() {
    return timeRemaining;
  }
}
