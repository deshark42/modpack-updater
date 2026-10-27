package io.github.deshark42.modpackupdater.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SizesTest {

    @ParameterizedTest(name = "{0} bytes reads as {1}")
    @CsvSource({
            "0, 0 B",
            "1023, 1023 B",
            "1024, 1.0 KiB",
            "1536, 1.5 KiB",
            "1048576, 1.0 MiB",
            "1073741824, 1.0 GiB",
            "1099511627776, 1.0 TiB",
            "1125899906842624, 1024.0 TiB"
    })
    void formatsByteCountsAtEveryUnitBoundary(long bytes, String expected) {
        assertEquals(expected, Sizes.human(bytes));
    }

    @ParameterizedTest(name = "{0} bytes in {1} ns reads as {2}")
    @CsvSource({
            "4, 1000000000, 4 B/s",
            "1536, 1000000000, 1.5 KiB/s",
            "31562137, 1000000000, 30.1 MiB/s",
            "7, 0, 7 B/s"
    })
    void reportsTheRateOfAByteCountOverTheTimeItTook(long bytes, long nanos, String expected) {
        assertEquals(expected, Sizes.rate(bytes, nanos));
    }
}
