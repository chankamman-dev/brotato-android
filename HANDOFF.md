# Brotato（土豆兄弟）原生 Android 移植：交接

仓库：https://github.com/chankamman-dev/brotato-android （main）。签名钥匙 `signing/`（keystore 和密码）不入库，只在这台机器上；丢了就没法覆盖升级 Thor 上的安装，要另外备份。

2026-11-07 19:30 搬家（用户统一整理安卓移植项目）：源码从 `G:\ai liabary\brotato-android` 移到 `G:\ai\android-recompile\brotato-android`（signing/ 一起），大件从 `E:\brotato-android-work` 移到 `E:\android-recompile-work\brotato-android-work`。顺带删了已被替代的测试包（旧的内置调试包、-0110/-quick/-quick2/-ach）和临时目录 so-check；保留 -ach2（Thor 上在用）、-quick3、最新的 release 和调试包、空壳包。搬完在新位置重新构建，结果和 -ach2 逐字节相同。

2026-11-07 01:25 **Thor 实测：从 Steam 下载游戏、快速启动都通过了。** Thor 上是 01:33 装的 `out/Brotato-android-release-quick3.apk`：直接进游戏；连 Steam 单次 8 秒超时、重试 3 次；游戏读存档前最多等 12 秒；修了 quick2 里"Steam 不显示在线"的问题。01:33 实测：云存档 3.3 秒完成，读存档前不用等，启动后约 6 秒 Steam 显示正在玩（playing 1942280）。
- **15:41 起 Thor 上是 `-ach2`**：在 quick3 的基础上加了成就解锁提示，并改成**只用 WebSocket 连 Steam**。之前用 JavaSteam 默认的 TCP+WebSocket 时，Clash 下 TCP 连接按裸 IP 走兜底规则，经常断开或超时（15:37 连续 9 次失败）；和以撒移植 steam.9 的结论一样。改完后登录 2.7 秒，在线状态正常。所有会话共用一份 `SteamConfiguration`（服务器列表持久化到 `files/steam-servers.bin`，连不上的服务器会被标记）。
- 首次下载：262 MB 约 63 秒（约 4 MB/s），中间有一块被 CDN 回 503，重试补上，校验通过，进了游戏。
- 启动耗时（点图标 → 游戏读存档）：最初约 32 秒 → 提速版约 16–20 秒 → 直接进游戏版约 **4–6 秒**。详见"启动速度"一节。
- 用户反馈过的问题：每次都要走一遍 Steam 登录等流程，太慢。现在平时打开不显示启动页。
- 一次失败：装完第一次打开时，那一分钟内所有到 Steam 的连接都超时（Thor 走 Clash 的 tun），游戏等满 20 秒后用本机存档进了游戏；一分钟后恢复。quick2 针对这种情况缩短了等待。

2026-11-07 00:35 **安装包不再内置游戏**（约 24 MB）：首次启动从 Steam 下载 `Brotato.pck`（281 MB），之后每次启动跟 Steam 对比、增量更新；也可以导入玩家自己从电脑复制来的 pck。详见"从 Steam 下载/更新游戏"一节。模拟器验证了缺游戏页、投放目录导入、以主包方式启动到标题。

2026-11-06 23:44 创意工坊在 Thor 上实测通过（用户同意后装的 23:39 包）：
- 订阅列表读到 3 个，3 个都从 Steam CDN 下载成功（共约 10 秒），ModLoader `DONE: Setup 3 mods`。
- GMO3（3779529366）和免费刷新（2958802806）正常加载。
- 重打这一波（3169210497）的场景引用了 `res://resources/fonts/actual/base/font_biggest.tres` 和 `font_big_title.tres`，1.2.0.1 的 pck 里没有这两个文件，是模组本身和当前版本不兼容（PC 上一样），不是移植问题。
- 成就修复也确认了：启动时重报 33 个，`already on Steam: 33`，没有任何 `unlocking`。
- 新启动页已经在 Thor 上跑过，用户还没反馈观感。
- 之后又做了一个小改动（插件启动时建 `files/logs`，消掉 ModLoader 每行一条的 "File must be opened before use"），23:48 的包已打好，**没装 Thor**。

2026-11-06 第一阶段完成：PC 版 Brotato 1.2.0.1 在 lime-sim 模拟器上跑到了标题画面，调试包和发布包都验证过（`shots/`）。点击 Start 能进角色选择，说明触屏可以操作菜单。

2026-11-06 01:45 第三阶段：用户授权后装到了 Thor，并扫码实测。登录、下载 PC 存档、上传、时长、DLC 所有权都正常。成就的位号读错了，误解锁了 `chal_farmer`。

2026-11-06 01:54 用户同意"装并撤销"后，修复版（01:52 构建）装到了 Thor。01:58:21 撤销成功：公开资料从 34 个已解锁变成 33 个，`chal_farmer` 变回未解锁，`chal_loud` 和 `chal_unlucky` 不受影响。说明 Steam 接受用写统计的方式清除单个成就位。撤销完成 0.3 秒后，应用被强制关闭（系统手势或启动器，pid 5882），所以修正后的成就核对（游戏启动时重报 33 个挑战）还没跑完。**待用户下次自己打开时看日志确认**：预期 `unlocking …` 一条都没有，`already on Steam: 33`。撤销只会执行一次，已经记录在偏好 `repair_20261106` 里。

当时启动页在"正在登录 Steam…"停了约 3 分钟（01:54:52 → 01:58:03），一恢复就在十几秒内完成。推测是 Thor 当时休眠了（超时计时在休眠期间也会停），没有证实。如果以后在亮屏状态下也出现，就要查 `SteamSession.connect` 的超时。

2026-11-06 第二阶段：Steam 桥已经做完，和活侠传一样，包括扫码登录、云存档、游戏时长、成就和 DLC 所有权。模拟器上验证了这几项：启动页能出来，能连上 Steam 拿到登录二维码，不登录直接进游戏时插件已注册、到得了标题，游戏运行中回桌面再点图标会回到游戏。**真实登录之后的环节（下载 PC 存档、上传、时长、成就）都还没测**，需要用户扫码。

还没上 Thor。装机前要先问用户。

## 结论

可行，而且是最省事的一类：

- Brotato 的 PC 版就是 Godot 3。pck 是独立文件，没有加密，脚本是编译成字节码的 `.gdc`，不是加密的 `.gde`。
- 官方 Godot 3.6.2 的 Android 模板能直接加载 PC 的 pck，游戏文件一个都不用改。唯一的例外是 `steam.gd`，见"坑"一节的第 1 条。
- 本机已经有现成的 Godot 3.6.2 编辑器和全套导出模板，是从 mother-encore-android 复制来的，**没有下载任何东西**。

## 游戏本体事实

| 项 | 结果 |
| --- | --- |
| 安装位置 | `H:\SteamLibrary\steamapps\common\Brotato`，appid 1942280，buildid 26406319 |
| 游戏版本 | 1.2.0.1（`progress_data.gd` 里的 `VERSION`） |
| 引擎 | 定制 Godot `3.7.dev.custom_build`，提交 `74e86be5…`，官方仓库里没有这个提交（Blobfish 自己的分叉） |
| 兼容性依据 | GDScript 字节码 v13；exe 里的内置函数表和官方 3.6/3.x 逐项一致；3.6-stable 到 3.x 之间，GDScript 词法和函数表都没有改动；场景里用到的 109 种节点类型全是官方类；没有 GDNative（没有 .gdnlib/.gdns） |
| pck | `Brotato.pck` 独立文件，281 MB，格式 v1（引擎 3.7.0），12046 个文件，没有加密 |
| Steam | GodotSteam 以模块形式编进 exe（旁边有 `steam_api64.dll`，项目设置里有 `steam/initialization/embed_callbacks`）。脚本只通过 `Engine.has_singleton/get_singleton("Steam")` 动态获取它 |
| DRM | exe 没有 `.bind` 段，所以没有 SteamStub 加壳。只有脚本层的检查：`restartAppIfNecessary` 和 `isSubscribed()`，后者为 false 时退出 |
| 平台层 | `Platform` 自动加载会按 `OS.has_feature` 选择 steam/epic/gog/local 实现。`project.binary` 里写死了 `_custom_features="steam"`，`override.cfg` 去不掉它，因为 3.x 只会往这个集合里加 |
| DLC | Abyssal Terrors（2868390）和 Primal Dread（4498220）的内容都在 pck 里，只靠 `is_dlc_owned` 决定能不能用。本机清单里装了 2868390 的 depot，4498220 没有 |
| 存档（PC） | `%APPDATA%\Brotato\<SteamID64>\`：`save_v3_0.json`、`run_v3_0.json`、`settings.json`，另有旧版的 save/save_v2。目录里有 `steam_autocloud.vdf`，说明用的是 Steam Auto-Cloud |
| 存档（安卓） | `user://` 就是 `/data/data/dev.kmbrotato.brotato/files/`；按 `get_user_id()` 分子目录，有 Steam ID 时用 ID，没有时用 `user` |
| 语言 | `getCurrentGameLanguage()` 返回 Steam 语言名，再映射成游戏的代码（schinese → zh）。本机 Steam 里这个游戏设的是 schinese |

**账号注意**：本机的存档目录（`steam_autocloud.vdf` 里的 accountid）和 appmanifest 的 LastOwner 可能不是同一个 Steam 账号。做云同步前要先确认扫哪个号，游戏和 DLC 是不是在这个号上。

## 官方手机版 vs 自己移植 PC 版（交给用户决定）

对官方手机版只看过一个第三方安装包的文件清单，**没有安装或运行**，也没有用里面的任何东西。看到的情况：

- 官方手机版是 Erabit Studios 用 **Unity 2021.3.15f1（IL2CPP）重写**的，和 PC 的 Godot 代码完全不通用。
- 包里有 Google 的 pairip 防篡改、Firebase、Bugly 和广告 SDK。

| | 官方手机版（Erabit） | PC 版移植（本项目） |
| --- | --- | --- |
| 代码 | Unity 重写，独立版本线（1.3.5xx–1.3.6xx） | 原版 pck，和 Steam 同步（1.2.0.1） |
| DLC | Abyssal Terrors 在手机上分段更新；App Store 上有两个 $2.99 的内购。Primal Dread 没查到 | 用 Steam 上已买的 DLC，由桥接按真实拥有情况判断 |
| Mod | 没有创意工坊 | 内置 ModLoader 照常运行；创意工坊订阅要另做 |
| 存档 | 自有格式，没查到和 Steam 互通的官方说明 | 和 PC 同一格式，可以走 Steam 云 |
| Steam 成就和时长 | 没有 | 可以做，复用活侠传的 JavaSteam 模块 |
| 价格 | Premium 版上市价 $4.99 | 用户已拥有 |
| 手柄 | 没核实 | PC 版本身完整支持手柄 |

来源：[TouchArcade 手机版上市报道](https://toucharcade.com/2023/03/28/top-down-roguelite-shooter-brotato-is-out-now-on-ios-and-android-as-a-premium-release/)、[App Store 页面](https://apps.apple.com/us/app/id1668755109)、[APKMirror 更新说明](https://www.apkmirror.com/?p=10064425)、[SteamDB 2868390](https://steamdb.info/app/2868390/info)。

用户已经选了 PC 版这条路（11-06：要求"和活侠传一样集成 Steam"，手机版只当参考）。

## 构建

```bash
cd "G:/ai/android-recompile/brotato-android"
powershell -ExecutionPolicy Bypass -File tools/export-shell.ps1   # 只在改了 shell/ 之后需要
bash tools/make-shell-steam.sh release                           # 同上；调试包用 debug
bash tools/build-steam.sh                                        # 改了 steam/src 之后需要
bash tools/build.sh              # 发布包（不内置游戏，约 24 MB）→ E:\android-recompile-work\brotato-android-work\out\Brotato-android-release.apk
KIND=debug bash tools/build.sh   # 调试包（会开放游戏自带的调试菜单，需先 make-shell-steam.sh debug）
BUNDLE=1 bash tools/build.sh     # 旧做法：把 pck 拆开内置进 assets/（约 290 MB）
STEAM=0 BUNDLE=1 bash tools/build.sh  # 不带 Steam 桥的纯离线包（只能内置）
```

原理：

1. `shell/` 是一个只用于导出的 Godot 3.6.2 空工程。官方导出器用它生成二进制清单（包名 `dev.kmbrotato.brotato`、名称、图标、横屏、沉浸模式、INTERNET 权限）和 `assets/_cl_`。只出 arm64。
2. `make-shell-steam.sh`：apktool 3.0.2 解开空壳（路径在 `tools/local.env` 的 `APKTOOL_JAR`），由 `patch_manifest.py` 改清单，再重新打包。改动是：插件声明、启动页成为桌面入口、权限命名空间修正、minSdk 26。资源 ID 靠 public.xml 保持不变，已经用 `aapt2 dump resources` 逐条核对过。
3. `build-steam.sh`：用 javac 编译 `steam/src`，再用 d8（build-tools 36）把它和 JavaSteam 的 32 个依赖 jar 一起转成 5 个 dex。依赖是从 `lom-android/cloud/build/deps` 复制的，没有重新下载。
4. `tools/assemble_apk.py`：保留空壳 APK 里除 assets 以外的内容。Steam 桥的 dex 排成 classes.dex 到 classes5.dex，Godot 原来的 dex 顺延。
   - 默认（`--external`）：assets 里只有 `_cl_`（追加 `--main-pack /data/data/dev.kmbrotato.brotato/files/game/Brotato.pck`）、`android/boot.gd`（来自 `android/boot.gd`）和 `android/overlay.zip`（由 `overlay/` 打成；`X.remap.delete` 在 zip 里变成指向 X 自己的 `X.remap`）。本机的 Brotato.pck 只用来读版本号。
   - `BUNDLE=1`：把 pck 的 12046 个文件逐个写到 `assets/<路径>`（Godot 3 官方导出的布局，包括 `assets/.import/`），`overlay/` 覆盖在上面，`<路径>.delete` 表示删掉这个文件。
5. 用 zipalign 对齐，用 `signing/brotato.keystore` 签名（别名 brotato，密码在 `signing/password.txt`）。以后升级必须用同一把钥匙，否则覆盖安装会丢数据。

## Steam 桥（2026-11-06）

改编自活侠传的 `lom-android/cloud`，源码在 `steam/src/dev/kmbrotato/steam/`，日志 tag 是 `BroSteam`。

| 文件 | 作用 |
| --- | --- |
| `LauncherActivity` | 桌面入口。已登录就先同步云存档再进游戏；没登录可以扫码，也可以直接进。游戏已经在运行时直接切回游戏，不会在游戏开着的时候同步 |
| `SteamPlugin` | Godot 3 插件，Engine 单例名 "Steam"。方法都只读缓存、立即返回，因为它们跑在渲染线程上 |
| `CloudSync` / `CloudClient` | 云端路径是 `%WinAppDataRoaming%Brotato/<SteamID64>/`（本机 remotecache.vdf 里 root 4），本地是 `files/<SteamID64>/`，也就是游戏的 `user://<id>/`。同步 `settings.json`、`save*.json`、`run*.json`。规则和活侠传一样：首次同步以云端为准，覆盖前先备份。补上了活侠传交接里提到的改进：两边都变了但内容相同时，不算冲突。备份放在 `files/cloud-backup/<id>/<时间>/` |
| `CloudBridge` | 用 FileObserver 盯存档目录：写完后等 4 秒再上传，切到后台时立刻上传。不需要改游戏脚本 |
| `SteamPlay` | 游戏在前台时上报游戏时长（game 1942280）。成就离线时先排队，每次连接时把 Steam 上已解锁的成就缓存下来，供 `isAchieved` 查询。只统计 GodotApp 自己的可见状态 |
| `Ownership` | 每次启动同步时，用 `getAppOwnershipTicket` 查 1942280、2868390、4498220 的所有权，只有明确的答复才更新缓存 |
| `TokenStore` | refresh token、SteamID64、所有权缓存，存在 `brotato_steam` 偏好里 |

游戏这一侧只改了一个文件 `overlay/singletons/platforms/steam.gd`：

- 没登录时离线游玩：存档放 `user://user`，没有成就，DLC 算未拥有。
- 登录后存档放 `user://<SteamID64>`，和 PC 的目录一致。
- 成就靠 `ChallengeService._sync_platform_challenges`：游戏每次启动都会把本地已完成的挑战再报一遍，所以离线时解锁的成就也能补上。
- 不会去重置 Steam 上的统计数据。商店和创意工坊页面用浏览器打开。
- SteamID64 用字符串传过 JNI，因为 Godot 3 插件的 int 是 32 位。

验证情况：模拟器（未登录）上，二维码能出来；直接进入时插件注册成功，到达标题画面，没有脚本错误；游戏运行中回桌面再点图标，回到的是游戏本身。

测试时用 `adb logcat -s BroSteam:V godot:V GodotPluginRegistry:V` 看日志。

### Thor 实测（2026-11-06 01:45，用户扫码）

- 扫码登录成功。首次同步把 PC 云存档下载到 `files/<SteamID64>/`，游戏加载的就是这份存档，界面中文（语言来自 PC 的设置）。
- 所有权：1942280 本体 OK，2868390 深渊恐惧 OK，4498220 远古恐惧 AccessDenied（没买）。游戏里的判断一致。
- 上传：游戏写 `settings.json` 后 4 秒上传；每波结束写 `run_v3_0.json` 和存档时上传 2 个文件；切到后台时也上传。
- 时长：`playing 1942280`。切到后台时会话断开，回来后重新上线。
- 性能（只看了一眼）：进程 CPU 约 42%，内存 635 MB。帧率没测。

### 成就 bug（已修，修复版 01:54 已装，误解锁 01:58 已撤销）

- Steam 成就表里，位号是 `stats/<id>/bits/<位号>` 的**键名**，条目里只有 name 和 display，没有 `bit` 字段。原代码读 `bit.get("bit").asInteger()`，读不到就得 0，于是每个成就都被当成所在组的第 0 位。这段代码来自活侠传的 SteamPlay，**活侠传那边有同样的问题**。
- 后果：01:46:05 写了第 5 组第 0 位，也就是 **`chal_farmer` 被误解锁**，而本来要报的 `chal_loud`（第 5 组第 4 位）早在 2023 年就解锁了。另外，凡是所在组第 0 位已点亮的成就都被当成"已解锁"跳过，日志里那两个 64、95 的数字也是错的。
- 怎么核对的：拿 PC Steam 的 `appcache/stats/UserGameStatsSchema_1942280.bin` 和 `UserGameStats_<accountid>_1942280.bin`（PC 客户端同步后的状态），加上公开资料的 `?xml=1`，再用 djb2 哈希（`Keys.generate_hash` 就是 Godot 的 `String.hash`）算出 PC 存档的 `challenges_completed`。结论：Steam 上 34 个已解锁，PC 存档对得上其中 32 个；多出来的是 `chal_unlucky`（2026-08-06 在 PC 上解锁，正常）和 `chal_farmer`（今天误解锁）。
- 修复：位号改为从键名读取；偏好 `brotato_play` 加了 `format=2`，旧版写下的 done/unlocked 一律作废，下次全部重新核对。`repairWrongUnlock` 只在以下条件全部满足时清掉第 5 组第 0 位，并且只执行一次（偏好 `repair_20261106`）：账号是出问题的那个号，这一位的解锁时间等于当时那一刻（±120 秒）。（这段一次性修复已经执行过，之后从代码里删掉了。）Steam 是否接受用写统计的方式清除成就，要看日志 `repair check` 和之后的 `already on Steam` 数字。

账号：扫码时要用有 Brotato 云存档的那个号。

| 路径 | 内容 |
| --- | --- |
| `E:\android-recompile-work\brotato-android-work\godot362` | 自包含的 Godot 3.6.2 编辑器（`_sc_`）和 Android 模板，从 mother-encore 复制，不要去改那边 |
| `E:\android-recompile-work\brotato-android-work\out` | 空壳 APK、成品 APK、导出日志 |
| `E:\android-recompile-work\brotato-android-work\pck-meta` | pck 里的脚本、场景和 project.binary（解包） |
| `E:\android-recompile-work\brotato-android-work\src-gd` | 597 个脚本反编译出的可读源码（`tools/gdc_decomp.py`）。仅自用 |
| `E:\android-recompile-work\brotato-android-work\ref-apk\listing.txt` | 破解手机包的文件清单（只有清单，没有解包） |

工具：`pck_list.py`（列 pck 目录）、`pck_extract.py`（按正则解包）、`gdc_dump.py`（导出 .gdc 的标识符和常量）、`gdc_decomp.py`（把 .gdc 还原成源码，基于 3.6 的记号表）、`ecfg_dump.py`（列 project.binary 的设置）。在 Git Bash 里跑这些脚本，正则参数要加 `MSYS_NO_PATHCONV=1`，原因见"坑"一节。

## 测试

lime-sim（emulator-5600，G:\AndroidLab 的镜像，带 arm64 转译）的启动方式：

```
ANDROID_SDK_ROOT=G:\AndroidLab\sdk ANDROID_AVD_HOME=H:\android-emu\avd emulator -avd lime-sim -port 5600 -no-window -no-audio -no-boot-anim -no-snapshot -gpu host
adb -s emulator-5600 install -r <apk>
adb -s emulator-5600 shell am start -n dev.kmbrotato.brotato/com.godot.game.GodotApp
adb -s emulator-5600 logcat -s godot:V
```

转译环境下大约 20 秒进主循环，再过 15–20 秒到标题。测完马上 `adb -s emulator-5600 emu kill`，它会占约 21 GB 显存。

## 坑

1. **发布模板缺 Steam 单例时会原生崩溃**（GL 线程 SIGSEGV，访问 0x10）。调试模板会拦下对 null 调方法，只报 SCRIPT ERROR；发布模板跳过这层检查，所以崩了。现在的解决办法是 `overlay/singletons/platforms/steam.gd`：文本脚本，同时删掉 `steam.gd.remap`，引擎就会加载这份文本而不是 .gdc。它在没有桥接时按"未登录"处理，DLC 一律算未拥有。原版在 Steam 初始化失败时会把所有 DLC 当作已拥有，这里刻意不沿用。语言跟随系统。有桥接时，行为和 PC 原版一致。
2. 空壳工程不能开 `fallback_to_gles2`，否则导出器要求 ETC 纹理并直接拒绝导出。运行时用的是 Brotato 自己的 project.binary（GLES3 加 GLES2 回退），不受影响。
3. Git Bash（MSYS）会把以 `/` 开头的参数，以及部分含 `://` 的参数改写成 Windows 路径，所以要加 `MSYS_NO_PATHCONV=1`。
4. build-tools 的 `apksigner.bat` 遇到带空格的路径（比如 `Documents\Ayn thor`）会出错，改用 `java -jar lib/apksigner.jar`。
5. 调试包的版本号显示 `1.2.0.1-debug`，并且能打开游戏内调试菜单（`DebugService` 检查 `OS.is_debug_build()`）。给用户的包一律用发布包。
6. ModLoader 在调试包里会刷大量 "Could not open log file"（`user://logs` 不存在），发布包里没有。创意工坊路径和 `steam_data.json` 找不到的报错没有影响。
7. 模拟器偶尔会弹系统的 "Bluetooth keeps stopping" 对话框，挡住截图，跟游戏无关。
8. apktool 3.0.2 解码 Godot 导出的清单时，权限条目会变成 `<uses-permission xmlns:n0="android" n0:name=...>`。不修就重新打包，权限就丢了。`patch_manifest.py` 会把它改回 `android:name`。
9. Godot 3.6 模板自带 Kotlin 2.1.20，ktor 3.3.3（JavaSteam 用）需要 2.2.21。Android 按 dex 编号顺序找类，所以 Steam 桥的 dex 必须排在最前面。
10. javac 的源文件路径里有空格，要用 `@参数文件`（`build-steam.sh` 里已经处理）。d8 要用 build-tools 36，34 的版本处理 commons-lang3 会崩（活侠传踩过）。
11. 发布包不可调试，`run-as` 用不了。查看应用文件要么用调试包，要么看日志。
12. 首次运行 apktool 解码很慢，要好几分钟；之后几秒就完成。
13. Steam 内容块有 zstd（VSZa）和老的 LZMA（VZip）两种压缩，JavaSteam 要 `com.github.luben.zstd.Zstd` 和 `org.tukaani.xz.LZMAInputStream`。依赖列表里没有它们，第一次下载模组时 `NoClassDefFoundError` 直接把整个应用崩回桌面（JavaSteam 的分块协程只接 Exception，Error 会漏到全局）。现在用活侠传的纯 Java 实现（`steam/src/com/github/luben/zstd/Zstd.java`、`steam/src/org/tukaani/xz/LZMAInputStream.java`，四个项目同一份）。
15. 这台机器的 javac 报错是中文（"错误"），用 grep 检查编译结果时要同时搜 `错误` 和 `error:`。build-steam.sh 有 `set -e`，编译失败时不会生成新的 dex。
14. 发布包里没有 `user://logs`（PC 上是 Godot 文件日志建的），ModLoader 每写一行日志就报一次 "File must be opened before use"，CrashReporter 也收不到日志。插件 `onMainCreate` 里建这个目录（23:48 的包，未装 Thor）。

## 后续工作量

| # | 内容 | 预估 | 说明 |
| --- | --- | --- | --- |
| 1 | Steam 桥 | 已完成，待扫码实测 | 见"Steam 桥"一节。实测时重点看：首次同步下载 7 个 json 后，游戏能不能读到 PC 存档；一局打完后是否触发上传（日志 `cloud upload (save …)`）；`playing 1942280`；成就是否解锁；DLC 所有权 |
| 2 | 云存档 | 已完成，待实测 | 同上 |
| 3 | 时长和成就 | 已完成，待实测 | 同上 |
| 4 | 手柄 | 小 | PC 版原生支持手柄。要上 Thor 看按键映射：Godot 3 走 Android KeyEvent 的标准映射。模拟器上没法测。 |
| 5 | 触屏 | 可选，中 | 菜单已经能点。战斗中移动没有虚拟摇杆。Thor 用实体手柄就够了；要加摇杆的话，可以用叠加层加一个自动加载的 CanvasLayer。 |
| 6 | 分辨率和横屏 | 已完成 | 游戏是 1920×1080，stretch 2d/keep，正好对上 Thor 主屏；清单里是 sensor_landscape 加沉浸模式。 |
| 7 | Thor 实机 | 小 | 性能、音频、手柄都要实测。装机前要问用户，并且不要替用户操作游戏。 |
| 8 | Mod / 创意工坊 | 完成，Thor 实测通过 | 见下面"创意工坊"一节 |
| 9 | 启动页正式版 | Thor 上跑过，待用户反馈观感 | 见"启动页"一节 |
| 10 | 从 Steam 下载和更新游戏本体 | Thor 实测首次下载通过；增量更新等 Steam 出新版 | 见"从 Steam 下载/更新游戏"一节 |
| 11 | 性能 | 启动已优化（约 4–6 秒）；帧率待测 | 见"启动速度"一节 |

### 创意工坊（2026-11-06，Thor 实测通过）

**PC 版的做法**
- 游戏自带 Godot Mod Loader。`project.binary` 里写死了 `_custom_features="steam"`，所以安卓上也会套用 steam 档（`profiles/steam.tres`，`steam_workshop_enabled=true`）。
- 原本的扫描目录是从 exe 路径往上推出来的 `steamapps/workshop/content/1942280/`，安卓上不存在，以前一直是静默失败。
- `_ModLoaderSteam.load_steam_workshop_zips()` 只加载 `int(目录名) in Platform.get_subscribed_mods()` 的目录，把里面的 zip 用 `load_resource_pack` 挂载。
- 自动加载顺序里 Platform 在 ModLoaderStore/ModLoader 前面，而且 Platform 在 `_init` 里就建好了实现对象，所以 ModLoader `_init` 里的调用是安全的。
- 模组菜单（`ui/menus/pages/mods/menu_mods.gd`）里，平台是 STEAM 时会显示"创意工坊"按钮，用浏览器打开创意工坊页面。

**安卓上的实现**
- `steam/src/.../Workshop.java`：
  - 在启动页同步里、DLC 所有权之后跑，用 CloudSync 已经登录好的那个会话。
  - 订阅列表先用 `PublishedFile.GetUserFiles(type="mysubscriptions")`（PC 的 steamclient64.dll 里 EUserUGCList 就映射成 myfiles/myfavorites/mysubscriptions 这些字符串）；出错或为空再试 `ClientUCMEnumerateUserSubscribedFilesWithUpdates`（自定义 `Ucm` handler 按 job id 收回包，头里带 routing_appid）。Thor 实测：UCM 对有订阅的号回的是"成功、0 个"，GetUserFiles 拿到了全部 3 个。日志看 `workshop: GetUserFiles page …`。
  - 列表为空时不删已下载的模组目录（只是这次不加载），防止误报的空列表导致下次全部重下。
  - `GetDetails` 拿标题和最新的 `hcontent_file`。
  - 下载走 SteamPipe：depot = 1942280，`getDepotDecryptionKey(1942280,1942280)`，`getServersForSteamPipe`（只用 SteamCache/CDN），`getManifestRequestCode`（拿不到就用 0），`downloadManifestFuture`，对每个 chunk 调 `downloadDepotChunkFuture` 写到偏移处，再校验整文件 SHA-1。最多换 3 台服务器。
  - 老式物品（没有 hcontent，只有 `file_url`）直接用 HTTP 下载。
  - 先下到 `files/workshop-staging/<id>/`，完整下完才替换 `files/workshop/<id>/`。下载失败就保留旧版。只有 Steam 真的返回了订阅列表，才会删除已退订的物品。
  - 状态存在 `files/workshop-state.json`（按账号）。可加载的 ID 存在 prefs `brotato_steam/workshop_ids`，退出登录时清掉。
  - 物品路径一律限制在物品目录内（不允许 `..` 和绝对路径），因为内容是别人上传的。
  - 整个过程不会抛异常，失败只记日志，并在启动页显示"沿用上次的模组"。
- overlay：
  - 工坊路径 `override_path_to_workshop="user://workshop"`：主包方式下由 `android/boot.gd` 在运行时改；`overlay/.../profiles/steam.tres` 只在 `BUNDLE=1` 时起作用。
  - `steam.gd` 的 `get_subscribed_mods()` 读 `steam.getSubscribedItemsString()`（逗号分隔的字符串，因为物品 ID 超过 2³¹），再用 `_parse_u64` 转成整数，并打印 `steam_workshop_items:`。
- （内置版才有的隐患，现在的主包方式已经没有了）Godot 3 挂载任意资源包后，`res://` 的目录列举会切到 DirAccessPack，只能看到包里的文件。主包方式下所有游戏文件都在包里，列举正常。

**测试样本**
- 用户 PC 上同一个账号订阅了 2 个：2958802806 `LoongFly-FreeRerolls.zip` 和 3169210497 `xzmzm-RestartWave.zip`，都是纯 GDScript 加翻译，在 `H:/SteamLibrary/steamapps/workshop/content/1942280/`。
- 实测时看这几样：
  - 启动页"创意工坊模组"那一行显示 2 个；
  - 日志里有 `workshop: subscribed [...] ready [...]`；
  - 游戏日志里有 `steam_workshop_items: [2958802806, 3169210497]` 和 ModLoader 的 `DONE: Setup 2 mods`；
  - 主菜单"模组"页里有两个模组。

**限制**
- 模组如果引用了当前版本已经删掉的游戏资源（比如重打这一波引用的旧字体），会加载失败，PC 上也一样。
- 依赖 GDNative/原生库、PC 文件路径或直接调用 GodotSteam API 的模组，在安卓上可能不工作。
- steam 档下 ModLoader 只读创意工坊目录，不读本地 `mods/`。
- 订阅和退订只能在网页（游戏里的创意工坊按钮）或 PC 上做，下次启动时同步。

### 启动页（2026-11-06 重做；2026-11-07 起平时打开不显示，见"启动速度"）

- `LauncherActivity` 改成横屏两栏：左边是游戏图标、名字和版本，右边是一张卡片，按步骤切换内容：
  - 登录邀请（列出云存档、成就和时长、DLC、创意工坊）；
  - 扫码页（二维码加 4 步说明，二维码会自动刷新）；
  - 同步清单（登录 Steam、同步云存档、确认游戏和 DLC、创意工坊模组，每行有转圈、勾或感叹号，加右侧详情）；
  - 完成页（2.5 秒后自动进入游戏，也可以按"进入游戏"）；
  - 出错页（会标出是哪一步失败的）。
- 按钮能用手柄选中，选中时有白色描边并略微放大。扫码页按 B 或返回键是取消，不会退出应用。
- `CloudSync.Log` 加了 `step(phase)`，供启动页知道当前在哪一步（常量 `STEP_*`）。

### 从 Steam 下载/更新游戏（2026-11-07，Thor 实测首次下载通过；增量更新未真测）

**结果**：APK 不再内置游戏文件（约 24 MB）。`files/game/Brotato.pck` 由启动页从 Steam 下载（每次启动对比 Steam、增量更新），或导入玩家自己的副本。

**事实**
- 游戏内容在 depot 1942281（Windows），1.2.0.1 是 manifest 1511471247775670824。只取其中的 `Brotato.pck`（281,316,965 字节，SHA-1 `01cd53b4dce10795f8480c6aeef3ff5273690042`），exe、dll 不下。DLC 内容就在这个 pck 里。
- Godot 3.6.2 安卓模板的命令行只来自 `assets/_cl_`，原生端支持 `--main-pack`；带 main pack 启动时会读 pck 同目录下的 `override.cfg`。
- **Godot 3.6 拒绝加载"更新的引擎做的 pck"**（文件头里引擎版本是 3.7，`PackedSourcePCK` 直接报 "Pack created with a newer version of the engine"）。以前内置版没碰到，是因为文件被拆开放进了 assets，绕过了 pck 头检查。现在的做法是在本机这份文件上把头里的版本改成 3.6.0（偏移 12–19 共 8 字节），内容不动。状态里记的 SHA-1 永远是 Steam 原文件的，所以对比 Steam 和增量更新都不受影响（头所在的那一块会被重新下载，1 MB）。

**设备上的文件**（都在 `files/game/`）
| 文件 | 内容 |
| --- | --- |
| `Brotato.pck` | 游戏（头已改成 3.6） |
| `state.json` | `manifest`（0 表示还不知道，比如刚导入的）、`sha1`（Steam 原文件的）、`size`、`source`（steam/import） |
| `Brotato.pck.part` + `part.json` | 下载中的新文件和已完成的块（按偏移），断了下次接着下 |
| `override.cfg` | 每次进游戏前写：`[autoload] CrashReporter="*res://android/boot.gd"` |
| `overlay.zip` | 每次进游戏前从 APK 的 `assets/android/overlay.zip` 复制 |

**启动时怎么接上补丁**（`android/boot.gd`）
- `override.cfg` 把第一个自动加载 CrashReporter 换成 `res://android/boot.gd`（在 APK assets 里；主包里没有的路径，读文件会回退到 assets）。改已有的 autoload 键会保留原来的顺序，所以它仍是第一个。
- boot.gd `extends "res://singletons/crash_reporter.gd"`，父类 `_init` 先跑，游戏原来的崩溃检查照旧。
- 然后 `ProjectSettings.load_resource_pack("user://game/overlay.zip", true)`：里面是我们的 `singletons/platforms/steam.gd` 和一个指向它自己的 `steam.gd.remap`（盖掉游戏的 remap，不再加载 `.gdc`）。路径和游戏注册的 `class_name SteamPlatform` 一致，不会报"全局类已存在"。
- crash_reporter.gd 编译时就 preload 了 `profiles/steam.tres`，所以 boot.gd 直接改缓存里的那个对象：`override_path_to_workshop = "user://workshop"`。
- 日志：`android boot: overlay mounted`。

**代码**
- `GameFiles.java`：
  - `ready()`：只看文件头（GDPC、v1、Godot 3、版本已改到 ≤3.6），启动页会频繁调用。
  - `sync(c, session, log)`：在 CloudSync 里、DLC 所有权之后跑（步骤 `STEP_GAME`）。PICS 查 public 分支的 manifest，和 state 一样就跳过。不一样就下载新 manifest。如果是导入的文件（manifest 0），SHA-1 对得上就直接记下 manifest。对不上就 `install()`：
    - 先比对本机已有的文件：按块的 SHA-1（ChunkID），在旧 manifest 里的偏移和同一偏移各试一次，对上的直接复制；
    - 剩下的块 6 线程并发下载，每 3 秒存一次进度；
    - 下完整个文件校验 SHA-1，再完整检查文件表（必须有 `project.binary` 和 `crash_reporter`），再改头，最后替换旧文件。
  - 失败处理：更新失败（已经有能玩的文件）时，启动页这一步显示"沿用当前版本"；没有文件时抛 `GameFiles.Missing`，启动页显示"重试 / 导入文件"。
  - 新版本如果不是 Godot 3 的 v1 包（比如换成 Godot 4），会报错并保留旧版。
  - `importFrom()`：边复制边算 SHA-1，然后检查文件、改头、替换。`manifest=0, source=import`，下次登录启动时会和 Steam 对比。
  - 投放目录：`Android/data/dev.kmbrotato.brotato/files/Brotato.pck`。缺游戏时启动页会自动导入，导入后删掉投放的副本。用 adb 推送时要加 `MSYS_NO_PATHCONV=1`，否则远端路径会被改成 `C:/Program Files/Git/sdcard/...`。
- `SteamDepot.java`：从公主那边改的。PICS 版本号、depot key、CDN 服务器、manifest、按块下载（`chunk()`，可并发，换服务器重试 8 次）。
- `LauncherActivity`：
  - 没登录又没游戏文件时显示"需要你的正版游戏文件"，按钮是扫码登录 / 导入文件。
  - 导入用系统文件选择器（`ACTION_OPEN_DOCUMENT`），有进度条，导入中按返回键无效。
  - 同步清单多了"游戏文件"一行和一根进度条，详情里显示"已下 / 总 MB · 速度"。
  - `enterGame` 之前写 override.cfg 和 overlay.zip；没有游戏文件时不会启动 Godot（缺 pck 时直接起 GodotApp 会在 GL 线程原生崩溃）。

**Thor 实测（2026-11-07）**
1. 首次下载通过。踩过两个坑，都已修：
   - 数据块"压缩后反而更大"（pck 里大多是 PNG、Ogg），接收缓冲区要按 max(压缩长度, 原始长度) 开（`SteamDepot.chunk` 自己分配）。之前按原始长度开，JavaSteam 报 "The destination buffer must be longer than the chunk CompressedLength"，这个报错文字是误导性的。创意工坊下载器有同样的隐患，也一起改了。
   - CDN 偶尔回 503：单块失败不再中断整个下载，最后统一补，最多补 4 轮（`ROUNDS`）。
2. 再次打开：`game: up to date`。
3. **增量更新还没真测**，要等 Steam 出新版本。也可以在登录状态下导入一个改过几个字节的 pck 来模拟：预期大部分块从本机复制，只下几块。
4. 加载时间已经和内置版一样，见"启动速度"一节。帧率还没测。

**风险**
- 游戏大更新换引擎（比如 Godot 4），或改了 SteamPlatform 接口：前者会被检查拦下并保留旧版；后者新方法会继承 pck 里 AbstractPlatform 的默认实现，一般不会崩。
- 第一次必须登录联网，或者导入文件。

### 启动速度（2026-11-07，Thor 实测）

**测量**（Thor，已登录，没有更新）
| 版本 | 点图标 → 读存档 | 说明 |
| --- | --- | --- |
| 第一个不内置版（00:59） | 约 32 秒 | 启动页同步 12 秒 + "准备好了"停 2.5 秒 + Godot 加载 17 秒 |
| 提速版（01:10） | 约 16–20 秒 | Godot 加载 3.4 秒；同步 12–16 秒（登录 6.5–8 秒） |
| 直接进游戏版（01:23） | 约 4–6 秒 | 同步和 Godot 加载同时进行，读存档前等待 0 毫秒 |

**做了什么**
1. **主包路径用 `user://game/Brotato.pck`**（`build.sh` 的 `DEVICE_PCK`）。Godot 3.6.2 安卓版对 `/data/...` 这种文件系统路径用的是 `FileAccessFilesystemJAndroid`（每次读都走 JNI 到 Java），`user://` 用的是原生 `FileAccessUnix`。改完 Godot 加载从 17 秒降到 3.4 秒，和以前内置版一样。`user://` 在 `Main::setup` 时就能解析（OS_Android 的数据目录不依赖项目设置），override.cfg 也会从 `user://game/` 读。
2. **平时打开直接进游戏**（`LauncherActivity.onCreate` → `CloudSync.startRoutine` → `startGame`）。条件：已登录、游戏文件就绪、首次同步做过、没有"需要重新登录"标记。启动页不显示。
   - 同步在同进程的后台线程里跑。云存档阶段一结束就打开 `CloudSync.Gate`。
   - `android/boot.gd` 的 `_ready()` 调插件 `waitForSaves(12000)`。Godot 先实例化全部自动加载（大部分加载时间都在这里），再按顺序调 `_ready`；boot.gd 是第一个自动加载，ProgressData 在它之后才读存档。所以加载和同步是并行的，存档又一定在读之前到位。
   - 超时后游戏照常读本机存档，`Gate.passed` 置位，之后同步不再改本机文件（`write()`、`copy()` 会抛异常）。
   - DLC 所有权、游戏版本、模组在云存档之后继续后台跑，结果都是"下次生效"：
     - 游戏更新下到 `files/game/Brotato.pck.next` + `next.json`；
     - 模组更新下到 `files/workshop-next/` + `workshop-next.json`；
     - 下次启动时由 `GameFiles.applyStaged` 和 `Workshop.applyStaged` 在 Godot 启动前换上。
     - 原因：Godot 每打开一个文件都会按路径重新打开 pck 或模组 zip，运行中替换文件会读错。
   - 有更新、没连上、登录失效时弹 Toast（`Notice`）。登录失效会在偏好里记 `relogin`，下次打开就显示完整启动页。
   - SteamPlay（在线状态、时长、成就）必须仍在 `onMainCreate` 里 `start()`：它靠在游戏 Activity 的 onStart 之前注册生命周期回调来判断"游戏在前台"，推迟 start 会让它永远以为游戏不可见，Steam 上就不显示在线（quick2 踩过）。只把它的登录推迟到云存档阶段之后（`SteamPlay.run` 开头的 `Gate.quietWait`），不跟同步抢。
3. **启动页（首次、缺游戏、要重新登录）**：
   - 登录后云存档和其他三项并行。
   - 没有异常就直接进游戏；有下载、失败、备份时停 1.5 秒给玩家看。
4. **连接**：
   - Steam 服务器列表缓存到 `files/steam-servers.bin`（JavaSteam 的 `FileServerListProvider`，`SteamSession.init`）。
   - quick2 起，连接单次 8 秒超时，重试 3 次。
   - 游戏里上传存档的会话用 `loginId ^ 0x30000`，避免和后台同步的会话（基础 loginId）互相顶掉；SteamPlay 一直用 `^ 0x10000`。
5. **计时日志**：`timing: signed in / cloud done / ownership done / game files done / workshop done after N ms`、`waitForSaves: ready|gave up after N ms`、`android boot: saves ready after N ms`。

**还能再快的地方**
- 冷启动时 JavaSteam 初始化本身要几秒，联网登录 2–4 秒。现在这部分和 Godot 加载重叠了，只有网络慢时才会让玩家等。
- 网络不通时最多等 12 秒（quick2），然后离线进游戏。

### 成就解锁提示（2026-11-07，Thor 上测试提示已验证）

用户要的是"像 Steam 原本一样"：PC 上 Steam 覆盖层在成就解锁时，右下角会弹出带图标的卡片。

- **什么时候弹**：`overlay/.../steam.gd` 的 `complete_challenge` 在 `setAchievement` 之后，满足以下条件时调用插件的 `notifyAchievement(name, title, desc, locale)`：
  - `Engine.get_idle_frames() > 0`。ChallengeService 每次启动都会在自动加载的 `_ready` 里（第一帧之前）把已完成的挑战全部重报一遍，这批不弹；
  - Steam 上还没有这个成就（`SteamPlay.onSteam` 查的是上次核对时 Steam 返回的已解锁列表）。
- **样子**（`AchievementToast`）：
  - 右下角深色卡片：56dp 图标，上面一行写"成就已解锁"（英文环境下是 Achievement Unlocked!），下面是成就名和描述；
  - 从底边升起，停 5 秒后原路降回去；多个成就依次排队弹；
  - 游戏自己的"挑战完成"框在左下角，两者不重叠。
- **成就名和图标的来源**：SteamPlay 每次核对成就时，把 Steam 成就表里每个成就的各语言名称、描述和图标文件名存进 `files/achievements.json`，再把图标下载到 `files/achievement-icons/`（来源 `cdn.cloudflare.steamstatic.com/steamcommunity/public/images/apps/1942280/<icon>`）。所以离线解锁也有名称和图标。
  - 语言跟随游戏当前语言（`TranslationServer.get_locale()`）；
  - 还没缓存时，用游戏自己的挑战名和描述（去掉 BBCode），图标用应用图标。
- **测试**：调试包，或者用 USB 在 `Android/data/dev.kmbrotato.brotato/files/` 下放一个空文件 `achievement-test`，然后重开游戏，执行：
  `adb shell am broadcast -a dev.kmbrotato.TEST_ACHIEVEMENT --es name chal_farmer`
  会弹一个测试提示。测完删掉这个文件。
- **Thor 验证（15:44）**：缓存了 200 个成就名、171 个图标；测试提示显示"成就已解锁 / 农夫 - 碰撞区域 / 使用农夫在碰撞区域赢一场比赛"，图标正确（截图 `shots/thor-achievement-toast.png`）。测试标记文件已删除。**真正解锁成就时的弹出还没验证**，要等用户完成一个新挑战。

### "Steam" 插件接口（SteamPlugin，供 overlay 的 steam.gd 调用）

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `isSignedIn()` | bool | 有 token，并且已经知道 SteamID |
| `getSteamIDString()` | String | SteamID64 的字符串形式；没登录时返回 "" |
| `isSubscribed()` | bool | 本体所有权；查不到时一律返回 true，不会把玩家挡在门外 |
| `isDLCOwned(appid)` | bool | 上次同步时 Steam 返回的结果（离线也能用）；没登录时返回 false |
| `getGameLanguage()` | String | 按系统语言映射到 Steam 的语言名（schinese 等） |
| `isAchieved(name)` | bool | Steam 上已解锁的，或者本地已确认、正在排队的 |
| `setAchievement(name)` | bool | 加入队列，由 SteamPlay 发送 |
| `loggedOn()` | bool | 游戏时长会话是否在线 |
| `openUrl(url)` | — | 用浏览器打开 |
| `getSubscribedItemsString()` | String | 已订阅并已下载好的创意工坊物品 ID，逗号分隔；没登录时返回 "" |
| `notifyAchievement(name, title, desc, locale)` | — | 弹 Steam 风格的成就解锁提示（Steam 上已有的成就不弹） |
| `waitForSaves(ms)` | bool | boot.gd 的 `_ready` 调用：等快速启动的云存档阶段结束（最多 ms）；true 表示存档已同步 |
