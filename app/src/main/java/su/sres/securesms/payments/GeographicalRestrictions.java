package su.sres.securesms.payments;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import io.reactivex.rxjava3.annotations.NonNull;
import su.sres.core.util.MapUtil;
import su.sres.core.util.logging.Log;
import su.sres.securesms.BuildConfig;
import su.sres.securesms.util.FeatureFlags;
import su.sres.securesms.util.Util;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class GeographicalRestrictions {

  private static final String TAG = Log.tag(GeographicalRestrictions.class);

  private GeographicalRestrictions() {}

  private static final Set<Integer> BLACKLIST;

  static {
    Set<Integer> set = new HashSet<>(BuildConfig.MOBILE_COIN_BLACKLIST.length);

    for (int i = 0; i < BuildConfig.MOBILE_COIN_BLACKLIST.length; i++) {
      set.add(BuildConfig.MOBILE_COIN_BLACKLIST[i]);
    }

    BLACKLIST = Collections.unmodifiableSet(set);
  }

  public static boolean regionAllowed(int regionCode) {
    return !BLACKLIST.contains(regionCode);
  }

  public static boolean e164Allowed(@Nullable String e164) {
    try {
      int countryCode = PhoneNumberUtil.getInstance()
                                       .parse(e164, null)
                                       .getCountryCode();

      return GeographicalRestrictions.regionAllowed(countryCode);
    } catch (NumberParseException e) {
      Log.w(TAG, e);
      return false;
    }
  }
}
