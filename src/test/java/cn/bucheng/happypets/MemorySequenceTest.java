package cn.bucheng.happypets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MemorySequenceTest {
    @Test
    void acceptsEveryTileOnceAndCompletesOnlyAtTheEnd() {
        MemorySequence sequence = new MemorySequence(new int[]{4, 1, 4});

        assertTrue(sequence.press(4));
        assertFalse(sequence.complete());
        assertTrue(sequence.press(1));
        assertTrue(sequence.press(4));
        assertTrue(sequence.complete());
        assertFalse(sequence.press(4));
    }

    @Test
    void wrongTileDoesNotAdvanceTheExpectedPosition() {
        MemorySequence sequence = new MemorySequence(new int[]{2, 7});

        assertFalse(sequence.press(7));
        assertFalse(sequence.complete());
        assertTrue(sequence.press(2));
        assertTrue(sequence.press(7));
        assertTrue(sequence.complete());
    }

    @Test
    void rejectsEmptyOrOutOfRangeSequences() {
        assertThrows(IllegalArgumentException.class, () -> new MemorySequence(new int[]{}));
        assertThrows(IllegalArgumentException.class, () -> new MemorySequence(new int[]{9}));
    }
}
