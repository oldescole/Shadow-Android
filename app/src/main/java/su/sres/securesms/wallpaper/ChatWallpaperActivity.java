package su.sres.securesms.wallpaper;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProviders;
import androidx.navigation.NavGraph;
import androidx.navigation.Navigation;

import su.sres.securesms.PassphraseRequiredActivity;
import su.sres.securesms.R;
import su.sres.securesms.recipients.RecipientId;
import su.sres.securesms.util.DynamicNoActionBarTheme;
import su.sres.securesms.util.DynamicTheme;

public final class ChatWallpaperActivity extends PassphraseRequiredActivity {

    private static final String EXTRA_RECIPIENT_ID = "extra.recipient.id";

    private final DynamicTheme dynamicTheme = new DynamicNoActionBarTheme();

    public static @NonNull Intent createIntent(@NonNull Context context) {
        return createIntent(context, null);
    }

    public static @NonNull Intent createIntent(@NonNull Context context, @Nullable RecipientId recipientId) {
        Intent intent = new Intent(context, ChatWallpaperActivity.class);
        intent.putExtra(EXTRA_RECIPIENT_ID, recipientId);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState, boolean ready) {
        ChatWallpaperViewModel.Factory factory = new ChatWallpaperViewModel.Factory(getIntent().getParcelableExtra(EXTRA_RECIPIENT_ID));
        ViewModelProviders.of(this, factory).get(ChatWallpaperViewModel.class);

        dynamicTheme.onCreate(this);
        setContentView(R.layout.chat_wallpaper_activity);

        if (savedInstanceState == null) {
            Bundle   extras = getIntent().getExtras();
            NavGraph graph  = Navigation.findNavController(this, R.id.nav_host_fragment).getGraph();

            Navigation.findNavController(this, R.id.nav_host_fragment).setGraph(graph, extras != null ? extras : new Bundle());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        dynamicTheme.onResume(this);
    }
}