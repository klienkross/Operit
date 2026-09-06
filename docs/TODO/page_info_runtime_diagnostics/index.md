---
title: Page Info 真机运行时诊断
date: 2026-09-06
status: in-progress
fork: https://github.com/klienkross/Operit
branch: fix/page-info-bounded-failure
starting_sha: 5e9b86a3a00d58c24092327bce94fff7ee0dee1f
---

# Page Info 真机运行时诊断

## 原状

认证后的 `GET /api/ui/page-info` 会在约 2.6 至 3 秒返回 `503 unavailable`。HTTP、认证和 Mobile Node job/runtime 已确认正常，但真机实际选择的 `UiTools` 实现及其阻塞位置尚无运行时证据。

## 意图与预期

用一次 instrumentation APK 覆盖 HTTP handler、provider、`ToolGetter.getUITools(context)`、所有可选 page-info 实现及其 Binder、Shizuku、libsu 和进程等待边界。provider timeout 时自动记录相关线程栈，再在同一次安装后依次探测 Settings、Operit、简单原生页面和小红书。

诊断完成时应能用运行时证据指出具体实现、具体方法、阻塞点、timeout 后存活状态、HTTP 与 main 线程状态，以及唯一的下一处最小修改点。

## 作用域

- 保留现有 2.5 秒 bounded provider failure 和 HTTP contract
- 只增加 page-info 专用结构化日志和 timeout 线程栈
- 由现有 CI 构建一次 instrumentation APK，安装一次并复用该安装完成 probe matrix
- 不修改 mobile-node、QQ Bot、External HTTP API 设计或 UI 功能实现
- 不合并 upstream，不在获得证据前修复 UI 子系统

## 提交

instrumentation commit 和真机证据将在 [诊断步骤](./01-runtime-diagnostics.md) 中记录。
