package su.sres.signalservice.api.storage;

import org.junit.Test;

import su.sres.signalservice.api.push.ACI;
import su.sres.signalservice.api.push.SignalServiceAddress;
import su.sres.signalservice.api.util.UuidUtil;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class SignalContactRecordTest {

  private static final ACI    ACI_A  = ACI.parseOrThrow("ebef429e-695e-4f51-bcc4-526a60ac68c7");
  private static final String E164_A = "+16108675309";

  @Test
  public void contacts_with_same_identity_key_contents_are_equal() {
    byte[] profileKey     = new byte[32];
    byte[] profileKeyCopy = profileKey.clone();

    SignalContactRecord a = contactBuilder(1, ACI_A, E164_A, "a").setIdentityKey(profileKey).build();
    SignalContactRecord b = contactBuilder(1, ACI_A, E164_A, "a").setIdentityKey(profileKeyCopy).build();

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void contacts_with_different_identity_key_contents_are_not_equal() {
    byte[] profileKey     = new byte[32];
    byte[] profileKeyCopy = profileKey.clone();
    profileKeyCopy[0] = 1;

    SignalContactRecord a = contactBuilder(1, ACI_A, E164_A, "a").setIdentityKey(profileKey).build();
    SignalContactRecord b = contactBuilder(1, ACI_A, E164_A, "a").setIdentityKey(profileKeyCopy).build();

    assertNotEquals(a, b);
    assertNotEquals(a.hashCode(), b.hashCode());
  }

  private static byte[] byteArray(int a) {
    byte[] bytes = new byte[4];
    bytes[3] = (byte) a;
    bytes[2] = (byte) (a >> 8);
    bytes[1] = (byte) (a >> 16);
    bytes[0] = (byte) (a >> 24);
    return bytes;
  }

  private static SignalContactRecord.Builder contactBuilder(int key,
                                                            ACI aci,
                                                            String e164,
                                                            String givenName)
  {
    return new SignalContactRecord.Builder(byteArray(key), new SignalServiceAddress(aci, e164))
        .setGivenName(givenName);
  }
}