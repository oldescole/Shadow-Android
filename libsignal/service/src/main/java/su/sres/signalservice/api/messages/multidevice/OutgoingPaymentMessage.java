package su.sres.signalservice.api.messages.multidevice;

import com.google.protobuf.ByteString;

import org.whispersystems.libsignal.util.guava.Optional;

import su.sres.signalservice.api.payments.Money;
import su.sres.signalservice.api.push.SignalServiceAddress;

import java.util.List;
import java.util.UUID;

public final class OutgoingPaymentMessage {

  private final Optional<SignalServiceAddress> recipient;
  private final Money.MobileCoin               amount;
  private final Money.MobileCoin               fee;
  private final ByteString                     receipt;
  private final long                           blockIndex;
  private final long                           blockTimestamp;
  private final Optional<byte[]>               address;
  private final Optional<String>               note;
  private final List<ByteString>               publicKeys;
  private final List<ByteString>               keyImages;

  public OutgoingPaymentMessage(Optional<SignalServiceAddress> recipient,
                                Money.MobileCoin amount,
                                Money.MobileCoin fee,
                                ByteString receipt,
                                long blockIndex,
                                long blockTimestamp,
                                Optional<byte[]> address,
                                Optional<String> note,
                                List<ByteString> publicKeys,
                                List<ByteString> keyImages)
  {
    this.recipient      = recipient;
    this.amount         = amount;
    this.fee            = fee;
    this.receipt        = receipt;
    this.blockIndex     = blockIndex;
    this.blockTimestamp = blockTimestamp;
    this.address        = address;
    this.note           = note;
    this.publicKeys     = publicKeys;
    this.keyImages      = keyImages;
  }

  public Optional<SignalServiceAddress> getRecipient() {
    return recipient;
  }

  public Money.MobileCoin getAmount() {
    return amount;
  }

  public ByteString getReceipt() {
    return receipt;
  }

  public Money.MobileCoin getFee() {
    return fee;
  }

  public long getBlockIndex() {
    return blockIndex;
  }

  public long getBlockTimestamp() {
    return blockTimestamp;
  }

  public Optional<byte[]> getAddress() {
    return address;
  }

  public Optional<String> getNote() {
    return note;
  }

  public List<ByteString> getPublicKeys() {
    return publicKeys;
  }

  public List<ByteString> getKeyImages() {
    return keyImages;
  }
}
