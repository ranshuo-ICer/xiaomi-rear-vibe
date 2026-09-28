# xiaomi-rear-vibe

**小米 17 系列背屏（Rear Screen）的 vibe coding 框架** —— 把任意 HTML 页面热推到背面屏上运行，外加一个能把**任意 App** 放上背屏的 adb 脚本（绕过 MIUI 限制）。

> 面向读者：AI agent / 开发者。读完即可独立操作：推页面、切页面、把任何 App 搬上背屏、以及按约定编写新页面。

**实测设备**：Xiaomi 17 Pro Max（`2509FPN0BC`，Android 17 / HyperOS）。副屏 976×596，左侧双摄开孔 296px。

---

## TL;DR（5 条命令上手）

```powershell
# 0) 手机开启：无线调试 + USB调试(安全设置)，然后
adb connect <手机IP>:43647          # 端口看 设置→开发者选项→无线调试

# 1) 安装容器 App（预构建 APK 在 dist/）
adb install -r dist/rearvibe.apk

# 2) 推一个 HTML 页面（无需重装 App）
adb push pages/clock.html /sdcard/Android/data/com.dsh.rearvibe/files/pages/

# 3) 刷新并搬上背屏
adb shell am broadcast -n com.dsh.rearvibe/.VibeReceiver -a dsh.vibe.REFRESH
pwsh -File scripts/rear-switch.ps1 -Package com.dsh.rearvibe -Activity .MainActivity

# 4) 切换页面（之后随时）
adb shell am broadcast -n com.dsh.rearvibe/.VibeReceiver -a dsh.vibe.NEXT
```

---

## 仓库结构

```
├── README.md                  ← 你在这里
├── app/                       RearVibe 容器框架源码 + 无 Gradle 构建脚本
│   ├── src/main/…             MainActivity.java, VibeReceiver.java, Manifest
│   └── build.ps1              aapt2+javac+d8+apksigner 一键构建
├── pages/                     4 个示范页面（也是编写约定的活例子）
│   ├── clock.html             实时时钟（黑底 + 扫描线）
│   ├── counter.html           计数器（渐变底 + 点击涟漪 + localStorage）
│   ├── vibe.html              极光动效页
│   └── welcome.html           说明书页（含速查命令）
├── scripts/
│   └── rear-switch.ps1        ★ 任意 App 上背屏（MIUI error 102 绕过）
├── examples/
│   └── hello-app/             演示"任意 App"的最小例子（原生计数器）
├── docs/
│   └── REAR-SCREEN-RESEARCH.md ★ 逆向研究笔记（MIUI 策略 / MRSS / 全部命令）
└── dist/
    ├── rearvibe.apk           预构建容器
    └── hello.apk              预构建示例
```

---

## 1. RearVibe：HTML 容器

一个全屏 WebView 容器，常驻背屏。**页面 = 手机目录里的 `.html` 文件**，增删改推文件即生效，永不重装。

- 页面目录：`/sdcard/Android/data/com.dsh.rearvibe/files/pages/`（按文件名排序循环）
- 切换记忆：当前页写入 SharedPreferences，重启不丢
- 遥控广播接收器 `VibeReceiver`（`adb` 可达 = 任何 agent 可达）

### 遥控命令

| 命令 | 作用 |
|---|---|
| `adb push x.html <页面目录>/` | 热更新 / 新增页面 |
| `… -a dsh.vibe.REFRESH` | 重扫目录并重载当前页（推完必发） |
| `… -a dsh.vibe.NEXT` / `PREV` | 下一页 / 上一页 |
| `adb shell am broadcast -n com.dsh.rearvibe/.VibeReceiver -a dsh.vibe.LOAD --es page x.html` | 指定页 |

广播完整形式：`adb shell am broadcast -n com.dsh.rearvibe/.VibeReceiver -a <ACTION>`

### 设备端交互（无 adb 时）

- **音量上/下** = 翻页
- 切页时底部浮现切换条（`◀ 2/4 counter.html ▶ ⟳`），2.2 秒后自动淡出——平时完全隐藏
- `FLAG_KEEP_SCREEN_ON` 已开；但 MIUI 背屏约 10s 无操作回 AOD（`subscreen_display_time`），唤醒见 §3

---

## 2. HTML 编写约定（★ 核心，新页面必须遵守）

### 坐标系

| 视角 | 数值 |
|---|---|
| 物理屏 | 976 × 596 |
| 摄像头开孔 | **左侧 x∈[0,296] 物理 px**（全高，双摄） |
| WebView CSS 视口 | ≈ 347 × 212（`window.innerWidth/Height`），**1 CSS px ≈ 2.8125 物理 px** |

### 三条规则

1. **背景全幅随意画**——直接盖到摄像头区下面（开孔区域画了也看不见，但渐变/纹理能自然延续）
2. **内容放进 `.safe` 容器**——`left: 30.33vw`（= 296/976，百分比与密度无关，永不失准）
3. **底部预留 `8vh`**——切换条浮现时不挡内容

### 页面模板

```html
<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
  :root{--cam:30.33vw;--bar:8vh}      /* 30.33vw = 296/976 摄像头区 */
  *{box-sizing:border-box}
  html,body{margin:0;height:100%;overflow:hidden;font-family:system-ui,sans-serif}
  body{background:linear-gradient(135deg,#0f172a,#1e3a5f)}   /* 全幅背景 */
  .safe{position:fixed;top:0;right:0;bottom:var(--bar);left:var(--cam);
        display:flex;flex-direction:column;align-items:center;justify-content:center;
        text-align:center;color:#e2e8f0}
</style>
</head>
<body>
<div class="safe">
  <!-- 内容只写在这里（可用区域约 242×195 CSS px） -->
</div>
<script>
  // JS 正常可用：localStorage / fetch CDN（INTERNET + cleartext 已开）/ 定时器
</script>
</body>
</html>
```

**尺寸感**：可用区约 `242×195` CSS px。大标题 ≤ 50px、巨字时钟 ≤ 62px，否则左右溢出（溢出会被 `overflow:hidden` 裁掉）。

范例参考：`pages/clock.html`（安全区排版）、`pages/counter.html`（交互+持久化）、`pages/vibe.html`（背景动效全幅、文字入安全区）。

---

## 3. 把任意 App 放上背屏（rear-switch.ps1）

MIUI 拦截第三方 `am start --display 1`（**error 102**），但**不拦任务移动**。脚本流程：

```
唤醒背屏 → 保证副屏桌面存活(底座) → 主屏正常启动目标 → 定位 RootTask
        → am display move-stack <id> 1   ← 核心绕过
        → 可见性兜底(往返搬移) → 截图验证
```

```powershell
# 任意包名（原生 App、你自己的工程、系统工具都行）
pwsh -File scripts/rear-switch.ps1 -Package com.example.myapp -Activity .MainActivity

# 还原：拉回副屏桌面
pwsh -File scripts/rear-switch.ps1 -Restore

# 参数：-Adb <路径>  -Serial <序列号>  -RearDisplay 1  -RearSfId <SF显示id>  -NoCapture
# 未指定 Serial 时自动取唯一在线设备；SF 显示 id 自动从 dumpsys display 解析。
```

**前置条件**（都在手机上，一次配置）：

| 开关 | 位置 | 用途 |
|---|---|---|
| 无线调试 | 开发者选项 | adb 免 USB 连接（Tailscale 局域网外也可用 `adb connect <ip>:<port>`） |
| **USB调试（安全设置）** | 开发者选项 | 没它 `input tap/keyevent` 抛 `SecurityException: INJECT_EVENTS`（需登录小米账号+插卡） |
| USB 安装 | 开发者选项 | 没它 `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED` |

**唤醒背屏**（脚本内置，手动用法）：

```powershell
adb shell input -d 1 keyevent KEYCODE_WAKEUP     # ✅ 有效
# ❌ am broadcast -a miui.intent.action.SUB_SCREEN_ON → shell 被权限拒绝
# ⚠️ dumpsys power setWakefulness 1 → 只唤醒主屏
```

**手搓版（不用脚本时的核心两行）**：

```powershell
adb shell am start -n <pkg>/<activity>          # 主屏启动，拿 RootTask id
adb shell am stack list                          # 找到该包所在的 RootTask id=N
adb shell am display move-stack N 1              # 搬到背屏（display 1）
```

完整研究（为什么 `force-stop` 没用、为什么相机 Intent 能过、往返可见性兜底原理…）见 [docs/REAR-SCREEN-RESEARCH.md](docs/REAR-SCREEN-RESEARCH.md)。

---

## 4. 从源码构建

无 Gradle、无 Android Studio，五件套流水线（`aapt2 → javac → d8 → jar → apksigner`）：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"            # JDK 17+
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"     # sdkmanager "platforms;android-35" "build-tools;35.0.0"
pwsh -File app/build.ps1        # → app/build/rearvibe.apk
pwsh -File examples/hello-app/build.ps1
```

keystore 固定在各工程根目录 `debug.jks`（**不要提交**，已 gitignore；丢了就只能卸载重装）。

---

## 5. 故障排查

| 症状 | 原因 / 解法 |
|---|---|
| `Error: Activity not started, unknown error code 102` | MIUI 拒绝第三方上背屏 → 用 `rear-switch.ps1`（move-stack 路径） |
| 背屏黑/AOD，`screencap` 出纯黑小图 | 背屏息屏 → `input -d 1 keyevent KEYCODE_WAKEUP`；连拍避开 10s 超时 |
| `SecurityException … INJECT_EVENTS` | 开「USB调试（安全设置）」 |
| `adb devices` 找不到设备 | `adb connect <ip>:<port>`；配对只做一次，重启 adb 服务后仍有效 |
| 任务在背屏但 `visible=false` | rear-switch 自动往返兜底；手动 `move-stack N 0` 再 `move-stack N 1` |
| 截图开头是 `[Warning] Multiple displays…` 文本 | 双屏机 `screencap` 往 stdout 混警告；用 `-d <SF id>` 显式指定可消除 |
| `uiautomator dump … could not get idle state` | 页面定时动画导致永不 idle（如时钟页）→ 停动画/用截图分析 |
| `input tap -d 1 … Invalid arguments` | 参数顺序：`input -d 1 tap x y`（`-d` 在子命令**前**） |
| 用了 dsh-android 插件截图乱码 | 该插件受双屏警告污染，需打补丁（见研究笔记 §6） |

---

## Credits & License

- 背屏逃逸思路逆向自 [MiRearScreenSwitcher (MRSS)](https://github.com/)（Shizuku 方案）——本仓库用纯 adb 复刻并扩展
- 实时画面/自动化：[dsh-android](https://github.com/ZSeven-W/dsh-android) 插件
- MIT © 2026 ranshuo-ICer
