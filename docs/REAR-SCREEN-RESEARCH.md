# 小米 17 Pro Max 背屏研究笔记

本文记录在 Xiaomi 17 Pro Max（`2509FPN0BC` / `popsicle`，Android 17，HyperOS）上探索背屏的全部实测结论。所有命令均在真机验证过，失败项同样记录（避免重复踩坑）。

## 1. 硬件 / 显示矩阵

| 逻辑 display | SF display id（`screencap -d` 用这个） | 分辨率 | 说明 |
|---|---|---|---|
| 0（主屏） | `4630946457447247251` | 1200×2608 | 顶部挖孔 `Rect(566,0-634,144)` |
| 1（背屏） | `4630946457447247252` | 976×596 | **左侧双摄 `x∈[0,296]` 全高**；installOrientation 90 |

- SF id 与逻辑 id 的映射：`dumpsys display` → 目标 display 的 `DisplayInfo` 行内 `uniqueId "local:<SF id>"`
- `screencap -d 0` **会失败**（`Failed to take screenshot`），必须用 SF 大 id
- 背屏 cutout：`insets=Rect(296,0-0,0)`，`cutoutSpec={… @bind_left_cutout}`
- 背屏状态机：`ON → DOZE_SUSPEND`（约 10s 无交互，键 `subscreen_display_time=10000`）

相关设置键（`settings list` 可见）：`subscreen_display_time`、`subscreen_aod_auto_off_enable`、`ime_support_subscreen`、`launch_alipay_payment_code_select_display=show_in_subscreen`（MIUI 按 Intent 路由付款码到背屏的证据）。

## 2. MIUI 的背屏启动拦截（error 102）

```console
$ adb shell am start -W --display 1 -n com.dsh.hello/.MainActivity
Error: Activity not started, unknown error code 102
```

`102 = FIRST_START_NON_FATAL_ERROR_CODE(100) + 2`；logcat 原文（`ActivityStarterImpl`，MIUI 私有改动）：

```
I ActivityStarterImpl: Launching on SubScreen via options in SUB_BUILTIN_DISPLAY
D ActivityStarterImpl: should not allow app = com.dsh.hello show on rear display
I ActivityStarterImpl: aborted activity = ... show on rear display
```

实测边界（全部打过）：

| 尝试 | 结果 |
|---|---|
| 三方包 `am start --display 1` | ❌ 102 |
| **系统设置** `com.android.settings` | ❌ 102（不是"三方 vs 系统"的区分） |
| 相机 Intent `android.media.action.STILL_IMAGE_CAMERA` | ✅ 通过（**Intent/包白名单**） |
| 白名单 Action + 三方显式组件 | ❌ 102（既查 Action 也查包） |
| `am force-stop` 副屏桌面后再 start | ❌ 102（启动路径恒拦，与桌面无关） |

结论：白名单在 ActivityStarter 层，按包/Intent 判定，无法通过 shell 参数绕过**启动**路径。

## 3. ★ 绕过：任务移动路径（本仓库 rear-switch 的核心）

启动被拦，**移动不拦**：

```powershell
adb shell am start -n <pkg>/<activity>       # 主屏正常启动
$rootId = (adb shell am stack list 中该包所在的 RootTask id)
adb shell am display move-stack <rootId> 1   # exit 0，任务上背屏
```

- `am display move-stack`（帮助文本里挂在 `am display` 子命令下，**不是** `am stack`）
- 拒绝"移到当前区域"（`Trying to move rootTask to its current taskDisplayArea`）→ 往返即重排 Z 序
- `am stack move-task <task> <root> true` 对 home 类型 root 被拒（`Attempt to move task … to rootTask 3`）

### 可见性（visible=true）问题

单次 move 后常为 `visible=false`（背屏 home RootTask 在其上）。两个必要条件：

1. **副屏桌面必须活着**：display 需要 home 根任务作底座，否则任何任务都不 resume（force-stop 后整屏无内容）
2. **往返重插**：`move-stack N 0` → `move-stack N 1` 会把根任务插到顶 → `visible=true`

系统调用等价物（MRSS 用的 Java 层）：`IActivityTaskManager.moveTaskToDisplay(taskId, displayId, toTop)`。

## 4. 唤醒 / 熄屏矩阵

| 方法 | 结果 |
|---|---|
| `adb shell input -d 1 keyevent KEYCODE_WAKEUP` | ✅ 背屏 `DOZE_SUSPEND → ON` |
| `adb shell input -d 1 tap 488 298` | ✅（`-d` 必须在子命令**前**；`input tap -d 1` 报 Invalid arguments） |
| `am broadcast -a miui.intent.action.SUB_SCREEN_ON` | ❌ `SecurityException: Permission Denial … uid=2000`（protected broadcast） |
| `dumpsys power setWakefulness 1` | ⚠️ 全局 wakefulness，只影响主屏 |
| `input keyevent KEYCODE_WAKEUP`（不带 -d） | ⚠️ 送达焦点 display（通常是主屏） |

截图节拍：熄屏 ~10s → 唤醒后 **立即**截图；或连拍（~1s/张）避开熄屏窗口。

## 5. 双屏截图

```powershell
adb exec-out screencap -d 4630946457447247252 -p > rear.png   # 背屏，纯净 PNG
adb exec-out screencap -p > x.bin                              # ⚠️ 双屏机 stdout 混入警告文本
```

不带 `-d` 时每次调用先输出：

```
[Warning] Multiple displays were found, but no display id was specified! Defaulting to the first display found…
```

该文本走 **stdout**（与 PNG 字节流混合），`adb shell`（pty）时才显示为 stderr——这是下面插件补丁的根因。

## 6. dsh-android 插件补丁（双屏警告污染）

`@zseven-w/dsh-android` 的截图路由 `screenshot()` 直接把 exec-out 输出存盘 → 文件以警告文本开头 → `pngDimensions` 解析失败 → 工具报 `unreadable PNG header`。

补丁（`node_modules/@zseven-w/dsh-android/lib/android-host.js`，切 PNG 签名前缀）：

```js
let png = await this.toolchain.execOut(serial, ['screencap', '-p'], { timeoutMs: CONTROL_TIMEOUT_MS });
// …
if (typeof png.indexOf === 'function') {
    const sig = png.indexOf(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
    if (sig > 0) png = png.subarray(sig);
}
```

画面循环不受影响（其 `PngFrameSplitter` 自带签名重同步）。注意：插件更新会覆盖此补丁。

## 7. MRSS（MiRearScreenSwitcher）逆向摘要

从 `MRSS-V2.1.0-release.apk`（`com.tgwgroup.MiRearScreenSwitcher`，Flutter + Kotlin + **Shizuku**）dex 字符串还原的工作流：

| 原语 | 用途 |
|---|---|
| `moveTaskToDisplay`（Java binder，非 shell） | App 上背屏（与本仓库 move-stack 同一系统路径） |
| `am stack list` + `getPackageNameFromTaskId` | 定位任务 |
| `am broadcast -a miui.intent.action.SUB_SCREEN_ON` | 唤醒（注意：其 Shizuku shell 身份同样会撞 protected broadcast，应有别的补充路径） |
| `dumpsys power setWakefulness 1` | 唤醒 |
| `am force-stop com.xiaomi.subscreencenter` / `am start --display 1 -n …SubScreenLauncher` | 桌面启停 |
| `wm density -d 1 <dpi>` / `wm density reset -d 1` | 背屏 DPI（本机型实测**未生效**） |
| `wm user-rotation -d` | 转屏 |
| `screencap -p -d <id>` | 截图 |
| `enable/disableSubScreenLauncher`（Shizuku 自定义事务） | 桌面开关 |
| `startRearDisplayPresentationSession` + VirtualDisplay | Presentation 会话（Flutter 侧） |

我们与 MRSS 的差异：它用 Shizuku（手机端免 PC 持久授权），本仓库纯 adb（PC/agent 端）；底层系统路径相同。

## 8. 其他坑

- **uiautomator 永不 idle**：页面含定时器动画（时钟 250ms 刷新）→ `could not get idle state`，dump 必失败；改用截图+像素分析，或停掉动画
- **wireless debugging 会话端口**（如 `:43647`）在重启 adb 服务后直接 `adb connect` 即可；配对密钥长期有效，端口变化用 `adb mdns services` 重新发现
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**：调试签名必须长期固定（keystore 放工程根目录，别放会被 clean 的 build/ 里）
- **WebView 文件加载**：`setAllowFileAccess(true)` + `setAllowFileAccessFromFileURLs(true)`；页面放 `getExternalFilesDir()/pages`，`adb push` 同用户组可写
- **Activity 启动后任务落在 display0**：`am start --display 1` 对**已存在**的实例同样拦 102 → 必须走 move
- **Task 快照 vs 实时帧**：`dumpsys` 显示 `topResumedActivity` 正常时，若截图疑似陈旧，先用页面内时间元素（时钟秒数）与真实时间比对再下结论

## 9. 参考时间线（探索顺序）

1. 无线调试 + Tailscale 直连（`adb pair` / `adb connect`）
2. dsh-android 插件接入 → 双屏警告污染 → 补丁
3. `am start --display 1` → error 102 → logcat 定位 `ActivityStarterImpl`
4. 白名单实验（相机 Intent / 系统设置 / force-stop / Action 伪装）
5. **发现 `am display move-stack` 通路** → 可见性问题 → 底座 + 往返兜底
6. MRSS 逆向 → 交叉验证 + 唤醒/密度命令
7. RearVibe 容器：WebView 安全区演进（框架内衬 → 像素拉伸 → **HTML `30.33vw` 约定**）
8. 切换条自动隐藏、NoActionBar、无 Gradle 构建流水线
