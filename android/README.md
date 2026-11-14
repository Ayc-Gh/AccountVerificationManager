# 账号验证管家 Android

账号验证管家的 Android 原生应用。v1.1.0 提供智能识别：账号记录、纯 2FA 密钥、Authenticator URI。Android 工程位于仓库的 `android/` 目录，可独立构建。

[下载 Android v1.1.0](https://github.com/Ayc-Gh/AccountVerificationManager/releases/tag/android-v1.1.0) · [Windows 版](https://github.com/Ayc-Gh/AccountVerificationManager/releases/tag/v1.4.0)

支持 Android 8.0 及以上，无网络权限，无第三方运行时依赖。界面支持系统深色模式。

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

脚本首次生成专用签名 `signing/release.jks` 和本地密码文件，以后始终复用；应单独备份整个 signing 目录以便升级。不要将签名文件提交或公开分享。源码压缩包排除此目录。输出文件名按版本号生成，当前为 `release/账号验证管家-v1.1.0.apk`，同时执行单元测试、发布版 lint 和 APK 签名验证。

## 真实设备测试

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am force-stop cn.local.accountmanager.qa
adb shell am instrument -w cn.local.accountmanager.qa.test/cn.local.accountmanager.AcceptanceInstrumentation
```

测试目标包为 `cn.local.accountmanager.qa`，与正式包 `cn.local.accountmanager` 的数据完全分开。设备测试只使用虚构数据，并在成功后清除测试历史；`-e preview true` 会在测试过程中将本应用窗口的截图保存到测试包的外部私有目录 `files/screenshots`，不保留演示账号。`build.ps1 -DeviceTests` 会附加生成两个测试 APK，设备执行仍使用上述 adb 命令。单元测试报告在 `app/build/reports/tests/testDebugUnitTest`；功能对应关系见 [迁移分析](docs/项目理解与功能对应.md)。

当前发布产物及验收范围见 [验证报告](docs/验证报告.md)。[纯密钥](docs/preview-secret.png)、[Authenticator URI](docs/preview-uri.png)、[URI 识别结果](docs/preview-uri-details.png)、[三种类型历史](docs/preview-smart-history.png) 均为虚构数据的真机窗口截图。

## 结构

`core/SmartParser` 区分三种输入，`AuthenticatorUri` 解析和生成 URI，`AccountParser` 保留原账号分隔规则，`OtpParameters` 和 `Totp` 处理实际验证码参数。`HistoryCodec` 写入 v2 数据并兼容读取 v1；加密文件封装、Keystore 别名和密钥保持兼容。`storage/` 串行异步读写，`MainActivity` 提供原生视图与生命周期处理。修改屏幕方向时仅在内存保留当前结果，进程退出后从历史手动恢复；不将凭据写入 Activity Bundle。

## 边界

Windows 的 DPAPI 历史无法直接在 Android 解密。Android v1.0.0 的历史可直接升级保留。支持单条 TOTP URI，不支持 HOTP、Google Authenticator 批量迁移 URI 或二维码导入。手机需保持正确的系统时间。历史不是跨设备备份，卸载会删除。复制内容受系统和输入法剪贴板设置影响。

实现参考：[Authenticator Key URI](https://github.com/google/google-authenticator/wiki/Key-Uri-Format)、[Android Keystore AES-GCM](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)、[敏感剪贴板](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)、[RFC 6238](https://www.rfc-editor.org/rfc/rfc6238)。
