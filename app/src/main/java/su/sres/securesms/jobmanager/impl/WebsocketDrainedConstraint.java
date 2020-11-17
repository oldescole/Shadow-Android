package su.sres.securesms.jobmanager.impl;

import android.app.job.JobInfo;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.jobmanager.Constraint;

/**
 * A constraint that is met once we have pulled down all messages from the websocket during initial
 * load. See {@link su.sres.securesms.messages.IncomingMessageObserver}.
 */
public final class WebsocketDrainedConstraint implements Constraint {

    public static final String KEY = "WebsocketDrainedConstraint";

    private WebsocketDrainedConstraint() {
    }

    @Override
    public boolean isMet() {
        return ApplicationDependencies.getIncomingMessageObserver().isWebsocketDrained();
    }

    @Override
    public @NonNull String getFactoryKey() {
        return KEY;
    }

    @RequiresApi(26)
    @Override
    public void applyToJobInfo(@NonNull JobInfo.Builder jobInfoBuilder) {
    }

    public static final class Factory implements Constraint.Factory<WebsocketDrainedConstraint> {

        @Override
        public WebsocketDrainedConstraint create() {
            return new WebsocketDrainedConstraint();
        }
    }
}