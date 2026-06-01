## 项目概述

fcitx5-android 是将 Fcitx5 输入法框架移植到 Android 平台的开源输入法应用，包名 `org.fcitx.fcitx5.android`。支持中文拼音/双拼/五笔/仓颉、注音、粤拼、越南语、日语、韩语、僧伽罗语、泰语等多种输入法，以及 RIME 通用输入方案。

## 技术栈

| 层面 | 技术 |
|------|------|
| 语言 | Kotlin（Android 层）、C/C++（Native 引擎层） |
| 构建 | Gradle (Kotlin DSL) + CMake (NDK native 构建) |
| 构建约定 | 自定义 Convention Plugins（`build-logic/convention/`） |
| Android | InputMethodService、Room、ViewBinding、DataBinding |
| IPC | AIDL（`IFcitxRemoteService`、`IClipboardEntryTransformer`） |
| 异步 | Kotlin Coroutines + SharedFlow/Channel |
| 日志 | Timber |
| 许可证 | LGPL-2.1-or-later |

## 目录结构

```
fcitx5-android/
├── app/                    # 主应用模块（IME 服务 + UI + JNI 桥接）
├── lib/                    # 核心库层
│   ├── common/             # 公共 AIDL 接口 + 插件服务基类
│   ├── fcitx5/             # Fcitx5 核心 C++ 源码（git submodule）+ prefab 打包
│   ├── fcitx5-lua/         # Fcitx5 Lua 插件支持（submodule）
│   ├── fcitx5-chinese-addons/ # 中文输入插件（submodule）
│   ├── libime/             # LibIME 输入法引擎库（submodule）
│   └── plugin-base/        # 插件开发 SDK（发布到 GitHub Packages）
├── plugin/                 # 外部输入法插件（独立 APK）
│   ├── anthy/              # 日语 Anthy
│   ├── chewing/            # 注音 Chewing
│   ├── clipboard-filter/   # 剪贴板过滤器（ClearURLs）
│   ├── hangul/             # 韩语 Hangul
│   ├── jyutping/           # 粤拼 Jyutping
│   ├── rime/               # RIME 通用输入
│   ├── sayura/             # 僧伽罗语 Sayura
│   ├── thai/               # 泰语
│   └── unikey/             # 越南语 UniKey
├── codegen/                # 编译时代码生成（按键映射、扫描码映射）
├── build-logic/            # Gradle Convention Plugins
└── .env.sh                 # 编译环境变量
```

## 关键入口 / 核心模块

- **IME 入口**: `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt`
- **引擎封装**: `app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt`
- **守护进程**: `app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt`
- **JNI 桥接**: `app/src/main/cpp/native-lib.cpp`
- **Native 前端**: `app/src/main/cpp/androidfrontend/`（Fcitx5 addon，实现 AndroidInputContext）
- **插件接口**: `lib/common/` 中的 AIDL 定义

## 运行与预览

本项目为 Android 应用，不支持 Web 预览。编译和运行需要 Android SDK + NDK 环境。

### 编译环境搭建（沙箱环境已验证）

环境变量文件 `.env.sh` 已存在于项目根目录，内容：

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/opt/android-sdk
export ANDROID_SDK_ROOT=/opt/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
export JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport"
export GRADLE_OPTS="-XX:-UseContainerSupport"
```

**从零搭建步骤：**

1. **安装 JDK 21 + 构建依赖**
   ```bash
   apt-get update && apt-get install -y openjdk-21-jdk-headless extra-cmake-modules gettext
   ```
   > `extra-cmake-modules` 和 `gettext` 是 Fcitx5 CMake 配置必需的，缺少会导致 CMake configure 阶段失败。

2. **安装 Android SDK**
   ```bash
   mkdir -p /opt/android-sdk/cmdline-tools/latest
   cd /tmp
   curl -sL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o cmdline-tools.zip
   unzip -q cmdline-tools.zip -d /opt/android-sdk/cmdline-tools-tmp
   mv /opt/android-sdk/cmdline-tools-tmp/cmdline-tools/* /opt/android-sdk/cmdline-tools/latest/
   ```

3. **接受许可证并安装 SDK 组件**
   ```bash
   source .env.sh
   yes | sdkmanager --licenses
   sdkmanager --install \
     "platforms;android-36" \
     "build-tools;36.1.0" \
     "platform-tools" \
     "ndk;28.0.13004108" \
     "cmake;3.31.6"
   ```

4. **初始化 Git Submodules**（Native 代码依赖子模块）
   ```bash
   git submodule update --init --recursive
   ```

5. **编译**
   ```bash
   source .env.sh
   ./gradlew :app:assembleDebug
   # 或指定 ABI 加速编译：
   # ./gradlew :app:assembleDebug -Pandroid.ndkBuildAbiFilter=arm64-v8a
   ```

6. **产出物**
   - Debug APK: `app/build/outputs/apk/debug/org.fcitx.fcitx5.android-*-debug.apk`
   - 4 个 ABI: arm64-v8a, armeabi-v7a, x86, x86_64

### 关键版本信息

| 组件 | 版本 |
|------|------|
| Gradle | 9.2.0 (wrapper) |
| Kotlin | 2.3.21 |
| AGP | 9.2.0 |
| compileSdk | 36 |
| minSdk | 23 |
| NDK | 28.0.13004108 |
| CMake | 3.31.6 |
| Build-Tools | 36.1.0 |
| JDK | 21 |

### 编译注意事项

- `JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport"` 和 `GRADLE_OPTS="-XX:-UseContainerSupport"` 是必需的，否则在容器环境中 JVM 会因内存检测异常而崩溃
- 首次编译耗时较长（CMake 需要编译 Fcitx5 C++ 核心 + 所有 addon），后续增量编译只编译 Kotlin 层，速度较快
- 如需加速，可用 `-Pandroid.ndkBuildAbiFilter=arm64-v8a` 只编译单个 ABI

## 用户偏好与长期约束

- 许可证为 LGPL-2.1-or-later，修改 Fcitx5 核心代码需遵守 LGPL 条款
- 插件必须与主应用同签名才能通过 AIDL 通信（`protectionLevel=signature`）
- 项目使用 `pnpm` 无关（非 Node.js 项目），Python 脚本仅用于辅助工具

## 详细知识库

踩坑记录和操作指南按类别存放在 `.agents/` 目录下：

| 文件 | 内容 | 适用场景 |
|------|------|----------|
| `.agents/build-pitfalls.md` | 构建环境踩坑记录 | 搭建环境、排查编译失败 |
| `.agents/dev-pitfalls.md` | 代码开发踩坑记录 | 修改 UI/自定义 View/Android API |
| `.agents/storage.md` | 对象存储使用指南 | 上传/删除 APK、获取下载链接 |

## 常见问题和预防

- **编译报 JDK 版本错误**: 确保使用 JDK 21，`JAVA_HOME` 指向 `/usr/lib/jvm/java-21-openjdk-amd64`
- **CMake 找不到 ECM/Gettext**: 安装 `extra-cmake-modules` 和 `gettext`（`apt-get install -y extra-cmake-modules gettext`）
- **CMake 找不到 NDK**: 确认 `ANDROID_HOME` 和 `ANDROID_SDK_ROOT` 都指向 `/opt/android-sdk`
- **子模块缺失**: 执行 `git submodule update --init --recursive`
- **容器内 JVM 崩溃**: 确认 `JAVA_TOOL_OPTIONS` 和 `GRADLE_OPTS` 包含 `-XX:-UseContainerSupport`
- **单 ABI 编译加速**: 加参数 `-Pandroid.ndkBuildAbiFilter=arm64-v8a`
