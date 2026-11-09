# 语燕输入法SDK

本分支的终端模式入口位于键盘「更多」页，英文直输、中文沿用既有方案，中英切换键保留。`keyboard/terminal/TerminalInputDispatcher.kt` 统一发送字母、符号与特殊键的修饰组合，符号和数字布局切换保留待用状态；编辑及数字等工具布局不触发词库方案校验。方向与粘贴使用原有编辑小键盘。「输入设置 → 数字输入」的「数字键盘兼容输入」使用偏好 `number_keyboard_compatible_input`，默认开启，仅将数字键盘0–9按文本提交；关闭后仍发送数字按键，终端修饰组合和 F(n) 编号处理不受此开关影响。终端更多按钮直接展开并保存展开状态，终端编辑小键盘的删除位置改为回车。终端顶栏依次为 ESC、TAB、CTRL、ALT、PGUP、PGDN 和更多；扩展区仅显示 SHIFT、DEL、F(n)、^Z、^C、^L、退出 单行功能键，移除 F1–F12 及下方符号行，不保留固定高度或空白占位。点击 F(n) 临时显示数字键盘，编号1到12实时更新按钮，再次点击发送标准功能键并恢复原容器，保留待用修饰键；退格改号，空号再点或长按取消。临时切换仅改变视图，不改变语言、中文方案或默认模式。

`view/KeyboardAuxiliaryPanel.kt` 将终端快捷键与花漾字原文处理栏分别挂载在候选栏下方和上方。花漾字启用后持续显示处理栏，原文通过本地 `BaseInputConnection` 编辑，宿主连接接收实时组合预览；完成后提交当前一段、清空原文并继续聚焦，空内容取消模式。点击宿主切回原始输入，点击处理框恢复处理；内部弹出菜单和编辑工具条不会触发原始输入切换。模式与上次样式使用 `flower_typeface_mode` 和 `last_flower_typeface_mode` 偏好保存。`TextTransformer`、`TextTransformers.register()` 与 `TextTransformSession` 保留通用异步转换接口供后续翻译复用，当前 UI 只显示花漾字处理，不提供独立转换入口。Room 8→9 仅清理旧 TextTransform 菜单，剪贴板锁定功能保留，顶栏依次显示搜索、删除（常用语页为添加）、锁定、常用语和原位剪贴板。Android 回归测试显式使用项目已有的 ConstraintLayout 2.2.0。

雨燕以其敏捷、优雅的飞行姿态，在雨天依然够飞翔、不畏艰险、勇往直前的飞翔态度，被赋予**灵巧、聪明、伶俐、积极、创新**的寓意。语言是人类交流的基本工具，是最重要的文化载体，输入方式是语言交流和信息传递的重要环节，使语言交流变得更加**高效、便捷**。  
[语燕输入法](https://github.com/gurecn/YuyanIme)秉承这些特点，以“**易用、快速、准确**”为核心理念，追求极致、卓越、流畅的输入体验。 在设计上，语燕输入法借鉴主流的谷歌拼音、微信输入法等主流输入法精华，追求整体简洁大方，易于上手。支持多种输入方式，规划包括拼音、手写、语音等，满足不同用户的输入需求。支持丰富的个性化设置选项，用户可以根据自己的喜好进行自定义设置，让输入更加符合个人习惯。

## 设计原则：
### 纯输入功能，主打轻快。
喜欢简洁的我看到当前主流拼音输入法工具软件逐渐趋向复杂，软件内各种眼花缭乱的无用功能以及烦人的广告让我无法忍受。  
**因此我想要定制出一款简洁、实用、专一的拼音输入法；**
### 最小、必要的权限原则，更安全。
当前主流输入法获取各类非必需权限，无视用户隐私，随意上传、保存、分析用户输入数据和使用习惯。虽然对普通用户的我们来说，一般不会出现重大事故，但我希望自己的秘密只存于自己的手机之内，不要在我不知情、未授权的情况下，把我的隐私拿走。    
**谨遵循必要、最小权限，只为输入而生，更纯净、安全、高效。**  
普通键盘输入在本机完成。可选语音输入使用网络和麦克风权限，仅在用户长按空格时录音并发送给所选供应商，不保存录音文件；手写模型首次下载使用网络，之后在本机识别。不访问位置、联系人或共享存储。

手写停笔后自动显示可改选的首选字，点击候选、空格或回车确认；失败和超时提示仅显示在候选栏，不会写入输入框。从书写区落下第一笔后，连续书写期间的后续落笔也会采集为笔画，底部按键、侧边符号与候选工具栏均不会响应点击；达到停笔时间、触发上屏流程后恢复按钮操作。停笔等待时间采用手写设置中的毫秒值。

语音供应商通过 `speech/SpeechProvider.kt` 的 `SpeechProvider`、`SpeechSession` 和 `SpeechListener` 接口接入，以 `SpeechProviders.register()` 注册；各供应商独立声明 `options`，设置页自动生成控件并按供应商保存参数。当前实现为豆包 WebSocket 流式识别，采用 16 kHz、16 bit、单声道 PCM；长按空格开始、松开输入、上滑取消；提前到达的最终结果会等待松开，取消后不再发送剩余音频或提交迟到文本。最终结果通过 `InputView.commitExternalText()` 与剪贴板条目共用提交路径；仍校验活动输入目标，取消后丢弃本次缓存文本，空结束帧复用本次最近非空结果。语音面板挂在外层输入视图、锚定当前键盘内容范围并置于提示层上方，预览区按剩余高度分配；词库准备提示条两侧也跟随键盘，避免覆盖层参与悬浮根布局测量；首次圆角轮廓在初始主题应用前完成初始化。「检查连接」通过同一公共接口发送内置测试语音，检查当前密钥、资源和参数并展示结果，不使用麦克风，离开页面或修改配置会取消检查。运行时 API Key 使用 Android Keystore 加密保存于 `noBackupFilesDir`，不写入默认偏好或构建配置。
5.5.0 语音按下即开始录音，`VoiceInputController.AudioUplink` 在连接就绪前按顺序暂存 PCM（上限约 20 秒），就绪后连续补发再转实时发送，豆包会话发送队列上限相应放宽到 1 MB。上滑超过 48dp 进入「松开取消」并暂停识别（录音不停，改发静音帧保持会话），回落到 32dp 以下恢复；松手时仍处于该状态才取消，语音面板自身的上滑也遵循同一规则。面板下边界止于按下的空格键所在行顶部。剪贴板搜索入口移到工具栏（`SkbMenuMode.ClipboardSearch`，位于删除或添加左侧），搜索栏与花漾字处理栏共用 `ImeInlineEditor`，行高一致并可获得本地焦点显示光标；搜索时不再隐藏终端按键行。有搜索条件时列表顶部显示条件细栏；`clipboard_floating_enter` 控制右下角悬浮回车键，按键走与键盘回车相同的路径。
5.4.6 悬浮时输入法窗口不再对状态栏垫顶部内边距（`InputView.applyWindowFitting()`），键盘可拖到窗口顶部；终端按键行高取键盘区高度的四分之一并沿用普通按键的上下左右间距，随键盘高度调节同步。
5.4.5 横屏普通键盘按键区与候选栏铺满屏幕宽度，不再受 70% 宽度限制。悬浮键盘的右、下边距上限按窗口内容区与键盘未受挤压的自然尺寸计算（`InputView.floatKeyboardOffsetLimits()`），并在布局后自动夹紧超出的残留边距；悬浮总高度由 `EnvironmentSingleton.floatMaxAreaHeight` 限制在窗口可用高度内，避免横屏下底行与拖动条被裁掉。
5.4.4 剪贴板和常用语条目使用起始对齐；顶部入口打开本地搜索栏，使用自己的键盘输入关键词并返回筛选结果，支持大小写不敏感匹配、空结果提示和清除条件。搜索经临时 `BaseInputConnection` 编辑，不写入宿主；同一输入目标重启时保留搜索及中英文模式，结束或切换目标时退出。筛选保留原有置顶与时间顺序，复制、置顶和删除针对展示中的记录。

### 源于Rime，但更易用。
当前开放的输入法引擎中，[Rime引擎](https://github.com/rime/librime)已经趋向完善。然而对于非技术流用户来说，上手却并不容易，各种输入方案定制、兼容、编译问题，以及键盘的界面效果优化问题不断，这是开源软件的通病。  
**但我想要开发出一款基于Rime引擎的安装即用，让没时间研究Rime的人也能用上好用的输入法；**
### 输入模式更完善。
最早先接触安卓平台的[同文输入法](https://github.com/osfans)，后接触[小企鹅输入法](https://github.com/fcitx5-android/fcitx5-android)，均支持Rime引擎方案，在输入模块能满足大部分需求。但小企鹅输入法不支持九宫格键盘，同文输入法候选词不便且无法选择九键拼音组合。  
语燕输入法优化Rime引擎九键输入方案，支持拼音选择、超级简拼、词语联想等实用功能，并内置丰富输入方案，支持双拼、简拼、乱序、笔画、手写等方案。内置丰富词库，支持绝大部分输出场景，提升输入效率。
语燕输入法励志做一款**不但能用，并且好用，不断优化，突破自身**的输入法。  
### 个性化定制，更贴心。
手机屏幕越来越大，但在走路时，一手提东西，一手打字回复消息对手小的我来说是个头疼的问题。选择候选词够不到、选择出错屡屡出现，因此我开发单手模式、悬浮键盘。  
输入数字要么切换到数字键盘，要么长按按键输入，对输入来说都不便捷，因此我开发了键盘数字行。  
夜间输入时，屏幕刺眼，因此我定制了深色主题自动切换功能。  
中文联想、长词匹配、编辑键盘、常用语、剪贴板、花漾字、全面屏扩展等等，我们也等等，见证更多贴心定制功能的完善。只要我们还年轻，语燕输入法就会一直向雨而翔。

## 实现功能：
+ 方案内置：全拼（九宫格、全键）、双拼(小鹤、智能ABC、自然码、紫光、微软、搜狗、乱序17)、手写、五笔画；支持简拼、全拼；
+ 英文输入：智能全键英文输入；
+ 词库拓展：支持雾凇词库、白霜词库等多种词库拓展，输入体验良好；
+ 符号输入：中文、英文、数学、颜文字、EMOJI表情输入、微信特效表情；
+ 数字输入：数字键盘输入、键盘数字行输入；
+ 键盘自定义：自定义菜单栏、主题、深色模式、键盘调节、键盘数字行、键盘位置移动；
+ 单手键盘：左、右手模式切换；
+ 悬浮键盘：悬浮键盘模式，键盘拖拽、移动；
+ 花漾字输入：火星文（焱暒妏）、 花藤字（ζั͡花ั͡藤ั͡字ั͡✾）、凌乱字（"҉҉҉凌҉҉҉乱҉҉҉字҉҉҉）、发芽字（发ོ芽ོ字ོ）、雾霾字（҈҈҈҈雾҈҈҈҈霾҈҈҈҈字҈҈҈҈）、禁止查看（禁⃠止⃠查⃠看⃠）、长草字（"҈长҉҉҈草҉҉҈字҉）、起风了（=͟͟͞͞风=͟͟͞͞太=͟͟͞͞大=͟͟͞͞）花漾输入；
+ 拼音输入扩展：支持繁体、简体，支持中英文混输，支持表情描述输入；
+ 剪切板：支持剪切板联想显示、剪切板及清空操作；
+ 常用语：支持自定义常用语、常用语快捷输入、编辑、删除等操作；
+ 底部快捷栏：默认使用系统底栏，可选增加输入法切换、剪贴板、常用语和滑动光标功能；
+ 隐藏输入法图标：支持隐藏输入法图标功能。

## 运行环境：
> Android Gradle Plugin：8.11.1；Gradle：8.14.3<br>
> kotlin-gradle-plugin：2.2.21<br>
> kotlin-serialization：2.2.21；KSP：2.2.21-2.0.5<br>
> ML Kit digital-ink-recognition：19.0.0（原生库支持 16 KB 页面）<br>
> Android minSdk：23  
> Android targetSdk：35  
> JDK: penjdk version "17.0.11" 2024-04-16

## 构建
### 1. 克隆此项目并拉取所有子模块。
```sh
git clone git@github.com:gurecn/yuyansdk.git
```
### 2. 导入Android Studio
建议使用最新、稳定版本，本人使用`Android Studio Narwhal Feature Drop | 2025.1.2`版本。
### 3 运行
#### 3.1 独立 Module 运行
导入项目后，执行`build.gradle`中task`makeaar`,待编译出对应的依赖资源后，放置到其他项目内进行依赖使用，具体调用方式参考[语燕输入法](https://github.com/gurecn/YuyanIme)
#### 3.1 应用内代码依赖
在Android Studio其他应用项目内，import Module操作导入项目后，在原应用`build.gradle`中添加项目依赖：`implementation project(':yuyansdk')`，然后更新项目即可。具体调用方式参考[语燕输入法](https://github.com/gurecn/YuyanIme)

## 键盘预览：

| 普通键盘 | 悬浮键盘 |
| --- | --- |
| ![普通键盘](https://raw.githubusercontent.com/daqiangganjun/YuyanSelfOpt/master/images/普通键盘.jpg) | ![悬浮键盘](https://raw.githubusercontent.com/daqiangganjun/YuyanSelfOpt/master/images/悬浮键盘.jpg) |

## 许可证与商业授权声明
本分支从 5.0.0 起使用 `native/` 中的自有 JNI 与固定版本的开源 librime，构建产物为 `libselfopt_rime.so`。旧 `libs/` 下的 `libyuyanime.so` 不再参与打包。以下闭源库授权说明保留作为原项目的历史声明，不适用于本分支自行实现的 JNI；所使用开源依赖的原始许可证随生成的词库包分发。

5.3.0 默认仅生成白霜资源，雾凇由 `native/publish_dictionaries.py` 生成独立下载包。`DictionaryStore` 管理资源校验、版本与组合部署；`data/transfer/` 实现导出恢复和按类别隔离的 WebDAV 多设备同步。同步范围包括设置、学习记录、常用语、剪贴板、主题、符号菜单、基础词库和词库更新规则，在「参与同步的数据」弹窗选择；默认冲突策略为保留最新，基础词库不会隐式拉取未选择的雾凇。连接凭据和更新/同步控制项仅保留本机，导出类别单独选择和记忆。5.4.1 将「词库与备份」下的入口拆为「词库」和「云同步」，点击「当前词库」选择或获取词库；默认使用内置官方源规则，支持手动 JSON 规则、网络规则和兼容包索引，仅显示当前方式需要的设置。更新仅针对所选词库，先计划依赖文件与总字节，按实际下载字节报告总进度和当前文件百分比。`dictionary/DictionaryBuildService` 在独立进程和临时目录完成资源适配、librime 编译、逐方案候选与提交测试，通过后才交给主进程启用；失败保留旧版。规则解析依赖固定的 SnakeYAML 2.3，Gradle 自动解析依赖。恢复默认入口不依赖引擎就绪，保留学习数据。

完整备份继续使用格式 2；分类备份使用格式 3，类别同时写入元数据及受 SHA256 校验的 `data.json`，读入时要求一致。恢复仅应用包含的类别，其余数据保留；旧版完整备份仍可导入。导出类别与 WebDAV 范围互不影响。

5.4.2 拼音串恢复原版上下 2dp 内边距，取消固定最小高度，保留点击编辑和宿主焦点保护；终端图案缩小约 15% 并调细线条，菜单和工具栏共用同一资源。

5.4.3 按键声音和触感分别使用 `key_press_sound_enabled`、`key_press_vibration_enabled` 保存开关，原强度键名与数值保留兼容；缺少新开关时按旧关闭档位推导。关闭不重置强度，滑条仅包含跟随系统和有效强度，跟随系统不覆盖全局触感或音效开关。新开关随普通设置导出和同步，设备保护存储复制时保留旧配置的有效开关状态。全拼直接使用原生 preedit，不根据候选注释再插入分词符。普通移动手柄与悬浮拖动条按手势起点计算逐像素纵向位移；英文补全候选与提交按实际输入编码保留逐字大小写，只有连续点击 Shift 才判定双击锁定。

5.3.1 自定义背景使用原图、归一化中心点和等比覆盖矩阵，随键盘尺寸重新取景，兼容旧裁剪记录与 EXIF 方向；工具栏高度不再使用动态按键间距。明暗模式由系统/手动状态决定，即使两槽位使用同一主题也刷新按钮选中态；照片菜单按钮与按键共用背景和蒙版，5.4.1 移除选中描边。拼音气泡使用 `ComposingBackgroundSampler` 采样实际背景左上角，尺寸变化时刷新并保证字色对比；非悬浮上圆下方，悬浮四角圆，点击编辑保留宿主焦点并刷新触摸区域。

本项目 [yuyansdk](https://github.com/gurecn/yuyansdk) 承诺，依据 [BSD-3-Clause license 许可证](./LICENSE) 条款**永久面向开源社区、个人用户免费开放使用，不上传输入数据**。为保障项目持续性，采用**双授权模式**：
### 一、开源代码授权（BSD 3-Clause）
您在遵守 BSD 3-Clause License 全部条款的前提下，可以自由地：
- 使用、研究本仓库中的公开源代码；
- 修改本仓库中的公开源代码；
- 分发本仓库公开源代码的原始或修改后的版本（需保留上述版权声明和免责声明）。
- **特别说明：** 根据 BSD 3-Clause 协议，您可以将这些公开源代码集成到您的任何项目（包括闭源商业项目）中，**无需**公开您的项目源码。
### 二、预编译闭源库（libyuyanime.so）的技术限制与商业授权
本项目分发的预编译闭源动态库 **不适用 BSD 3-Clause 协议**，其使用受以下条件及商业授权约束：
在您使用我们提供的**预编译二进制文件（.so）** 时，需遵守以下技术使用条款：
#### 未获得商业授权
- 您可以将 SDK 集成到您的应用中进行调试、测试或试用。
- **注意事项：**预编译库内置技术保护措施。集成时**不得擅自修改或更换应用的包名（Package Name）**，否则将导致核心验证失败，输入功能无法正常初始化或运行。
#### 获得商业授权
- 我们将为您指定的应用包名提供专属预编译库文件，**解除试用期及包名绑定的技术限制**，确保您的应用长期、稳定运行。
### 三、重要澄清
上述技术限制**仅针对我们分发的预编译闭源库文件**。  
**特别说明**：本仓库公开的源代码为上层调用框架及接口层代码，**不包含**我们对 librime 核心库的优化及修改源码。如果您希望构建一个不受包名限制的核心库，您有以下合法途径：
1. 依据 BSD 3-Clause 协议，自行获取并修改上游 [librime](https://github.com/rime/librime) 的原始代码，独立实现您所需的功能优化；
2. 联系我们获取商业授权版本，以获得无技术限制的预编译库。
### 四、上游版权声明
本 SDK 包含来自开源项目 [librime](https://github.com/rime/librime) 的修改代码。librime 遵循 **BSD 3-Clause License**。（点击展开查看完整文本）：

<details>
<summary>📄 librime BSD 3-Clause 许可证原文</summary>

Copyright (c) 2014, RIME Developers
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

* Redistributions of source code must retain the above copyright
  notice, this list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright
  notice, this list of conditions and the following disclaimer in
  the documentation and/or other materials provided with the
  distribution.

* Neither the name of the copyright holder nor the names of its
  contributors may be used to endorse or promote products derived
  from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

</details>

## 联系作者：
访问我的资源: <a href="https://github.com/gurecn">https://github.com/gurecn</a>

给我发送邮箱：[gurecn@163.com](mailto:gurecn@163.com)

## 鸣谢：
感谢以下优秀的开源社区贡献：
- [RIME](http://rime.im)
- [同文输入法](https://github.com/osfans)
- [小企鹅输入法](https://github.com/fcitx5-android/fcitx5-android)
- [雾凇拼音](https://github.com/iDvel/rime-ice)
- [白霜拼音](https://github.com/gaboolic/rime-frost)
