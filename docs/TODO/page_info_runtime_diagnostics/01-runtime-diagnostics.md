---
title: Page Info 运行时诊断步骤
date: 2026-09-06
status: in-progress
---

# Page Info 运行时诊断步骤

## Instrumentation

1. 为每个 HTTP 请求分配 request id，记录认证、provider 调用、响应状态和总耗时。
2. 记录 provider worker 生命周期、具体 `UiTools` 类、返回类型、timeout 后存活状态和相关线程栈。
3. 记录 `ToolGetter` 的权限偏好与最终具体类。
4. 记录 Standard、Accessibility、Debugger、Admin 继承路径和 Root 的 page-info 入口及返回。
5. 记录 Accessibility Binder、Shizuku process、libsu `.exec()` 与 `process.waitFor()` 等边界的进入、返回、异常、线程和耗时。

## 构建与真机矩阵

1. 审查变更只覆盖诊断作用域后推送一次 instrumentation commit，由现有 CI 构建 debug APK；触发后不在本轮等待产物。
2. 用户确认安装一次后，清理或标记 logcat。
3. 依次探测 Android Settings、Operit、Launcher 或简单原生 App、小红书；每次记录 HTTP status、耗时、实现、边界和 timeout stack。
4. 基础 case 之间不改代码、不重装 APK。只有现有证据无法区分剩余假设时才考虑第二次 instrumentation APK。

## 完成记录

- instrumentation commit：待记录
- CI 构建：待 instrumentation push 触发，本轮不等待
- 安装确认次数：0
- selected implementation：待真机证据
- blocking point：待真机证据
- root cause：待真机证据
