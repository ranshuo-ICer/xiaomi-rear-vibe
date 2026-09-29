# Android 开发调试实战手册（供 Agent 使用）

一次完整的实战记录：从「一台小米手机 + Tailscale」到「背屏上跑着热部署的番茄钟」。所有命令都在真机（Xiaomi 17 Pro Max / Android 17 / HyperOS）验证过，失败路径同样标注——照着做可以跳过我们踩过的全部坑。

相关文档：[背屏研究笔记](REAR-SCREEN-RESEARCH.md)（MIUI 底层机制）· [README](../README.md)（RearVibe 使用）

---

## 0. 环境与连接

### 0.1 无线 adb（跨网络，推荐 Tailscale）

```powershell
# 手机：设置 → 开发者选项 → 无线调试 → 显示配对码/IP端口
adb pair <ip>:<配对端口> <六位配对码>     # 只需一次，密钥长期有效
adb connect <ip>:<连接端口>               # 每次会话
adb mdns services                          # 端口变了就用它重新发现
```

- **配对一次终身有效**；adb server 被杀（本机服务重启）后直接 `adb connect` 即可
- 连接端口与配对端口不同、且可能变化；无线调试和 Tailscale 让「手机在口袋里、agent 在电脑上」成为常态
- 掉线重连三板斧：`adb connect` → 失败则 `adb kill-server; adb start-server` → 再连；仍失败让人类看一眼无线调试是否被系统关了

### 0.2 MIUI 必开开关（少一个都会报特定错误）

| 开关 | 不开的症状 |
|---|---|
| 无线调试 | 没有 adb |
| **USB 调试（安全设置）** | `SecurityException: ... INJECT_EVENTS` —— `input tap/keyevent` 全被拒（需登录小米账号 + 插 SIM） |
| USB 安装 | `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` |

### 0.3 工具链（无 Gradle 流水线）

```powershell
winget install Google.PlatformTools          # adb
winget install EclipseAdoptium.Temurin.21.JDK # JAVA_HOME
sdkmanager "platforms;android-35" "build-tools;35.0.0"   # ANDROID_HOME
```

五步构建（本仓库 `app/build.ps1` 完整实现，~10 秒完成）：

```
aapt2 link（manifest，无需资源）→ javac → d8 → jar uf classes.dex → apksigner
```

**签名规则（血泪）**：
- keystore 固定放**工程根目录**，绝不能放进会被 `Remove-Item` 清理的 `build/`
- 与已装 App 冲突时**不要卸载重装**（`uninstall` 会连 `/sdcard/Android/data/<pkg>/` 的用户数据一起删！），用取证法找匹配的 key：

```powershell
adb shell pm path <pkg>          # 拉下已装 APK
adb pull <path> installed.apk
apksigner verify --print-certs installed.apk          # 记下 SHA-256
keytool -list -keystore candidate.jks -storepass android   # 对比指纹
```

---

## 1. 热部署遥控协议（RearVibe 模式，Web 类 App 通用）

**核心思想：内容与容器分离** —— HTML 是数据（放外部目录，`adb push` 热更），容器 App 只提供 WebView + 广播接收器。

```powershell
# 推页面（无需重装）
adb push page.html /sdcard/Android/data/<pkg>/files/pages/
# 遥控（广播不受 ActivityStarter 拦截，是背屏场景的天然控制通道）
adb shell am broadcast -n <pkg>/.VibeReceiver -a dsh.vibe.LOAD --es page page.html
adb shell am broadcast -n <pkg>/.VibeReceiver -a dsh.vibe.REFRESH
```

要点：
- 接收器 `exported=true` + 显式组件广播（`-n pkg/.Receiver`）
- 容器进程活着时走静态引用直调（`runOnUiThread`），死了才冷启动——避免广播触发 Activity 启动被 MIUI 拦
- **列表类数据在命令处理时重新扫描磁盘**（我们踩过：LOAD 用旧列表找不到新推的文件，静默回退）

---

## 2. MIUI/HyperOS 特有限制速查

| 症状 | 原因 | 解法 |
|---|---|---|
| `am start --display 1` → `unknown error code 102` | ActivityStarter 包级白名单（系统设置都被拒，相机 Intent 放行） | **启动不拦、移动不拦**：`am display move-stack <rootTaskId> 1` |
| 移动后 `visible=false` | 副屏 home 在上 / 没有底座 | ①桌面必须活着作底座（否则永不 resume）②往返搬移 `move 0 → move 1` 重排到顶 |
| App 过一会儿被桌面盖住 | 桌面进程自动重启（~1s） | **先 move 后**循环 `am force-stop com.xiaomi.subscreencenter`（每 1-2s，单次无效） |
| 背屏 ~10s 息屏回 AOD | `subscreen_display_time=10000` | 单次 `input -d 1 keyevent KEYCODE_WAKEUP` 只延后一次；**循环发**（200ms）永久保持 |
| `am broadcast -a miui.intent.action.SUB_SCREEN_ON` 被拒 | protected broadcast（方向是 系统→应用，shell 不能发） | 用 `input -d 1 keyevent KEYCODE_WAKEUP` |
| `wm density -d 1 320` 静默无效 | **参数顺序** | `wm density 320 -d 1`（`<dpi>` 在 `-d` 前！写反只回显 Physical density） |
| `input tap -d 1` → Invalid arguments | `-d` 必须在子命令**前** | `input -d 1 tap x y` |
| 双屏机截图开头是 `[Warning] Multiple displays…` | `screencap` 往 stdout 混警告 | 显式 `-d <SF id>`；SF id 从 `dumpsys display` 的 `uniqueId "local:<id>"` 解析（**`-d 0` 会失败，必须用 SF 大 id**） |

底层实现（验证自 [MRSS 源码](https://github.com/AntiOblivionis/MiRearScreenSwitcher)）：`moveTaskToDisplay` = `service call activity_task 50 i32 <taskId> i32 <displayId>`，`am display move-stack` 是其封装。

---

## 3. 调试工具箱：症状 → 命令

### 3.1 启动/操作被系统拒绝 → 去 logcat 找"判决书"

```powershell
# 先清日志 → 复现 → 只看系统组件的判决行
adb shell logcat -c
adb shell am start -W --display 1 -n <pkg>/.MainActivity   # 拿到错误码
adb shell logcat -d | Select-String "ActivityStarter|ActivityTaskManager"
# 输出示例: D ActivityStarterImpl: should not allow app = xxx show on rear display
```

**错误码不认识就查 AOSP**：`unknown error code N` 的机制是 `N = FIRST_START_NON_FATAL_ERROR_CODE(100) + 常量序号`，web 搜索错误串 + `ActivityManagerShellCommand.java` 可定位语义。

### 3.1b 状态查询 dumpsys 速查

| 想知道 | 命令 |
|---|---|
| 任务在哪个 display、可见吗 | `adb shell am stack list`（RootTask 行 + 缩进的 taskId 行） |
| 谁 resumed / 顶层 | `dumpsys activity activities` → `Display #N` 段 + `topResumedActivity` |
| 窗口焦点 / 显示屏状态 | `dumpsys window \| grep mCurrentFocus`、`dumpsys display`（`state ON/DOZE_SUSPEND`、cutout、uniqueId） |
| 包信息 | `dumpsys package <pkg>`（`lastUpdateTime` 验证安装是否生效！签名冲突时它不动） |
| 进程活着吗 | `ps -A \| grep <pkg>` |

### 3.2 截图与**客观验证**（比肉眼可靠）

```powershell
# 背屏截图（SF id 自动解析见上表）
adb exec-out screencap -d <SF_ID> -p > shot.png
# 熄屏风险：唤醒后立即截，或 ~1s 连拍避开超时窗口
```

**两条铁律**（我们肉眼误判了两次白屏/时间，被人类纠正）：
1. **图片"看起来不对"时改用像素统计**：亮度分区域均值、特定色像素计数、包围盒测量——结论可证伪：

```powershell
# 例：统计左安全区 vs 内容区亮度、找黄色时钟像素包围盒
Add-Type -AssemblyName System.Drawing; $b=[Bitmap]::FromFile("shot.png")
# 采样 GetPixel → 分区求 luminance 均值 / 条件计数
```

2. **截图可能是任务快照或陈旧帧**：用页面里的**时间元素**（时钟秒数）对比真实时间判断新旧，再下结论。

### 3.3 UI 自动化的可用与不可用

| 工具 | 何时可用 |
|---|---|
| `uiautomator dump` | **页面无定时动画时**（时钟 250ms 刷新 → `could not get idle state` 永远失败）。资源用 `resource-id`，文本用 `text` |
| `input -d <display> tap x y` | 通用注入；坐标用物理像素（除以 display 物理分辨率），指定 `-d` 才不会打到别的屏 |
| OCR 类工具 | 取决于宿主（如 dsh-android 在 Windows 上 OCR 不可用，仅 macOS Vision） |
| 像素分析 | 永远可用，动画页面的最后手段 |

### 3.4 注入前守则（真机 = 真实后果）

```
tap 之前：
1. dumpsys activity activities → Display#N 段确认目标 App 是 topResumedActivity
2. 注入必须带 -d <display>（焦点可能在另一个显示屏的别的 App 上！）
3. 没法确认 → 停下来报告，绝不猜坐标
```

我们靠这条闸门拦住了两次误击（一次差点点进 Edge 对话页）。

---

## 4. App 生命周期/可见性问题的排查顺序

```
截图黑白/冻结 → 1) 页面时间元素 vs 真实时间（快照 or 陈旧帧?）
              → 2) ps -A（进程活着?）
              → 3) Display#N topResumedActivity（resumed?）
              → 4) am stack list visible=（可见?）
              → 5) dumpsys display state（屏醒着?）
```

常见结论链：屏睡了→唤醒循环；visible=false→底座/往返；resumed 是桌面→先 move 再杀桌面；全绿但画面旧→快照/截帧问题。

---

## 5. 逆向 → 源码 的验证方法论

拿到一个闭源 App 想学它怎么做到的：

1. `aapt2 dump badging x.apk`（包名/权限/组件）+ 列 zip 找 `classes.dex` / `lib/*/libapp.so`
2. dex 用字节流抽可打印串，按关键词过滤（`am `、`display`、`shell`、`service call`…）——能还原 80% 的命令面
3. Flutter App 注意 **Dart 逻辑在 libapp.so 不在 dex**
4. **结论要标注证据等级**：dex 串只能说明"存在"，行为要靠（a）真机实验（b）找到源码仓库后对照
5. 我们的教训：dex 里见过 `am broadcast SUB_SCREEN_ON`，**clone 源码后发现是死代码**（源码只监听不发送）——重要结论前先搜有没有开源实现

---

## 6. PowerShell 坑（agent 脚本高频踩）

| 坑 | 症状 | 解法 |
|---|---|---|
| 单元素集合变标量 | `$found[0]` 取到**字符串首字符**（`"1"`），下游 `adb -s 1` 全崩 | `@(...)` 强制数组 |
| `"$var:"` 插值 | `The string is missing the terminator` / 变量引用无效 | `"${var}:"` |
| adb shell 里混入 PS 管道 | `Select-String: inaccessible or not found` | PS 管道只写在宿主侧，别拼进 shell 字符串 |
| 原生命令 stderr | 2>&1 后是 ErrorRecord 对象，`Select -Last 1` 可能显示空 | 先 `$out = ... 2>&1` 再逐行格式化 |
| 执行策略 | `running scripts is disabled` | 进程级 `Set-ExecutionPolicy -Scope Process Bypass -Force` |
| 嵌套引号 cmd 重定向 | 解析错误 | 二进制重定向用 `cmd /c "adb ... > file"`，路径加引号 |

---

## 7. 端到端工作流模板（照抄即可）

```powershell
# 0) 连接
adb pair <ip>:<pport> <code>; adb connect <ip>:<cport>; adb devices

# 1) 写 + 构建 + 装（keystore 固定在工程根）
pwsh app/build.ps1; adb install -r app\build\*.apk
adb shell dumpsys package <pkg> | findstr lastUpdateTime   # 确认装上了

# 2) 上背屏
pwsh scripts/rear-switch.ps1 -Package <pkg> -Activity .MainActivity -KeepAwake

# 3) 遥控 + 客观验证
adb shell am broadcast -n <pkg>/.VibeReceiver -a dsh.vibe.LOAD --es page x.html
adb shell input -d 1 keyevent KEYCODE_WAKEUP
adb exec-out screencap -d <SF_ID> -p > check.png    # 像素分析确认内容

# 4) 注入交互（带守卫）
#    dumpsys activity activities 确认 topResumedActivity == 目标
adb shell input -d 1 tap <x> <y>
```

出错就回到 §3 对应小节，按「错误串 → logcat 判决行 → AOSP/源码语义」三步走。
