package com.IDDagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ReportTaskStore {

    private static final Logger log = LoggerFactory.getLogger(ReportTaskStore.class);
    private final Map<String, ReportTask> tasks = new ConcurrentHashMap<>();

    /** 报告任务数据模型 */
    public static class ReportTask {
        private final String reportId;
        private final String templateId;
        private final String templateName;
        private final String companyName;
        private final String creditCode;
        private final String userId;
        private final String sourceFile;
        private final String organization;
        private final String conversationId;
        private final List<String> attachmentNames;
        private final List<String> attachmentFileIds;
        private volatile String status;          // generating / completed / failed
        private volatile String content;         // 生成的 markdown（模板填充后）
        private volatile String errorMessage;
        private volatile Map<String, String> extractedData;  // 从附件解析出的数据字段
        private final Instant createdAt;
        private volatile Instant completedAt;
        private volatile int progress;
        private volatile int generationVersion;   // 生成版本号，防止旧版本覆盖新结果
        /** 是否已被前端 report-completed 消费推进（消费后不再出现在 /active、/pending 轮询结果中，
         *  避免已完成报告被 3s 轮询持续返回、反复调用推进接口刷屏日志） */
        private volatile boolean consumed;

        public ReportTask(String templateId, String templateName, String companyName,
                          String creditCode, String userId, String sourceFile,
                          String organization, String conversationId,
                          List<String> attachmentNames, List<String> attachmentFileIds) {
            this.reportId = UUID.randomUUID().toString();
            this.templateId = templateId;
            this.templateName = templateName;
            this.companyName = companyName;
            this.creditCode = creditCode;
            this.userId = userId;
            this.sourceFile = sourceFile != null ? sourceFile : "";
            this.organization = organization != null ? organization : "";
            this.conversationId = conversationId != null ? conversationId : "";
            this.attachmentNames = attachmentNames != null ? new CopyOnWriteArrayList<>(attachmentNames) : new CopyOnWriteArrayList<>();
            this.attachmentFileIds = attachmentFileIds != null ? new CopyOnWriteArrayList<>(attachmentFileIds) : new CopyOnWriteArrayList<>();
            this.status = "generating";
            this.content = "";
            this.errorMessage = "";
            this.extractedData = new LinkedHashMap<>();
            this.createdAt = Instant.now();
            this.completedAt = null;
            this.progress = 0;
            this.consumed = false;
        }

        // Getters
        public String getReportId() { return reportId; }
        public String getTemplateId() { return templateId; }
        public String getTemplateName() { return templateName; }
        public String getCompanyName() { return companyName; }
        public String getCreditCode() { return creditCode; }
        public String getUserId() { return userId; }
        public String getSourceFile() { return sourceFile; }
        public String getOrganization() { return organization; }
        public String getConversationId() { return conversationId; }
        public List<String> getAttachmentNames() { return attachmentNames; }
        public List<String> getAttachmentFileIds() { return attachmentFileIds; }
        public String getStatus() { return status; }
        public String getContent() { return content; }
        public String getErrorMessage() { return errorMessage; }
        public Map<String, String> getExtractedData() { return extractedData; }
        public Instant getCreatedAt() { return createdAt; }
        public Instant getCompletedAt() { return completedAt; }
        public int getProgress() { return progress; }
        public int getGenerationVersion() { return generationVersion; }
        /** 递增并返回新版本号 */
        public int nextGenerationVersion() { return ++generationVersion; }
        public boolean isConsumed() { return consumed; }
        /** 标记已消费：前端已通过 report-completed 推进（或幂等跳过），轮询不再返回该任务 */
        public void markConsumed() { this.consumed = true; }

        public void setStatus(String status) { this.status = status; }
        public void setContent(String content) { this.content = content; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
        public void setExtractedData(Map<String, String> extractedData) { this.extractedData = extractedData; }
        public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
        public void setProgress(int progress) { this.progress = progress; }
    }

    /** 创建报告任务 */
    public ReportTask createTask(String templateId, String templateName, String companyName,
                                 String creditCode, String userId, String sourceFile,
                                 String organization, String conversationId,
                                 List<String> attachmentNames, List<String> attachmentFileIds) {
        ReportTask task = new ReportTask(templateId, templateName, companyName,
                creditCode, userId, sourceFile, organization, conversationId,
                attachmentNames, attachmentFileIds);
        tasks.put(task.getReportId(), task);
        log.info("报告任务已创建: reportId={}, template={}, company={}, sourceFile={}",
                task.getReportId(), templateName, companyName, sourceFile);
        return task;
    }

    /** 获取任务 */
    public ReportTask getTask(String reportId) {
        return tasks.get(reportId);
    }

    /** 获取用户活跃任务：生成中全部 + 最近窗口内刚完成/失败的任务
     * （报告为同步生成 2ms 即完成，聊天页 3s 轮询必然错过 generating 态；
     *  故补充最近 completedAt 窗口内的已完成任务，前端按 reportId 去重注入进度卡；
     *  已被 report-completed 消费推进的任务不再返回，避免轮询反复推进刷屏） */
    public List<ReportTask> getRecentTasksByUser(String userId, int recentMinutes) {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(recentMinutes));
        return tasks.values().stream()
                .filter(t -> t.getUserId().equals(userId))
                .filter(t -> !t.isConsumed())
                .filter(t -> "generating".equals(t.getStatus())
                        || (("completed".equals(t.getStatus()) || "failed".equals(t.getStatus()))
                        && t.getCompletedAt() != null && t.getCompletedAt().isAfter(cutoff)))
                .sorted(Comparator.comparing(ReportTask::getCreatedAt).reversed())
                .toList();
    }

    /** 获取用户所有报告 */
    public List<ReportTask> getTasksByUser(String userId) {
        return tasks.values().stream()
                .filter(t -> t.getUserId().equals(userId))
                .toList();
    }

    /** 按对话 ID 获取所有待处理（生成中+已完成尚未确认）任务；已消费推进的不再返回 */
    public List<ReportTask> getTasksByConversation(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) return List.of();
        return tasks.values().stream()
                .filter(t -> conversationId.equals(t.getConversationId()))
                .filter(t -> !t.isConsumed())
                .filter(t -> "generating".equals(t.getStatus()) || "completed".equals(t.getStatus()))
                .toList();
    }
}
