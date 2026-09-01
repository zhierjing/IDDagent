package com.IDDagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Phase 6（交互 ID 化）：结构化交互协议消息解析单元测试。
 * 覆盖文档第 14 节 select_candidate 形态协议（select_candidate/select_intent/select_template
 * + interactionId + frameId）与宽松解析策略（非协议消息返回 null，不误伤普通对话）。
 */
class StructuredInteractionActionTest {

    @Test
    void parseSelectCandidateWithInput() {
        StructuredInteractionAction action = StructuredInteractionAction.parse(
                "{\"action\":\"select_candidate\",\"frameId\":\"F001\",\"interactionId\":\"I001\","
                        + "\"input\":\"公司：小米科技\\n统一信用代码：91310000XXXX\"}");
        assertEquals(StructuredInteractionAction.SELECT_CANDIDATE, action.action());
        assertEquals("F001", action.frameId());
        assertEquals("I001", action.interactionId());
        assertEquals("公司：小米科技\n统一信用代码：91310000XXXX", action.input());
        assertNull(action.skill());
    }

    @Test
    void parseSelectIntentWithSkill() {
        StructuredInteractionAction action = StructuredInteractionAction.parse(
                "{\"action\":\"select_intent\",\"frameId\":\"F001\",\"interactionId\":\"I002\",\"skill\":\"generate_report\"}");
        assertEquals(StructuredInteractionAction.SELECT_INTENT, action.action());
        assertEquals("generate_report", action.skill());
        assertNull(action.input());
    }

    @Test
    void parseSelectTemplateWithWhitespace() {
        // 带空白分隔的 JSON（兼容手写/格式化，前端 JSON.stringify 输出无空白）
        StructuredInteractionAction action = StructuredInteractionAction.parse(
                "{ \"action\": \"select_template\", \"frameId\": \"F001\", \"interactionId\": \"I003\", "
                        + "\"input\": \"【模板选择】tpl_dd\" }");
        assertEquals(StructuredInteractionAction.SELECT_TEMPLATE, action.action());
        assertEquals("tpl_dd", action.input().substring("【模板选择】".length()));
    }

    @Test
    void parseTrimsOuterWhitespace() {
        StructuredInteractionAction action = StructuredInteractionAction.parse(
                "  {\"action\":\"select_candidate\",\"frameId\":\"F_A\",\"interactionId\":\"I_A\","
                        + "\"input\":\"公司：云禾科技\\n统一信用代码：913\"}  ");
        assertEquals("F_A", action.frameId());
        assertEquals("I_A", action.interactionId());
    }

    @Test
    void parseLegacyTextProtocolReturnsNull() {
        // 旧文本协议/普通消息不是结构化交互协议：继续走原有判定路径
        assertNull(StructuredInteractionAction.parse("公司：小米科技\n统一信用代码：91310000XXXX"));
        assertNull(StructuredInteractionAction.parse("【意图选择】generate_report"));
        assertNull(StructuredInteractionAction.parse("【模板选择】tpl_dd"));
        assertNull(StructuredInteractionAction.parse("帮我查一下小米科技的基本信息"));
    }

    @Test
    void parseMissingFrameIdOrInteractionIdReturnsNull() {
        // 缺 frameId 或 interactionId：无法定位卡片归属 → 判为非协议
        // （前端对历史旧卡无 frameId 时回退发送旧文本协议，不会出现此形态）
        assertNull(StructuredInteractionAction.parse("{\"action\":\"select_candidate\",\"interactionId\":\"I001\"}"));
        assertNull(StructuredInteractionAction.parse("{\"action\":\"select_candidate\",\"frameId\":\"F001\"}"));
        assertNull(StructuredInteractionAction.parse("{\"action\":\"select_candidate\"}"));
    }

    @Test
    void parseUnknownActionReturnsNull() {
        assertNull(StructuredInteractionAction.parse(
                "{\"action\":\"resume_frame\",\"frameId\":\"F001\",\"interactionId\":\"I001\"}"));
        assertNull(StructuredInteractionAction.parse(
                "{\"action\":\"select_unknown\",\"frameId\":\"F001\",\"interactionId\":\"I001\"}"));
        assertNull(StructuredInteractionAction.parse(
                "{\"action\":\"\",\"frameId\":\"F001\",\"interactionId\":\"I001\"}"));
    }

    @Test
    void parseMalformedJsonAndBlankReturnsNull() {
        assertNull(StructuredInteractionAction.parse("{not a json"));
        assertNull(StructuredInteractionAction.parse("{\"action\":\"select_candidate\",\"frameId\""));
        assertNull(StructuredInteractionAction.parse(null));
        assertNull(StructuredInteractionAction.parse(""));
        assertNull(StructuredInteractionAction.parse("   "));
    }
}
