package dev.minecp.colony;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The mod's happiness formula: 10 × the weighted mean of the factors that aren't neutral, at most 10. */
class HappinessTest {
    private static Happiness.Factor f(double weight, double value) {
        return new Happiness.Factor("x", weight, value);
    }

    @Test
    void neutralFactorsAreLeftOut() {
        assertEquals(10, Happiness.of(List.of(f(3, 1), f(2, 1))));      // nothing to complain about
        assertEquals(5, Happiness.of(List.of(f(3, 1), f(2, 0.5))));     // only the 0.5 counts
    }

    @Test
    void weightedMean() {
        // homeless (0, weight 3) and well guarded (2, weight 4): 10 × 8/7, capped at 10
        assertEquals(10, Happiness.of(List.of(f(3, 0), f(4, 2))));
        // homeless (0, weight 3) and jobless (0.5, weight 2): 10 × 1/5
        assertEquals(2, Happiness.of(List.of(f(3, 0), f(2, 0.5))), 1e-9);
    }
}
