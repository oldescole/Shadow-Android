package su.sres.securesms.service.webrtc;

import androidx.annotation.NonNull;

import org.signal.ringrtc.CallException;
import org.signal.ringrtc.CallManager;

import su.sres.securesms.components.webrtc.BroadcastVideoSink;
import su.sres.securesms.events.CallParticipant;
import su.sres.securesms.events.CallParticipantId;
import su.sres.securesms.events.WebRtcViewModel;
import su.sres.core.util.logging.Log;
import su.sres.securesms.ringrtc.RemotePeer;
import su.sres.securesms.service.webrtc.state.WebRtcServiceState;
import su.sres.signalservice.api.messages.calls.OfferMessage;

import static su.sres.securesms.webrtc.CallNotificationBuilder.TYPE_INCOMING_CONNECTING;

/**
 * Encapsulates the logic to begin a 1:1 call from scratch. Other action processors
 * delegate the appropriate action to it but it is not intended to be the main processor for the system.
 */
public class BeginCallActionProcessorDelegate extends WebRtcActionProcessor {

  public BeginCallActionProcessorDelegate(@NonNull WebRtcInteractor webRtcInteractor, @NonNull String tag) {
    super(webRtcInteractor, tag);
  }

  @Override
  protected @NonNull WebRtcServiceState handleOutgoingCall(@NonNull WebRtcServiceState currentState,
                                                           @NonNull RemotePeer remotePeer,
                                                           @NonNull OfferMessage.Type offerType)
  {
    remotePeer.setCallStartTimestamp(System.currentTimeMillis());
    currentState = currentState.builder()
                               .actionProcessor(new OutgoingCallActionProcessor(webRtcInteractor))
                               .changeCallInfoState()
                               .callRecipient(remotePeer.getRecipient())
                               .callState(WebRtcViewModel.State.CALL_OUTGOING)
                               .putRemotePeer(remotePeer)
                               .putParticipant(remotePeer.getRecipient(),
                                               CallParticipant.createRemote(new CallParticipantId(remotePeer.getRecipient()),
                                                                            remotePeer.getRecipient(),
                                                                            null,
                                                                            new BroadcastVideoSink(currentState.getVideoState().getLockableEglBase(),
                                                                                                   false,
                                                                                                   true,
                                                                                                   currentState.getLocalDeviceState().getOrientation().getDegrees()),
                                                                            true,
                                                                            false,
                                                                            0,
                                                                            true,
                                                                            0,
                                                                            false,
                                                                            CallParticipant.DeviceOrdinal.PRIMARY
                                               ))
                               .build();

    CallManager.CallMediaType callMediaType = WebRtcUtil.getCallMediaTypeFromOfferType(offerType);

    try {
      webRtcInteractor.getCallManager().call(remotePeer, callMediaType, 1);
    } catch (CallException e) {
      return callFailure(currentState, "Unable to create outgoing call: ", e);
    }

    return currentState;
  }

  @Override
  protected @NonNull WebRtcServiceState handleStartIncomingCall(@NonNull WebRtcServiceState currentState, @NonNull RemotePeer remotePeer) {
    remotePeer.answering();

    Log.i(tag, "assign activePeer callId: " + remotePeer.getCallId() + " key: " + remotePeer.hashCode());

    webRtcInteractor.setCallInProgressNotification(TYPE_INCOMING_CONNECTING, remotePeer);
    webRtcInteractor.retrieveTurnServers(remotePeer);

    return currentState.builder()
                       .actionProcessor(new IncomingCallActionProcessor(webRtcInteractor))
                       .changeCallInfoState()
                       .callRecipient(remotePeer.getRecipient())
                       .activePeer(remotePeer)
                       .callState(WebRtcViewModel.State.CALL_INCOMING)
                       .putParticipant(remotePeer.getRecipient(),
                                       CallParticipant.createRemote(new CallParticipantId(remotePeer.getRecipient()),
                                                                    remotePeer.getRecipient(),
                                                                    null,
                                                                    new BroadcastVideoSink(currentState.getVideoState().getLockableEglBase(),
                                                                                           false,
                                                                                           true,
                                                                                           currentState.getLocalDeviceState().getOrientation().getDegrees()),
                                                                    true,
                                                                    false,
                                                                    0,
                                                                    true,
                                                                    0,
                                                                    false,
                                                                    CallParticipant.DeviceOrdinal.PRIMARY
                                       ))
                       .build();
  }
}
