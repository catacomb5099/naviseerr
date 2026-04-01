// file: `src/test/java/com/catacomb5099/naviseerr/util/TransferedFileUtilTest.java`
package com.catacomb5099.naviseerr.util;

import com.catacomb5099.naviseerr.schema.slskd.TransferState;
import com.catacomb5099.naviseerr.schema.slskd.TransferedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class TransferedFileUtilTest {

    @Test
    void unknownStateIsIgnored() {
        TransferedFile file = mock(TransferedFile.class);
        TransferState first = TransferState.values()[0];
        when(file.getState()).thenReturn("NOT_A_STATE," + first.name());
        List<TransferState> result = TransferedFileUtil.getStateList(file);
        assertEquals(List.of(first), result);
    }

    @ParameterizedTest
    @MethodSource("combos")
    void parameterizedStateCombinations(String input, List<TransferState> expected) {
        TransferedFile file = mock(TransferedFile.class);
        when(file.getState()).thenReturn(input);
        List<TransferState> result = TransferedFileUtil.getStateList(file);
        assertEquals(expected, result);
    }

    static Stream<Arguments> combos() {
        TransferState[] vals = TransferState.values();
        TransferState a = vals[0];
        TransferState b = vals[1];

        return Stream.of(
                Arguments.of(a.name(), List.of(a)),
                Arguments.of(" " + a.name().toLowerCase() + " ", List.of(a)),
                Arguments.of(a.name() + "," + b.name(), List.of(a, b)),
                Arguments.of(b.name() + "," + a.name(), List.of(b, a)),
                Arguments.of(a.name() + ",UNKNOWN," + b.name(), List.of(a, b))
        );
    }

    @Test
    void emptyStringReturnsEmptyList() {
        TransferedFile file = mock(TransferedFile.class);
        when(file.getState()).thenReturn("");
        List<TransferState> result = TransferedFileUtil.getStateList(file);
        assertTrue(result.isEmpty());
    }
}
