# 計劃:media-dler — Android 分享下載 App

> 本計劃已於首次實作完成並驗證(`:core` 單元測試綠燈、`assembleDebug` / `assembleRelease` 成功、CI 建置)。下文已依實作經驗修正;**標 ⚠️ 的是首次實作踩到的坑與正確作法,照做即可直接避開。**

## Context(為什麼做這個)

使用者想做一個 Android app:當從其他 app「分享」一個 URL 過來時,能自動解析並下載該 URL 內的 **影片或圖片**,盡量支援所有主流平台(YouTube、Instagram、TikTok、Threads、X/Twitter、Facebook、Reddit、Bilibili…)。

經提問收斂出的需求:

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

採 **兩個 module**:純 Kotlin 的 `:core`(無 Android 相依,可直接 JVM 單測,也是未來 KMP `:shared`)+ Android 的 `:app`。⚠️ 把「容易出錯的純邏輯」(URL 抽取、yt-dlp JSON 解析、格式字串、預設選擇)放進 `:core` 用 JUnit 做 red/green TDD;Android 層只剩無法單測的整合碼,交給 CI 編譯把關。

```
core/                          // 純 Kotlin/JVM,無 Android 相依
  model/    MediaItem, MediaFormat, DownloadTask, DownloadStatus,
            DownloadRequest, FormatSelection, AppSettings(+enums)
  repo/     MediaExtractor, Downloader, SettingsRepository (介面)
  extract/  UrlExtractor, YtDlpInfoParser          // 有單元測試
  download/ FormatSelector, SelectionPlanner        // 有單元測試
  test/     *Test.kt (JUnit, red/green)

app/
  MediaDlerApp.kt              // Application:建立 AppContainer、背景 init 引擎
  MainActivity.kt              // Compose 首頁(NavHost: home / settings)
  ShareReceiverActivity.kt     // 透明 Activity,接 ACTION_SEND / VIEW / PROCESS_TEXT
  di/ AppContainer.kt          // ⚠️ 手寫 DI(不用 Hilt,省掉 KSP 工具鏈)
  data/
    ytdlp/    EngineInitializer, YtDlpMediaExtractor, YtDlpDownloader
    storage/  MediaStoreStorage, SafStorage
    settings/ DataStoreSettingsRepository
    history/  HistoryStore      // ⚠️ DataStore + JSON(不用 Room)
  download/   DownloadQueue, DownloadService, Notifications
  ui/  theme/ home/ picker/ settings/ common/(共用 Composable)
```

## 已驗證的版本與技術決策

⚠️ 以下是實際編譯通過的組合;沿用可省去「版本/座標/套件名」的試錯。

**相依(放 `gradle/libs.versions.toml`,皆可從 Maven Central 取得):**
- `io.github.junkfood02.youtubedl-android:library:0.18.1` + `:ffmpeg:0.18.1`
  - ⚠️ **groupId 與 Java 套件名不同**:程式裡 import 的是 `com.yausername.youtubedl_android.*`、`com.yausername.ffmpeg.FFmpeg`。
  - `:aria2c` 可選(加速),本次未用。
- AGP `8.7.3`、Kotlin `2.0.21`;Compose 用 BOM `2024.12.01`,編譯器套件為 `org.jetbrains.kotlin.plugin.compose`(Kotlin 2.0 起必須)。
- AndroidX:core-ktx `1.15.0`、lifecycle `2.8.7`(含 `lifecycle-runtime-compose` 供 `collectAsStateWithLifecycle`)、activity-compose `1.9.3`、navigation-compose `2.8.5`、datastore-preferences `1.1.1`、documentfile `1.0.1`。
- coroutines `1.9.0`、kotlinx-serialization-json `1.7.3`、Coil `2.7.0`。
- 以 **JDK 17** 跑 AGP;Gradle wrapper 8.14.x。

**刻意的簡化(musk-review):移除整條註解處理工具鏈,建置更穩更快。**
- ⚠️ **不用 Hilt** → `AppContainer` 手寫 DI,由 `Application` 持有;Composable 以 `viewModel(factory = viewModelFactory { initializer { … } })` 取得 ViewModel。
- ⚠️ **不用 Room** → 歷史用 `HistoryStore`(DataStore + kotlinx.serialization JSON,只在終態寫入)。
- 結果:**整個專案不需要 KSP**(只需 AGP + kotlin-android + compose + serialization plugin)。

## yt-dlp 整合契約(youtubedl-android 0.18.1 實測 API)

⚠️ 照此呼叫,免去反組譯 AAR:
- 初始化:`YoutubeDL.getInstance().init(app)`(會丟 `YoutubeDLException`)、`FFmpeg.getInstance().init(app)`。放背景執行緒;用一個 `EngineInitializer` 以 `Mutex` 確保只成功初始化一次,且**失敗要可重試**(只在 `Ready` 狀態短路,`Failed` 下次再試)。
- 解析:⚠️ **不要用 `getInfo()` / `VideoInfo`(走 Jackson)**。改 `execute(YoutubeDLRequest(url).addOption("-J").addOption("--no-warnings"))`,讀 `response.out` 拿 JSON,**自己用 kotlinx.serialization 解析**。如此輪播/多圖貼文(`entries`)才好處理,也不被 VideoInfo 模型綁死。
- 下載:`execute(request, processId, cb)`,`cb` 型別是 `(Float, Long, String) -> Unit`(progress 0–100,-1 代表未知)。
- 組指令:`YoutubeDLRequest(url).addOption(k[, v])` 或 `addCommands(List<String>)`。
- 取消:`YoutubeDL.getInstance().destroyProcessById(processId)`。
- ⚠️ **線上更新是必要功能,不是可選**:打包進 APK 的 yt-dlp 很快就過期(尤其 YouTube 三天兩頭改版,舊版會抓不動)。用 `updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE)`(回傳 `UpdateStatus`)+ `version(app)`。實作:**啟動時背景更新一次** + 設定頁手動「更新 yt-dlp」按鈕 + **解析失敗時自動 update 再重試一次**(self-heal)。少了它,使用者裝好後第一個遇到的就是「YouTube 不能下載」。
- ⚠️ **平台支援度 = yt-dlp 有沒有對應 extractor**:例如撰寫時 yt-dlp **沒有 Threads extractor**(threads.com / threads.net 都不匹配),會 fallback 到 `generic` 硬爬網頁,常常只抓到 og:title / og:image 之類垃圾(會顯示成像亂碼的標題)。對策:偵測到 `generic` 或抓不到真正可下載媒體時,給明確「不支援或需登入」錯誤,別把垃圾當媒體丟給使用者。
  - 補:Threads **影片**可行做法 — 把貼文 URL 改寫成 `…/embed`(threads.net),yt-dlp 的 `html5` extractor 就抓得到 `<video>` 的 mp4(見 `ThreadsUrl.embedUrlOrNull`)。**圖片/多圖**則不行(embed 只給首圖,完整輪播要登入/GraphQL),屬已知限制。

## 實作步驟

### 1. 專案骨架
- Gradle(Kotlin DSL)+ version catalog;`:core`(kotlin-jvm + serialization)與 `:app`。minSdk 29 / targetSdk 35 / compileSdk 35。
- ⚠️ `:core` 是純 JVM module → 先寫測試再寫實作(red/green)。注意 `:app` 套用 Android plugin 後,連 `:core:test` 也要 Android SDK 才能 configure;CI 要先裝 SDK。
- Manifest:
  - `ShareReceiverActivity`:`ACTION_SEND`(text/plain)+ `ACTION_VIEW`(http/https)+ `ACTION_PROCESS_TEXT`。⚠️ **別用 `launchMode="singleTask"`**(未實作 `onNewIntent` 會吃掉後續分享);用預設 launchMode + `taskAffinity=""` + `excludeFromRecents`。
  - 權限:`INTERNET`、`POST_NOTIFICATIONS`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`;API 29 用 MediaStore 寫 Downloads 不需 `WRITE_EXTERNAL_STORAGE`。
  - Service 宣告 `android:foregroundServiceType="dataSync"`。

### 2. 引擎整合(data/ytdlp,見上「整合契約」)
- `YtDlpMediaExtractor`:`-J` → 自家 `YtDlpInfoParser` → `List<MediaItem>`(每項帶解析度/是否含音訊/副檔名/是否圖片)。
- `YtDlpDownloader`:輸出到 app cache 暫存目錄(`-o .../media.%(ext)s`),完成後交給 storage 複製出去。
  - ⚠️ 多圖貼文:用原貼文 URL + `--playlist-items <1-based index>`;單一影片才加 `--no-playlist`(兩者互斥)。
  - ⚠️ 用 `runInterruptible { execute(...) }`,catch 時呼叫 `destroyProcessById`,避免取消後 yt-dlp 行程殘留。
  - 格式字串與輸出檔挑選見下方「格式選擇正確作法」。

### 3. 儲存(data/storage)
- 預設 `MediaStoreStorage`:寫入 `MediaStore.Downloads`,relative path `Download/media-dler/`;先下載到 app cache,完成後 insert + `ContentResolver` 串流複製(API 29 scoped storage 友善)。
- `SafStorage`:`ACTION_OPEN_DOCUMENT_TREE` 取得 tree uri,`takePersistableUriPermission` 持久化後寫入。⚠️ **同名檔不要先 delete 再建**(會誤刪使用者檔案);直接 `createFile` 讓 SAF 自動去重。

### 4. 設定(data/settings + ui/settings)
- DataStore 存 `AppSettings`:shareMode(ONE_TAP/ASK)、defaultMediaKind、defaultVideoQuality、audioFormat、storageMode、safTreeUri、downloadAllWhenMultiple。
- ⚠️ `update()` 要在**同一個 `edit { }` 交易內讀-改-寫**,否則並發更新會互相覆蓋。

### 5. 分享入口與流程(ShareReceiverActivity + ui/picker)
- 從 intent(EXTRA_TEXT / EXTRA_PROCESS_TEXT / dataString)用 regex 抽 URL(見「解析/URL 正確作法」)。
- 一鍵模式:單一 media 直接送佇列;多 media 依設定「全下載」或跳輕量勾選。彈窗模式:列出縮圖 + 每項格式選單 + 勾選。解析耗時顯示 loading,失敗顯示可讀錯誤。
- ⚠️ **POST_NOTIFICATIONS 在分享流程也要請求**(只在首頁請求的話,只用分享的人永遠看不到通知);做成共用 Composable 兩處共用。

### 6. 下載服務與通知(download/)
- Foreground `DownloadService` 跑 `DownloadQueue`(in-memory `StateFlow`,單一 worker 以 `AtomicBoolean` 控制),每個任務一條進度通知,完成可點開檔案。
- ⚠️ 通知 id 用遞增計數器對應 taskId,**不要用 `UUID.hashCode()`**(會碰撞、互蓋通知)。
- ⚠️ 閒置收尾用 `stopSelf(startId)`(非無參數 `stopSelf()`),finally 內再查一次佇列;`start()` 包 `runCatching` 以防背景啟動限制丟例外。

### 7. 首頁(ui/home)
- 顯示「記憶體中任務(live)」合併「DataStore 歷史」;可重試/刪除/開啟。
- ⚠️ `remove` 要同時刪記憶體與**持久化歷史**(`HistoryStore.remove`),否則重啟後又冒出來。

## ⚠️ 格式選擇正確作法(踩過的坑)

`FormatSelector` 產生 yt-dlp `-f`:
- 最佳:`-f bv*+ba/b`。
- **限制畫質**:`-f "bv*[height<=H]+ba/b[height<=H]/b" -S res:H`。
  - ⚠️ 若只以 `…/b` 結尾(無上限 fallback),當沒有 ≤H 的串流時會**默默下載到 4K**;加 `-S res:H` 讓 fallback 取「最接近上限」而非全域最佳。
- 音訊:`-x --audio-format mp3|m4a -f ba/b`。
- 指定單一格式(若 UI 有開放):video-only 用 `id+ba/b`;⚠️ **別用 `id+ba/id/b`**(第二段會變成無聲影片)。
- 圖片:不加 `-f`,讓 yt-dlp 抓原圖。

**輸出檔挑選**:⚠️ 不要只挑「最大的檔」。音訊轉檔時來源影片可能比 mp3 大 → 會誤存成影片。要**依需求類型挑**(audio 找對應副檔名、image 找圖片副檔名,其餘才取最大),並排除 `.part` / `.ytdl` / `.tmp` 等暫存檔。

## ⚠️ 解析 / URL 正確作法

- `UrlExtractor`:regex 抓第一個 http(s);去尾端標點時,**括號要做平衡判斷**(維基 `/Foo_(bar)` 結尾的 `)` 是 URL 的一部分,不能砍),但句尾 `.,;:!?` 要去掉。
- `YtDlpInfoParser`:
  - 從 `url` 推副檔名時**只看 path**(先去掉 `?`、`#`,再取最後一段、再取最後的 `.`),否則 query 裡的點(如 `?v=1.0`)會被當副檔名 → 圖片被誤判為影片。
  - `entries` 可能含 `null` 或巢狀 playlist(分享頻道時)→ 用 `mapIndexedNotNull`,並**丟掉沒有任何可下載 format 的項目**,避免空殼/垃圾流到 UI。

## 建置、簽章與發佈

- **APK 體積**:Python + ffmpeg × 多 ABI 是大宗;debug universal ≈ 252MB。
  - ⚠️ **R8 只省得到 DEX/資源幾十 MB**(universal 降到 ~193MB);真正關鍵是 **ABI split**:每個 ABI 約 48–57MB。
  - release 開 `isMinifyEnabled` + `isShrinkResources` + `splits.abi`(含 universal)。⚠️ 用 `gradle.startParameter` 判斷讓 split **只在 release 啟用**,debug/CI 才不會變慢、變多檔。
  - packaging:`jniLibs { useLegacyPackaging = true }`。
  - proguard:`-keep class com.yausername.**`、`-dontwarn com.fasterxml.jackson.**`(VideoInfo 會用到但我們沒用)。
- **簽章**:sideload app 的金鑰不敏感 → keystore 與 `keystore.properties` 可直接放進 repo;`signingConfigs.release` 讀 `keystore.properties`,缺檔時輸出未簽章版(CI 仍可跑)。
- **CI(GitHub Actions)**:
  - `build.yml`:每次 push 先跑 `:core:test`(red/green 閘門)→ 再 `assembleDebug`。需先 `sdkmanager "platforms;android-35" "build-tools;35.0.0"`。
  - `release.yml`:推 `v*` tag → `assembleRelease` → 把簽章好的各 ABI APK 上傳到 Releases 頁(softprops/action-gh-release)。
- yt-dlp 可在 app 內 `YoutubeDL.updateYoutubeDL()` 線上更新,平台改版時不必重發版。

## 實測遇到的問題與解法(裝置實測記錄)

依實機測試逐一遇到並修正,照此可少走彎路:

1. **release 版引擎初始化失敗(「引擎尚未就緒 / 初始化失敗」),debug 正常** — R8 / minify 只在 release 跑,動到 youtubedl-android 的載入流程。解法:release 先**關閉 R8**(`isMinifyEnabled = false`);要開就得補齊 keep 規則並實機驗證。體積交給 ABI split。
2. **YouTube 抓不動** — 打包的 yt-dlp 過期(YouTube 常改版)。解法:啟動時背景 `updateYoutubeDL` + 設定頁手動更新 + **解析失敗時自動 update 再重試一次**。
3. **Bilibili「Requested format is not available」** — B 站是 DASH(只有 video-only / audio-only,**沒有 muxed `b`**),限制畫質的字串以 `/b` 收尾會失敗。解法:fallback 改為 `…/bv*+ba/b`,`-S res:H` 取最接近上限。
4. **Threads 亂碼標題(如「Q3.e」)/ 抓不到** — yt-dlp 無 Threads extractor,generic 抓到 og:title 之類垃圾。解法:走專用 extractor — **自己用瀏覽器 UA 抓 `…/embed`,regex 取 cdninstagram 直連 `.mp4` / 圖片**(`ThreadsEmbedParser` + `ThreadsExtractor`),直連 URL 交給既有下載器。⚠️ 別只靠 yt-dlp 的 `html5`-on-embed:它常被導到 `embed?_fb_noscript=1`(無 `<video>`)→「Unsupported URL」,只當 fallback。純圖 / 多圖多半只拿得到首圖(完整輪播需登入 / GraphQL)。
5. **裝錯 ABI → native 載入失敗** — 單一 ABI 版裝到不符的機器。解法:不確定就裝 **universal**;Releases 同時提供各 ABI 與 universal。
6. **沙箱 push tag 被擋(HTTP 403)** — Git proxy 只允許指定分支。解法:讓 **CI 用 `GITHUB_TOKEN` 在伺服器端建 tag / release**(`action-gh-release` 指定 `tag_name`),並改在「push 到分支」時發佈滾動的 `dev` 預發佈。
7. **直接 CDN 連結可下載** — yt-dlp 的 generic 能直接抓直連 `.mp4` / `.jpg`,所以抽取端若拿到直連 URL,可直接交給既有下載器,不必另寫下載路徑。
8. **部分站點命名很爛(如「Threads (1)」)** — yt-dlp(html5 / generic)回的 title 不具辨識度,直接當檔名很糟。解法:抽取後針對該站覆寫成有意義的名稱,例如 Threads 用貼文短碼 → `ThreadsVideo_<code>`(見 `ThreadsUrl.postCode`)。
9. **Threads / Bilibili 在清單中沒有預覽縮圖** — 這些來源沒有可用的遠端縮圖(B 站縮圖網域常需 Referer,Coil 直接載入會失敗),只有 YouTube 有。解法:影片下載完成後,用 Android 內建 **`MediaMetadataRetriever`** 抽一張影格存成本地預覽(app 私有 `media-dler/preview/<名>.jpg`),清單優先顯示本地預覽,並隨任務刪除一併清掉(`previewPath` 也存進歷史)。⚠️ **別指望 youtubedl-android 的 `FFmpeg` 跑任意指令**——它只有 `init` / `getInstance`,沒有 `execute`。
10. **自用時錯誤要夠詳細** — 解析 / 下載失敗訊息帶上來源 URL、HTTP 狀態、頁面大小等線索;錯誤對話框做成**可捲動 + 可選取複製**,回報 debug 方便很多。

## 風險與注意
- **平台反爬**:IG/FB 部分內容需登入;先支援公開內容,登入(cookie)留作後續。
- **進度回呼**:youtubedl-android 進度以行解析,精度有限,UI 以「解析中/下載中/完成」狀態為主,百分比盡力呈現。
- **未來 KMP**:`:core` 保持純 Kotlin、無 Android 相依,方便日後抽 `:shared`;引擎與儲存是 platform-specific,iOS 需另尋方案(yt-dlp 在 iOS 困難,屬未來題)。

## 驗證方式(end-to-end)
0. ⚠️ `./gradlew :core:test` 綠燈(URL 抽取 / JSON 解析 / 格式字串 / 預設選擇的純邏輯)。
1. `./gradlew assembleDebug` 編譯成功;安裝到 Android 10+ 實機/模擬器。
2. 開啟 app 確認首次啟動完成 yt-dlp/ffmpeg init(看 log 無錯)。
3. 從 YouTube app「分享 → media-dler」:
   - 彈窗模式:出現格式選單,選 720p 下載,檔案出現在 `Download/media-dler/`,相簿可見且可播放。
   - 切一鍵模式:再分享一次,直接下載、通知列顯示進度與完成。
4. 分享 IG/Threads 多圖貼文 → 確認列出多個 media 可逐項勾選下載。
5. 音訊:選 MP3,確認輸出可播放的 .mp3。
6. 設定改成 SAF 指定目錄,重下載確認寫入該目錄。
7. 測試非法/不支援 URL → 顯示友善錯誤,不崩潰。
8. release:推 `v0.1.0` tag → 確認 CI 在 Releases 頁產出各 ABI 的簽章 APK。
