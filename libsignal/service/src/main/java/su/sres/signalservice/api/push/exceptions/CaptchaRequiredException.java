package su.sres.signalservice.api.push.exceptions;

public class CaptchaRequiredException extends NonSuccessfulResponseCodeException {
    public CaptchaRequiredException() {
        super(402);
    }
}