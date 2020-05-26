package su.sres.signalservice.internal.push.http;


import su.sres.signalservice.api.crypto.AttachmentCipherOutputStream;
import su.sres.signalservice.api.crypto.DigestingOutputStream;

import java.io.IOException;
import java.io.OutputStream;

public class AttachmentCipherOutputStreamFactory implements OutputStreamFactory {

  private final byte[] key;
  private final byte[] iv;

  public AttachmentCipherOutputStreamFactory(byte[] key, byte[] iv) {
    this.key = key;
    this.iv  = iv;
  }

  @Override
  public DigestingOutputStream createFor(OutputStream wrap) throws IOException {
    return new AttachmentCipherOutputStream(key, iv, wrap);
  }

}
