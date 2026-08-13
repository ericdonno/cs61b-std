package byog.AI;

import java.util.Objects;

/** Result of skill-specific validation, including normalized arguments. */
public record SkillValidation(
        DecisionValidator.ValidationResult result,
        SkillInvocation invocation) {
    public SkillValidation {
        Objects.requireNonNull(result, "result");
    }

    public static SkillValidation accepted(SkillInvocation invocation) {
        return new SkillValidation(
                DecisionValidator.ValidationResult.ACCEPTED,
                Objects.requireNonNull(invocation, "invocation"));
    }

    public static SkillValidation rejected(
            DecisionValidator.ValidationResult result) {
        if (result == DecisionValidator.ValidationResult.ACCEPTED) {
            throw new IllegalArgumentException("accepted result needs an invocation");
        }
        return new SkillValidation(result, null);
    }

    public boolean accepted() {
        return result == DecisionValidator.ValidationResult.ACCEPTED;
    }
}
