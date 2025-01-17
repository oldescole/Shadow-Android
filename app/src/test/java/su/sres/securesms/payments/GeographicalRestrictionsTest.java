package su.sres.securesms.payments;

import org.junit.Before;
import org.junit.Test;
import su.sres.core.util.logging.Log;
import su.sres.securesms.BuildConfig;
import su.sres.securesms.testutil.EmptyLogger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public final class GeographicalRestrictionsTest {

  @Before
  public void setup() {
    Log.initialize(new EmptyLogger());
  }

  @Test
  public void bad_number_not_allowed() {
    assertFalse(GeographicalRestrictions.e164Allowed("bad_number"));
  }

  @Test
  public void null_not_allowed() {
    assertFalse(GeographicalRestrictions.e164Allowed(null));
  }

  @Test
  public void uk_allowed() {
    assertTrue(GeographicalRestrictions.e164Allowed("+441617151234"));
  }

  @Test
  public void crimea_not_allowed() {
    assertFalse(GeographicalRestrictions.e164Allowed("+79782222222"));
  }

  @Test
  public void blacklist_not_allowed() {
    for (int code : BuildConfig.MOBILE_COIN_BLACKLIST) {
      assertFalse(GeographicalRestrictions.regionAllowed(code));
    }
  }
}
