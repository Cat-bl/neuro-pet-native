# Neuro-sama桌宠AI版（原生）：基于民间 4.5UI 开发的原生卡片

比亚迪车机上的桌宠：Neuro 和 Evil 两只宠物住在一片扁平风格的风景里，上面是时钟、日期农历和车况胶囊。放进车机的多窗口（双画中画）或悬浮窗口里用。

车况、歌词、封面来自 4.5UI（宿主应用 BydModule）；画面用安卓原生控件画，不经过浏览器内核。没装宿主的设备上用模拟的车况。

## 画面

- 白天 / 傍晚 / 夜晚三套风景，跟着车机的日夜模式换；夜里有星空、流星、按农历画的月相。
- 数字时钟或表盘，下面是日期、农历节日、倒数日、陪伴时长、电量油量、正在放的歌。
- 放歌时时间后面铺着封面，下面是一句一句跟着唱的歌词，两只跟着哼。
- 节日特效：春节灯笼、中秋满月、烟花、爱心、气球、圣诞雪。

## 操作

- 点宠物说话，双击抛高高，按住拖起来扔出去；长按空地放饼干；往上划一起跳，往下划坐下。
- 点时间换表盘；太阳、月亮、云朵能点。
- 左右滑或底部导航切到车况、日历、倒数日页，两只站在页面上陪你。
- 有歌的时候点宠物或歌名，出来上一首 / 暂停 / 下一首。
- 聊天键：填了大模型的 API Key 就能打字跟它们聊，它们还能查车况、开空调、切歌。

## 设置

齿轮点开：显示宠物、宠物大小、AI 聊天、场景、时钟样式、显示内容（不想看的都能关）、说话气泡、随机事件、休息提醒、开场看车况、倒数日、生日、新手引导。

## 在车上用

要有：比亚迪车机（Android 9 以上），装好 4.5UI（宿主应用 BydModule，包名 `com.wzw.bydmodule`）。实车用过的 4.5UI 版本是 2.2.1.6 到 2.2.1.18，更早的版本可能还没有给外部应用的接口。

1. 把 APK 装到车机上，比如 `adb install -r xxx.apk`。
2. 在比亚迪「系统加速 → 应用加速管理」里给它打开"允许在后台运行"。不开的话，车机内存紧张时宿主的授权页一盖上来，系统就会把应用结束，授权申请跟着作废。
3. 在 4.5UI 的应用中心里把它加进多窗口。小格子、大格子各有一份名单，只加进大格子的名单时，拖不到小格子里。
4. 打开后点"申请授权"，在宿主弹出的确认页里允许，并勾上"记住选择"。

## 接口文档

- uiplus Android 接口（这个应用用的）：https://u223v6sxpn.apifox.cn
- uiplus JS 接口（网页卡片用的）：https://1yquh73bv5.apifox.cn

应用绑定宿主的 `com.wzw.bydmodule/.uiplus.UiplusExternalService`，开会话、申请授权，再按方法名发请求、收订阅推送；方法名和参数同网页卡片的 `window.uiplus`。对接时踩过的坑（授权页、宿主后端晚好、会话超时等）写在 `src/` 里 `Uiplus.java` 的开头。

## 从源码打包

不用 Gradle、Android Studio，脚本直接调 Android 的构建工具。要装：

- Node.js 18 以上；
- JDK 17（`javac`、`java`、`keytool`）；
- Android SDK 里的 build-tools（测过 34，要带 aapt、aapt2、zipalign、`lib/d8.jar`、`lib/apksigner.jar`、`core-lambda-stubs.jar`）和 `platforms;android-30`（用它的 android.jar）。用 sdkmanager 装：`sdkmanager "build-tools;34.0.0" "platforms;android-30"`。

设好 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）和 `JAVA_HOME`，脚本自己去找工具；也可以直接设 `ANDROID_BUILD_TOOLS`（build-tools 目录）和 `ANDROID_JAR`（android.jar）。JDK 在 PATH 里也行。

```
node build.mjs            # → out/Neuro-sama桌宠AI版（原生） v<版本>.apk
node build.mjs --install  # 打完用 adb install -r 装上（adb 要在 PATH 里）
```

中间产物在 `build/`。版本号在 `AndroidManifest.xml`：`versionCode` 每发一版加 1，`versionName` 是显示的版本。

## 签名

第一次打包会在这个目录生成签名钥匙 `release.keystore`（密码 android），之后一直用它。**要备份，别外传。**

- 宿主按应用的签名认人，授权记在签名上。换了钥匙，车上要重新授权。
- 安卓不让不同签名的包覆盖安装：车上装着别人签的同名应用（包名一样）时，要先卸载才能装你签的。
- 改了代码自己发布时，最好把包名改掉（`AndroidManifest.xml` 的 `package`、`src/` 下的目录、每个文件的 `package` 那一行），免得和原版冲突。

## 代码结构

工程是 `AndroidManifest.xml`、`res/`、`src/`（包名 `com.x3card.pet`）；`assets/` 里是打进 APK 的资源，已经做好，直接用；`build.mjs`、`apkbuild.mjs` 是打包脚本，只用 Node 自带的模块。

`src/com/x3card/pet/`：

| 文件 | 做什么 |
|---|---|
| `MainActivity.java` / `FullActivity.java` / `Wiring.java` | 入口、全屏页、把各部分装到卡片上 |
| `App.java` | 主逻辑：场景、时钟、宠物、气泡、手势、闲聊、页面、开场看车况 |
| `Card.java` / `Layer.java` / `U.java` | 一层层叠起来的卡片、绝对定位、尺寸（1rem = 高度 / 10.52） |
| `Sky.java` / `SceneData.java` | 天空、太阳月亮、星星、云、远山、草地 |
| `Clock.java` / `Pills.java` / `Lyric.java` / `Cover.java` | 时钟和表盘、胶囊、歌词、封面 |
| `PetLayer.java` / `Pet.java` / `mascot/` | 宠物：引擎（web-mascot 的 Java 移植）和画 |
| `Bubbles.java` / `Bubble.java` / `Lines.java` | 说话气泡、台词库 |
| `Play.java` / `Fx.java` | 饼干、游戏、踢球、随机事件；节日特效、爆裂、音符 |
| `Car.java` / `Music.java` | 车况接话、胶囊、行程、实时歌词；音乐控制 |
| `Pages.java` / `CarPage.java` / `CalendarPage.java` / `CountdownPage.java` | 车况 / 日历 / 倒数日页面 |
| `Ai*.java` / `ai/` | 大模型聊天、聊天室、操作车机、联网搜索 |
| `Panel.java` / `Sheet.java` / `Kit.java` / `Ui.java` / `Guide.java` | 设置面板、生日表单、面板小部件、按钮导航、新手引导 |
| `Uiplus.java` / `DebugHost.java` / `Host.java` | 宿主接口（车上）、调试宿主（模拟器、手机） |
| `Lunar.java` / `Holidays.java` | 农历、节气、节日、放假安排 |

## 许可

- 资源的许可：`assets/LICENSE-CC-BY-NC-SA-4.0.txt`、`assets/LICENSE-Eviling.txt`、`assets/LICENSE-Neuron.txt`、`assets/LICENSE-oswald.txt`、`assets/LICENSE-outfit.txt`、`assets/LICENSE-web-mascot.txt`。
- 宠物引擎 `src/com/x3card/pet/mascot/` 是 web-mascot（https://github.com/Cat-bl/web-mascot）的 Java 移植，web-mascot 的代码是 GPL-3.0 以上，见 `assets/LICENSE-web-mascot.txt`。
- 宠物素材 Neuron、Eviling 是 CC BY-NC-SA 4.0：**不能商用**，署名见 `assets/LICENSE-Neuron.txt`、`assets/LICENSE-Eviling.txt`。
