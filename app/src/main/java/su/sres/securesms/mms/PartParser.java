package su.sres.securesms.mms;

import com.google.android.mms.ContentType;
import com.google.android.mms.pdu_alt.CharacterSets;
import com.google.android.mms.pdu_alt.PduBody;
import com.google.android.mms.pdu_alt.PduPart;

import su.sres.securesms.logging.Log;
import su.sres.securesms.util.Util;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.List;


public class PartParser {

  private static final String       TAG            = Log.tag(PartParser.class);
  private static final List<String> DOCUMENT_TYPES = Arrays.asList("text/vcard", "text/x-vcard");

  public static String getMessageText(PduBody body) {
    String bodyText = null;

    for (int i=0;i<body.getPartsNum();i++) {
      if (isText(body.getPart(i)) && !isDocument(body.getPart(i))) {
        String partText;

        try {
          String characterSet = CharacterSets.getMimeName(body.getPart(i).getCharset());

          if (characterSet.equals(CharacterSets.MIMENAME_ANY_CHARSET))
            characterSet = CharacterSets.MIMENAME_UTF_8;

          if (body.getPart(i).getData() != null) {
            partText = new String(body.getPart(i).getData(), characterSet);
          } else {
            partText = "";
          }
        } catch (UnsupportedEncodingException e) {
          Log.w(TAG, e);
          partText = "Unsupported Encoding!";
        }

        bodyText = (bodyText == null) ? partText : bodyText + " " + partText;
      }
    }

    return bodyText;
  }

  public static PduBody getSupportedMediaParts(PduBody body) {
    PduBody stripped = new PduBody();

    for (int i=0;i<body.getPartsNum();i++) {
      if (isDisplayableMedia(body.getPart(i)) || isDocument(body.getPart(i))) {
        stripped.addPart(body.getPart(i));
      }
    }

    return stripped;
  }

  public static int getSupportedMediaPartCount(PduBody body) {
    int partCount = 0;

    for (int i=0;i<body.getPartsNum();i++) {
      if (isDisplayableMedia(body.getPart(i)) || isDocument(body.getPart(i))) {
        partCount++;
      }
    }

    return partCount;
  }

  public static boolean isImage(PduPart part) {
    return ContentType.isImageType(Util.toIsoString(part.getContentType()));
  }

  public static boolean isAudio(PduPart part) {
    return ContentType.isAudioType(Util.toIsoString(part.getContentType()));
  }

  public static boolean isVideo(PduPart part) {
    return ContentType.isVideoType(Util.toIsoString(part.getContentType()));
  }

  public static boolean isText(PduPart part) {
    return ContentType.isTextType(Util.toIsoString(part.getContentType()));
  }

  public static boolean isDisplayableMedia(PduPart part) {
    return isImage(part) || isAudio(part) || isVideo(part);
  }

  public static boolean isDocument(PduPart part) {
    return DOCUMENT_TYPES.contains(Util.toIsoString(part.getContentType()).toLowerCase());
  }
}
