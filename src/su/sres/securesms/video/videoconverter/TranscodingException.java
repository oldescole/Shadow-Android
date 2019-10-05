package su.sres.securesms.video.videoconverter;

final class TranscodingException extends Exception {

    TranscodingException(String message) {
        super(message);
    }

    TranscodingException(Throwable inner) {
        super(inner);
    }
}