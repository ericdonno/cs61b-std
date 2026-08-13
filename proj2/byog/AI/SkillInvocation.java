package byog.AI;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Normalized, transport-independent request passed to the skill registry. */
public record SkillInvocation(
        String skillId,
        Map<String, Object> parameters,
        double confidence,
        int validForTicks,
        InterruptPolicy interruptPolicy,
        PlanMetadata planMetadata) {

    public SkillInvocation {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(interruptPolicy, "interruptPolicy");
        Objects.requireNonNull(planMetadata, "planMetadata");
        parameters = immutableObject(parameters);
    }

    private static Map<String, Object> immutableObject(Map<String, Object> source) {
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "parameter key"),
                    immutableValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Integer || value instanceof Long
                || value instanceof Double) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("parameter key must be a string");
                }
                copy.put(key, immutableValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return Collections.unmodifiableList(list.stream()
                    .map(SkillInvocation::immutableValue).toList());
        }
        throw new IllegalArgumentException("unsupported parameter value");
    }
}
