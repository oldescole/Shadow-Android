package su.sres.securesms.conversation.ui.mentions;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.database.GroupDatabase;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.groups.ui.GroupMemberEntry;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;

import java.util.Collections;
import java.util.List;

final class MentionsPickerRepository {

    private final RecipientDatabase recipientDatabase;
    private final GroupDatabase groupDatabase;

    MentionsPickerRepository(@NonNull Context context) {
        recipientDatabase = DatabaseFactory.getRecipientDatabase(context);
        groupDatabase     = DatabaseFactory.getGroupDatabase(context);
    }

    @WorkerThread
    @NonNull List<RecipientId> getMembers(@Nullable Recipient recipient) {
        if (recipient == null || !recipient.isPushV2Group()) {
            return Collections.emptyList();
        }

        return Stream.of(groupDatabase.getGroupMembers(recipient.requireGroupId(), GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF))
                .map(Recipient::getId)
                .toList();
    }

    @WorkerThread
    @NonNull List<Recipient> search(@NonNull MentionQuery mentionQuery) {
        if (mentionQuery.query == null) {
            return Collections.emptyList();
        }

        return recipientDatabase.queryRecipientsForMentions(mentionQuery.query, mentionQuery.members);
    }

    static class MentionQuery {
        @Nullable private final String            query;
        @NonNull  private final List<RecipientId> members;

        MentionQuery(@Nullable String query, @NonNull List<RecipientId> members) {
            this.query   = query;
            this.members = members;
        }
    }
}