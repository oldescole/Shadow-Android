/*
 * Copyright (C) 2014 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package su.sres.securesms.logsubmit.util;

import androidx.annotation.NonNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrub data for possibly sensitive information.
 */
public final class Scrubber {

  private Scrubber() {
  }

  /**
   * The middle group will be censored.
   * Handles URL encoded +, %2B
   */
  private static final Pattern E164_PATTERN = Pattern.compile("(\\+|%2B)(\\d{8,13})(\\d{2})");
  private static final String  E164_CENSOR  = "*************";

  /**
   * The second group will be censored.
   */
  private static final Pattern CRUDE_EMAIL_PATTERN = Pattern.compile("\\b([^\\s/])([^\\s/]*@[^\\s]+)");
  private static final String  EMAIL_CENSOR        = "...@...";

  /**
   * The middle group will be censored.
   */
  private static final Pattern GROUP_ID_PATTERN = Pattern.compile("(__)(textsecure_group__![^\\s]+)([^\\s]{2})");
  private static final String  GROUP_ID_CENSOR  = "...group...";

  public static CharSequence scrub(@NonNull CharSequence in) {

    in = scrubE164(in);
    in = scrubEmail(in);
    in = scrubGroups(in);

    return in;
  }

  private static CharSequence scrubE164(@NonNull CharSequence in) {
    return scrub(in,
            E164_PATTERN,
            (matcher, output) -> output.append(matcher.group(1))
                    .append(E164_CENSOR, 0, matcher.group(2).length())
                    .append(matcher.group(3)));
  }

  private static CharSequence scrubEmail(@NonNull CharSequence in) {
    return scrub(in,
            CRUDE_EMAIL_PATTERN,
            (matcher, output) -> output.append(matcher.group(1))
                    .append(EMAIL_CENSOR));
  }

  private static CharSequence scrubGroups(@NonNull CharSequence in) {
    return scrub(in,
            GROUP_ID_PATTERN,
            (matcher, output) -> output.append(matcher.group(1))
                    .append(GROUP_ID_CENSOR)
                    .append(matcher.group(3)));
  }

  private static CharSequence scrub(@NonNull CharSequence in, @NonNull Pattern pattern, @NonNull ProcessMatch processMatch) {
    final StringBuilder output  = new StringBuilder(in.length());
    final Matcher matcher = pattern.matcher(in);

    int lastEndingPos = 0;

    while (matcher.find()) {
      output.append(in, lastEndingPos, matcher.start());

      processMatch.scrubMatch(matcher, output);

      lastEndingPos = matcher.end();
    }

    if (lastEndingPos == 0) {
      // there were no matches, save copying all the data
      return in;
    } else {
      output.append(in, lastEndingPos, in.length());

      return output;
    }
  }

  private interface ProcessMatch {
    void scrubMatch(@NonNull Matcher matcher, @NonNull StringBuilder output);
  }
}
