package com.IDDagent.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 6（交互 ID 化）：结构化交互协议消息解析。
 *
 * <p>前端交互类卡片（企业候选选择/意图澄清/模板选择）点击后发送 JSON 协议
 * （文档第 14 节 select_candidate 形态），携带 interactionId + frameId 定位卡片
 * 所属任务，由后端校验帧归属（文档第 44 节：点击挂起帧的旧卡 → INTERACTION_SUSPENDED）：
 * <pre>
 *   {"action":"select_candidate","frameId":"F001","interactionId":"I001","input":"公司：xxx\n统一信用代码：xxx"}
 *   {"action":"select_intent","frameId":"F001","interactionId":"I002","skill":"generate_report"}
 *   {"action":"select_template","frameId":"F001","interactionId":"I003","input":"【模板选择】tpl_dd"}
 * </pre>
 *
 * <p>宽松解析策略与 StructuredResumeAction 一致：{ 开头 + action/frameId/interactionId
 * 齐全才视为协议，其余消息返回 null（不误伤普通对话）。input/skill 由 action 类型决定
 * 是否必需（select_candidate/select_template 依赖 input 文本转发，select_intent 依赖 skill）。
 */
public record StructuredInteractionAction(String action, String frameId, String interactionId,
                                          String input, String skill) {

    /** 企业候选选择（等价文本形态："公司：xxx\n统一信用代码：xxx" 或查询句） */
    public static final String SELECT_CANDIDATE = "select_candidate";
    /** 意图澄清选择（等价文本形态："【意图选择】<skill>"） */
    public static final String SELECT_INTENT = "select_intent";
    /** 报告模板选择（等价文本形态："【模板选择】<template_id>"） */
    public static final String SELECT_TEMPLATE = "select_template";

    private static final Pattern ACTION_PATTERN = Pattern.compile("\"action\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FRAME_ID_PATTERN = Pattern.compile("\"frameId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern INTERACTION_ID_PATTERN = Pattern.compile("\"interactionId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern INPUT_PATTERN = Pattern.compile("\"input\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern SKILL_PATTERN = Pattern.compile("\"skill\"\\s*:\\s*\"([^\"]+)\"");

    public static StructuredInteractionAction parse(String message) {
        if (message == null) return null;
        String text = message.trim();
        if (!text.startsWith("{")) return null;
        String action = extract(ACTION_PATTERN, text);
        String frameId = extract(FRAME_ID_PATTERN, text);
        String interactionId = extract(INTERACTION_ID_PATTERN, text);
        if (!(SELECT_CANDIDATE.equals(action) || SELECT_INTENT.equals(action) || SELECT_TEMPLATE.equals(action))
                || frameId == null || frameId.isEmpty()
                || interactionId == null || interactionId.isEmpty()) {
            return null;
        }
        return new StructuredInteractionAction(action, frameId, interactionId,
                decodeInput(extract(INPUT_PATTERN, text)), extract(SKILL_PATTERN, text));
    }

    /** 解码 input 中的 JSON 转义（JSON.stringify 将换行输出为 \n，需还原为真实换行
     *  才能与旧文本协议消息形态一致——looksLikeCompanySelection 等按真实换行归一化） */
    private static String decodeInput(String raw) {
        if (raw == null) return null;
        return raw.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String extract(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
