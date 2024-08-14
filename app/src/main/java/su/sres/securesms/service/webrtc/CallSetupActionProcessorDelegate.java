package su.sres.securesms.service.webrtc;

import androidx.annotation.NonNull;

import org.signal.ringrtc.CallException;
import org.signal.ringrtc.CallManager;

import su.sres.securesms.dependencies.ApplicationDependencies;
import su.sres.securesms.events.WebRtcViewModel;
import su.sres.core.util.logging.Log;
import su.sres.securesms.ringrtc.CallState;
import su.sres.securesms.ringrtc.Camera;
import su.sres.securesms.ringrtc.RemotePeer;
import su.sres.securesms.service.webrtc.state.WebRtcServiceState;
import su.sres.securesms.util.NetworkUtil;
import su.sres.securesms.webrtc.audio.SignalAudioManager;
import su.sres.securesms.webrtc.locks.LockManager;

import static su.sres.securesms.webrtc.CallNotificationBuilder.TYPE_ESTABLISHED;

/**
 * Encapsulates the shared logic to setup a 1:1 call. Setup primarily includes retrieving turn servers and
 * transitioning to the connected state. Other action processors delegate the appropriate action to it but it is
 * not intended to be the main processor for the system.
 */
public class CallSetupActionProcessorDelegate extends WebRtcActionProcessor {

    public CallSetupActionProcessorDelegate(@NonNull WebRtcInteractor webRtcInteractor, @NonNull String tag) {
        super(webRtcInteractor, tag);
    }

    @Override
    public @NonNull WebRtcServiceState handleCallConnected(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
        if (!remotePeer.callIdEquals(currentState.getCallInfoState().getActivePeer())) {
            Log.w(tag, "handleCallConnected(): Ignoring for inactive call.");
            return currentState;
        }

        Log.i(tag, "handleCallConnected(): call_id: " + remotePeer.getCallId());

        RemotePeer activePeer = currentState.getCallInfoState().requireActivePeer();

        ApplicationDependencies.getAppForegroundObserver().removeListener(webRtcInteractor.getForegroundListener());
        webRtcInteractor.startAudioCommunication();

        activePeer.connected();

        if (currentState.getLocalDeviceState().getCameraState().isEnabled()) {
            webRtcInteractor.updatePhoneState(LockManager.PhoneState.IN_VIDEO);
        } else {
            webRtcInteractor.updatePhoneState(WebRtcUtil.getInCallPhoneState(context));
        }

        currentState = currentState.builder()
                .actionProcessor(new ConnectedCallActionProcessor(webRtcInteractor))
                .changeCallInfoState()
                .callState(WebRtcViewModel.State.CALL_CONNECTED)
                .callConnectedTime(System.currentTimeMillis())
                .commit()
                .changeLocalDeviceState()
                .build();

        webRtcInteractor.setCallInProgressNotification(TYPE_ESTABLISHED, activePeer);
        webRtcInteractor.unregisterPowerButtonReceiver();

        try {
            CallManager callManager = webRtcInteractor.getCallManager();
            callManager.setCommunicationMode();
            callManager.setAudioEnable(currentState.getLocalDeviceState().isMicrophoneEnabled());
            callManager.setVideoEnable(currentState.getLocalDeviceState().getCameraState().isEnabled());
            callManager.updateBandwidthMode(NetworkUtil.getCallingBandwidthMode(context));
        } catch (CallException e) {
            return callFailure(currentState, "Enabling audio/video failed: ", e);
        }

        if (currentState.getCallSetupState().isAcceptWithVideo()) {
            currentState = currentState.getActionProcessor().handleSetEnableVideo(currentState, true);
        }

        if (currentState.getCallSetupState().isAcceptWithVideo() || currentState.getLocalDeviceState().getCameraState().isEnabled()) {
            webRtcInteractor.setDefaultAudioDevice(SignalAudioManager.AudioDevice.SPEAKER_PHONE, false);
        } else {
            webRtcInteractor.setDefaultAudioDevice(SignalAudioManager.AudioDevice.EARPIECE, false);
        }

        return currentState;
    }

    @Override
    protected @NonNull WebRtcServiceState handleSetEnableVideo(@NonNull WebRtcServiceState currentState, boolean enable) {
        Log.i(tag, "handleSetEnableVideo(): enable: " + enable);
        Camera camera = currentState.getVideoState().requireCamera();

        if (camera.isInitialized()) {
            camera.setEnabled(enable);
        }

        currentState = currentState.builder()
                .changeCallSetupState()
                .enableVideoOnCreate(enable)
                .commit()
                .changeLocalDeviceState()
                .cameraState(camera.getCameraState())
                .build();

        WebRtcUtil.enableSpeakerPhoneIfNeeded(webRtcInteractor, currentState);

        return currentState;
    }
}
