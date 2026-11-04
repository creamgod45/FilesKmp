# Windows 打包流程

只建置 Windows x64 與 ARM64，各產生 MSI、內含 Java runtime 的免安裝 ZIP，以及 SHA256SUMS。

- Pull request：建置、啟動檢查、上傳 Actions artifacts，不建立 Release。
- 推送 `v1.2.3` 形式的 tag：使用 tag 版本，兩個架構皆成功後發佈 Release。
- Actions → Windows Packages → Run workflow：輸入 `1.2.3`；預設建立草稿 Release。
- 重跑同版本會覆蓋該 Release 的同名附件。

專案 Git 根目錄是此 `app` 資料夾，workflow 從根目錄執行 `gradlew.bat windowsMsi`。
本機可用 `./gradlew.bat windowsMsi '-PappVersion=1.2.3'`，須有 JDK 25 和 WiX 3.14.1（candle/light 在 PATH）。

ARM64 預設使用 `windows-11-arm`，必須搭配 ARM64 JDK，不能在 x64 JDK 上交叉打包。
GitHub 私人儲存庫若無法使用標準 ARM64 runner，請配置 Windows ARM64 larger runner，
並將 repository Actions variable `WINDOWS_ARM64_RUNNER` 設為該 runner 的 label。
參考：https://docs.github.com/en/actions/reference/runners/github-hosted-runners

CI 檢查 launcher 和 JVM DLL 的 PE 架構，再執行打包程式的 screenshot 模式，確保 runtime 與繪圖原生函式庫可載入。
MSI 尚未簽章。流程只需內建 GITHUB_TOKEN，release job 使用 contents: write。
