# Huawei NFC E-Ink · myron 改造分支

基于 [ZhaoGY-N/huawei-nfc-eink-android-app](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app) v2.11.1 的改造版本，聚焦两件事：**更多机型能刷**、**出图更好看**。

## 相对上游的增量

1. **SN100U HAL 兼容层**：芯片门禁从 SN220 扩展到 SN100U（同族同符号，`nfc_nci.nqx.default.hw.v1.so` 等后缀变体由正则覆盖），Redmi Note 12T Pro 实机验证。
2. **前台直连架构**：root 只负责安装/卸载兼容层，刷屏会话由前台 App 以纯标准 API（`enableReaderMode` + `IsoDep`）完成——绕开 worker 调 `setReaderMode` 的三类系统差异（4/5 参签名、UID 0 包名 NPE、MTK `makeApplicationInner` NPE）。
3. **出图算法：清晰锐利三档 + 网点版画**（从 [wiwiwei/ink-nfc-lab](https://github.com/wiwiwei/ink-nfc-lab) 整包移植，逐字节 PORT-EXACT 验证）。

## 实机验证

| 机型 | NFC 芯片 | 结果 |
|---|---|---|
| Xiaomi K90 Pro Max | SN220 | 全链路可用 |
| Redmi Note 12T Pro | SN100U + 兼容层 | 寻卡 → D1 → 476 包 → D4 → 忙/空闲 → 画面完整 |

适配过程数据见上游 [Issue #5 回帖](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/5#issuecomment-6393811055)。

## 下载

[Releases](https://github.com/yhl88890/huawei-nfc-eink-myron/releases) · 最新 v2.11.1-myron19

⚠️ 调试签名构建，与官方 APK 签名不同，**不能覆盖安装**。需要 root + Shizuku（小米/MTK 机型建议 [thedjchi fork 13.7.0](https://github.com/thedjchi/shizuku)）。

## 许可

跟随上游 PolyForm Noncommercial 1.0.0（仅限非商业用途）。感谢上游作者 ZhaoGY-N 与 [HBWuChang 的 Issue #3](https://github.com/ZhaoGY-N/huawei-nfc-eink-android-app/issues/3) 公开的适配思路，第三方参考与许可见上游 THIRD_PARTY_NOTICES.md。

## 源码

随仓库分支更新上传；当前 Release 附 APK。
