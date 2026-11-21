# ModemSwitch — 源码工程

把内置的原厂 modem 镜像写入 `/system/etc/firmware/modem_1_*_n.img`；当系统分支与镜像标识不一致时，只对**拷贝出来的副本**改写 3 个标识字节，让内核版本校验通过。APK 内的原版镜像始终原样，永不修改。

纯 Java 8、单 Activity、无第三方依赖、不用 Gradle，用 `aapt2 + javac + d8 + apksigner` 直接构建。

---

## 1. 目录结构

```
app/
  assets/                             固件放入处（不入库，需自备，见 1.1）
  AndroidManifest.xml                 清单（minSdk 17 / target 19，需 root）
  build.py                            一键构建脚本（唯一的构建入口）
  src/com/tool/modemswitch/MainActivity.java   全部界面与逻辑，单文件约 600 行
  res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png   启动图标
  icon_src/                           图标源图
  make_icon.py                        由源图生成各密度图标
  my.jks                              签名密钥（alias `a`，口令均为 `123456`）
README.md                             本文档
```

工具链与固件都不入库：工具链按 2.2 放到与 `app/` 同级的 `build_tools_env/`，或按 2.3 改用自备 SDK 的绝对路径。

`app/assets/*.img`、`app/out/`、`app/ModemSwitch.apk` 都是产物，不入库。

### 1.1 固件（必须自备，不随源码提供）

构建要求 `app/assets/` 下有两个镜像，缺一不可：

| 文件 | 长度 | MD5 | 说明 |
|---|---|---|---|
| `modem_1_tg_n.img` | 4,435,564 | `497b0272…` | T00 移动版原厂镜像（真 TG） |
| `modem_1_wg_n.img` | 5,005,356 | `6789082a…` | U00 联通版原厂镜像（真 WG） |

获取方式：

- 从设备 dump（需 root）：`adb shell su -c "dd if=/system/etc/firmware/modem_1_tg_n.img of=/sdcard/tg.img"`，再 `adb pull /sdcard/tg.img app/assets/modem_1_tg_n.img`；
- 从原厂固件包解出（`update.app` / SP Flash Tool 散包中的 `modem_1_*_n.img`）。

放好后 `build.py` 会原样内置；若 `assets/` 为空，脚本会尝试从仓库外的 `../backup/` 按 `t00_tg.img`、`modem_1_wg_n.img.orig` 拷入，仍找不到直接报错退出。

两份缺一不可：只有其中一份时构建会立刻中止，不会产出缺少固件的包。换了镜像要同步更新 `MainActivity.java` 顶部的 `MD5_TG` / `MD5_WG`，否则应用在写入前校验会中止。

TG 原版可直接从设备导出（无需 root）：

```bat
adb pull /system/etc/firmware/modem_1_tg_n.img app\assets\modem_1_tg_n.img
```

U00 的 `modem_1_wg_n.img` 只有原厂包里才有，设备上没有。

---

## 2. 环境搭建教程

### 2.1 需要什么

| 组件 | 版本 / 说明 | 用途 |
|---|---|---|
| JDK | 17（也可 11/8） | `javac`、`java`、`keytool` |
| Android build-tools | 30.0.x（目录名 `android-11`） | `aapt2.exe`、`zipalign.exe`、`lib/d8.jar`、`lib/apksigner.jar` |
| Android platform | `android.jar`（API 30 及以上均可） | 编译期 classpath |
| Python | 3.8+ | 执行 `build.py` |
| 平台 | Windows（脚本按 `.exe` 路径写死） | 其它系统需改 `build.py` 顶部常量 |

### 2.2 方式 A：离线工具链（推荐）

工具链不入库。把 JDK、build-tools、platform 按下面布局放成 `build_tools_env/`，**与 `app/` 同级**（`build.py` 里 `ENV = app/../build_tools_env`），即可完全离线构建，不需要 Android Studio：

```
build_tools_env/
  jdk/jdk-17.0.2/bin/{java,javac,keytool}.exe
  bt/android-11/{aapt2.exe, zipalign.exe, lib/d8.jar, lib/apksigner.jar}
  plat/android-11/android.jar
```

#### 2.2.1 从零下载（三个包，约 300 MB）

在工程根目录执行：

```bat
curl -L -o jdk17.zip      https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse
curl -L -o buildtools.zip https://dl.google.com/android/repository/91936d4ee3ccc839f0addd53c9ebf087b1e39251.build-tools_r30.0.3-windows.zip
curl -L -o platform30.zip https://dl.google.com/android/repository/platform-30_r03.zip
```

> build-tools 的 Windows 包文件名带一段哈希前缀（`91936d4e…`），直接写 `build-tools_r30.0.3-windows.zip` 会 404；上面的地址来自 SDK 索引 `repository2-3.xml`。

解压摆放：

```bat
mkdir build_tools_env\bt build_tools_env\plat build_tools_env\jdk
tar -xf jdk17.zip      -C build_tools_env\jdk       :: 解出 jdk-17.x.y\，重命名为 jdk-17.0.2
tar -xf buildtools.zip -C build_tools_env\bt        :: 解出 android-11\，内部即 aapt2.exe、zipalign.exe、lib\
tar -xf platform30.zip -C build_tools_env\plat      :: 解出 android-11\，取其中的 android.jar
```

两个 zip 解出来都叫 `android-11`，必须分别放进 `bt\` 与 `plat\`，否则会互相覆盖。

#### 2.2.2 现有包复用

已经下载过 `jdk17.zip` / `buildtools.zip` / `platform30.zip` 时，直接按上面的 `tar -xf` 三步摆放即可，无需联网。目录名 `android-11`、`jdk-17.0.2` 只是历史命名，实际内容是 JDK 17 + build-tools 30.0.3 + platform API 30；`bt` 目录必须是 `aapt2.exe`、`zipalign.exe`、`lib/d8.jar`、`lib/apksigner.jar` 齐全的那一份。

`build.py` 顶部的路径映射：

| 常量 | 指向 |
|---|---|
| `ENV` | `build_tools_env/` |
| `BT` | `build_tools_env/bt/android-11/` |
| `PLAT` | `build_tools_env/plat/android-11/android.jar` |
| `JDK` | `build_tools_env/jdk/jdk-17.0.2/` |

换机器时把整个 `build_tools_env/` 一起拷走即可，脚本里没有绝对路径；`build_tools_env/` 已在 `.gitignore` 中，不会污染源码仓库。

### 2.3 方式 B：用自备 SDK 替换

1. 安装 JDK 17，记下 `JAVA_HOME`；
2. 用 sdkmanager 安装 `build-tools;30.0.3` 与 `platforms;android-30`；
3. 在 `app/build.py` 里改这三行：

```python
BT   = r'C:\Android\sdk\build-tools\30.0.3'
PLAT = r'C:\Android\sdk\platforms\android-30\android.jar'
JDK  = r'C:\Program Files\Java\jdk-17'
```

4. 确认 `bt` 目录下确实有 `aapt2.exe`、`zipalign.exe`、`lib\d8.jar`、`lib\apksigner.jar`（缺 `lib\` 时 d8/apksigner 会以 `java -jar` 方式失败）。

### 2.4 就绪自检

```bat
build_tools_env\jdk\jdk-17.0.2\bin\java.exe -version
build_tools_env\bt\android-11\aapt2.exe version
python -V
```

三条都能打印版本号即可进入下一步。

---

## 3. 编译教程

### 3.1 一条命令

```bat
cd c:\Users\geekchun\CodeBuddy\20261119204823
python app\build.py
```

正常结束打印：

```
APK: ...\app\ModemSwitch.apk 6241985
```

失败时抛 `FAILED rc=…`，并回显上一步命令的最后 3000 字符输出，直接看那一段即可定位。

### 3.2 脚本做了七件事

| 步骤 | 命令要点 | 说明 |
|---|---|---|
| 1 准备 assets | `prepare_assets()` | `app/assets/` 已有同名非空文件则原样保留（打印 `asset keep`）；缺失才尝试从 `../backup/` 拷入；都没有就报错退出，不会产出缺固件的包 |
| 2 编译资源 | `aapt2 compile --dir res -o out/res.zip` | 图标编译成二进制 flat 包 |
| 3 链接 | `aapt2 link -o out/base.apk -I <android.jar> --manifest AndroidManifest.xml -A assets --min-sdk-version 17 --target-sdk-version 19 res.zip` | 清单、资源、assets 全部打进 `base.apk` |
| 4 编译 Java | `javac -source 8 -target 8 -encoding UTF-8 -classpath <android.jar> -d out/classes <所有 .java>` | 递归收集 `src/` 下所有 `.java`，加新文件不用改脚本 |
| 5 生成 dex | `java -cp lib/d8.jar com.android.tools.r8.D8 --min-api 17 --output out/dex <所有 .class>` | `--min-api 17` 与 minSdk 对应 |
| 6 合成 APK | `zipfile.ZipFile(base,'a')` 写入 `classes.dex` | 用 ZIP 追加方式，不重打资源包 |
| 7 对齐签名 | `zipalign -f 4` → `apksigner sign --v1 true --v2/v3 false` → `apksigner verify` | 只开 v1 签名：v2/v3 在 Android 4.2 上不认，且开 v2 后 4.2 反而装不上 |

签名密钥：`app/my.jks`，alias `a`，store/key 口令 `123456`；文件不存在时脚本会用 keytool 自动生成同一套参数。

### 3.3 产物

| 文件 | 说明 |
|---|---|
| `app/ModemSwitch.apk` | 安装包，约 6.2 MB（两份固件压缩后占绝大部分） |
| `app/out/` | 中间件，每次构建前整体删除，可随时手删 |

### 3.4 安装与冒烟测试

```bat
adb install -r app\ModemSwitch.apk
adb shell am start -n com.tool.modemswitch/.MainActivity
adb shell input tap 240 505          :: 点第一个按钮，弹确认框
adb logcat -s ModemSwitch            :: 需要时看日志
```

界面上应看到两张卡片：基带版本（徽标 + 版本串 + 文件名）、系统版本（徽标 + Android 版本 + DISPLAY）。点按钮弹出的确认框正文 11sp、标题 14sp。

### 3.5 常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `aapt2: error: no such file` | `res/` 缺失或 `BT` 路径错 |
| `javac: 编码 GBK 的不可映射字符` | 源码含中文，必须保留 `-encoding UTF-8` |
| `error: undefined reference to R` | 本工程不使用 `R` 类，资源全部通过 `getIdentifier` 或路径访问；若新增 `R` 引用，需在 `aapt2 link` 后把 `R.java` 加进 javac 源列表 |
| `apksigner: ... not aligned` | `zipalign` 必须在签名之前，且用 `-f 4` |
| 安装报 `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | 加固/系统限制导致 v1 签名被剥离，换回 `--v1-signing-enabled true` |
| `d8` 报 `Unsupported class file major version` | javac 用了 JDK 17 但 `-source/-target 8` 没生效，检查 `JAVA_HOME` |

---

## 4. 程序细节

### 4.1 整体结构

单 `Activity`，无布局 XML（界面全部代码构建），无第三方库，不用 `R` 资源索引。线程模型：`refreshStatus` 与 `doSwitch` 都在后台线程干活，通过 `Handler ui` 回主线程改控件。

### 4.2 关键常量

| 常量 | 值 | 用途 |
|---|---|---|
| `FWDIR` | `/system/etc/firmware/` | 写入目录 |
| `NAME_TG` / `NAME_WG` | `modem_1_tg_n.img` / `modem_1_wg_n.img` | assets 内文件名 |
| `MD5_TG` / `MD5_WG` | `497b0272…` / `6789082a…` | 原版完整性校验 |
| `IMG_LEN` | `5005356` | 分区镜像定长，短镜像写入后补零到该长度 |
| `BG` / `CARD` / `TXT` / `SUB` / `BLUE` / `GREEN` | 深色主题色值 | 界面配色 |

### 4.3 界面构成

- **卡片一「基带版本」**：徽标（`TG 移动版` 蓝 / `WG 联通版` 绿 / `? 未知` 橙）+ 设备上生效镜像的版本串（等宽加粗）+ 文件名；
- **卡片二「系统版本」**：徽标（`移动版` / `联通版` / `未知`）+ `Android 版本 · Build.DISPLAY`；
- 两个 52dp 主按钮、「执行日志」标题与 140dp 滚动日志区；执行中按钮禁用并变半透明；
- 确认弹窗按系统与镜像是否跨版本给出两种文案，只列「源文件 / 目标文件 / 重启生效」，不写多余说明。

### 4.4 状态读取 `refreshStatus`

后台线程依次做四件事，再把结果一次性刷到 UI：

1. `findModemImg()` 找设备上实际加载的镜像：先按系统分支拼名字，不存在则取 `/system/etc/firmware` 下第一个 `modem_*.img`；
2. 读该文件尾部 1024 字节里最后一处 `MOLY.` 版本串作为「当前基带版本」；
3. `detectSystemType()` 判系统分支，结果缓存在 `sysKind`（`volatile`）供弹窗复用；
4. 徽标、版本串、文件名、系统版本一次性 `setText`。

读取失败：徽标显示 `!`、版本串「读取失败」、文件名 `--`。

### 4.5 两个判定维度

| 维度 | 取值 | 判定方法 |
|---|---|---|
| 系统分支 `sysType` | `TG` / `WG` / `null` | `Build.DISPLAY` 含 `T00` → TG，含 `U00` → WG；判不出再用 `su cat /sys/kernel/ccci/md1_postfix`，含 `tg`/`wg` 兜底；仍判不出返回 `null`（按原样写入） |
| 镜像标识 `imgType` | `TG` / `WG` / `null` | 读末尾 `min(1024, len)` 字节，取最后一处 `MOLY.` 版本串，含 `MD.TG.` → TG、`MD.WG.` → WG |

两者一致或任一为 `null` → 原样写入；不一致 → 打补丁。

### 4.6 补丁算法 `patchImage`（只改 3 字节）

| 位置 | 规则 | WG 原版 | TG 版 |
|---|---|---|---|
| 版本串 `MD.` 后第一个字母 | `W` ↔ `T`，即 `MD.WG.` ↔ `MD.TG.` | `0x57` | `0x54` |
| `len-14` 起的 16 位小端字段 | WG `0x0120` ↔ TG `0x00c0` | `20 01` | `c0 00` |

- 全部按**相对末尾**定位（窗口 1024 字节），因此对 4.4 MB 的真 TG 与 5 MB 的 WG 都适用；
- 以 5,005,356 B 的 WG 原版为例，绝对偏移是 `0x4c5f42`、`0x4c601e`、`0x4c601f`，改完 MD5 = `9170ea3c…`，与设备实测可启动的那份逐字节一致（已用 Python 复现验证）；
- 射频校准表、DSP 代码、协议栈一个字节都不动，仍是 WG 实体，只是让 TDD 内核的版本校验放行。

### 4.7 写入流程 `doSwitch`

1. `copyAsset()` 把 `assets/<镜像>` 拷到 `files/`（原版不动）；
2. 比对副本 MD5 与 `MD5_TG` / `MD5_WG`，不符立刻中止；
3. 判 `sysType` / `imgType`，跨版本则打补丁，并把补丁前后 MD5 打进日志；
4. `nameForSystem()` 决定目标文件名（见 4.8）；
5. `writeScript()` 在 `files/` 生成 `switch.sh`：

```sh
mount -o rw,remount /system
cp -f <副本> <目标> || exit 2
SZ=$(wc -c < <目标>)
[ "$SZ" -lt 5005356 ] && dd if=/dev/zero bs=1 count=$((5005356-SZ)) seek=$SZ of=<目标>
chown 0:0 <目标>; chmod 644 <目标>
sync
echo OK_REPLACED <目标>
```

6. `runRoot("sh switch.sh")` 执行，先试 `su 0 sh -c`，无输出再退回 `su -c`；
7. 输出含 `OK_REPLACED` 才算成功：再 `su md5 <目标>` 记录设备端摘要，弹「写入完成」对话框（可立即重启）；否则弹「失败」。

补零的原因：真 TG 只有 4,435,564 B，内核按分区定长读取，短了会读失败。

### 4.8 文件名规则 `nameForSystem`

- `DISPLAY` / `MODEL` / `DEVICE` 含 `U00` → `modem_1_wg_n.img`；
- 含 `T00` → `modem_1_tg_n.img`；
- 都判不出 → 取 `/system/etc/firmware` 下第一个 `modem_*.img`（以设备实际加载的名字为准）；
- 目录里一个都没有 → 用默认 `modem_1_tg_n.img`。

### 4.9 安全设计

- **原版隔离**：补丁只作用于 `files/` 副本，每次切换重新拷贝；
- **先校验后写**：MD5 不符、补丁失败、无 `OK_REPLACED` 全部中止并提示，脚本 `cp` 失败直接 `exit 2`，不会留半成品；
- **最小改动**：只写一个文件，权限固定 `0:0 / 644`，末尾 `sync`；
- **可回滚**：原厂镜像自行留档（`app/assets/` 里那份即可）；动手前也可先在设备上 `dd` 备份现有镜像。

### 4.10 代码索引（`app/src/com/tool/modemswitch/MainActivity.java`）

| 方法 | 行 | 职责 |
|---|---|---|
| `mkCard` | 154 | 卡片容器（圆角、内边距、上边距） |
| `refreshStatus` | 204 | 读设备镜像与系统分支，刷新两卡片 |
| `nameForSystem` | 275 | 按系统分支选目标文件名 |
| `ask` | 336 | 确认弹窗（分同版本/跨版本文案，正文 11sp） |
| `doSwitch` | 367 | 拷贝 → 校验 → 判定 → 打补丁 → 写入 → 复核 → 提示 |
| `finishFail` / `doneDialog` | 433 / 444 | 失败提示 / 完成提示 |
| `cnName` | 460 | `TG`→移动版、`WG`→联通版 |
| `detectSystemType` | 467 | 系统分支判定 |
| `detectImageType` | 482 | 镜像标识判定 |
| `patchImage` | 506 | 副本 3 字节补丁 |
| `writeScript` | 538 | 生成 root 写入脚本 |
| `runRoot` / `exec` | 554 / 563 | su 执行与输出收集 |
| `copyAsset` / `md5` | 582 / 593 | 资源拷贝 / 摘要计算 |

---

## 5. 已知限制与风险

- 跨制式写入后**标识能过，射频实体仍可能不匹配**，表现为有基带无信号；用原厂镜像回滚（`app/assets/` 里那份）；
- `IMG_LEN = 5,005,356` 写死，换机型或分区变化必须同步改；
- 依赖 `su` 与可 remount 的 `/system`（Android 4.2 机型），未提供免 root 方案；
- 不做签名校验、不支持 A/B、不支持增量；
- 只保证「写进去的字节 = 期望的字节」，不保证制式在硬件上可用。