package dev.dsadebug.tracer;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class JobTest {
    @Test
    void parsesListArgsAndDefaults() {
        Job j = Job.fromJson("{\"code\":\"c\",\"args\":[{\"name\":\"nums\",\"raw\":\"[1]\"}]}");
        assertEquals(new Job.Arg("nums", "[1]"), j.args().get(0));
        assertTrue(j.record());
        assertNull(j.method());
        assertEquals(Job.Limits.DEFAULTS, j.limits());
        assertEquals(5000, j.limits().maxSteps());
    }

    @Test
    void rejectsObjectArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> Job.fromJson("{\"code\":\"c\",\"args\":{\"nums\":\"[1]\"}}"));
        assertThrows(IllegalArgumentException.class,
                () -> Job.fromJson("{\"code\":\"c\",\"args\":[{\"name\":\"n\"}]}"));
    }
}
