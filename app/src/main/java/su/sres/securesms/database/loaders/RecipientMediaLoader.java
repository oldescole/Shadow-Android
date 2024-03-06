package su.sres.securesms.database.loaders;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.MediaDatabase;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;

/**
 * It is more efficient to use the {@link ThreadMediaLoader} if you know the thread id already.
 */
public final class RecipientMediaLoader extends MediaLoader {

  @Nullable private final RecipientId           recipientId;
  @NonNull private final  MediaType             mediaType;
  @NonNull private final  MediaDatabase.Sorting sorting;

  public RecipientMediaLoader(@NonNull Context context,
                              @Nullable RecipientId recipientId,
                              @NonNull MediaType mediaType,
                              @NonNull MediaDatabase.Sorting sorting)
  {
    super(context);
    this.recipientId = recipientId;
    this.mediaType   = mediaType;
    this.sorting     = sorting;
  }

  @Override
  public Cursor getCursor() {
    if (recipientId == null || recipientId.isUnknown()) return null;

    long threadId = DatabaseFactory.getThreadDatabase(getContext())
                                   .getOrCreateThreadIdFor(Recipient.resolved(recipientId));

    return ThreadMediaLoader.createThreadMediaCursor(context, threadId, mediaType, sorting);
  }

}