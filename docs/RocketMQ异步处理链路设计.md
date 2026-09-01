# 基于 RocketMQ 的 AI 文档异步处理链路 — 设计文档

> 版本：v1.0（设计定稿）
> 适用范围：IDDagent 智能尽调助手（Spring Boot 3.2 WebFlux 后端 + React/TS 前端）
> 前置依赖：《意图识别与任务规划方案.md》《意图穿插处理方案.md》。本文档在现有报告生成链路（`ReportController` / `ReportTaskStore` / `FileParserService` / `LLMFieldExtractor`）基础上，增加基于 RocketMQ 的异步消息化改造，所有类名、方法名、状态机均与最新代码对齐。

---

## 目录

1. [背景与问题分析](#一背景与问题分析)
2. [总体设计](#二总体设计)
3. [消息模型与幂等键](#三消息模型与幂等键)
4. [消费幂等设计](#四消费幂等设计)
5. [失败自动重试机制](#五失败自动重试机制)
6. [DLQ 异常任务兜底](#六dlq-异常任务兜底)
7. [可靠性专项对照](#七可靠性专项对照)
8. [顺序性与并发控制](#八顺序性与并发控制)
9. [后端改造清单](#九后端改造清单)
10. [配置与示例](#十配置与示例)
11. [监控与可观测性](#十一监控与可观测性)
12. [落地建议与风险](#十二落地建议与风险)
13. [验收标准](#十三验收标准)

---

## 一、背景与问题分析

### 1.1 现状链路（基于最新代码）

当前报告生成的解析链路是「请求内同步 + 线程池异步」的组合：

1. 前端上传附件 → `ReportController.createReport` 创建 `ReportTask`（纯内存态，见 `ReportTaskStore` 的 `ConcurrentHashMap`）→ 在 `boundedElastic` 线程中**同步**执行 `parseAttachments(task)` → 再 `CompletableFuture.runAsync` 启动 `generateReport`。
2. `parseAttachments`（`ReportController` L528-583）：遍历 `attachmentFileIds` → `FileParserService.extractText`（PDFBox/POI，**不支持 png/jpg 等图片 OCR**）→ 拼接全部原始文本 → 同步调用 `LLMFieldExtractor.extractFields`。
3. `LLMFieldExtractor.extractFields`（L80-105）：`.block(Duration.ofSeconds(60))` 阻塞 HTTP 调用，`catch (Exception e)` 吞掉异常并返回空 `Map.of()`。
4. `generateReport`（L387-460）：模板加载 → `fillTemplate` → 版本号检查（`generationVersion` 防旧覆盖）→ 置 `completed` → `ReportStoreService.saveReportJson` 入库。

### 1.2 现存问题

| # | 问题 | 根因 | 后果 |
|---|------|------|------|
| P1 | LLM 超时即"静默失败" | 60s 阻塞调用，超时/异常一律被 catch 吞掉返回空 Map | 解析结果缺失，任务仍标记 completed，报告出现大量空字段，**且不可重试** |
| P2 | 耗时任务挤压请求链路 | 解析在请求线程内同步完成，生成靠无界 `CompletableFuture` 线程池 | 突发多任务时线程耗尽、接口响应变慢；无队列缓冲、无背压 |
| P3 | 任务态纯内存 | `ReportTaskStore` 全部在 `ConcurrentHashMap` | 进程重启即丢，解析中/生成中的任务全部丢失，前端进度卡永久悬挂 |
| P4 | 无去重机制 | 同一批附件重复上传/重复触发会创建多个独立任务 | 重复消费与重复生成，浪费 LLM 调用费用且结果互相覆盖 |
| P5 | 无失败兜底 | `generateReport` 的 catch 仅置 `failed` 状态 | 失败原因只留日志，无重放、无告警、无人工介入通道 |
| P6 | 图片文件不可解析 | `FileParserService` 仅支持 xlsx/xls/pdf/docx | 上传营业执照等图片附件时提取不到任何文本，跳过 LLM 解析 |

---

## 二、总体设计

### 2.1 三段式消息流水线

按处理阶段拆分 Topic，每个阶段一个独立消费者，便于独立扩容与监控：

```
上传附件 ──► ReportController（仅落任务 + 投递消息，不再同步解析）
              │
              ▼
   ┌─────────────────────┐   ① 文本提取 / 图片 OCR
   │ Topic: DD_DOC_PARSE │ ───────────► DocParseConsumer（FileParserService + 新增 OcrService）
   └─────────────────────┘
              │  产出：每文件 rawText / OCR 文本（写回任务存储）
              ▼
   ┌─────────────────────┐   ② LLM 结构化解析
   │ Topic: DD_LLM_PARSE │ ───────────► LlmExtractConsumer（改造 LLMFieldExtractor）
   └─────────────────────┘
              │  产出：extractedData 字段表（写回任务存储）
              ▼
   ┌─────────────────────┐   ③ 报告生成（模板填充 + report.json 入库）
   │ Topic: DD_REPORT    │ ───────────► ReportGenerateConsumer（迁移 generateReport）
   └─────────────────────┘
              │  产出：completed 状态 + content
              ▼
         前端 /status、/user/{userId}/active 轮询（契约不变，零改动）
```

### 2.2 Topic 与 Tag 规划

| Topic | Tag | 消费组 | 语义 |
|---|---|---|---|
| `DD_DOC_PARSE` | `extract` / `ocr` | `CG_DOC_PARSE` | 按文件类型路由：pdf/docx/xlsx 走文本提取，png/jpg/bmp/webp 走 OCR |
| `DD_LLM_PARSE` | `field_extract` | `CG_LLM_PARSE` | LLM 结构化字段提取（一次任务一条消息，聚合全部附件文本） |
| `DD_REPORT` | `generate` | `CG_REPORT` | 模板填充 + 结果持久化 |

**拆分原则**：三个阶段的依赖关系固定（parse → llm → generate），拆分后每级消费者可独立设置并发度、重试策略与消费超时；单级故障（如 LLM 服务宕机）只阻塞本级，上游消息仍在队列积压而不丢失，故障恢复后自动继续消费。

**顺序性保证**：跨 Topic 的先后依赖通过「上一阶段成功后显式投递下一跳」实现（见 2.3），同一 Topic 内按 `reportId` 哈希选队列保证同报告消息有序（见第八章）。

### 2.3 阶段流转协议

```
DocParseConsumer:
  ① 消费 DD_DOC_PARSE 消息
  ② 按扩展名路由：图片 → OcrService；文档 → FileParserService.extractText
  ③ 合并全部文件 rawText，写回 dd_report_task（本阶段内部持久化）
  ④ 更新 dd_task_stage(DOC_PARSE) = SUCCESS
  ⑤ syncSend(DD_LLM_PARSE, 携带聚合文本引用或文本摘要)   ← 显式投递下一跳

LlmExtractConsumer:
  ① 消费 DD_LLM_PARSE 消息
  ② 调 LLMFieldExtractor 提取字段（异常分类，见 5.1）
  ③ extractedData 合并写回 dd_report_task
  ④ 更新 dd_task_stage(LLM_PARSE) = SUCCESS
  ⑤ syncSend(DD_REPORT, 携带 reportId)

ReportGenerateConsumer:
  ① 消费 DD_REPORT 消息
  ② 执行 fillTemplate + 版本检查（保留 generationVersion）
  ③ 置 completed + ReportStoreService.saveReportJson
  ④ 更新 dd_task_stage(REPORT) = SUCCESS
```

---

## 三、消息模型与幂等键

### 3.1 消息体

统一携带 `bizId`（业务幂等键）与 `traceId`（全链路追踪）：

```json
{
  "bizId": "9f8e7d6c-..._LLM_PARSE",
  "reportId": "9f8e7d6c-...",
  "stage": "LLM_PARSE",
  "traceId": "d5c1a2...",
  "payload": {
    "userId": "u_001",
    "conversationId": "conv_17",
    "templateId": "financial_analysis",
    "companyName": "小米科技",
    "creditCode": "91...",
    "attachmentFileIds": ["a1", "a2"],
    "filePaths": ["data/uploads/report-files/a1.docx"],
    "extractVersion": 1
  }
}
```

### 3.2 幂等键规则

- **`bizId = reportId + "_" + stage`**（如 `9f8e..._DOC_PARSE`、`9f8e..._LLM_PARSE`、`9f8e..._REPORT`）。
- 同一阶段消息因重试、补偿投递重复出现时，`bizId` 不变。
- 用户侧「重新生成」= 新 `reportId`（新 UUID），自然绕过旧幂等记录。

### 3.3 任务持久化改造

`ReportTaskStore` 由纯内存扩展为「内存 + 落库」双写（沿用现有 `ReportTask` 字段，状态机扩展为 `pending → extracting → generating → completed / failed / dlq`），这是幂等判断与重启恢复的数据基础：

```sql
-- 任务主表（对应 ReportTaskStore.ReportTask 字段）
CREATE TABLE dd_report_task (
  report_id          VARCHAR(64) PRIMARY KEY,
  template_id        VARCHAR(64),
  template_name      VARCHAR(128),
  company_name       VARCHAR(128),
  credit_code        VARCHAR(64),
  user_id            VARCHAR(64),
  conversation_id    VARCHAR(64),
  source_file        VARCHAR(256),
  organization       VARCHAR(128),
  attachment_names   TEXT,          -- JSON 数组
  attachment_file_ids TEXT,         -- JSON 数组
  status             VARCHAR(16) NOT NULL,   -- pending/extracting/generating/completed/failed/dlq
  extracted_data     TEXT,          -- JSON，对应 extractedData
  content            MEDIUMTEXT,    -- 生成的 markdown
  progress           INT DEFAULT 0,
  error_message      TEXT,
  generation_version INT DEFAULT 0, -- 保留现有防旧版本覆盖机制
  created_at         DATETIME,
  completed_at       DATETIME
);

-- 阶段执行记录（幂等依据：唯一索引兜底）
CREATE TABLE dd_task_stage (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_id      VARCHAR(128) NOT NULL,   -- reportId + "_" + stage
  report_id   VARCHAR(64)  NOT NULL,
  stage       VARCHAR(32)  NOT NULL,   -- DOC_PARSE / LLM_PARSE / REPORT
  status      VARCHAR(16)  NOT NULL,   -- PROCESSING / SUCCESS / RETRYING / FAILED
  retry_count INT DEFAULT 0,
  created_at  DATETIME,
  updated_at  DATETIME,
  UNIQUE KEY uk_biz_stage (biz_id)     -- 幂等核心：唯一索引
);
```

---

## 四、消费幂等设计

### 4.1 双层幂等

采用「状态机前置校验 + DB 唯一索引兜底」，`MessageListenerConcurrently` 实现（区别于 `MessageListenerOrderly`，便于并发消费）：

```
consumeMessage(msgs) {
    msg = parse(msgs[0]);
    ① 幂等预检：查 dd_task_stage，若 status = SUCCESS → 直接 CONSUME_SUCCESS（丢弃重复消息）
    ② 状态校验：查 dd_report_task，若 report 已 failed/dlq/completed 且不允许流转 → ACK 丢弃
    ③ 幂等写入：INSERT dd_task_stage(biz_id, status='PROCESSING')
         └─ 主键冲突（唯一索引）→ 并发重复到达 → 直接 CONSUME_SUCCESS
    ④ 执行业务处理（OCR / LLM / 模板生成）
    ⑤ 更新 dd_task_stage status='SUCCESS' + dd_report_task 进度/结果
    ⑥ return CONSUME_SUCCESS
}
```

### 4.2 关键点

- **第③步是真正的幂等闸门**：`uk_biz_stage` 唯一索引保证即使多个实例同时消费同一消息，也只有一个写入成功，其余直接 ACK。①②只是减少无效消费，不依赖其做最终幂等。
- **结果以合并写入为准**：`LlmExtractConsumer` 产出字段后 `extracted_data` 做 JSON 合并更新，重复消息不会产生脏数据。
- **保留 `generationVersion` 作为并发兜底**：即使链路并发穿透，旧版本生成结果也不会覆盖新版本（现有 `generateReport` L432 的检查原样迁移到 `ReportGenerateConsumer`）。

---

## 五、失败自动重试机制

### 5.1 失败分类（决定走重试还是走 DLQ）

在消费者内对异常显式分类，这是整个重试设计的核心：

| 异常类型 | 判定 | 策略 |
|---|---|---|
| `TransientException`（可重试） | LLM/OCR 服务 5xx、网络超时、连接重置、上游限流 429、`SocketTimeoutException` | 抛回容器，触发 RocketMQ 延迟重试 |
| `PermanentException`（不可重试） | 文件不存在、文件损坏无法解析、模板缺失、LLM 返回 4xx（参数/鉴权错误）、响应 JSON 解析失败 | **不重试**，直接投递 DLQ 并置任务 `failed` |
| 业务幂等冲突 | 状态机校验不满足 | 视为成功，ACK 丢弃 |

**改造要点**：`LLMFieldExtractor.extractFields` 现有的 `catch (Exception e) { return Map.of(); }`（L102-105）必须拆分：
- `5xx`、`ReadTimeoutException`、`ConnectException` → 抛 `TransientException` 触发重试；
- `4xx`、响应体非合法 JSON → 抛 `PermanentException` 进 DLQ；
- 仅「LLM 正常返回但某字段为空」是合法空值（空字符串字段不算失败，现有语义保留）。

### 5.2 消费失败重试（RocketMQ 消息级重试）

- 消费者抛异常 → Broker 将消息按**延迟等级**重新投递到消费组重试队列，默认最多 16 次，延迟递进：`1s / 5s / 10s / 30s / 1m / 2m / 3m / 4m / 5m / 6m / 7m / 8m / 9m / 10m / 20m / 30m / 1h / 2h`。瞬时故障（如 DeepSeek 短暂限流）通常第 1~2 次重试即可恢复，无需人工干预。
- 通过 `max-reconsume-times` 自定义重试上限（低于默认 16 次），避免 AI 服务长时间不可用时 16 次重试打满上游。
- **消费超时与 LLM 超时联动**：`consume-timeout(5min) > LLM 调用超时(120s)`，保证 LLM 未返回时消息不会被 Broker 判为消费超时重复投递；同时把 `.block(60s)` 上调至 `120s`，给长文本解析留足余量（现有 30000 字符截断保留）。

### 5.3 重试期间的进度反馈

重试不改变任务状态机：任务保持 `extracting`，仅更新 `error_message = "LLM 服务暂时不可用，正在重试（第 n/8 次）"` 与 `progress`（35~45 区间）。前端 3s 轮询 `/status` 与 `/user/{userId}/active` 完全复用，用户感知为「解析中」，不会误判为失败。

---

## 六、DLQ 异常任务兜底

### 6.1 自动进入 DLQ

RocketMQ 原生机制：消费组重试次数打满（`max-reconsume-times`）后，消息自动进入 `%DLQ%CG_DOC_PARSE`、`%DLQ%CG_LLM_PARSE` 等 DLQ Topic，无需人工搬运。

### 6.2 DLQ 消费与重放（兜底闭环）

新增 `DlqConsumer` 监听全部 `%DLQ%CG_*` Topic：

1. **落库告警**：DLQ 消息写入 `dd_dlq_record` 表（biz_id、topic、error、retry_count、入队时间），任务状态置 `dlq`、`error_message` 写入具体原因；前端进度卡显示「解析失败，可点击重试」；同时推送企业微信/钉钉机器人告警（含 `reportId`、`traceId`、失败阶段、原因摘要）。
2. **人工/半自动重放**：管理接口 `POST /api/admin/dlq/{id}/replay`，以**原 bizId** 重新投递回原 Topic —— 幂等键不变，重放后由 `dd_task_stage` 唯一索引安全拦截或正确执行（对应 5.1 幂等冲突策略）。
3. **定期巡检**：定时任务扫描 `dd_dlq_record`，超过 24h 未处理且未告警的 DLQ 消息二次告警，避免「静默死亡」。
4. **用户侧兜底重放**：前端报告进度卡在 `failed`/`dlq` 态提供「重新生成」入口，复用现有 `/update` 接口语义重新投递 `DD_DOC_PARSE`（新 reportId 自然绕过旧幂等记录）。

### 6.3 与现有 failed 状态的衔接

前端对 `failed` 的展示逻辑（进度卡 + 错误文案）不做改动，仅新增 `dlq` 分支显示「已进入异常队列，系统正在重试或等待人工处理」。两者都保证 `completedAt` 非空，符合 `getRecentTasksByUser` 的 10 分钟窗口过滤逻辑（`ReportTaskStore` L122-131）。

```sql
-- DLQ 记录表
CREATE TABLE dd_dlq_record (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  biz_id      VARCHAR(128) NOT NULL,
  report_id   VARCHAR(64)  NOT NULL,
  topic       VARCHAR(64)  NOT NULL,   -- 原始 Topic
  error_msg   TEXT,
  retry_count INT DEFAULT 0,
  replay_times INT DEFAULT 0,          -- 已重放次数
  status      VARCHAR(16) NOT NULL,    -- PENDING / REPLAYED / IGNORED
  created_at  DATETIME,
  updated_at  DATETIME
);
```

---

## 七、可靠性专项对照

| 故障场景 | 链路中的应对 | 现状对照 |
|---|---|---|
| **AI 服务超时** | LLM/OCR 调用设独立超时（120s）且 < 消费超时（5min）；超时抛 `TransientException` → 消息延迟重试；重试打满 → DLQ 告警 + 人工重放 | 现在：60s 超时吞异常返回空 Map，静默丢数据 |
| **重复消费** | Producer 同步发送 + 失败重投（幂等键不变）；Consumer 三层防重：状态机预检 + `dd_task_stage` 唯一索引 + `generationVersion` 防旧覆盖 | 现在：完全无防重，重复触发产生多任务 |
| **瞬时故障** | RocketMQ 延迟等级重试（1s~2h 递进）自动覆盖网络抖动、限流；`DD_DOC_PARSE` 失败重试期间 `DD_LLM_PARSE` 消息仍在队列，不丢消息 | 现在：一次失败即终态，原因只在日志里 |

**消息可靠性补充**：Producer 用**同步发送**（`syncSend`）并校验 `SendResult.sendStatus`，失败重试 3 次，仍失败则回写任务状态 `failed` 并告警，绝不静默丢弃。任务先落库、再投递、投递失败置任务失败，以补偿式保证覆盖「任务存在则必有消息」的原子性要求；若后续要求严格事务边界，再升级为 `RocketMQLocalTransactionListener` 事务消息（当前阶段不引入，控制复杂度）。

---

## 八、顺序性与并发控制

- **同一 reportId 的顺序保证**：跨 Topic 依赖靠「上一阶段成功后显式投递下一跳」实现；同一 Topic 内同一 reportId 若存在多条消息（如多文件 OCR 拆分），按 `reportId` 做 `MessageQueueSelector` 哈希选队列，保证同报告消息有序消费。
- **消费并发与背压**：`consume-thread` 按阶段配置（OCR 多线程、LLM 少线程）；`LlmExtractConsumer` 内加**令牌桶限流**（如 5 QPS）保护上游 API，避免多线程打满 DeepSeek 触发全局限流反而拖垮整条链路。
- **进度与幂等写入的原子性**：`dd_report_task` / `dd_task_stage` 的更新放同一事务，避免「阶段标记 SUCCESS 但任务数据未落库」的中间态。

---

## 九、后端改造清单

| 位置 | 改动 |
|---|---|
| `pom.xml` | 新增 `rocketmq-spring-boot-starter`（2.3.x，兼容 Spring Boot 3.2）+ `spring-boot-starter-jdbc`（任务持久化） |
| `ReportController.createReport` | 删除请求内同步 `parseAttachments` 调用；仅创建任务 + `syncSend(DD_DOC_PARSE)`；任务状态置 `extracting` |
| `ReportController.parseAttachments` | 逻辑迁往 `LlmExtractConsumer`，控制器内删除（L528-583） |
| 新增 `DocParseConsumer` | 从消息解析 filePaths，按扩展名路由：图片 → 新增 `OcrService`，pdf/docx/xlsx → 复用 `FileParserService.extractText`；合并 rawText 写回任务，随后投递 `DD_LLM_PARSE` |
| 新增 `OcrService` | 图片 OCR 能力接入（PaddleOCR/Tesseract/云 OCR），作为 `FileParserService` 的图片分支补充 |
| `LLMFieldExtractor.extractFields` | 异常分类改造（Transient/Permanent）；`.block(60s)` → `120s`；移除吞异常返回空 Map 的写法（L102-105） |
| 新增 `LlmExtractConsumer` | 替代 `parseAttachments` 中的 LLM 调用段（L562-578），`extractedData` 合并写回任务，投递 `DD_REPORT` |
| 新增 `ReportGenerateConsumer` | 迁移 `generateReport(task, true)` 主体（模板加载、`fillTemplate`、`ReportStoreService.saveReportJson`），保留 `generationVersion` 防覆盖检查 |
| `ReportTaskStore` | 增加落库读写（`dd_report_task`），内存态保留供现有 `getTask` / `getRecentTasksByUser` / 轮询接口零改动使用 |
| 新增 `DlqConsumer` + `DlqRecordService` | DLQ 落库、告警、管理端重放接口 |
| `ReportController.updateReport` | 「确认并生成报告」由 `CompletableFuture.runAsync` 改为投递 `DD_REPORT` 消息并轮询结果，接口返回语义不变 |
| 前端 | **零改动**（`/status`、`/user/{userId}/active` 轮询契约不变，新增 `dlq` 状态仅需补充文案映射） |

---

## 十、配置与示例

### 10.1 pom.xml 新增依赖

```xml
<!-- RocketMQ -->
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-spring-boot-starter</artifactId>
    <version>2.3.0</version>
</dependency>

<!-- 任务持久化（H2 可作本地开发默认，生产换 MySQL） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
```

### 10.2 application.yml（关键配置）

```yaml
rocketmq:
  name-server: ${ROCKETMQ_NAMESRV:127.0.0.1:9876}
  producer:
    group: PG_DD_TASK
    send-message-timeout: 5000      # 投递超时 5s，失败重试 3 次
  consumer:
    doc-parse:
      group: CG_DOC_PARSE
      topic: DD_DOC_PARSE
      consume-thread-min: 4         # IO 密集型（OCR/文本提取）
      consume-thread-max: 16
      max-reconsume-times: 10       # 业务自定义重试上限，超过进 DLQ
      consume-timeout: 300000       # 单条消费超时 5min，需 > LLM 调用超时
    llm-parse:
      group: CG_LLM_PARSE
      topic: DD_LLM_PARSE
      consume-thread-min: 2         # LLM 接口需限流，线程数保守
      consume-thread-max: 8
      max-reconsume-times: 8
      consume-timeout: 300000
    report-gen:
      group: CG_REPORT
      topic: DD_REPORT
      consume-thread-min: 2
      consume-thread-max: 4
      max-reconsume-times: 3
      consume-timeout: 120000
    dlq:
      group: CG_DLQ_MONITOR
      topic: "%DLQ%CG_DOC_PARSE,%DLQ%CG_LLM_PARSE,%DLQ%CG_REPORT"
      consume-thread-min: 1
      consume-thread-max: 2
```

### 10.3 消费者骨架（以 LlmExtractConsumer 为例）

```java
@RocketMQMessageListener(topic = "DD_LLM_PARSE", consumerGroup = "CG_LLM_PARSE",
        selectorExpression = "field_extract")
@Component
public class LlmExtractConsumer implements RocketMQListener<DocParseMessage> {

    private final LLMFieldExtractor llmFieldExtractor;
    private final ReportTaskStore taskStore;
    private final RocketMQTemplate rocketMQTemplate;
    private final RateLimiter limiter = RateLimiter.create(5.0); // LLM 限流 5 QPS

    @Override
    public void onMessage(DocParseMessage msg) {
        // ① 幂等预检 + ② 状态校验（查询 dd_task_stage / dd_report_task）
        if (stageAlreadySuccess(msg.getBizId())) return;
        try {
            // ③ 幂等写入（唯一索引冲突则直接返回）
            insertStageWithConflictGuard(msg.getBizId(), msg.getReportId(), "LLM_PARSE");

            // ④ 执行业务（含令牌桶限流；异常分类抛出）
            limiter.acquire();
            Map<String, String> fields = llmFieldExtractor.extractFields(
                    msg.getRawText(), msg.getTemplateId(),
                    msg.getCompanyName(), msg.getCreditCode());

            // ⑤ 结果合并写回 + 阶段置 SUCCESS + 投递下一跳
            taskStore.mergeExtractedData(msg.getReportId(), fields);
            markStageSuccess(msg.getBizId());
            rocketMQTemplate.syncSend("DD_REPORT:generate", toReportMsg(msg));
        } catch (PermanentException e) {
            markStageFailed(msg.getBizId());
            taskStore.markFailed(msg.getReportId(), e.getMessage());
            sendToDlqMonitor(msg, e);          // 记录 dd_dlq_record + 告警
        } // TransientException 不捕获 → 抛出触发消息级延迟重试
    }
}
```

---

## 十一、监控与可观测性

- **链路追踪**：`traceId` 随消息体贯穿 create → parse → llm → report 全链路，日志统一打印，配合 `reportId` 快速定位单次任务在哪个阶段、重试了几次、最终落在哪。
- **指标埋点**（Micrometer + Prometheus）：各消费组消费耗时 P95/P99、消费成功/失败计数、重试次数分布、DLQ 消息数、任务各状态数量（`extracting/generating/completed/failed/dlq`）。
- **告警阈值建议**：DLQ 5 分钟新增 > 3 条；消费成功率 < 95%；`CG_LLM_PARSE` 消费堆积（consumerLag）> 50 —— 堆积是「AI 服务不可用导致任务积压」的最直接信号。
- **RocketMQ Dashboard**：直接查看各消费组堆积与消费速率。

---

## 十二、落地建议与风险

1. **分阶段灰度**：先落地 `DD_DOC_PARSE + DD_LLM_PARSE`（解析链路异步化），`DD_REPORT` 阶段暂保持 `CompletableFuture` 并行过渡，验证稳定后再迁移，降低一次性改造风险。
2. **OCR 是新增能力**：`OcrService` 需先行接入；`DocParseConsumer` 图片分支做降级——OCR 失败但同任务存在可解析文本文件时，仍可继续 LLM 阶段。
3. **本地开发环境**：提供 RocketMQ 单机部署（namesrv + broker + dashboard）或 docker-compose 配置；`rocketmq.name-server` 走 `.env` 注入，避免硬编码。
4. **遗留同步兼容**：`updateReport` 迁移后接口返回语义（`status=generating`）保持不变，前端无需感知差异。
5. **消息体体积控制**：`DD_LLM_PARSE` 若携带聚合全文（上限 30000 字符）会导致消息体过大，建议消息只带 fileIds + 落盘文本路径，消费者按需读取。

---

## 十三、验收标准

1. 上传含图片附件的任务可正常完成 OCR → LLM → 报告生成全流程（P6 关闭）。
2. 模拟 LLM 服务 5xx：任务自动延迟重试，服务恢复后自动完成，期间前端进度卡保持「解析中」（P1 关闭）。
3. 模拟重复投递同一 `bizId` 消息：`dd_task_stage` 唯一索引拦截，任务数据无重复写入（P4 关闭）。
4. 模拟消费重试打满：消息进入 `%DLQ%CG_LLM_PARSE`，`dd_dlq_record` 落库 + 告警触发，管理端重放后任务可恢复完成（P5 关闭）。
5. 重启后端进程：进行中任务从 `dd_report_task` 恢复，状态与进度不回退（P3 关闭）。
6. 前端轮询接口（`/status`、`/user/{userId}/active`）返回契约与改造前完全一致，无需前端改动。
