package su.sres.securesms.service.webrtc;

import androidx.annotation.NonNull;

import java.util.Set;

import su.sres.core.util.logging.Log;
import su.sres.securesms.components.webrtc.BroadcastVideoSink;
import su.sres.securesms.ringrtc.CameraState;
import su.sres.securesms.service.webrtc.state.WebRtcServiceState;
import su.sres.securesms.webrtc.audio.SignalAudioManager;

/**
 * Encapsulates the shared logic to deal with local device actions. Other action processors inherit
 * the behavior by extending it instead of delegating. It is not intended to be the main processor
 * for the system.
 */
public abstract class DeviceAwareActionProcessor extends WebRtcActionProcessor {

  public DeviceAwareActionProcessor(@NonNull WebRtcInteractor webRtcInteractor, @NonNull String tag) {
    super(webRtcInteractor, tag);
  }

  @Override
  protected @NonNull WebRtcServiceState handleAudioDeviceChanged(@NonNull WebRtcServiceState currentState, @NonNull SignalAudioManager.AudioDevice activeDevice, @NonNull Set<SignalAudioManager.AudioDevice> availableDevices) {
    Log.i(tag, "handleAudioDeviceChanged(): active: " + activeDevice + " available: " + availableDevices);

    if (!currentState.getLocalDeviceState().getCameraState().isEnabled()) {
      webRtcInteractor.updatePhoneState(WebRtcUtil.getInCallPhoneState(context));
    }

    return currentState.builder()
                       .changeLocalDeviceState()
                       .setActiveDevice(activeDevice)
                       .setAvailableDevices(availableDevices)
                       .build();
  }

  @Override
  protected @NonNull WebRtcServiceState handleSetUserAudioDevice(@NonNull WebRtcServiceState currentState, @NonNull SignalAudioManager.AudioDevice userDevice) {
    Log.i(tag, "handleSetUserAudioDevice(): userDevice: " + userDevice);

    webRtcInteractor.setUserAudioDevice(userDevice);

    return currentState;
  }

  @Override
  protected @NonNull WebRtcServiceState handleSetCameraFlip(@NonNull WebRtcServiceState currentState) {
    Log.i(tag, "handleSetCameraFlip():");

    if (currentState.getLocalDeviceState().getCameraState().isEnabled() && currentState.getVideoState().getCamera() != null) {
      currentState.getVideoState().getCamera().flip();
      return currentState.builder()
                         .changeLocalDeviceState()
                         .cameraState(currentState.getVideoState().getCamera().getCameraState())
                         .build();
    }
    return currentState;
  }

  @Override
  public @NonNull WebRtcServiceState handleCameraSwitchCompleted(@NonNull WebRtcServiceState currentState, @NonNull CameraState newCameraState) {
    Log.i(tag, "handleCameraSwitchCompleted():");

    BroadcastVideoSink localSink = currentState.getVideoState().getLocalSink();
    if (localSink != null) {
      localSink.setRotateToRightSide(newCameraState.getActiveDirection() == CameraState.Direction.BACK);
    }

    return currentState.builder()
                       .changeLocalDeviceState()
                       .cameraState(newCameraState)
                       .build();
  }
}
