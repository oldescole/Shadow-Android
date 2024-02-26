package su.sres.securesms.groups.ui.creategroup;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Pair;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.annimon.stream.Stream;

import su.sres.securesms.ContactSelectionActivity;
import su.sres.securesms.ContactSelectionListFragment;
import su.sres.securesms.R;
import su.sres.securesms.contacts.ContactsCursorLoader;
import su.sres.securesms.contacts.sync.DirectoryHelper;
import su.sres.securesms.database.RecipientDatabase;
import su.sres.securesms.groups.GroupsV2CapabilityChecker;
import su.sres.securesms.groups.ui.creategroup.details.AddGroupDetailsActivity;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.core.util.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.util.FeatureFlags;
import su.sres.securesms.util.Stopwatch;
import su.sres.securesms.util.Util;
import su.sres.securesms.util.concurrent.SimpleTask;
import su.sres.securesms.util.views.SimpleProgressDialog;

import org.whispersystems.libsignal.util.guava.Optional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CreateGroupActivity extends ContactSelectionActivity {

    private static final String TAG = Log.tag(CreateGroupActivity.class);

    private static final short REQUEST_CODE_ADD_DETAILS = 17275;

    private View next;

    public static Intent newIntent(@NonNull Context context) {

        Intent intent = new Intent(context, CreateGroupActivity.class);

        intent.putExtra(ContactSelectionListFragment.REFRESHABLE, false);
        intent.putExtra(ContactSelectionActivity.EXTRA_LAYOUT_RES_ID, R.layout.create_group_activity);

        int displayMode = Util.isDefaultSmsProvider(context) ? ContactsCursorLoader.DisplayMode.FLAG_SMS | ContactsCursorLoader.DisplayMode.FLAG_PUSH
                : ContactsCursorLoader.DisplayMode.FLAG_PUSH;

        intent.putExtra(ContactSelectionListFragment.DISPLAY_MODE, displayMode);
        intent.putExtra(ContactSelectionListFragment.SELECTION_LIMITS, FeatureFlags.groupLimits().excludingSelf());

        return intent;
    }

    @Override
    public void onCreate(Bundle bundle, boolean ready) {
        super.onCreate(bundle, ready);
        assert getSupportActionBar() != null;
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        next = findViewById(R.id.next);

        next.setOnClickListener(v -> handleNextPressed());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == REQUEST_CODE_ADD_DETAILS && resultCode == RESULT_OK) {
            finish();
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public boolean onBeforeContactSelected(Optional<RecipientId> recipientId, String number) {
        if (contactsFragment.hasQueryFilter()) {
            getContactFilterView().clear();
        }

        enableNext();

        return true;
    }

    @Override
    public void onContactDeselected(Optional<RecipientId> recipientId, String number) {
        if (contactsFragment.hasQueryFilter()) {
            getContactFilterView().clear();
        }
    }

    @Override
    public void onSelectionChanged() {
        int selectedContactsCount = contactsFragment.getTotalMemberCount();
        if (selectedContactsCount == 0) {
            getToolbar().setTitle(getString(R.string.CreateGroupActivity__select_members));
        } else {
            getToolbar().setTitle(getResources().getQuantityString(R.plurals.CreateGroupActivity__d_members, selectedContactsCount, selectedContactsCount));
        }
    }

    private void enableNext() {
        next.setEnabled(true);
        next.animate().alpha(1f);
    }

    private void disableNext() {
        next.setEnabled(false);
        next.animate().alpha(0.5f);
    }

    private void handleNextPressed() {
        Stopwatch stopwatch = new Stopwatch("Recipient Refresh");
        SimpleProgressDialog.DismissibleDialog dismissibleDialog = SimpleProgressDialog.showDelayed(this);

        SimpleTask.run(getLifecycle(), () -> {
            List<RecipientId> ids = Stream.of(contactsFragment.getSelectedContacts())
                    .map(selectedContact -> selectedContact.getOrCreateRecipientId(this))
                    .toList();

            List<Recipient> resolved = Recipient.resolvedList(ids);

            stopwatch.split("resolve");

            List<Recipient> registeredChecks = Stream.of(resolved)
                    .filter(r -> r.getRegistered() == RecipientDatabase.RegisteredState.UNKNOWN)
                    .toList();

            Log.i(TAG, "Need to do " + registeredChecks.size() + " registration checks.");

            if (!registeredChecks.isEmpty()) {
                try {
                    DirectoryHelper.refreshDirectory(this);
                } catch (IOException e) {
                    Log.w(TAG, "Failed to refresh registered status", e);
                }
            }

            stopwatch.split("registered");

            List<Recipient> recipientsAndSelf = new ArrayList<>(resolved);
            recipientsAndSelf.add(Recipient.self().resolve());

            if (!SignalStore.internalValues().gv2DoNotCreateGv2Groups()) {
                try {
                    GroupsV2CapabilityChecker.refreshCapabilitiesIfNecessary(recipientsAndSelf);
                } catch (IOException e) {
                    Log.w(TAG, "Failed to refresh all recipient capabilities.", e);
                }
            }

            stopwatch.split("capabilities");

            resolved = Recipient.resolvedList(ids);

            Pair<Boolean, List<RecipientId>> result;

            boolean gv2 = Stream.of(recipientsAndSelf).allMatch(r -> r.getGroupsV2Capability() == Recipient.Capability.SUPPORTED);
            if (!gv2 && Stream.of(resolved).anyMatch(r -> !r.hasE164())) {
                Log.w(TAG, "Invalid GV1 group...");
                ids = Collections.emptyList();
                result = Pair.create(false, ids);
            } else {
                result = Pair.create(true, ids);
            }

            stopwatch.split("gv1-check");

            return result;
        }, result -> {
            dismissibleDialog.dismiss();

            stopwatch.stop(TAG);

            if (result.first) {
                startActivityForResult(AddGroupDetailsActivity.newIntent(this, result.second), REQUEST_CODE_ADD_DETAILS);
            } else {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.CreateGroupActivity_some_contacts_cannot_be_in_legacy_groups)
                        .setPositiveButton(android.R.string.ok, (d, w) -> d.dismiss())
                        .show();
            }
        });
    }
}