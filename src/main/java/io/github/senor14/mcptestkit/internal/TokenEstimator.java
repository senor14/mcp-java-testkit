package io.github.senor14.mcptestkit.internal;

/**
 * Rough token estimation for budget gates.
 *
 * <p>Uses the common ~4 characters/token heuristic. This is intentionally a model-agnostic
 * estimate: budget gates exist to catch order-of-magnitude bloat (a tool list ballooning from
 * 1K to 20K tokens), not to bill by exact token counts.</p>
 */
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 4.0;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
