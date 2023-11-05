/*
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package su.sres.securesms;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.AlertDialog;

import su.sres.securesms.contacts.sync.DirectoryHelper;
import su.sres.securesms.conversation.ConversationIntents;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.groups.ui.creategroup.CreateGroupActivity;
import su.sres.securesms.jobmanager.impl.NetworkConstraint;
import su.sres.core.util.logging.Log;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import org.whispersystems.libsignal.util.guava.Optional;

import java.io.IOException;

import su.sres.securesms.util.TextSecurePreferences;
import su.sres.securesms.util.concurrent.SimpleTask;
import su.sres.securesms.util.views.SimpleProgressDialog;

/**
 * Activity container for starting a new conversation.
 *
 * @author Moxie Marlinspike
 *
 */
public class NewConversationActivity extends ContactSelectionActivity
        implements ContactSelectionListFragment.ListCallback
{

  @SuppressWarnings("unused")
  private static final String TAG = Log.tag(NewConversationActivity.class);

  @Override
  public void onCreate(Bundle bundle, boolean ready) {
    super.onCreate(bundle, ready);
    assert getSupportActionBar() != null;
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
  }

  @Override
  public boolean onBeforeContactSelected(Optional<RecipientId> recipientId, String number) {
    if (recipientId.isPresent()) {
      launch(Recipient.resolved(recipientId.get()));
    } else {
      // is there a case when a recipientId would be absent?
      Log.i(TAG, "[onContactSelected] Maybe creating a new recipient.");
      if (TextSecurePreferences.isPushRegistered(this) && NetworkConstraint.isMet(this)) {
        Log.i(TAG, "[onContactSelected] Doing contact refresh.");

        AlertDialog progress = SimpleProgressDialog.show(this);

        SimpleTask.run(getLifecycle(), () -> {
          Recipient resolved = Recipient.external(this, number);

          if (!resolved.isRegistered() || !resolved.hasUuid()) {
            Log.i(TAG, "[onContactSelected] Not registered or no UUID. Doing a directory refresh.");
            try {
              DirectoryHelper.refreshDirectory(this);
              resolved = Recipient.resolved(resolved.getId());
            } catch (IOException e) {
              Log.w(TAG, "[onContactSelected] Failed to refresh directory.");
            }
          }

          return resolved;
        }, resolved -> {
          progress.dismiss();
          launch(resolved);
        });
      } else {
        launch(Recipient.external(this, number));
      }
    }
    return true;
  }

  private void launch(Recipient recipient) {

    long   existingThread = DatabaseFactory.getThreadDatabase(this).getThreadIdIfExistsFor(recipient.getId());
    Intent intent         = ConversationIntents.createBuilder(this, recipient.getId(), existingThread)
            .withDraftText(getIntent().getStringExtra(Intent.EXTRA_TEXT))
            .withDataUri(getIntent().getData())
            .withDataType(getIntent().getType())
            .build();

    startActivity(intent);
    finish();
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    super.onOptionsItemSelected(item);

    switch (item.getItemId()) {
      case android.R.id.home:   super.onBackPressed();   return true;
      case R.id.menu_refresh:   handleManualRefresh();   return true;
      case R.id.menu_new_group: handleCreateGroup(this); return true;
//      case R.id.menu_invite:    handleInvite();          return true;
    }

    return false;
  }

  private void handleManualRefresh() {
    contactsFragment.setRefreshing(true);
    onRefresh();
  }

  private void handleCreateGroup(Context context) {
    if(TextSecurePreferences.isPushRegistered(context)) {
      startActivity(CreateGroupActivity.newIntent(this));
    }
  }

  private void handleInvite() {
    startActivity(new Intent(this, InviteActivity.class));
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    menu.clear();
    getMenuInflater().inflate(R.menu.new_conversation_activity, menu);

    super.onCreateOptionsMenu(menu);
    return true;
  }

  @Override
  public void onInvite() {
    handleInvite();
    finish();
  }

  @Override
  public void onNewGroup(boolean forceV1) {
    handleCreateGroup(this);
    finish();
  }
}
