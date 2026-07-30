package byog.Bridge;

/**
 * Receives validated session-level messages on the game thread.
 */
public interface AgentHandler {

    enum IntentHandlingResult {
        ACCEPTED,
        REJECTED
    }

    /** Handles an intent and reports whether the game accepted it. */
    IntentHandlingResult onIntentSubmitted(
            AgentProtocol.SubmitIntentData data,
            AgentProtocol.Envelope envelope,
            AgentSession.RequestContext requestContext);

    /** Handles an acknowledgement for a request that was being cancelled. */
    void onCancelAcknowledged(
            AgentProtocol.CancelAckData data,
            AgentProtocol.Envelope envelope,
            AgentSession.RequestContext cancelledRequest);

    /** Handles a typed protocol rejection without mutating the world. */
    void onProtocolRejected(
            AgentProtocolCodec.ProtocolFailure failure);
}
