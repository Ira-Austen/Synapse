# Synapse · 安卓系统级超低功耗后台保活微内核

[![CI & Release](https://github.com/Ira-Austen/Synapse/actions/workflows/ci-release.yml/badge.svg)](https://github.com/Ira-Austen/Synapse/actions/workflows/ci-release.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-teal.svg)](LICENSE)

**Synapse** 是一个基于 Modern `libxposed` API 101/102 的 **单点 `system_server` 微内核后台调度框架**，面向 **Android 11 – Android 16/17（API 30–37）** 及 **ColorOS 12–16（OplusROM Hans 墓碑）** 设备打造，专注于 **零系统盘写入、100% 无痕可逆、极致省电、高隐身** 的消息推送保活与管理体验。

---

## 核心特性

### 1. 五大微内核插件（单点挂载 `system_server`，零第三方 App 注入）
- **`ProcessGuardPlugin`（精准保活与防冻杀）**
  - 适配 Android 15/16 `OomAdjusterModernImpl` 预提交 `curAdj` 钳制与 `curProcState` 保护。
  - **精准冻结豁免**：仅豁免目标推送子进程与主进程（彻底解决 Android 14–16 `FREEZER BINDER TRANSACTION: Sync transaction while frozen` 同步冻杀吞通知问题），其余臃肿子进程（如 `:gameservice`、`:tool`、`:miniapp`、`:webview`）交由系统正常冻结以最大化续航。
  - 支持断线/划卡自动拉活（遵守 `FLAG_STOPPED` 强停尊重与防死循环滑动窗口熔断）及开机解锁自动拉起。
- **`OemHansPlugin`（ColorOS 12–16 墓碑防冻）**
  - 纯内存适配 `com.android.server.hans.OplusHansImportance`、`com.android.server.hans.freeze.HansCGroup` 与 `OplusHansManager` 原生白名单校验，不禁用 `com.oplus.athena`、不写系统盘，内置 OTA 指纹变更安全预检。
- **`MemoryDozePlugin`（息屏网络不断联）**
  - 内存级联动 `DeviceIdleController` 与 `NetworkPolicyManagerService`（NPMS / `BpfNetMaps`），零修改 `/data/system/deviceidle.xml`。
- **`StealthHidePlugin`（系统级隐身防检测）**
  - 适配 Android 13–17 `ComputerEngine` 与 Android 11–12 `AppsFilter`，随机伪装包名与高熵 Magic Binder 通道，内置管理器/桌面白名单防自断后路。
- **`NotificationAuditPlugin`（零权限推送历史）**
  - 在 `NotificationManagerService` 被动记录通知时间线，**无需开启通知监听权限、不写系统设置**，自动节流高频常驻进度条通知。

### 2. 极致性能与零后台轮询设计
- **热路径零分配**：`PolicySnapshot` 采用不可变 `@Volatile` 快照 + O(1) 快速拒接集合 + 按 `Class` 缓存反射访问器，99.5%+ 非托管系统调用零堆分配。
- **事件驱动直推**：通过带 `linkToDeath` 保护的单向 `IBinder` 观察者，进程变动与新推送毫秒级直推前台 UI，退到后台瞬间解绑、零定时轮询。
- **免 Root `<1ms` 进程监控**：直接从 `system_server` 内存读取托管应用存活进程数、PID、优先级、冻结状态与实时 RSS 内存。
- **单共享低优后台线程**：整个微内核在 `system_server` 内仅复用 1 条 `THREAD_PRIORITY_BACKGROUND` 线程。

### 3. 小白友好的 Material 3 界面
- **底部抽屉一键选应用**：自动扫描已安装应用，结合当前机型品牌与 16+ 主流推送 SDK（腾讯 TPNS、极光、个推、阿里 ACCS/友盟、小米、华为、荣耀、OPPO、vivo、FCM、腾讯 IM/MSF、字节、融云、环信、网易云信等）智能推荐推送进程；授予可选 Root 后支持 **实时 `ESTABLISHED` TCP/UDP 长连接实测实锤**。
- **后台存活进程页**：一眼查看每个已托管应用当前有几个进程存活、内存占用与一键唤醒推送。
- **端侧 Root AI 排查助手（可选）**：支持 OpenAI 兼容接口，按需排查疑难应用的长连接与通知故障。
- **100% 无痕还原**：所有默认保活路径不修改系统分区或系统设置，在 LSPosed 取消勾选并重启即恢复原厂状态。

---

## 环境要求与安装

1. **前置条件**：
   - Android 11 – Android 16/17（推荐 Android 13+，已在真机 Android 16 / ColorOS 16 与 Android 15 实测通过）。
   - 已安装支持 Modern `libxposed` API 101/102 的框架（如 **LSPosed v2.0+** / **Vector**）。
   - Root（KernelSU / Magisk / APatch）**仅在需要「实测长连接」或「AI 排查」时可选**；日常保活、免 Root 进程监控与推送记录均不依赖 Root。
2. **安装与启用**：
   - 前往 [Releases](https://github.com/Ira-Austen/Synapse/releases) 下载最新 `Synapse-v*-modern-release.apk` 并安装（桌面伪装名称为 `System Calibrator`）。
   - 在 LSPosed / Vector 管理器中启用模块，确认作用域为 **系统框架 (`system`)**，然后重启设备一次即可生效。

---

## 模块结构

```text
Synapse/
├── core-protocol/   # 纯 JVM 协议模型、零依赖 JSON 编解码与 IPC 契约
├── kernel-engine/   # system_server 微内核核心与 5 大调度/防冻/隐身/通知插件
├── agent-runtime/   # 按需启动的端侧 Root AI 诊断引擎、/proc/net 解析与事务账本
├── app/             # Jetpack Compose Material 3 管理端与 libxposed API 101/102 入口
└── docs/            # 架构设计文档与任务拆解
```

## 本地构建

```bash
# 运行全部 4 个模块单元测试
./gradlew :core-protocol:test :agent-runtime:test :kernel-engine:testDebugUnitTest :app:testModernDebugUnitTest

# 构建 Modern Release 安装包（首次构建会自动在 local.properties 生成随机隐身包名与 IPC 魔数）
./gradlew :app:assembleModernRelease
```

---

## 开源协议

本项目基于 [MIT License](LICENSE) 开源。
