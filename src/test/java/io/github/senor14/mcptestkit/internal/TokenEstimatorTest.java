package io.github.senor14.mcptestkit.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    @Test
    void emptyAndNullAreZero() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void estimatesRoughlyFourCharsPerToken() {
        assertEquals(1, TokenEstimator.estimate("abcd"));
        assertEquals(2, TokenEstimator.estimate("abcde"));
        assertEquals(25, TokenEstimator.estimate("x".repeat(100)));
    }

    @Test
    void neverNegative() {
        assertTrue(TokenEstimator.estimate("a") > 0);
    }
}
