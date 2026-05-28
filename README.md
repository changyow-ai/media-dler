# media-dler

從任何 app「**分享**」一個連結過來，就自動解析並下載當中的**影片或圖片**。以 [yt-dlp](https://github.com/yt-dlp/yt-dlp) 為引擎（透過 [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)），一次涵蓋 YouTube、Instagram、TikTok、Threads、X / Twitter、Facebook、Reddit、Bilibili… 等 1000+ 平台。

![build](https://github.com/changyow-ai/media-dler/actions/workflows/build.yml/badge.svg)

- **平台**：Android 10（API 29）以上，原生 Kotlin + Jetpack Compose
- **散布**：sideload / F-Droid 取向，**不上 Google Play**（因此可自由使用 yt-dlp）
- **狀態**：v0.1.0 初版 — `:core` 單元測試綠燈、`assembleDebug` / `assembleRelease` 與 CI 建置通過；裝置端端對端測試請見[驗證](#驗證)。

---

## 功能

- **分享即下載**：在任意 app 點「分享 → media-dler」，或對選取文字用「處理文字」、或開啟連結。
- **貼上連結**：複製連結後在首頁按「貼上連結」，會讀剪貼簿解析並下載。
- **兩種模式**（可在設定切換）：
  - **一鍵模式**：直接用預設值（畫質 / 格式 / 位置）下載，進度顯示在通知列。
  - **彈窗模式**：跳出對話框，逐項選畫質、音訊或圖片再下載。
- **多媒體逐項勾選**：IG 輪播、多圖貼文等單一連結含多個 media 時，可勾選要下載哪些。
- **格式選擇**：最佳畫質、限制解析度（1080p / 720p / 480p / 360p）、音訊轉 **MP3 / M4A**（ffmpeg）、圖片原檔。
- **儲存位置**：預設公開的 `Download/media-dler/`（系統相簿 / 檔案 app 可見），亦可用 SAF 指定任意資料夾。
- **前景通知**：每個任務一條進度通知，完成後可點開檔案。
- **下載歷史**：首頁顯示進行中與歷史紀錄，可重試 / 移除 / 開啟。
- **引擎線上更新**：yt-dlp 可在 app 內更新，平台改版時不必重新發版。

---

## 運作方式

採兩個 module，把容易出錯的純邏輯與 Android 整合層分開：

- **`:core`** — 純 Kotlin / JVM，**無 Android 相依**。包含領域模型、`yt-dlp -J` 的 JSON 解析、URL 抽取、格式字串組裝與預設選擇等純函式，皆有 JUnit 單元測試（red/green TDD）。同時是未來抽出 KMP `:shared` 的基礎。
- **`:app`** — Android（Compose）。yt-dlp / ffmpeg 引擎整合、MediaStore / SAF 儲存、DataStore 設定與歷史、前景下載服務與 UI。以**手寫 DI**（`AppContainer`）取代 Hilt、用 **DataStore + JSON** 取代 Room — 整個專案不需要 KSP，建置更精簡。

下載流程：`ShareReceiverActivity` 取出 URL → `YtDlpMediaExtractor`（`yt-dlp -J` → 解析）→ 一鍵 / 彈窗決定 `DownloadRequest` → 丟進 `DownloadQueue` → 前景 `DownloadService` 逐筆下載到 cache → 複製進 MediaStore / SAF。

### 專案結構

```
core/                          # 純 Kotlin/JVM（可單元測試）
  src/main/kotlin/.../core/
    model/    MediaItem, MediaFormat, DownloadTask, FormatSelection, AppSettings…
    repo/     MediaExtractor, Downloader, SettingsRepository（介面）
    extract/  UrlExtractor, YtDlpInfoParser
    download/ FormatSelector, SelectionPlanner
  src/test/kotlin/...          # JUnit 測試
app/                           # Android（Compose）
  src/main/java/.../mediadler/
    MediaDlerApp, MainActivity, ShareReceiverActivity
    di/        AppContainer（手寫 DI）
    data/      ytdlp/ · storage/ · settings/ · history/
    download/  DownloadQueue, DownloadService, Notifications
    ui/        theme/ · home/ · picker/ · settings/ · common/
.github/workflows/             # build.yml（測試＋debug）、release.yml（簽章發佈）
```

### 技術棧

Kotlin 2.0、Jetpack Compose（Material 3）、Navigation Compose、Coroutines / Flow、DataStore、Coil、kotlinx.serialization、youtubedl-android（`library` + `ffmpeg`）`0.18.1`。AGP 8.7、JDK 17、compileSdk 35。

---

## 開始開發

需求：**JDK 17** 與 Android SDK（`platforms;android-35`、`build-tools;35.0.0`）。

```bash
# 純邏輯單元測試（不需模擬器，最快的回饋）
./gradlew :core:test

# 建置 debug APK
./gradlew assembleDebug      # 產物：app/build/outputs/apk/debug/app-debug.apk
```

> 提示：`local.properties` 需指向你的 Android SDK（`sdk.dir=...`）。

---

## 發佈（Release）

release 版用 **ABI split** 縮小體積（原生 Python + ffmpeg 是大宗，每個 ABI 各出一支）。**R8 目前關閉**：minify 只在 release 跑，曾導致引擎初始化失敗（詳見 [plan](plan/media-dler-plan.md) 的實測記錄），驗證可靠前先關，體積交給 split。

| APK | 大小 |
| --- | --- |
| `app-arm64-v8a-release.apk`（多數手機） | ~57 MB |
| `app-armeabi-v7a-release.apk` | ~50 MB |
| `app-x86-release.apk` / `app-x86_64-release.apk` | ~55 / 60 MB |
| `app-universal-release.apk`（含全部 ABI） | ~202 MB |

簽章金鑰（`release-keystore.jks` + `keystore.properties`）**直接放在 repo 內**——這是 sideload app，金鑰不具敏感性。本機手動建置：

```bash
./gradlew assembleRelease    # 產物在 app/build/outputs/apk/release/
```

**自動發佈**：`release.yml` 在 push 到開發分支時發佈一個滾動的 **`dev` 預發佈**（CI 以 `GITHUB_TOKEN` 在伺服器端建 tag / release）；推 `v*` tag 則發佈正式版：

```bash
git tag v0.1.0 && git push origin v0.1.0
```

---

## 安裝

到 [Releases](https://github.com/changyow-ai/media-dler/releases) 下載：

- 多數現代手機選 **`arm64-v8a`**；不確定就用 **`universal`**（較大但通用）。
- 安裝時需允許「安裝未知來源應用程式」。

## 使用

1. 在任一 app（瀏覽器、YouTube、IG…）點**分享**，選 **media-dler**。
2. 一鍵模式直接下載；彈窗模式選好畫質 / 音訊 / 勾選項目再下載。
3. 首頁查看進度與歷史；下載完成的檔案在 `Download/media-dler/`（或你指定的 SAF 資料夾）。

---

## CI

- **`build.yml`**：每次 push → 跑 `:core:test`（red/green 閘門）→ `assembleDebug`。
- **`release.yml`**：推 `v*` tag → `assembleRelease` → 上傳各 ABI 簽章 APK 到 Releases。

---

## 注意與限制

- **平台支援度取決於 yt-dlp**：絕大多數站由 yt-dlp 處理。**Threads 沒有官方 extractor** — 影片靠改寫 `/embed` 取得（可下載），**純圖 / 多圖貼文不支援**（embed 只給首圖，完整輪播需登入）。
- **下載失敗先更新引擎**：打包的 yt-dlp 會過期（YouTube 尤其常壞）。app 會在啟動與解析失敗時自動更新，也可到「設定 → 下載引擎 → 更新 yt-dlp」手動更新。
- **裝對 ABI**：native 函式庫需與裝置相符；不確定就裝 `universal`。
- 部分內容（IG / FB 私人貼文等）需登入；目前僅支援公開內容，cookie 登入留待後續。
- 進度回呼以行解析，百分比為盡力呈現；UI 以「解析中 / 下載中 / 完成」狀態為主。
- 請尊重各平台服務條款與著作權，僅下載你有權保存的內容。

## 致謝

本專案站在這些之上：[yt-dlp](https://github.com/yt-dlp/yt-dlp)、[FFmpeg](https://ffmpeg.org/)、[youtubedl-android](https://github.com/JunkFood02/youtubedl-android)。

詳細設計與實作筆記見 [`plan/media-dler-plan.md`](plan/media-dler-plan.md)。
