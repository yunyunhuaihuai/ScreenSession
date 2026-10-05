# ScreenSession / UnlockSessionTimer 工程说明（供后续 agent）

- 本仓库是"解锁限时"应用（applicationId `com.local.unlocksession`），仅面向 OPPO R15x（PBCT10，Android 10 / API 29，序列号 `b185de42`）。范围是整机单次解锁会话限时，不要加按应用统计/每日配额/联网能力。
- 构建：`gradlew assembleDebug`（JDK 21；AGP 8.11.1 / Kotlin 2.2.20 / Gradle 8.13 wrapper 已配好）。直连 dl.google.com 会被本机代理劫持，settings.gradle.kts 已优先 Aliyun 镜像，勿删。
- release 构建用 debug 签名（单机侧载约定，与工作区其他项目一致）。
- 目标设备用 `adb -s b185de42` 指定；ColorOS 侧载安装会弹"USB 安装"确认页，需点击"继续安装"。
- 测试/取证数据放 `..\ScreenSession_Data\`（不入库）；本仓库 `git status` 应保持干净。
- 状态机改动必须同步补 `app/src/test`（纯 JVM 测试）；真机行为验证后把日志拉到 `_Data` 归档。
- 远端为 https://github.com/yunyunhuaihuai/ScreenSession（origin 已配置）。执行约定：**不自动推送远端**，用户明确要求推送或 PR 时才执行；本仓库在功能分支上开发，不自动合并 main。
