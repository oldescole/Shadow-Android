package su.sres.securesms.components.reminder;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Space;
import android.widget.TextView;

import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import su.sres.securesms.R;

import java.util.List;

/**
 * View to display actionable reminders to the user
 */
public final class ReminderView extends FrameLayout {
  private ProgressBar           progressBar;
  private TextView              progressText;
  private ViewGroup             container;
  private ImageButton           closeButton;
  private TextView              title;
  private TextView              text;
  private OnDismissListener     dismissListener;
  private Space                 space;
  private RecyclerView          actionsRecycler;
  private OnActionClickListener actionClickListener;

  public ReminderView(Context context) {
    super(context);
    initialize();
  }

  public ReminderView(Context context, AttributeSet attrs) {
    super(context, attrs);
    initialize();
  }

  public ReminderView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    initialize();
  }

  private void initialize() {
    LayoutInflater.from(getContext()).inflate(R.layout.reminder_header, this, true);
    progressBar     = findViewById(R.id.reminder_progress);
    progressText    = findViewById(R.id.reminder_progress_text);
    container       = findViewById(R.id.container);
    closeButton     = findViewById(R.id.cancel);
    title           = findViewById(R.id.reminder_title);
    text            = findViewById(R.id.reminder_text);
    space           = findViewById(R.id.reminder_space);
    actionsRecycler = findViewById(R.id.reminder_actions);
  }

  public void showReminder(final Reminder reminder) {
    if (!TextUtils.isEmpty(reminder.getTitle())) {
      title.setText(reminder.getTitle());
      title.setVisibility(VISIBLE);
      space.setVisibility(GONE);
    } else {
      title.setText("");
      title.setVisibility(GONE);
      space.setVisibility(VISIBLE);
    }

    if (!reminder.isDismissable()) {
      space.setVisibility(GONE);
    }

    text.setText(reminder.getText());
    text.setTextColor(ContextCompat.getColor(getContext(), R.color.signal_button_primary_text));
    switch (reminder.getImportance()) {
      case NORMAL:
        container.setBackgroundResource(R.drawable.reminder_background_normal);
        break;
      case ERROR:
        container.setBackgroundResource(R.drawable.reminder_background_error);
        text.setTextColor(ContextCompat.getColor(getContext(), R.color.signal_text_primary));
        break;
      case TERMINAL:
        container.setBackgroundResource(R.drawable.reminder_background_terminal);
        break;
      default:
        throw new IllegalStateException();
    }

    setOnClickListener(reminder.getOkListener());

    closeButton.setVisibility(reminder.isDismissable() ? View.VISIBLE : View.GONE);
    closeButton.setOnClickListener(new OnClickListener() {
      @Override
      public void onClick(View v) {
        hide();
        if (reminder.getDismissListener() != null) reminder.getDismissListener().onClick(v);
        if (dismissListener != null) dismissListener.onDismiss();
      }
    });

    int progress = reminder.getProgress();
    if (progress != -1) {
      progressBar.setProgress(progress);
      progressBar.setVisibility(VISIBLE);
      progressText.setText(getContext().getString(R.string.reminder_header_progress, progress));
      progressText.setVisibility(VISIBLE);
    } else {
      progressBar.setVisibility(GONE);
      progressText.setVisibility(GONE);
    }

    List<Reminder.Action> actions = reminder.getActions();
    if (actions.isEmpty()) {
      actionsRecycler.setVisibility(GONE);
    } else {
      actionsRecycler.setVisibility(VISIBLE);
      actionsRecycler.setAdapter(new ReminderActionsAdapter(actions, this::handleActionClicked));
    }

    container.setVisibility(View.VISIBLE);
  }

  private void handleActionClicked(@IdRes int actionId) {
    if (actionClickListener != null) actionClickListener.onActionClick(actionId);
  }

  public void setOnDismissListener(OnDismissListener dismissListener) {
    this.dismissListener = dismissListener;
  }

  public void setOnActionClickListener(@Nullable OnActionClickListener actionClickListener) {
    this.actionClickListener = actionClickListener;
  }

  public void requestDismiss() {
    closeButton.performClick();
  }

  public void hide() {
    container.setVisibility(View.GONE);
  }

  public interface OnDismissListener {
    void onDismiss();
  }

  public interface OnActionClickListener {
    void onActionClick(@IdRes int actionId);
  }
}
