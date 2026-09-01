package com.IDDagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 5（Structured Resume）：结构化恢复协议消息解析单元测试。
 * 覆盖文档第 14 节协议形态（resume_frame/abandon_frame + frameId）与
 * 宽松解析策略（非协议消息返回 null，不误伤普通对话）。
 */
class StructuredResumeActionTest {

    @Test
    void parseResumeFrameAction() {
        StructuredResumeAction action =
                StructuredResumeAction.parse("{\"action\":\"resume_frame\",\"frameId\":\"F001\"}");
        assertEquals(StructuredResumeAction.RESUME_FRAME, action.action());
        assertEquals("F001", action.frameId());
        assertFalse(action.isAbandon());
    }

    @Test
    void parseAbandonFrameActionWithWhitespace() {
        // 带空白分隔的 JSON（前端 JSON.stringify 输出无空白，但兼容手写/格式化）
        StructuredResumeAction action =
                StructuredResumeAction.parse("{\"action\": \"abandon_frame\", \"frameId\": \"F001\"}");
        assertEquals(StructuredResumeAction.ABANDON_FRAME, action.action());
        assertEquals("F001", action.frameId());
        assertTrue(action.isAbandon());
    }

    @Test
    void parseTrimsOuterWhitespace() {
        StructuredResumeAction action =
                StructuredResumeAction.parse("  {\"action\":\"resume_frame\",\"frameId\":\"F_A\"}  ");
        assertEquals("F_A", action.frameId());
    }

    @Test
    void parseLegacyTextProtocolReturnsNull() {
        // 旧文本协议不是结构化协议：继续走旧兼容路径（handleInterruptAnswer）
        assertNull(StructuredResumeAction.parse("【管道恢复】继续"));
        assertNull(StructuredResumeAction.parse("【管道恢复】放弃"));
        assertNull(StructuredResumeAction.parse("继续"));
    }

    @Test
    void parseMissingFrameIdReturnsNull() {
        // 缺 frameId：无法定位恢复目标，判为非协议（前端对历史旧卡无 frameId 时
        // 回退发送旧文本协议，不会出现此形态）
        assertNull(StructuredResumeAction.parse("{\"action\":\"resume_frame\"}"));
    }

    @Test
    void parseUnknownActionReturnsNull() {
        assertNull(StructuredResumeAction.parse("{\"action\":\"select_candidate\",\"frameId\":\"F001\"}"));
        assertNull(StructuredResumeAction.parse("{\"action\":\"\",\"frameId\":\"F001\"}"));
    }

    @Test
    void parseMalformedJsonReturnsNull() {
        assertNull(StructuredResumeAction.parse("{not a json"));
        assertNull(StructuredResumeAction.parse("{\"action\":\"resume_frame\",\"frameId\""));
    }

    @Test
    void parseNullAndBlankReturnsNull() {
        assertNull(StructuredResumeAction.parse(null));
        assertNull(StructuredResumeAction.parse(""));
        assertNull(StructuredResumeAction.parse("   "));
    }
}
