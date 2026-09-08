package com.example.server.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaFile 实体测试
 * <p>
 * 覆盖加固计划的字段改造：
 * - 问题 1：version 字段（乐观锁）
 * - 问题 5：compensationAttempts 字段
 * </p>
 */
class MediaFileTest {

    @Test
    void testVersionField_InitialValue() {
        MediaFile file = new MediaFile();
        file.setVersion(0);

        assertEquals(0, file.getVersion());
    }

    @Test
    void testVersionField_Increment() {
        MediaFile file = new MediaFile();
        file.setVersion(1);

        assertEquals(1, file.getVersion());

        file.setVersion(file.getVersion() + 1);
        assertEquals(2, file.getVersion());
    }

    @Test
    void testCompensationAttemptsField_InitialValue() {
        MediaFile file = new MediaFile();
        file.setCompensationAttempts(0);

        assertEquals(0, file.getCompensationAttempts());
    }

    @Test
    void testCompensationAttemptsField_Increment() {
        MediaFile file = new MediaFile();
        file.setCompensationAttempts(0);

        file.setCompensationAttempts(file.getCompensationAttempts() + 1);
        assertEquals(1, file.getCompensationAttempts());

        file.setCompensationAttempts(file.getCompensationAttempts() + 1);
        assertEquals(2, file.getCompensationAttempts());
    }

    @Test
    void testAiAttemptsAndCompensationAttempts_IndependentFields() {
        MediaFile file = new MediaFile();
        file.setAiAttempts(5);
        file.setCompensationAttempts(2);

        assertEquals(5, file.getAiAttempts());
        assertEquals(2, file.getCompensationAttempts());

        // 验证两个字段独立，修改一个不影响另一个
        file.setAiAttempts(0);
        assertEquals(0, file.getAiAttempts());
        assertEquals(2, file.getCompensationAttempts());
    }

    @Test
    void testMediaFile_AllNewFields() {
        MediaFile file = new MediaFile();
        file.setId(1L);
        file.setUserId(100L);
        file.setFilename("test.mp4");
        file.setAiStatus("PROCESSING");
        file.setAiProcessAt(LocalDateTime.now());
        file.setAiAttempts(3);
        file.setCompensationAttempts(2);
        file.setVersion(1);

        assertNotNull(file.getId());
        assertNotNull(file.getUserId());
        assertNotNull(file.getFilename());
        assertNotNull(file.getAiStatus());
        assertNotNull(file.getAiProcessAt());
        assertEquals(3, file.getAiAttempts());
        assertEquals(2, file.getCompensationAttempts());
        assertEquals(1, file.getVersion());
    }

    @Test
    void testMediaFile_NullCompensationAttempts() {
        MediaFile file = new MediaFile();
        file.setCompensationAttempts(null);

        assertNull(file.getCompensationAttempts());

        // 模拟补偿调度器对 null 值的处理
        int attempts = (file.getCompensationAttempts() == null ? 0 : file.getCompensationAttempts()) + 1;
        assertEquals(1, attempts);
    }

    @Test
    void testMediaFile_NullVersion() {
        MediaFile file = new MediaFile();
        file.setVersion(null);

        assertNull(file.getVersion());
    }
}
