package su.sres.securesms.recipients.ui.managerecipient;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProviders;

import su.sres.securesms.AvatarPreviewActivity;
import su.sres.securesms.LoggingFragment;
import su.sres.securesms.MediaPreviewActivity;
import su.sres.securesms.MuteDialog;
import su.sres.securesms.R;
import su.sres.securesms.components.AvatarImageView;
import su.sres.securesms.components.ThreadPhotoRailView;
import su.sres.securesms.contacts.avatars.FallbackContactPhoto;
import su.sres.securesms.contacts.avatars.FallbackPhoto80dp;
import su.sres.securesms.conversation.colors.AvatarColor;
import su.sres.securesms.database.DatabaseFactory;
import su.sres.securesms.groups.ui.GroupMemberListView;
import su.sres.securesms.keyvalue.SignalStore;
import su.sres.securesms.mediaoverview.MediaOverviewActivity;
import su.sres.securesms.mms.GlideApp;
import su.sres.securesms.notifications.NotificationChannels;
import su.sres.securesms.profiles.edit.EditProfileActivity;
import su.sres.securesms.recipients.Recipient;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.recipients.ui.disappearingmessages.RecipientDisappearingMessagesActivity;
import su.sres.securesms.recipients.ui.notifications.CustomNotificationsDialogFragment;
import su.sres.securesms.util.DateUtils;
import su.sres.securesms.util.LifecycleCursorWrapper;
import su.sres.securesms.util.ServiceUtil;
import su.sres.securesms.util.Util;
import su.sres.core.util.concurrent.SignalExecutors;
import su.sres.securesms.util.ViewUtil;
import su.sres.securesms.wallpaper.ChatWallpaperActivity;

import java.util.Locale;
import java.util.Objects;

public class ManageRecipientFragment extends LoggingFragment {
  private static final String RECIPIENT_ID      = "RECIPIENT_ID";
  private static final String FROM_CONVERSATION = "FROM_CONVERSATION";

  private static final int REQUEST_CODE_RETURN_FROM_MEDIA = 405;
  private static final int REQUEST_CODE_ADD_CONTACT       = 588;
  private static final int REQUEST_CODE_VIEW_CONTACT      = 610;

  private ManageRecipientViewModel               viewModel;
  private GroupMemberListView                    sharedGroupList;
  private Toolbar                                toolbar;
  private TextView                               title;
  private TextView                               about;
  private TextView                               subtitle;
  private ViewGroup                              internalDetails;
  private TextView                               internalDetailsText;
  private View                                   disableProfileSharingButton;
  private View                                   contactRow;
  //   private TextView                               contactText;
  //   private ImageView                              contactIcon;
  private AvatarImageView                        avatar;
  private ThreadPhotoRailView                    threadPhotoRailView;
  private View                                   mediaCard;
  private ManageRecipientViewModel.CursorFactory cursorFactory;
  private View                                   sharedMediaRow;
  private View                                   disappearingMessagesCard;
  private View                                   disappearingMessagesRow;
  private TextView                               disappearingMessages;
  private View                                   blockUnblockCard;
  private TextView                               block;
  private TextView                               unblock;
  private View                                   groupMembershipCard;
  private TextView                               addToAGroup;
  private SwitchCompat                           muteNotificationsSwitch;
  private View                                   muteNotificationsRow;
  private TextView                               muteNotificationsUntilLabel;
  private View                                   notificationsCard;
  private TextView                               customNotificationsButton;
  private View                                   customNotificationsRow;
  private View                                   toggleAllGroups;
  private View                                   viewSafetyNumber;
  private TextView                               groupsInCommonCount;
  private View                                   messageButton;
  private View                                   secureCallButton;
  private View                                   secureVideoCallButton;
  private TextView                               chatWallpaperButton;

  static ManageRecipientFragment newInstance(@NonNull RecipientId recipientId, boolean fromConversation) {
    ManageRecipientFragment fragment = new ManageRecipientFragment();
    Bundle                  args     = new Bundle();

    args.putParcelable(RECIPIENT_ID, recipientId);
    args.putBoolean(FROM_CONVERSATION, fromConversation);
    fragment.setArguments(args);

    return fragment;
  }

  @Override
  public @Nullable View onCreateView(@NonNull LayoutInflater inflater,
                                     @Nullable ViewGroup container,
                                     @Nullable Bundle savedInstanceState)
  {
    View view = inflater.inflate(R.layout.recipient_manage_fragment, container, false);

    avatar     = view.findViewById(R.id.recipient_avatar);
    toolbar    = view.findViewById(R.id.toolbar);
    contactRow = view.findViewById(R.id.recipient_contact_row);
    // contactText                 = view.findViewById(R.id.recipient_contact_text);
    //  contactIcon                 = view.findViewById(R.id.recipient_contact_icon);
    title                       = view.findViewById(R.id.name);
    about                       = view.findViewById(R.id.about);
    subtitle                    = view.findViewById(R.id.username_number);
    internalDetails             = view.findViewById(R.id.recipient_internal_details);
    internalDetailsText         = view.findViewById(R.id.recipient_internal_details_text);
    disableProfileSharingButton = view.findViewById(R.id.recipient_internal_details_disable_profile_sharing_button);
    sharedGroupList             = view.findViewById(R.id.shared_group_list);
    groupsInCommonCount         = view.findViewById(R.id.groups_in_common_count);
    threadPhotoRailView         = view.findViewById(R.id.recent_photos);
    mediaCard                   = view.findViewById(R.id.recipient_media_card);
    sharedMediaRow              = view.findViewById(R.id.shared_media_row);
    disappearingMessagesCard    = view.findViewById(R.id.recipient_disappearing_messages_card);
    disappearingMessagesRow     = view.findViewById(R.id.disappearing_messages_row);
    disappearingMessages        = view.findViewById(R.id.disappearing_messages);
    blockUnblockCard            = view.findViewById(R.id.recipient_block_and_leave_card);
    block                       = view.findViewById(R.id.block);
    unblock                     = view.findViewById(R.id.unblock);
    viewSafetyNumber            = view.findViewById(R.id.view_safety_number);
    groupMembershipCard         = view.findViewById(R.id.recipient_membership_card);
    addToAGroup                 = view.findViewById(R.id.add_to_a_group);
    muteNotificationsUntilLabel = view.findViewById(R.id.recipient_mute_notifications_until);
    muteNotificationsSwitch     = view.findViewById(R.id.recipient_mute_notifications_switch);
    muteNotificationsRow        = view.findViewById(R.id.recipient_mute_notifications_row);
    notificationsCard           = view.findViewById(R.id.recipient_notifications_card);
    customNotificationsButton   = view.findViewById(R.id.recipient_custom_notifications_button);
    customNotificationsRow      = view.findViewById(R.id.recipient_custom_notifications_row);
    toggleAllGroups             = view.findViewById(R.id.toggle_all_groups);
    messageButton               = view.findViewById(R.id.recipient_message);
    secureCallButton            = view.findViewById(R.id.recipient_voice_call);
    secureVideoCallButton       = view.findViewById(R.id.recipient_video_call);
    chatWallpaperButton         = view.findViewById(R.id.chat_wallpaper);

    return view;
  }

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);

    RecipientId                      recipientId      = Objects.requireNonNull(requireArguments().getParcelable(RECIPIENT_ID));
    boolean                          fromConversation = requireArguments().getBoolean(FROM_CONVERSATION, false);
    ManageRecipientViewModel.Factory factory          = new ManageRecipientViewModel.Factory(recipientId);

    viewModel = ViewModelProviders.of(requireActivity(), factory).get(ManageRecipientViewModel.class);

    viewModel.getCanCollapseMemberList().observe(getViewLifecycleOwner(), canCollapseMemberList -> {
      if (canCollapseMemberList) {
        toggleAllGroups.setVisibility(View.VISIBLE);
        toggleAllGroups.setOnClickListener(v -> viewModel.revealCollapsedMembers());
      } else {
        toggleAllGroups.setVisibility(View.GONE);
      }
    });

    viewModel.getIdentity().observe(getViewLifecycleOwner(), identityRecord -> {
      viewSafetyNumber.setVisibility(identityRecord != null ? View.VISIBLE : View.GONE);

      if (identityRecord != null) {
        viewSafetyNumber.setOnClickListener(view -> viewModel.onViewSafetyNumberClicked(requireActivity(), identityRecord));
      }
    });

    toolbar.setNavigationOnClickListener(v -> requireActivity().onBackPressed());
    toolbar.setOnMenuItemClickListener(this::onMenuItemSelected);
    toolbar.inflateMenu(R.menu.manage_recipient_fragment);

    if (recipientId.equals(Recipient.self().getId())) {
      notificationsCard.setVisibility(View.GONE);
      groupMembershipCard.setVisibility(View.GONE);
      blockUnblockCard.setVisibility(View.GONE);
      contactRow.setVisibility(View.GONE);
    } else {
      viewModel.getVisibleSharedGroups().observe(getViewLifecycleOwner(), members -> sharedGroupList.setMembers(members));
      viewModel.getSharedGroupsCountSummary().observe(getViewLifecycleOwner(), members -> groupsInCommonCount.setText(members));
      addToAGroup.setOnClickListener(v -> viewModel.onAddToGroupButton(requireActivity()));
      sharedGroupList.setRecipientClickListener(recipient -> viewModel.onGroupClicked(requireActivity(), recipient));
      sharedGroupList.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    viewModel.getTitle().observe(getViewLifecycleOwner(), title::setText);
    viewModel.getSubtitle().observe(getViewLifecycleOwner(), text -> {
      subtitle.setText(text);
      subtitle.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
      subtitle.setOnLongClickListener(null);
      title.setOnLongClickListener(null);
      setCopyToClipboardOnLongPress(TextUtils.isEmpty(text) ? title : subtitle);
    });
    viewModel.getDisappearingMessageTimer().observe(getViewLifecycleOwner(), string -> disappearingMessages.setText(string));
    viewModel.getRecipient().observe(getViewLifecycleOwner(), this::presentRecipient);
    viewModel.getMediaCursor().observe(getViewLifecycleOwner(), this::presentMediaCursor);
    viewModel.getMuteState().observe(getViewLifecycleOwner(), this::presentMuteState);
    viewModel.getCanAddToAGroup().observe(getViewLifecycleOwner(), canAdd -> addToAGroup.setVisibility(canAdd ? View.VISIBLE : View.GONE));

    if (SignalStore.internalValues().recipientDetails()) {
      viewModel.getInternalDetails().observe(getViewLifecycleOwner(), internalDetailsText::setText);
      disableProfileSharingButton.setOnClickListener(v -> {
        SignalExecutors.BOUNDED.execute(() -> DatabaseFactory.getRecipientDatabase(requireContext()).setProfileSharing(recipientId, false));
      });
      internalDetails.setVisibility(View.VISIBLE);
    } else {
      internalDetails.setVisibility(View.GONE);
    }

    disappearingMessagesRow.setOnClickListener(v -> startActivity(RecipientDisappearingMessagesActivity.forRecipient(requireContext(), recipientId)));
    block.setOnClickListener(v -> viewModel.onBlockClicked(requireActivity()));
    unblock.setOnClickListener(v -> viewModel.onUnblockClicked(requireActivity()));

    muteNotificationsRow.setOnClickListener(v -> {
      if (muteNotificationsSwitch.isEnabled()) {
        muteNotificationsSwitch.toggle();
      }
    });

    customNotificationsRow.setVisibility(View.VISIBLE);
    customNotificationsRow.setOnClickListener(v -> CustomNotificationsDialogFragment.create(recipientId)
                                                                                    .show(requireFragmentManager(), "CUSTOM_NOTIFICATIONS"));

    //noinspection CodeBlock2Expr
    if (NotificationChannels.supported()) {
      viewModel.hasCustomNotifications().observe(getViewLifecycleOwner(), hasCustomNotifications -> {
        customNotificationsButton.setText(hasCustomNotifications ? R.string.ManageRecipientActivity_on
                                                                 : R.string.ManageRecipientActivity_off);
      });
    }

    viewModel.getCanBlock().observe(getViewLifecycleOwner(),
                                    canBlock -> block.setVisibility(canBlock ? View.VISIBLE : View.GONE));

    viewModel.getCanUnblock().observe(getViewLifecycleOwner(),
                                      canUnblock -> unblock.setVisibility(canUnblock ? View.VISIBLE : View.GONE));

    messageButton.setOnClickListener(v -> {
      if (fromConversation) {
        requireActivity().onBackPressed();
      } else {
        viewModel.onMessage(requireActivity());
      }
    });
    secureCallButton.setOnClickListener(v -> viewModel.onSecureCall(requireActivity()));
    secureVideoCallButton.setOnClickListener(v -> viewModel.onSecureVideoCall(requireActivity()));
    chatWallpaperButton.setOnClickListener(v -> startActivity(ChatWallpaperActivity.createIntent(requireContext(), recipientId)));
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_CODE_RETURN_FROM_MEDIA) {
      applyMediaCursorFactory();
    } else if (requestCode == REQUEST_CODE_ADD_CONTACT) {
      // noop
    } else if (requestCode == REQUEST_CODE_VIEW_CONTACT) {
      // noop
    }
  }

  private void presentRecipient(@NonNull Recipient recipient) {
    Drawable colorCircle = recipient.getChatColors().asCircle();
    colorCircle.setBounds(0, 0, ViewUtil.dpToPx(16), ViewUtil.dpToPx(16));
    TextViewCompat.setCompoundDrawablesRelative(chatWallpaperButton, null, null, colorCircle, null);

    // [system contacts]
        /* if (recipient.isSystemContact()) {
            contactText.setText(R.string.ManageRecipientActivity_this_person_is_in_your_contacts);
            contactIcon.setVisibility(View.VISIBLE);
            contactRow.setOnClickListener(v -> {
                startActivityForResult(new Intent(Intent.ACTION_VIEW, recipient.getContactUri()), REQUEST_CODE_VIEW_CONTACT);
            });
        } else {
            contactText.setText(R.string.ManageRecipientActivity_add_to_system_contacts);
            contactIcon.setVisibility(View.GONE);
            contactRow.setOnClickListener(v -> {
                startActivityForResult(RecipientExporter.export(recipient).asAddContactIntent(), REQUEST_CODE_ADD_CONTACT);
            });
        } */

    String aboutText = recipient.getCombinedAboutAndEmoji();
    about.setText(aboutText);
    about.setVisibility(Util.isEmpty(aboutText) ? View.GONE : View.VISIBLE);

    disappearingMessagesCard.setVisibility(recipient.isRegistered() ? View.VISIBLE : View.GONE);
    addToAGroup.setVisibility(recipient.isRegistered() ? View.VISIBLE : View.GONE);

    AvatarColor recipientColor = recipient.getAvatarColor();
    avatar.setFallbackPhotoProvider(new Recipient.FallbackPhotoProvider() {
      @Override
      public @NonNull FallbackContactPhoto getPhotoForRecipientWithoutName() {
        return new FallbackPhoto80dp(R.drawable.ic_profile_80, recipientColor.colorInt());
      }

      @Override
      public @NonNull FallbackContactPhoto getPhotoForLocalNumber() {
        return new FallbackPhoto80dp(R.drawable.ic_note_80, recipientColor.colorInt());
      }
    });
    avatar.setAvatar(recipient);
    avatar.setOnClickListener(v -> {
      FragmentActivity activity = requireActivity();
      activity.startActivity(AvatarPreviewActivity.intentFromRecipientId(activity, recipient.getId()),
                             AvatarPreviewActivity.createTransitionBundle(activity, avatar));
    });

    secureCallButton.setVisibility(recipient.isRegistered() && !recipient.isSelf() ? View.VISIBLE : View.GONE);
    secureVideoCallButton.setVisibility(recipient.isRegistered() && !recipient.isSelf() ? View.VISIBLE : View.GONE);
  }

  private void presentMediaCursor(ManageRecipientViewModel.MediaCursor mediaCursor) {
    if (mediaCursor == null) return;
    sharedMediaRow.setOnClickListener(v -> startActivity(MediaOverviewActivity.forThread(requireContext(), mediaCursor.getThreadId())));

    setMediaCursorFactory(mediaCursor.getMediaCursorFactory());

    threadPhotoRailView.setListener(mediaRecord ->
                                        startActivityForResult(MediaPreviewActivity.intentFromMediaRecord(requireContext(),
                                                                                                          mediaRecord,
                                                                                                          ViewUtil.isLtr(threadPhotoRailView)),
                                                               REQUEST_CODE_RETURN_FROM_MEDIA));
  }

  private void presentMuteState(@NonNull ManageRecipientViewModel.MuteState muteState) {
    if (muteNotificationsSwitch.isChecked() != muteState.isMuted()) {
      muteNotificationsSwitch.setOnCheckedChangeListener(null);
      muteNotificationsSwitch.setChecked(muteState.isMuted());
    }

    muteNotificationsSwitch.setEnabled(true);
    muteNotificationsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
      if (isChecked) {
        MuteDialog.show(requireContext(), viewModel::setMuteUntil, () -> muteNotificationsSwitch.setChecked(false));
      } else {
        viewModel.clearMuteUntil();
      }
    });
    muteNotificationsUntilLabel.setVisibility(muteState.isMuted() ? View.VISIBLE : View.GONE);

    if (muteState.isMuted()) {
      muteNotificationsUntilLabel.setText(R.string.ManageRecipientActivity_always);
    } else {
      muteNotificationsUntilLabel.setText(getString(R.string.ManageRecipientActivity_until_s,
                                                    DateUtils.getTimeString(requireContext(),
                                                                            Locale.getDefault(),
                                                                            muteState.getMutedUntil())));
    }
  }

  public boolean onMenuItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.action_edit) {
      startActivity(EditProfileActivity.getIntentForUserProfileEdit(requireActivity()));
      return true;
    }

    return false;
  }

  private void setMediaCursorFactory(@Nullable ManageRecipientViewModel.CursorFactory cursorFactory) {
    if (this.cursorFactory != cursorFactory) {
      this.cursorFactory = cursorFactory;
      applyMediaCursorFactory();
    }
  }

  private void applyMediaCursorFactory() {
    Context context = getContext();
    if (context == null) return;
    if (cursorFactory != null) {
      Cursor cursor = cursorFactory.create();
      getViewLifecycleOwner().getLifecycle().addObserver(new LifecycleCursorWrapper(cursor));

      threadPhotoRailView.setCursor(GlideApp.with(context), cursor);
      mediaCard.setVisibility(cursor.getCount() > 0 ? View.VISIBLE : View.GONE);
    } else {
      threadPhotoRailView.setCursor(GlideApp.with(context), null);
      mediaCard.setVisibility(View.GONE);
    }
  }

  private static void setCopyToClipboardOnLongPress(@NonNull TextView textView) {
    textView.setOnLongClickListener(v -> {
      Util.copyToClipboard(v.getContext(), textView.getText().toString());
      ServiceUtil.getVibrator(v.getContext()).vibrate(250);
      Toast.makeText(v.getContext(), R.string.RecipientBottomSheet_copied_to_clipboard, Toast.LENGTH_SHORT).show();
      return true;
    });
  }
}