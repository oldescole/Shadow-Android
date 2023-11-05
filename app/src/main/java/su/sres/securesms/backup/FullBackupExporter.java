package su.sres.securesms.backup;

import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.documentfile.provider.DocumentFile;

import android.text.TextUtils;

import com.annimon.stream.function.Consumer;
import com.annimon.stream.function.Predicate;
import com.google.protobuf.ByteString;

import net.sqlcipher.database.SQLiteDatabase;

import org.greenrobot.eventbus.EventBus;
import su.sres.securesms.attachments.AttachmentId;
import su.sres.securesms.crypto.AttachmentSecret;
import su.sres.securesms.crypto.ClassicDecryptingPartInputStream;
import su.sres.securesms.crypto.IdentityKeyUtil;
import su.sres.securesms.crypto.ModernDecryptingPartInputStream;
import su.sres.securesms.database.AttachmentDatabase;
import su.sres.securesms.database.GroupReceiptDatabase;
import su.sres.securesms.database.JobDatabase;
import su.sres.securesms.database.KeyValueDatabase;
import su.sres.securesms.database.MmsDatabase;
import su.sres.securesms.database.MmsSmsColumns;
import su.sres.securesms.database.OneTimePreKeyDatabase;
import su.sres.securesms.database.SearchDatabase;
import su.sres.securesms.database.SessionDatabase;
import su.sres.securesms.database.SignedPreKeyDatabase;
import su.sres.securesms.database.SmsDatabase;
import su.sres.securesms.database.StickerDatabase;
import su.sres.core.util.logging.Log;
import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.keyvalue.KeyValueDataSet;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.profiles.AvatarHelper;
import su.sres.core.util.Conversions;
import su.sres.securesms.util.SetUtil;
import su.sres.securesms.util.Stopwatch;
import su.sres.securesms.util.TextSecurePreferences;
import su.sres.securesms.util.Util;
import org.whispersystems.libsignal.kdf.HKDFv3;
import org.whispersystems.libsignal.util.ByteUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class FullBackupExporter extends FullBackupBase {

  private static final String TAG = Log.tag(FullBackupExporter.class);

  private static final Set<String> BLACKLISTED_TABLES = SetUtil.newHashSet(
          SignedPreKeyDatabase.TABLE_NAME,
          OneTimePreKeyDatabase.TABLE_NAME,
          SessionDatabase.TABLE_NAME,
          SearchDatabase.SMS_FTS_TABLE_NAME,
          SearchDatabase.MMS_FTS_TABLE_NAME
  );

  public static void export(@NonNull Context context,
                            @NonNull AttachmentSecret attachmentSecret,
                            @NonNull SQLiteDatabase input,
                            @NonNull File output,
                            @NonNull String passphrase,
                            @NonNull BackupCancellationSignal cancellationSignal)
      throws IOException
  {
    try (OutputStream outputStream = new FileOutputStream(output)) {
      internalExport(context, attachmentSecret, input, outputStream, passphrase, true, cancellationSignal);
    }
  }

  @RequiresApi(29)
  public static void export(@NonNull Context context,
                            @NonNull AttachmentSecret attachmentSecret,
                            @NonNull SQLiteDatabase input,
                            @NonNull DocumentFile output,
                            @NonNull String passphrase,
                            @NonNull BackupCancellationSignal cancellationSignal)
          throws IOException
  {
    try (OutputStream outputStream = Objects.requireNonNull(context.getContentResolver().openOutputStream(output.getUri()))) {
      internalExport(context, attachmentSecret, input, outputStream, passphrase, true, cancellationSignal);
    }
  }

  public static void transfer(@NonNull Context context,
                              @NonNull AttachmentSecret attachmentSecret,
                              @NonNull SQLiteDatabase input,
                              @NonNull OutputStream outputStream,
                              @NonNull String passphrase)
          throws IOException
  {
    internalExport(context, attachmentSecret, input, outputStream, passphrase, false, () -> false);
  }

  private static void internalExport(@NonNull Context context,
                                     @NonNull AttachmentSecret attachmentSecret,
                                     @NonNull SQLiteDatabase input,
                                     @NonNull OutputStream fileOutputStream,
                                     @NonNull String passphrase,
                                     boolean closeOutputStream,
                                     @NonNull BackupCancellationSignal cancellationSignal)
          throws IOException
  {
    BackupFrameOutputStream outputStream = new BackupFrameOutputStream(fileOutputStream, passphrase);
    int                     count        = 0;

    try {
      outputStream.writeDatabaseVersion(input.getVersion());
      count++;

      List<String> tables = exportSchema(input, outputStream);
      count += tables.size() * 3;

      Stopwatch stopwatch = new Stopwatch("Backup");

      for (String table : tables) {
        throwIfCanceled(cancellationSignal);
        if (table.equals(MmsDatabase.TABLE_NAME)) {
          count = exportTable(table, input, outputStream, FullBackupExporter::isNonExpiringMmsMessage, null, count, cancellationSignal);
        } else if (table.equals(SmsDatabase.TABLE_NAME)) {
          count = exportTable(table, input, outputStream, FullBackupExporter::isNonExpiringSmsMessage, null, count, cancellationSignal);
        } else if (table.equals(GroupReceiptDatabase.TABLE_NAME)) {
          count = exportTable(table, input, outputStream, cursor -> isForNonExpiringMessage(input, cursor.getLong(cursor.getColumnIndexOrThrow(GroupReceiptDatabase.MMS_ID))), null, count, cancellationSignal);
        } else if (table.equals(AttachmentDatabase.TABLE_NAME)) {
          count = exportTable(table, input, outputStream, cursor -> isForNonExpiringMessage(input, cursor.getLong(cursor.getColumnIndexOrThrow(AttachmentDatabase.MMS_ID))), (cursor, innerCount) -> exportAttachment(attachmentSecret, cursor, outputStream, innerCount), count, cancellationSignal);
        } else if (table.equals(StickerDatabase.TABLE_NAME)) {
          count = exportTable(table, input, outputStream, cursor -> true, (cursor, innerCount) -> exportSticker(attachmentSecret, cursor, outputStream, innerCount), count, cancellationSignal);
        } else if (!BLACKLISTED_TABLES.contains(table) && !table.startsWith("sqlite_")) {
          count = exportTable(table, input, outputStream, null, null, count, cancellationSignal);
        }
        stopwatch.split("table::" + table);
      }

    for (BackupProtos.SharedPreference preference : IdentityKeyUtil.getBackupRecord(context)) {
      throwIfCanceled(cancellationSignal);
      EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.PROGRESS, ++count));
      outputStream.write(preference);
    }

      for (BackupProtos.SharedPreference preference : TextSecurePreferences.getPreferencesToSaveToBackup(context)) {
        throwIfCanceled(cancellationSignal);
        EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.PROGRESS, ++count));
        outputStream.write(preference);
      }

    stopwatch.split("prefs");

      count = exportKeyValues(outputStream, SignalStore.getKeysToIncludeInBackup(), count, cancellationSignal);

      stopwatch.split("key_values");

      for (AvatarHelper.Avatar avatar : AvatarHelper.getAvatars(context)) {
        throwIfCanceled(cancellationSignal);
        if (avatar != null) {
          EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.PROGRESS, ++count));
          outputStream.write(avatar.getFilename(), avatar.getInputStream(), avatar.getLength());
        }
    }

    stopwatch.split("avatars");
    stopwatch.stop(TAG);

      outputStream.writeEnd();
    } finally {
      if (closeOutputStream) {
        outputStream.close();
      }
      EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.FINISHED, ++count));
    }
  }

  private static void throwIfCanceled(@NonNull BackupCancellationSignal cancellationSignal) throws BackupCanceledException {
    if (cancellationSignal.isCanceled()) {
      throw new BackupCanceledException();
    }
  }

  private static List<String> exportSchema(@NonNull SQLiteDatabase input, @NonNull BackupFrameOutputStream outputStream)
      throws IOException
  {
    List<String> tables = new LinkedList<>();

    try (Cursor cursor = input.rawQuery("SELECT sql, name, type FROM sqlite_master", null)) {
      while (cursor != null && cursor.moveToNext()) {
        String sql  = cursor.getString(0);
        String name = cursor.getString(1);
        String type = cursor.getString(2);

        if (sql != null) {

          boolean isSmsFtsSecretTable = name != null && !name.equals(SearchDatabase.SMS_FTS_TABLE_NAME) && name.startsWith(SearchDatabase.SMS_FTS_TABLE_NAME);
          boolean isMmsFtsSecretTable = name != null && !name.equals(SearchDatabase.MMS_FTS_TABLE_NAME) && name.startsWith(SearchDatabase.MMS_FTS_TABLE_NAME);

          if (!isSmsFtsSecretTable && !isMmsFtsSecretTable) {
            if ("table".equals(type)) {
              tables.add(name);
            }

            outputStream.write(BackupProtos.SqlStatement.newBuilder().setStatement(cursor.getString(0)).build());
          }
        }
      }
    }

    return tables;
  }

  private static int exportTable(@NonNull String table,
                                 @NonNull SQLiteDatabase input,
                                 @NonNull BackupFrameOutputStream outputStream,
                                 @Nullable Predicate<Cursor> predicate,
                                 @Nullable PostProcessor postProcess,
                                 int count,
                                 @NonNull BackupCancellationSignal cancellationSignal)
      throws IOException
  {
    String template = "INSERT INTO " + table + " VALUES ";

    try (Cursor cursor = input.rawQuery("SELECT * FROM " + table, null)) {
      while (cursor != null && cursor.moveToNext()) {
        throwIfCanceled(cancellationSignal);

        if (predicate == null || predicate.test(cursor)) {
          StringBuilder                     statement        = new StringBuilder(template);
          BackupProtos.SqlStatement.Builder statementBuilder = BackupProtos.SqlStatement.newBuilder();

          statement.append('(');

          for (int i=0;i<cursor.getColumnCount();i++) {
            statement.append('?');

            if (cursor.getType(i) == Cursor.FIELD_TYPE_STRING) {
              statementBuilder.addParameters(BackupProtos.SqlStatement.SqlParameter.newBuilder().setStringParamter(cursor.getString(i)));
            } else if (cursor.getType(i) == Cursor.FIELD_TYPE_FLOAT) {
              statementBuilder.addParameters(BackupProtos.SqlStatement.SqlParameter.newBuilder().setDoubleParameter(cursor.getDouble(i)));
            } else if (cursor.getType(i) == Cursor.FIELD_TYPE_INTEGER) {
              statementBuilder.addParameters(BackupProtos.SqlStatement.SqlParameter.newBuilder().setIntegerParameter(cursor.getLong(i)));
            } else if (cursor.getType(i) == Cursor.FIELD_TYPE_BLOB) {
              statementBuilder.addParameters(BackupProtos.SqlStatement.SqlParameter.newBuilder().setBlobParameter(ByteString.copyFrom(cursor.getBlob(i))));
            } else if (cursor.getType(i) == Cursor.FIELD_TYPE_NULL) {
              statementBuilder.addParameters(BackupProtos.SqlStatement.SqlParameter.newBuilder().setNullparameter(true));
            } else {
              throw new AssertionError("unknown type?"  + cursor.getType(i));
            }

            if (i < cursor.getColumnCount()-1) {
              statement.append(',');
            }
          }

          statement.append(')');

          EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.PROGRESS, ++count));
          outputStream.write(statementBuilder.setStatement(statement.toString()).build());

          if (postProcess != null) {
            count = postProcess.postProcess(cursor, count);
          }
        }
      }
    }

    return count;
  }

  private static int exportAttachment(@NonNull AttachmentSecret attachmentSecret, @NonNull Cursor cursor, @NonNull BackupFrameOutputStream outputStream, int count) {
    try {
      long rowId    = cursor.getLong(cursor.getColumnIndexOrThrow(AttachmentDatabase.ROW_ID));
      long uniqueId = cursor.getLong(cursor.getColumnIndexOrThrow(AttachmentDatabase.UNIQUE_ID));
      long size     = cursor.getLong(cursor.getColumnIndexOrThrow(AttachmentDatabase.SIZE));

      String data   = cursor.getString(cursor.getColumnIndexOrThrow(AttachmentDatabase.DATA));
      byte[] random = cursor.getBlob(cursor.getColumnIndexOrThrow(AttachmentDatabase.DATA_RANDOM));

      if (!TextUtils.isEmpty(data)) {
        long fileLength = new File(data).length();
        long dbLength   = size;

        if (size <= 0 || fileLength != dbLength) {
          size = calculateVeryOldStreamLength(attachmentSecret, random, data);
          Log.w(TAG, "Needed size calculation! Manual: " + size + " File: " + fileLength + "  DB: " + dbLength + " ID: " + new AttachmentId(rowId, uniqueId));
        }
      }

      if (!TextUtils.isEmpty(data) && size > 0) {
        InputStream inputStream;

        if (random != null && random.length == 32) inputStream = ModernDecryptingPartInputStream.createFor(attachmentSecret, random, new File(data), 0);
        else                                       inputStream = ClassicDecryptingPartInputStream.createFor(attachmentSecret, new File(data));

        EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.PROGRESS, ++count));
        outputStream.write(new AttachmentId(rowId, uniqueId), inputStream, size);
      }
    } catch (IOException e) {
      Log.w(TAG, e);
    }

    return count;
  }

  private static int exportSticker(@NonNull AttachmentSecret attachmentSecret, @NonNull Cursor cursor, @NonNull BackupFrameOutputStream outputStream, int count) {
    try {
      long rowId    = cursor.getLong(cursor.getColumnIndexOrThrow(StickerDatabase._ID));
      long size     = cursor.getLong(cursor.getColumnIndexOrThrow(StickerDatabase.FILE_LENGTH));

      String data   = cursor.getString(cursor.getColumnIndexOrThrow(StickerDatabase.FILE_PATH));
      byte[] random = cursor.getBlob(cursor.getColumnIndexOrThrow(StickerDatabase.FILE_RANDOM));

      if (!TextUtils.isEmpty(data) && size > 0) {
        EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.PROGRESS, ++count));
        InputStream inputStream = ModernDecryptingPartInputStream.createFor(attachmentSecret, random, new File(data), 0);
        outputStream.writeSticker(rowId, inputStream, size);
      }
    } catch (IOException e) {
      Log.w(TAG, e);
    }

    return count;
  }

  private static long calculateVeryOldStreamLength(@NonNull AttachmentSecret attachmentSecret, @Nullable byte[] random, @NonNull String data) throws IOException {
    long result = 0;
    InputStream inputStream;

    if (random != null && random.length == 32) inputStream = ModernDecryptingPartInputStream.createFor(attachmentSecret, random, new File(data), 0);
    else                                       inputStream = ClassicDecryptingPartInputStream.createFor(attachmentSecret, new File(data));

    int read;
    byte[] buffer = new byte[8192];

    while ((read = inputStream.read(buffer, 0, buffer.length)) != -1) {
      result += read;
    }

    return result;
  }

  private static int exportKeyValues(@NonNull BackupFrameOutputStream outputStream,
                                     @NonNull List<String> keysToIncludeInBackup,
                                     int count,
                                     BackupCancellationSignal cancellationSignal) throws IOException
  {
    KeyValueDataSet dataSet = KeyValueDatabase.getInstance(ApplicationDependencies.getApplication())
            .getDataSet();

    for (String key : keysToIncludeInBackup) {
      throwIfCanceled(cancellationSignal);
      if (!dataSet.containsKey(key)) {
        continue;
      }
      BackupProtos.KeyValue.Builder builder = BackupProtos.KeyValue.newBuilder()
              .setKey(key);

      Class<?> type = dataSet.getType(key);
      if (type == byte[].class) {
        builder.setBlobValue(ByteString.copyFrom(dataSet.getBlob(key, null)));
      } else if (type == Boolean.class) {
        builder.setBooleanValue(dataSet.getBoolean(key, false));
      } else if (type == Float.class) {
        builder.setFloatValue(dataSet.getFloat(key, 0));
      } else if (type == Integer.class) {
        builder.setIntegerValue(dataSet.getInteger(key, 0));
      } else if (type == Long.class) {
        builder.setLongValue(dataSet.getLong(key, 0));
      } else if (type == String.class) {
        builder.setStringValue(dataSet.getString(key, null));
      } else {
        throw new AssertionError("Unknown type: " + type);
      }

      EventBus.getDefault().post(new BackupEvent(BackupEvent.Type.PROGRESS, ++count));
      outputStream.write(builder.build());
    }

    return count;
  }

  private static boolean isNonExpiringMmsMessage(@NonNull Cursor cursor) {
    return cursor.getInt(cursor.getColumnIndexOrThrow(MmsSmsColumns.EXPIRES_IN)) <= 0 &&
            cursor.getInt(cursor.getColumnIndexOrThrow(MmsDatabase.VIEW_ONCE))    <= 0;
  }

  private static boolean isNonExpiringSmsMessage(@NonNull Cursor cursor) {
    return cursor.getInt(cursor.getColumnIndexOrThrow(MmsSmsColumns.EXPIRES_IN)) <= 0;
  }

  private static boolean isForNonExpiringMessage(@NonNull SQLiteDatabase db, long mmsId) {
    String[] columns = new String[] { MmsDatabase.EXPIRES_IN, MmsDatabase.VIEW_ONCE};
    String   where   = MmsDatabase.ID + " = ?";
    String[] args    = new String[] { String.valueOf(mmsId) };

    try (Cursor mmsCursor = db.query(MmsDatabase.TABLE_NAME, columns, where, args, null, null, null)) {
      if (mmsCursor != null && mmsCursor.moveToFirst()) {
        return mmsCursor.getLong(mmsCursor.getColumnIndexOrThrow(MmsDatabase.EXPIRES_IN)) == 0 &&
                mmsCursor.getLong(mmsCursor.getColumnIndexOrThrow(MmsDatabase.VIEW_ONCE))  == 0;
      }
    }

    return false;
  }


  private static class BackupFrameOutputStream extends BackupStream {

    private final OutputStream outputStream;
    private final Cipher       cipher;
    private final Mac          mac;

    private final byte[]       cipherKey;
    private final byte[]       macKey;

    private byte[] iv;
    private int    counter;

    private BackupFrameOutputStream(@NonNull OutputStream output, @NonNull String passphrase) throws IOException {
      try {
        byte[]   salt    = Util.getSecretBytes(32);
        byte[]   key     = getBackupKey(passphrase, salt);
        byte[]   derived = new HKDFv3().deriveSecrets(key, "Backup Export".getBytes(), 64);
        byte[][] split   = ByteUtil.split(derived, 32, 32);

        this.cipherKey = split[0];
        this.macKey    = split[1];

        this.cipher       = Cipher.getInstance("AES/CTR/NoPadding");
        this.mac          = Mac.getInstance("HmacSHA256");
        this.outputStream = output;
        this.iv           = Util.getSecretBytes(16);
        this.counter      = Conversions.byteArrayToInt(iv);

        mac.init(new SecretKeySpec(macKey, "HmacSHA256"));

        byte[] header = BackupProtos.BackupFrame.newBuilder().setHeader(BackupProtos.Header.newBuilder()
                                                                                           .setIv(ByteString.copyFrom(iv))
                                                                                           .setSalt(ByteString.copyFrom(salt)))
                                                .build().toByteArray();

        outputStream.write(Conversions.intToByteArray(header.length));
        outputStream.write(header);
      } catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidKeyException e) {
        throw new AssertionError(e);
      }
    }

    public void write(BackupProtos.SharedPreference preference) throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder().setPreference(preference).build());
    }

    public void write(BackupProtos.KeyValue keyValue) throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder().setKeyValue(keyValue).build());
    }

    public void write(BackupProtos.SqlStatement statement) throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder().setStatement(statement).build());
    }

    public void write(@NonNull String avatarName, @NonNull InputStream in, long size) throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder()
                                                  .setAvatar(BackupProtos.Avatar.newBuilder()
                                                          .setRecipientId(avatarName)
                                                                                .setLength(Util.toIntExact(size))
                                                                                .build())
                                                  .build());

      if (writeStream(in) != size) {
        throw new IOException("Size mismatch!");
      }
    }

    public void write(@NonNull AttachmentId attachmentId, @NonNull InputStream in, long size) throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder()
                                                  .setAttachment(BackupProtos.Attachment.newBuilder()
                                                                                        .setRowId(attachmentId.getRowId())
                                                                                        .setAttachmentId(attachmentId.getUniqueId())
                                                                                        .setLength(Util.toIntExact(size))
                                                                                        .build())
                                                  .build());

      if (writeStream(in) != size) {
        throw new IOException("Size mismatch!");
      }
    }

    public void writeSticker(long rowId, @NonNull InputStream in, long size) throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder()
              .setSticker(BackupProtos.Sticker.newBuilder()
                      .setRowId(rowId)
                      .setLength(Util.toIntExact(size))
                      .build())
              .build());

      if (writeStream(in) != size) {
        throw new IOException("Size mismatch!");
      }
    }

    void writeDatabaseVersion(int version) throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder()
                                                  .setVersion(BackupProtos.DatabaseVersion.newBuilder().setVersion(version))
                                                  .build());
    }

    void writeEnd() throws IOException {
      write(outputStream, BackupProtos.BackupFrame.newBuilder().setEnd(true).build());
    }

    /**
     * @return The amount of data written from the provided InputStream.
     */
    private long writeStream(@NonNull InputStream inputStream) throws IOException {
      try {
        Conversions.intToByteArray(iv, 0, counter++);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cipherKey, "AES"), new IvParameterSpec(iv));
        mac.update(iv);

        byte[] buffer = new byte[8192];
        long   total  = 0;

        int read;

        while ((read = inputStream.read(buffer)) != -1) {
          byte[] ciphertext = cipher.update(buffer, 0, read);

          if (ciphertext != null) {
            outputStream.write(ciphertext);
            mac.update(ciphertext);
          }

          total += read;
        }

        byte[] remainder = cipher.doFinal();
        outputStream.write(remainder);
        mac.update(remainder);

        byte[] attachmentDigest = mac.doFinal();
        outputStream.write(attachmentDigest, 0, 10);

        return total;
      } catch (InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
        throw new AssertionError(e);
      }
    }

    private void write(@NonNull OutputStream out, @NonNull BackupProtos.BackupFrame frame) throws IOException {
      try {
        Conversions.intToByteArray(iv, 0, counter++);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cipherKey, "AES"), new IvParameterSpec(iv));

        byte[] frameCiphertext = cipher.doFinal(frame.toByteArray());
        byte[] frameMac        = mac.doFinal(frameCiphertext);
        byte[] length          = Conversions.intToByteArray(frameCiphertext.length + 10);

        out.write(length);
        out.write(frameCiphertext);
        out.write(frameMac, 0, 10);
      } catch (InvalidKeyException | InvalidAlgorithmParameterException | IllegalBlockSizeException | BadPaddingException e) {
        throw new AssertionError(e);
      }
    }


    public void close() throws IOException {
      outputStream.close();
    }
  }

  public interface PostProcessor {
    int postProcess(@NonNull Cursor cursor, int count);
  }

  public interface BackupCancellationSignal {
    boolean isCanceled();
  }

  public static final class BackupCanceledException extends IOException { }
}
