# 账号验证管家 Android

账号验证管家的 Android 原生应用。当前正式版本为 **1.10.1**，支持 OpenAI / Claude 官方网页授权、历史账号自动填充以及 CPA / sub2api JSON 导出。Android 真实网页登录与目标服务导入未验收；Windows OpenAI 真实登录与双格式保存已通过。Claude 按用户要求停止真实账号核验。同时保留“数字 + 分 / 时 / 天 / 周 / 月”保存期限、历史置顶/删除及多选密码加密导入导出。首次保存时开始计时，1 月按 30 天计算。原有功能提供历史记录命名、二维码拍照/上传与智能识别：账号记录、纯 2FA 密钥、Authenticator URI。Android 工程位于仓库的 `android/` 目录，可独立构建。

[Android v1.10.1 APK](https://github.com/Ayc-Gh/AccountVerificationManager/releases/download/android-v1.10.1/AccountVerificationManager-android-v1.10.1.apk) · [Windows v1.10.1](https://github.com/Ayc-Gh/AccountVerificationManager/releases/download/v1.10.1/AccountVerificationManager-v1.10.1-win-portable.zip) · [统一项目接手说明](../docs/项目接手.md)

**Android 旧版用户请先备份。** 1.10.1 启用新的发布签名。请先在旧版的历史管理中选中所有需保留的记录，导出加密 `.avmx` 文件，确认文件保存在卸载后仍可访问的位置，并记住备份密码。再卸载旧版、安装 1.10.1 并导入历史。 详见 [迁移说明](../docs/v1.10.1发布说明.md)。

支持 Android 8.0 及以上，解析与验证码离线运行、登录按需联网；自动填充需 Android 9.0+。二维码解码使用内置 ZXing，拍照文件使用 AndroidX FileProvider。界面支持系统深色模式。

## 更换 2FA

先识别或从历史恢复目标账号，点击验证码卡片右上角“更换 2FA”。可复制旧验证码，粘贴新密钥或导入二维码，预览并复制新验证码；确认服务端换绑完成后点击“保存替换”。Windows 支持上传图片，Android 支持拍照和上传。

更换保留原记录的账号、密码、名称、排序和时间。纯密钥沿用当前 TOTP 参数，URI 使用新参数且不覆盖当前账号。取消、无效输入或保存失败保留原记录；其他窗口已更新的记录会拒绝覆盖。

带自定义参数的账号记录会以末项 URI 保留参数，重新识别不会退回默认值。新版更换后的记录请继续使用 1.8.0 或后续版本。

## 二维码导入

点击“拍照识别二维码”调用系统相机，确认照片后识别；点击“上传二维码图片”从系统文件选择器选择图片。图像在本机解码，支持纯密钥与标准 TOTP URI，保留算法、位数和周期。同图多个有效二维码可选择一条。取消不会改动当前结果，失败会使旧复制按钮失效。已恢复防截图、录屏及最近任务预览保护，复制内容使用敏感标记隐藏系统预览。

两端正式版本统一为 1.10.1，Android versionCode 为 11001。1.9.x 的期限管理与加密备份已包含，详情见 [历史管理与加密导入导出](../docs/v1.9.0历史管理与加密导入导出.md)。

## 历史命名

在历史记录右侧点击“命名 / 改名”，输入最多 40 个字符并保存。留空恢复默认显示，取消不保存；同一条记录再次识别时保留名称，名称随历史在本机加密保存。

## 功能

- **智能识别**：直接粘贴完整账号记录、纯 Base32 2FA 密钥或 `otpauth://totp/...`，自动区分类型。
- **Authenticator URI**：解析账号、发行方、密钥、算法、位数和周期；支持 SHA1/SHA256/SHA512、6/8 位、正整数秒周期。省略参数时采用 SHA1 / 6 位 / 30 秒。
- **纯密钥**：支持大小写、分组空白和标准 Base32 填充，按 SHA1 / 6 位 / 30 秒生成验证码。
- 自动识别连续 3 个及以上标点/符号，支持不同长度横线、全角符号、混合符号和 Unicode 表情；保留手动精确匹配。
- 使用第一处和最后一处分隔符；保留密码中的内部符号、大小写、首尾空格。
- 账号记录沿用 SHA1 / 6 位 / 30 秒 TOTP；URI 验证码和倒计时遵从其实际参数。
- 逐项复制与复制全部；复制时计算当前验证码，敏感剪贴板标记隐藏系统预览。
- 最近 10 条成功记录，完全相同凭据去重置顶；历史点击恢复，四个快捷复制按钮。
- Android Keystore AES-256-GCM 加密持久化、原子写入、禁用备份；损坏文件不会被解析操作覆盖。
- 输入或解析模式变更立即清除旧结果，防止复制到旧账号的验证码。
- 只展示实际存在的账号、密码和发行方；URI 可一键复制为包含全部验证码参数的规范化 URI。
- 兼容 v1.0.0 加密历史；新记录保留类型和完整 TOTP 参数，重启恢复后不丢失。

## 输入示例

下面均为公开标准测试密钥和虚构账号。

```text
demo@example.com------Demo%_Password*-----GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ
otpauth://totp/Example:alice%40example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example&algorithm=SHA256&digits=8&period=60
```

URI 仅在本机解析，不打开链接或访问网络。缺少 `secret`、参数重复、发行方冲突、不支持的算法/位数和无效周期均会给出明确提示。

## 构建

Windows PowerShell 7、JDK 21、Android SDK platform 37 和 build-tools 37.0.0。

```powershell
.\build.ps1 -JdkPath 'C:\path\jdk-21' -SdkPath 'C:\path\android-sdk'
# 依赖已缓存时
.\build.ps1 -Offline
```

构建可覆盖升级的正式版前，请从私有备份恢复 `android/signing/release.jks` 和 `release.properties`。正式构建会核对密钥别名对应的证书，导出到 `release/` 前还会验证生成的 APK 签名。校验依据是 1.10.1 起使用的发布证书 SHA-256，见[签名更换记录](../docs/v1.10.1发布说明.md)。签名材料应单独备份，源码包排除了整个签名目录。脚本同时执行单元测试和发布版 lint，输出文件按版本号命名，例如 `release/账号验证管家-v1.10.1.apk`。

仅开发或测试时，可在配置好 JDK、SDK 后从 `android/` 运行 `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`，使用独立 QA 包和调试签名。

维护发布流程时可运行 `.\test-release-signing.ps1`。该检查在临时目录验证缺失、错误及匹配证书的情形，并检查正式产物的复制时机。依赖已缓存时可加 `-Offline`。

## 真实设备测试

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am force-stop cn.local.accountmanager.qa
adb shell am instrument -w cn.local.accountmanager.qa.test/cn.local.accountmanager.AcceptanceInstrumentation
```

测试目标包为 `cn.local.accountmanager.qa`，与正式包 `cn.local.accountmanager` 的数据完全分开。上述 instrumentation 使用虚构数据，在成功后清除 QA 历史。设备验收保留应用的截图和录屏保护。`build.ps1 -DeviceTests` 会附加生成两个测试 APK，设备执行仍使用上述 adb 命令。单元测试报告在 `app/build/reports/tests/testDebugUnitTest`；功能对应关系见 [迁移分析](docs/项目理解与功能对应.md)。

文件选址和进程回收的专项回归由电脑端驱动。先启动带系统文件选择器和浏览器的 QA 模拟器，在该模拟器安装本次 debug APK，然后从 `android/` 用 PowerShell 7 运行以下两项。将参数替换为实际模拟器的序列号和 AVD 名称；若 adb 未加入 PATH，可另传 `-AdbPath`。

```powershell
pwsh -File ./test-json-picker.ps1 -Serial emulator-5554 -AvdName My_QA_AVD
pwsh -File ./test-json-process-recovery.ps1 -Serial emulator-5554 -AvdName My_QA_AVD
```

第一项在系统选择器等待选址时旋转，并从 Downloads 读回 CPA 和 sub2api 文件。第二项在选择器前台时终止后台 QA 进程，再从选择器返回并发起一次新的手动登录。两项均使用虚构账号和独立文件名，可以在离线模拟器上运行。

1.10.0 发布产物及验收范围见 [登录与文件生成](../docs/v1.10.0登录与文件生成.md)；[早期验证报告](docs/验证报告.md) 保留历史验收范围。[纯密钥](docs/preview-secret.png)、[Authenticator URI](docs/preview-uri.png)、[URI 识别结果](docs/preview-uri-details.png)、[三种类型历史](docs/preview-smart-history.png) 均为虚构数据的真机窗口截图。

## 结构

`core/SmartParser` 区分三种输入，`AuthenticatorUri` 解析和生成 URI，`AccountParser` 保留原账号分隔规则，`OtpParameters` 和 `Totp` 处理实际验证码参数。`HistoryCodec` 写入 v4 数据并兼容读取 v1/v2/v3；加密文件封装、Keystore 别名和密钥保持兼容。`storage/` 串行异步读写，`MainActivity` 提供原生视图与生命周期处理。修改屏幕方向时仅在内存保留当前结果，进程退出后从历史手动恢复；不将凭据写入 Activity Bundle。

## 边界

Windows 的 DPAPI 历史无法直接在 Android 解密。Android v1.0.0 的历史可直接升级保留。支持单条 TOTP URI 及其二维码，不支持 HOTP 或 Google Authenticator 批量迁移 URI/二维码。手机需保持正确的系统时间。历史不是跨设备备份，卸载会删除。复制内容受系统和输入法剪贴板设置影响。

实现参考：[Authenticator Key URI](https://github.com/google/google-authenticator/wiki/Key-Uri-Format)、[Android Keystore AES-GCM](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)、[敏感剪贴板](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)、[RFC 6238](https://www.rfc-editor.org/rfc/rfc6238)。
