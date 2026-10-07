package com.mindflow.harness.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillContextBufferTest {

    private final SkillContextBuffer buffer = new SkillContextBuffer();

    @Test
    void drainRendersAndConsumesOnce() {
        buffer.offer("u1", "web-access", "决策手册正文");
        assertFalse(buffer.isEmpty("u1"));

        String drained = buffer.drain("u1");
        assertTrue(drained.contains("## 已加载 Skill：web-access"));
        assertTrue(drained.contains("决策手册正文"));

        assertTrue(buffer.isEmpty("u1"));
        assertEquals("", buffer.drain("u1"));
    }

    @Test
    void keepsAtMostThreeBodies() {
        buffer.offer("u1", "s1", "body1");
        buffer.offer("u1", "s2", "body2");
        buffer.offer("u1", "s3", "body3");
        buffer.offer("u1", "s4", "body4");

        String drained = buffer.drain("u1");
        assertFalse(drained.contains("body1"), "oldest body should be evicted");
        assertTrue(drained.contains("body2"));
        assertTrue(drained.contains("body4"));
    }

    @Test
    void isolatesUsersAndSupportsClear() {
        buffer.offer("u1", "s1", "body1");
        buffer.offer("u2", "s2", "body2");

        assertTrue(buffer.drain("u1").contains("body1"));
        assertTrue(buffer.drain("u2").contains("body2"));

        buffer.offer("u1", "s1", "body1");
        buffer.clear("u1");
        assertTrue(buffer.isEmpty("u1"));
    }
}
