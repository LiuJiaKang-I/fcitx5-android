# 构建环境踩坑记录

## 系统依赖

`extra-cmake-modules` 和 `gettext` 是 Fcitx5 CMake 配置的硬依赖。如果缺失，CMake configure 阶段会报 `Could not find ECM` 或 `Could not find Gettext`，但报错位置在 native 构建任务中，容易被 Kotlin 编译的日志淹没。务必在 `apt-get install` 时一并安装，不要等 CMake 报错再补装。

## clean 后必须清除 .cxx 缓存

`./gradlew :app:clean` 只清理 `app/build/`，不会清理 `lib/fcitx5/.cxx`、`lib/libime/.cxx` 等子模块的 CMake 缓存。如果 CMake 配置发生了环境变化（如新装了 ECM/Gettext），旧的 `.cxx` 缓存会导致 configure 使用过期的 CMakeCache.txt，依然报错。正确做法：

```bash
rm -rf lib/fcitx5/.cxx lib/fcitx5-lua/.cxx lib/fcitx5-chinese-addons/.cxx lib/libime/.cxx app/.cxx
```

## `-Pandroid.ndkBuildAbiFilter=arm64-v8a` 对 lib 模块无效

该参数只影响 `app` 模块的 APK 打包（只打入指定 ABI 的 .so），但 `lib/fcitx5`、`lib/libime` 等 native 库模块仍然会编译所有 4 个 ABI。因此首次编译时间不会因该参数显著缩短。这是项目构建脚本的设计决定，不是 Gradle 的默认行为。

## 首次全量编译耗时约 40-60 分钟

沙箱环境（4 核）实测：从 `git submodule update` 到 `BUILD SUCCESSFUL`，包含 CMake configure + 4 ABI native 编译 + Kotlin 编译 + APK 打包，总计约 40-60 分钟。其中 native 编译占 80% 以上时间。增量编译（只改 Kotlin 代码）约 2-3 分钟。

## 编译日志追踪方法

每次编译必须将日志重定向到临时文件，方便在另一个终端中 `tail -f` 实时监控：

```bash
# 编译命令模板（后台运行，日志写入 /tmp/build-{id}.log）
./gradlew :app:assembleDebug ... 2>&1 | tee /tmp/build-1.log

# 在另一个终端监控
tail -f /tmp/build-1.log
```

- 日志文件命名用递增 id（`/tmp/build-1.log`、`/tmp/build-2.log`），避免多次编译覆盖同一文件
- 用 `tee` 而非纯重定向，保证 tee 命令本身能拿到退出码判断成功/失败
- Gradle daemon 日志位于 `/root/.gradle/daemon/<version>/daemon-<pid>.out.log`，可通过 `grep "FAIL\|SUCCESS" <log>` 快速判断构建结果
- 如果 `ps aux | grep java` 显示 Gradle 进程 CPU 为 0% 且长时间无新文件产出，大概率是构建已失败但后台命令的输出流未回传，应检查 daemon 日志

## `./gradlew --stop` 清理 daemon 缓存

如果连续执行多次构建（特别是失败后重试），可能有多个 Gradle daemon 进程残留，占用内存。用 `./gradlew --stop` 统一清理后再启动新构建，避免 daemon 间冲突。

## 旧 APK 不会自动覆盖

`app/build/outputs/apk/debug/` 下的 APK 文件名包含版本号 hash（如 `org.fcitx.fcitx5.android-e7bd5056-arm64-v8a-debug.apk`）。如果版本号变了会生成新文件，旧文件不会自动删除。如需确认当前 APK 是最新编译的，用 `ls -lh` 检查时间戳。

## 子模块状态检查

编译前务必执行 `git submodule status` 确认子模块已检出。前缀 `-` 表示未初始化，`+` 表示指向的 commit 与主仓库记录不一致。两者都需要 `git submodule update --init --recursive` 修复。
