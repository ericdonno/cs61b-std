package byog.AI;

import byog.Bridge.AgentProtocol;
import byog.Perception.ObservationEnvelope;
import byog.TileEngine.TETile;

import java.util.Objects;

/** Validates remote decisions without changing game state. */
public final class DecisionValidator {

    public enum ValidationResult {
        ACCEPTED,
        SCHEMA_MISMATCH,
        IDENTITY_MISMATCH,
        SESSION_EPOCH_MISMATCH,
        REQUEST_GENERATION_MISMATCH,
        DECISION_ID_MISMATCH,
        OBSERVATION_SEQ_MISMATCH,
        UNKNOWN_SKILL,
        INVALID_PARAMETERS,
        PARAMETER_LIMIT_EXCEEDED,
        MISSING_REQUIRED_PARAMETER,
        UNKNOWN_PARAMETER,
        PARAMETER_TYPE_MISMATCH,
        PARAMETER_OUT_OF_RANGE,
        TARGET_OUT_OF_BOUNDS,
        TARGET_NOT_KNOWN,
        TARGET_UNREACHABLE,
        INVALID_TTL,
        INVALID_INTERRUPT_POLICY,
        STALE_PRECONDITION,
        SKILL_PRECONDITION_FAILED
    }

    /** Identity and source observation captured when the request was issued. */
    public static final class RequestExpectation {
        public final AgentProtocol.Identity identity;
        public final String expectedDecisionId;
        public final long expectedObservationSeq;
        public final long expectedRequestGeneration;
        public final ObservationEnvelope sourceObservation;

        public RequestExpectation(AgentProtocol.Identity identity,
                                  String expectedDecisionId,
                                  long expectedObservationSeq,
                                  long expectedRequestGeneration,
                                  ObservationEnvelope sourceObservation) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.expectedDecisionId = Objects.requireNonNull(
                    expectedDecisionId, "expectedDecisionId");
            if (expectedObservationSeq < 0) {
                throw new IllegalArgumentException(
                        "expectedObservationSeq must be >= 0");
            }
            if (expectedRequestGeneration < 0) {
                throw new IllegalArgumentException(
                        "expectedRequestGeneration must be >= 0");
            }
            this.expectedObservationSeq = expectedObservationSeq;
            this.expectedRequestGeneration = expectedRequestGeneration;
            this.sourceObservation = Objects.requireNonNull(
                    sourceObservation, "sourceObservation");
            if (identity.requestGeneration != expectedRequestGeneration) {
                throw new IllegalArgumentException(
                        "identity/request generation mismatch");
            }
            if (sourceObservation.getObservationSeq() != expectedObservationSeq
                    || !identity.runId.equals(sourceObservation.getRunId())
                    || identity.floorId != sourceObservation.getFloorId()
                    || !identity.agentId.equals(sourceObservation.getAgentId())) {
                throw new IllegalArgumentException(
                        "source observation/request identity mismatch");
            }
        }
    }

    /** Accepted validation atomically carries the translated domain values. */
    public record DecisionValidation(
            ValidationResult result,
            StrategicIntent intent,
            InterruptPolicy interruptPolicy) {
        public boolean accepted() {
            return result == ValidationResult.ACCEPTED;
        }

        static DecisionValidation rejected(ValidationResult result) {
            return new DecisionValidation(result, null, null);
        }
    }

    private final TacticalSkillRegistry registry;

    public DecisionValidator() {
        this(TacticalSkillRegistry.standard());
    }

    public DecisionValidator(TacticalSkillRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public ValidationResult validate(
            AgentProtocol.SubmitIntentData proposal,
            AgentProtocol.Envelope envelope,
            RequestExpectation expectation,
            ReflexObservation currentObservation,
            TETile[][] committedWorld) {
        return validateDetailed(proposal, envelope, expectation,
                currentObservation, committedWorld).result();
    }

    public DecisionValidation validateDetailed(
            AgentProtocol.SubmitIntentData proposal,
            AgentProtocol.Envelope envelope,
            RequestExpectation expectation,
            ReflexObservation currentObservation,
            TETile[][] committedWorld) {
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(expectation, "expectation");
        Objects.requireNonNull(currentObservation, "currentObservation");
        Objects.requireNonNull(committedWorld, "committedWorld");

        ValidationResult common = validateEnvelope(
                proposal, envelope, expectation);
        if (common != ValidationResult.ACCEPTED) {
            return DecisionValidation.rejected(common);
        }

        AgentProtocol.IntentData wire = proposal.intent();
        if (!AgentProtocol.INTENT_VERSION.equals(wire.intentVersion())) {
            return DecisionValidation.rejected(ValidationResult.SCHEMA_MISMATCH);
        }
        if (!Double.isFinite(wire.confidence())
                || wire.confidence() < 0.0 || wire.confidence() > 1.0) {
            return DecisionValidation.rejected(ValidationResult.INVALID_PARAMETERS);
        }
        if (wire.validForTicks() < 1 || wire.validForTicks() > 60) {
            return DecisionValidation.rejected(ValidationResult.INVALID_TTL);
        }
        if (!wire.interruptPolicy().respondToAdjacentThreat()) {
            return DecisionValidation.rejected(
                    ValidationResult.INVALID_INTERRUPT_POLICY);
        }

        InterruptPolicy policy = InterruptPolicy.fromData(wire.interruptPolicy());
        AgentProtocol.PlanMetadataData metadata = wire.planMetadata();
        SkillInvocation invocation = new SkillInvocation(
                wire.skill(), wire.parameters(), wire.confidence(),
                wire.validForTicks(), policy,
                new PlanMetadata(metadata.planId(), metadata.stepId(),
                        metadata.revision()));
        TacticalSkillRegistry.RegistryValidation result =
                registry.validateAndTranslate(invocation,
                        new SkillValidationContext(
                                expectation.sourceObservation,
                                currentObservation, committedWorld));
        return new DecisionValidation(
                result.result(), result.intent(), result.interruptPolicy());
    }

    private static ValidationResult validateEnvelope(
            AgentProtocol.SubmitIntentData proposal,
            AgentProtocol.Envelope envelope,
            RequestExpectation expectation) {
        if (!AgentProtocol.ENVELOPE_VERSION.equals(envelope.schemaVersion)) {
            return ValidationResult.SCHEMA_MISMATCH;
        }
        if (envelope.type != AgentProtocol.MessageType.SUBMIT_INTENT
                || !(envelope.data instanceof AgentProtocol.SubmitIntentData)
                || !proposal.equals(envelope.data)) {
            return ValidationResult.SCHEMA_MISMATCH;
        }
        AgentProtocol.Identity expected = expectation.identity;
        if (!expected.runId.equals(envelope.runId)
                || expected.floorId != envelope.floorId
                || !expected.agentId.equals(envelope.agentId)) {
            return ValidationResult.IDENTITY_MISMATCH;
        }
        if (expected.sessionEpoch != envelope.sessionEpoch) {
            return ValidationResult.SESSION_EPOCH_MISMATCH;
        }
        if (expectation.expectedRequestGeneration
                != proposal.requestGeneration()) {
            return ValidationResult.REQUEST_GENERATION_MISMATCH;
        }
        if (!expectation.expectedDecisionId.equals(proposal.decisionId())) {
            return ValidationResult.DECISION_ID_MISMATCH;
        }
        if (expectation.expectedObservationSeq != proposal.observationSeq()) {
            return ValidationResult.OBSERVATION_SEQ_MISMATCH;
        }
        return ValidationResult.ACCEPTED;
    }
}
