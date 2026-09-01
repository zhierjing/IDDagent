package com.IDDagent.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 5（Structured Resume）：结构化恢复协议消息。
 *
 * <p>前端恢复卡（InterruptAskCard）按钮点击后静默发送 JSON 文本：
 * <pre>
 * {"action":"resume_frame","frameId":"F001"}
 * {"action":"abandon_frame","frameId":"F001"}
 * </pre>
 * 相比旧文本协议（【管道恢复】继续/放弃），结构化协议携带 frameId，后端可校验
 * 恢复目标是否为挂起栈栈顶（文档第 31 节：不匹配返回 STALE_ACTION，禁止跳过
 * 栈顶直接恢复深层 Frame；Case 13）。
 *
 * <p>旧文本协议继续兼容（文档第 29 节：第一阶段继续兼容，不要一次删除）。
 *
 * @param action  协议动作（resume_frame / abandon_frame）
 * @param frameId 目标执行帧标识（栈顶帧，非空）
 */
public record StructuredResumeAction(String action, String frameId) {

    /** 恢复栈顶帧动作 */
    public static final String RESUME_FRAME = "resume_frame";

    /** 放弃栈顶帧动作 */
    public static final String ABANDON_FRAME = "abandon_frame";

    private static final Pattern ACTION_PATTERN = Pattern.compile("\"action\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FRAME_ID_PATTERN = Pattern.compile("\"frameId\"\\s*:\\s*\"([^\"]+)\"");

    /**
     * 从用户消息解析结构化恢复协议；非协议消息返回 null（视为普通消息，
     * 继续走旧文本协议 / 穿插判定）。
     *
     * <p>宽松解析（不校验完整 JSON 合法性）：仅要求文本以 "{" 开头且
     * action/frameId 字段齐全。协议消息由前端按钮生成，字段缺失或动作
     * 未知时直接判为非协议消息，避免误伤正常对话。
     */
    public static StructuredResumeAction parse(String message) {
        if (message == null) return null;
        String text = message.trim();
        if (!text.startsWith("{")) return null;
        String action = extract(ACTION_PATTERN, text);
        String frameId = extract(FRAME_ID_PATTERN, text);
        if ((RESUME_FRAME.equals(action) || ABANDON_FRAME.equals(action))
                && frameId != null && !frameId.isEmpty()) {
            return new StructuredResumeAction(action, frameId);
        }
        return null;
    }

    /** 是否放弃动作（resume_frame 之外的协议动作即放弃） */
    public boolean isAbandon() {
        return ABANDON_FRAME.equals(action);
    }

    private static String extract(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
