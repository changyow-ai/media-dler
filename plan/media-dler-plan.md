# 計劃:media-dler — Android 分享下載 App

## Context(為什麼做這個)

使用者想做一個 Android app:當從其他 app「分享」一個 URL 過來時,能自動解析並下載該 URL 內的 **影片或圖片**,盡量支援所有主流平台(YouTube、Instagram、TikTok、Threads、X/Twitter、Facebook、Reddit、Bilibili…)。

目前 repo 為全新空專案。經提問收斂出的需求:

- **引擎**:採用 yt-dlp(透過 `youtubedl-android` 函式庫),一次涵蓋 1000+ 平台,維護成本最低。
- **技術棧**:原生 Kotlin + Jetpack Compose。
- **散布**:sideload / F-Droid(不上 Google Play,故可自由使用 yt-dlp,不受 Play 禁止 YouTube 下載的政策限制)。
- **最低版本**:Android 10(API 29)。
- **分享流程**:兩種模式,設定可切換 —
  - *一鍵模式*:直接用使用者在設定中存的預設值(畫質/格式/位置)下載,進度顯示在通知列。
  - *彈窗模式*:分享後顯示對話框,可選畫質、解析度、音訊轉檔、圖片等。
  - 兩種模式下,**若單一 URL 含多個 media(如 IG 輪播、多圖貼文),都要能逐項勾選**要下載哪些。
- **格式需求**:最佳畫質影片、可選解析度(1080p/720p/480p…)、音訊轉 MP3/M4A(需 ffmpeg)、圖片下載(含多圖貼文)。
- **儲存位置**:預設 `Download/media-dler/`;使用者可改(用 SAF 讓使用者指定目錄,並請求必要權限)。檔案落在公開 Downloads 讓系統相簿/檔案 app 可見。
- **未來性**:預留未來以同一份規劃做 iOS / Kotlin Multiplatform(iOS 端可能改存照片庫)。故領域層(設定、下載任務模型、平台無關邏輯)與 Android/引擎實作分離。

## 架構總覽

採單一 `:app` module 起步,但內部分層清楚,為未來抽出 KMP `:shared` 預留:

```
app/
  src/main/java/.../mediadler/
    MainActivity.kt              // Compose 首頁(歷史/設定入口)
    ShareReceiverActivity.kt     // 接收 ACTION_SEND / ACTION_VIEW 的透明 Activity
    di/                          // Hilt module
    domain/                      // *平台無關*,未來搬到 KMP shared
      model/  (MediaItem, MediaFormat, DownloadTask, DownloadStatus, AppSettings)
      repo/   (interface: MediaExtractor, Downloader, SettingsRepository)
    data/
      ytdlp/  YtDlpMediaExtractor.kt   // 包 youtubedl-android:getInfo→MediaItem 列表
              YtDlpDownloader.kt       // 包 YoutubeDLRequest 執行下載+進度回呼
      storage/ MediaStoreStorage.kt / SafStorage.kt
      settings/ DataStoreSettingsRepository.kt   // Jetpack DataStore
    download/
      DownloadService.kt         // foreground service,跑佇列+前景通知
      DownloadQueue.kt
    ui/
      home/  HomeScreen.kt + ViewModel        // 下載歷史/進行中清單
      picker/ MediaPickerScreen.kt / Dialog   // 彈窗模式:列出多 media + 格式選擇,勾選下載
      settings/ SettingsScreen.kt + ViewModel  // 模式切換、預設畫質、預設位置
```

## 關鍵相依套件

- `io.github.junkfood02.youtubedl-android:library`(yausername youtubedl-android 的維護分支,含 yt-dlp Python runtime)
- 同系列 `:ffmpeg`(音訊轉檔/合流)、`:aria2c`(可選,加速)
- Jetpack Compose、Hilt、DataStore、Kotlin Coroutines/Flow
- WorkManager 或自建 foreground `Service`(下載需長時間+前景通知,選 foreground Service 較直接;佇列用 coroutine)

> 註:`youtubedl-android` 會把 Python + yt-dlp 打包進 APK(體積約 +30~50MB,含多 ABI;可用 `abiFilters` 拆分 split APK 縮小)。yt-dlp 本身可在 app 內 `YoutubeDL.updateYoutubeDL()` 線上更新,平台改版時不必重發版。

## 實作步驟

### 1. 專案骨架
- 建立 Gradle(Kotlin DSL)、`:app` module、`minSdk 29` / `targetSdk` 最新、Compose、Hilt。
- `AndroidManifest.xml`:
  - `ShareReceiverActivity` 註冊 intent-filter:`ACTION_SEND`(mimeType `text/plain`)+ `ACTION_VIEW`(http/https),`ACTION_PROCESS_TEXT` 可選。
  - 權限:`INTERNET`、`POST_NOTIFICATIONS`(API 33+)、`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`。API 29 用 MediaStore 寫 Downloads 不需 WRITE_EXTERNAL_STORAGE。

### 2. 引擎整合(data/ytdlp)
- App 啟動時 `YoutubeDL.getInstance().init(app)`、`FFmpeg.getInstance().init(app)`(放 `Application.onCreate`,背景執行緒)。
- `YtDlpMediaExtractor`:輸入 URL → 用 `getInfo`/`--dump-single-json`(對輪播/playlist 用 flat 列舉 entries)→ 轉成 `List<MediaItem>`,每個 item 帶可用 `MediaFormat`(解析度、是否含音訊、副檔名、是否圖片)。
- `YtDlpDownloader`:依選定 `MediaItem`+`MediaFormat` 組 `YoutubeDLRequest`:
  - 影片最佳:`-f bv*+ba/b`;指定解析度:`-f bv[height<=720]+ba/b`;
  - 音訊:`-x --audio-format mp3/m4a`;
  - 圖片:直接取 URL 下載(yt-dlp 對純圖貼文回傳直連)。
  - 透過 callback 回報進度 % → 更新通知與 UI。

### 3. 儲存(data/storage)
- 預設 `MediaStoreStorage`:寫入 `MediaStore.Downloads`,relative path `Download/media-dler/`。
- yt-dlp 需要實體檔案路徑,故策略:先下載到 app cache,完成後用 `MediaStore` insert + `ContentResolver` 複製進公開 Downloads(API 29 scoped storage 友善)。
- 設定可切到 `SafStorage`:`ACTION_OPEN_DOCUMENT_TREE` 取得 tree uri 持久化,下載完成寫入該目錄。

### 4. 設定(data/settings + ui/settings)
- DataStore 存 `AppSettings`:`shareMode`(ONE_TAP / ASK)、`defaultVideoQuality`、`defaultFormat`(video/audio)、`storageMode`(downloads / saf tree uri)。

### 5. 分享入口與流程(ShareReceiverActivity + ui/picker)
- 從 intent 取出 URL(text/plain 內可能夾雜文字,用 regex 抽 URL)。
- 先呼叫 extractor 取得 media 清單:
  - **一鍵模式**:單一 media → 直接用設定值丟進下載佇列、關閉 Activity、通知列顯示進度。多 media → 仍跳輕量勾選(或設定「多項時全下載」)。
  - **彈窗模式**:顯示 `MediaPickerScreen`:列出所有 media 縮圖 + 每項格式/解析度/音訊選項 + 勾選框,確認後送佇列。
- extractor 解析可能耗時 → 顯示 loading;失敗顯示可讀錯誤。

### 6. 下載服務與通知(download/)
- Foreground `DownloadService` 跑 `DownloadQueue`(coroutine,序列或限制併發),每個任務一條進度通知,完成點擊可開啟檔案/相簿。

### 7. 首頁(ui/home)
- 顯示進行中 + 歷史紀錄(Room 或 DataStore 簡易存),可重試/刪除/開啟。

## 風險與注意
- **APK 體積**:Python runtime 大;用 ABI split / app bundle(sideload 用 universal 或多 ABI APK)。
- **平台反爬**:IG/FB 部分內容需登入;先支援公開內容,登入(cookie)留作後續。
- **進度回呼**:youtubedl-android 進度回呼以行解析,精度有限,UI 以「解析中/下載中/完成」狀態為主,百分比盡力呈現。
- **未來 KMP**:`domain/` 保持純 Kotlin、無 Android 相依,方便日後抽 `:shared`;引擎與儲存是 platform-specific,iOS 需另尋方案(yt-dlp 在 iOS 困難,屬未來題)。

## 驗證方式(end-to-end)
1. `./gradlew assembleDebug` 編譯成功;安裝到 Android 10+ 實機/模擬器。
2. 開啟 app 確認首次啟動完成 yt-dlp/ffmpeg init(看 log 無錯)。
3. 從 YouTube app「分享 → media-dler」:
   - 彈窗模式:出現格式選單,選 720p 下載,檔案出現在 `Download/media-dler/`,相簿可見且可播放。
   - 切一鍵模式:再分享一次,直接下載、通知列顯示進度與完成。
4. 分享 IG/Threads 多圖貼文 → 確認列出多個 media 可逐項勾選下載。
5. 音訊:選 MP3,確認輸出可播放的 .mp3。
6. 設定改成 SAF 指定目錄,重下載確認寫入該目錄。
7. 測試非法/不支援 URL → 顯示友善錯誤,不崩潰。
```
