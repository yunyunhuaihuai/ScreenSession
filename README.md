# 解锁限时（UnlockSessionTimer）

单机、离线、侧载使用的 Android 应用：**整机单次解锁会话的限时**。每次真正完成系统解锁后强制先选择本次使用时长（三个可编辑快捷时长 / 不限 / 自定义），计时结束调用真实系统锁屏（设备管理员 `force-lock` + `DevicePolicyManager.lockNow()`），锁屏后由家长输入系统密码解锁。不做按应用统计、每日配额、账号、云同步、VPN 或内容识别。

仅面向指定设备：OPPO R15x（PBCT10，Android 10 / API 29 / ColorOS 7.1，固件 PBCT10_11_F.23_241210，ADB 序列号 `b185de42`）。

## 构建环境

| 项 | 值 |
|---|---|
| JDK | Temurin 21（JAVA_HOME 已设） |
| Gradle | 8.13（wrapper 已提交） |
| Android Gradle Plugin | 8.11.1 |
| Kotlin | 2.2.20 |
| compileSdk | 36（本机已装平台 android-36；仅编译用途，不改变设备执行链路） |
| minSdk / targetSdk | 29 / 29（与目标设备 Android 10 实际执行链路一致：前台服务无需类型声明、精确闹钟无 API 31 限制、通知无运行时权限；单机侧载不上架） |
| 依赖 | androidx.core-ktx 1.13.1、appcompat 1.7.0、junit 4.13.2 |
| 仓库镜像 | settings.gradle.kts 优先 Aliyun 镜像（本机直连 dl.google.com 常被代理软件劫持超时），google()/mavenCentral() 兜底 |

```bash
# 单元测试（34 个，纯 JVM 状态机测试）
gradlew :app:testDebugUnitTest
# 构建（release 用 debug 签名，可直接侧载）
gradlew assembleDebug assembleRelease
# 产物
#   app/build/outputs/apk/debug/app-debug.apk
#   app/build/outputs/apk/release/app-release.apk
# 安装（本机）
adb -s b185de42 install -r app\build\outputs\apk\debug\app-debug.apk
```

## 首次授权（应用内"系统授权"卡片集中处理）

1. **设备管理员**（必须）：应用内点击"启用设备管理员"，系统页激活。仅申请 `force-lock` 一项策略（`res/xml/admin_policies.xml`）。
2. **悬浮窗权限**（必须）：应用内点击"授权悬浮窗"，允许"显示在其他应用上层"。
3. **电池优化白名单**（建议）：应用内"申请忽略电池优化"。
4. **OPPO 自启动**（建议）：应用内"打开 OPPO 自启动设置"；ColorOS 7.1 组件名未命中时弹出文字指引（设置 → 电池 → 应用耗电管理 → 允许后台运行；最近任务下拉卡片锁定）。
5. **锁屏密码**（正式使用必须）：当前测试机未设密码，到期锁屏可用但可直接滑开；正式交给儿童前请先设置系统锁屏密码。应用不读取、不保存、不代填该密码。

三项能力未就绪时应用如实显示"未就绪"并拦截会话创建（不会假装功能正常），通知栏有提醒。

adb 快捷授权（调试用，正式使用走上面的界面）：

```bash
adb -s b185de42 shell dpm set-active-admin com.local.unlocksession/.service.AdminReceiver
# 悬浮窗：ColorOS 禁止 shell 改 appops，须在系统页面手动开启
adb -s b185de42 shell dumpsys deviceidle whitelist +com.local.unlocksession
```

## 设计与状态流转

统一状态机 `SessionStateMachine`（纯 Kotlin，可单测）+ 唯一执行方 `SessionController`（单线程串行），事件源：前台服务 `UnlockMonitorService` 动态注册的 `ACTION_SCREEN_ON/SCREEN_OFF/USER_PRESENT`、`SessionAlarmReceiver` 到期闹钟、开机/进程恢复 `Recover` 事件、UI 选择。

| 状态 | 行为 |
|---|---|
| 已锁屏／无会话 | 无活动计时，等待真正解锁 |
| 待选择 | `TYPE_APPLICATION_OVERLAY` 全屏选择层，未选择不能进入普通应用 |
| 正在计时 | 截止时间固定，只允许查看（通知每秒刷新剩余时间，无任何修改入口） |
| 本次不限 | 不创建到期任务，锁屏即结束 |
| 已发出锁屏请求 | `lockNow()` 已调用，等系统熄屏确认；10s 看门狗 + 1 次重试，失败进入"锁屏失败"并记录原因 |

关键规则与实现：

- **解锁判定**：`USER_PRESENT`（keyguard 消失）是唯一建会话入口；`SCREEN_ON` 不是解锁。无密码设备（如当前测试机）keyguard 不锁定，由"漏事件安全网"兜底：亮屏且未锁且无会话 → 立即进入待选择，不放过未限时使用。
- **计时**：`SystemClock.elapsedRealtime()` 毫秒，截止时间 = 确认时刻 + 时长；到期执行用 `AlarmManager.setExact(ELAPSED_REALTIME_WAKEUP)` + 显式广播。改系统时间不影响倒计时。输入严格校验（拒绝空/零/负/非数字/超范围，不把 0 当不限）。
- **旧事件失效**：每次会话唯一自增 id；到期事件核对 id，不匹配即忽略；提前触发的闹钟按原截止时间重排。
- **熄屏即结束**：`SCREEN_OFF`（通话中接近传感器黑屏除外，AudioManager mode 尽力判断）→ 结束会话 + 取消闹钟；系统有安全凭据时附加 `lockNow()` 消除解锁宽限期。
- **恢复**：开机 / STICKY 重启 / 应用更新后执行恢复检查。用 `Settings.Global.BOOT_COUNT` 区分重启（旧会话一律作废，`elapsedRealtime` 严禁跨重启复用）与同次开机进程恢复（合法会话沿用原截止时间，绝不重新给足；已过期则执行到期锁屏；已锁屏则结束）。服务可能在首次解锁广播之后才启动，恢复检查主动核对当前交互/锁屏状态，补上待选择。
- **悬浮层**：可聚焦窗口，"自定义"输入直接在窗口内 EditText 完成（软键盘可弹）；返回键在面板模式被吞掉，Home/最近任务不解除选择要求；单例防重复挂载/漏移除。
- **防间接取消**：会话进行中监控开关锁定，界面/通知无停止、重置、延长、转不限入口。

## 参考项目（均只作研读，未整仓搬入代码）

| 项目 | 版本（HEAD） | 许可证 | 结论 |
|---|---|---|---|
| Screen Off Timer | doniarifin/screen-off-timer@f517676 (2026-07-20) | Apache-2.0 | 证实其计时纯靠墙钟 `System.currentTimeMillis()` 协程轮询、无任何 AlarmManager/开机恢复；快捷时长 5/10/30/45 硬编码在 Compose 组件 `PresetTime.kt`（无桌面 Widget）。本项目的反面教材。 |
| FallASleep | moritzgloeckl/FallASleep@a2b8336 (2022-11-11) | MIT | 证实 `SleepTimerService` 是普通 Kotlin 类 + 进程内 `CountDownTimer`（非 Android Service）；其最小管理员策略 `policies.xml`（仅 force-lock）与 `ACTION_ADD_DEVICE_ADMIN` 激活方式被借鉴。 |
| Mindful | akaMrNagar/Mindful@b6eb68d (2025-08-27) | GPL-2.0 | 动态注册 `USER_PRESENT/SCREEN_OFF`、FGS STICKY、`TYPE_APPLICATION_OVERLAY` 窗口参数、ColorOS 自启动页组件名等模式有参考；本应用未使用其无障碍/用量统计方案。 |
| Open TimeLimit | timelimit/opentimelimit-android@fad71e3 (2024-07-29, v7.1.0) | GPL-3.0 | 覆盖层单例防重、权限判断（Settings.canDrawOverlays + AppOps）、`isBackgroundActivityRestricted` 降级思路作补充参考。 |

## 验证结果

### 自动化（本机）

- `:app:testDebugUnitTest`：**34 个测试，0 失败**。覆盖：解锁创建会话、重复 USER_PRESENT 幂等、只亮屏不建会话、安全网、选择计时/不限、非法时长拒绝、旧会话事件忽略、到期锁屏请求、重复到期幂等、闹钟提前重排、熄屏结束（含通话中忽略）、漏事件自愈、lockNow 失败/重试/看门狗、恢复 8 种场景（重启作废/同机沿用原截止/过期锁屏/已锁屏结束/补待选择等）、分钟与秒输入校验。

### 真机（OPPO R15x / b185de42 / 2026-10-05，日志证据见 `..\ScreenSession_Data\diag-20261005\diag.log`）

| 场景 | 结果 |
|---|---|
| 解锁后出现选择层，状态待选择 #1 | ✅（RECOVER→PENDING→"悬浮选择层已挂载"） |
| 待选择时按 Home / 打开设置 | ✅ 悬浮层仍覆盖，选择要求继续生效（截图 shot_home_overlay.png） |
| 点"10 分钟" | ✅ PENDING→TIMING，截止=确认时刻+600000ms，闹钟排定，悬浮层移除 |
| 计时中打开主界面 | ✅ 只显示状态与剩余时间；监控开关锁定；服务恢复检查重排同一截止时间（不重新给足） |
| 电源键锁屏 | ✅ TIMING→NO_SESSION，闹钟取消 |
| 再亮屏滑动解锁 | ✅ 新会话 #2 待选择（多次循环，每次只一个选择层） |
| 自定义"1 分钟" | ✅ 会话 #3，60000ms |
| **到期真实锁屏** | ✅ 闹钟在截止后 24ms 触发 → lockNow → **641ms 后系统真实熄屏**（mWakefulness=Asleep） |
| 旧会话到期事件重放（debug） | ✅ "闹钟到达但状态=UNLIMITED，忽略（旧事件）" |
| 选择"不限"后锁屏再解锁 | ✅ 不限结束、重新选择 |
| 待选择中熄屏 | ✅ 放弃本次选择，悬浮层移除 |
| 重启手机（boot 17→18） | ✅ BOOT_COMPLETED 恢复，sameBoot=false 旧会话作废，无遗留误锁，首次解锁重新选择 |
| OPPO 自启动按钮 | ✅ 组件未命中时按设计弹出文字指引（ColorOS 7.1 组件名与预置不同） |

### 已知边界与未验证项（如实声明）

- **未设锁屏密码的设备语义**：本机无密码，keyguard 不进入"锁定"态，`USER_PRESENT` 不总是触发，靠安全网在亮屏时立即要求选择；"只亮屏看通知"场景因此无法与"要用手机"区分（没有锁屏界面可看）。生产上家长设置密码后：亮屏→keyguard 锁定→不建会话，真正解锁才选择——该链路在本机**未实测**（本机无法设置测试密码，需人工输入凭据）。
- "熄屏加强锁定"（防宽限期）在本机被正确跳过（无安全凭据），该路径未实测。
- 通话中接近传感器黑屏：AudioManager mode 判断为尽力实现，**未在真机通话中实测**。
- ColorOS 激进杀后台的长期（数天）存活率未验证；已做 FGS+STICKY+电池白名单+恢复检查，"故意强行停止"后不承诺自动恢复（系统语义，符合需求第 10 条）。
- 旋转/缺口/软键盘的交互仅在本机竖屏下人工点验；未做自动化 UI 测试。
- Shell 无法改 appops（ColorOS 安全限制），悬浮窗授权必须人工在系统页开启。
- 测试期间曾执行 `svc power stayon true`（保持亮屏）与一次 `adb reboot`；`svc` 随重启自动失效，系统设置未做任何持久修改。

## 工程结构

```
app/src/main/java/com/local/unlocksession/
├── App.kt                    # 单例组装
├── logic/                    # 纯 Kotlin：SessionModel（状态/事件/动作）+ SessionStateMachine + DurationInput
├── core/                     # SessionController（串行执行方）+ SessionNotifier
├── data/SessionRepository.kt # SharedPreferences 持久化（快照+配置+boot 归属）
├── diag/Diagnostics.kt       # 256KB 上限诊断日志（可查看/导出/复制）
├── lock/LockController.kt    # 管理员状态 + lockNow（不触碰锁屏密码）
├── overlay/SelectionOverlay.kt   # 全屏选择悬浮层（单例）
├── session/SessionPanelBridge.kt # 面板回调→控制器
├── service/                  # UnlockMonitorService / SessionAlarmReceiver / AdminReceiver / BootReceiver
└── ui/                       # MainActivity / SelectionActivity（兜底）/ LogActivity / SessionPanelView
```
