# 計劃：video2text — 在 media-dler 上擴充「影音轉逐字稿」

> 本計畫在現有 **media-dler**（Kotlin + Compose + youtubedl-android）上擴充一個轉錄能力，
> 不另開 repo。新功能走獨立 flow，**不改動既有下載路徑**（surgical changes）。
> 原 `~/Projects/fun-tools/video2text/` 已廢棄。

## Context（為什麼做這個）

收到分享 → 變逐字稿。輸入有三種，全部匯入同一條 pipeline：

1. **本機 video/voice 檔分享**（手機內檔案直接分享進來）
2. **影片連結分享**（沿用既有 yt-dlp 下載音訊）
3. **YouTube（或任何 yt-dlp 有字幕的站）有 CC** → 直接抓字幕，**完全跳過下載音訊／切段／轉錄引擎**

姊妹專案：`fun-tools/whisper`（桌面 Qwen3-ASR + opencc `s2twp` 正體）是品質參照；
`fun-tools/media-dler-ios` 是 iOS 對照。本專案的 on-device 引擎走 whisper.cpp。

## 收斂後的需求（經 musk-review）

- **輸入**：本機檔 / 連結 / YouTube CC 捷徑，三者共用 pipeline。
- **分享路由（UX，與既有下載共存）**：
  - **分享網址** → 沿用既有 share 選單（picker/dialog），**多加一個「轉文字」動作**，與下載選項並列。
  - **分享本機檔** → 跳選單（見 M6，**已改**）：
    - **影片** → 「轉成文字」**或「取出聲音」**（抽出原音軌存成 .m4a，不轉文字）。
    - **聲音檔** → 只有「轉成文字」（抽自己無意義）。
  - 既有下載功能、首頁、清單**完全不動**。
- **轉錄引擎**：`:core` 定義 `TranscriptionEngine` 介面、可抽換。
  - **第一個實作：on-device whisper.cpp**（離線、隱私、免金鑰）— 也是**預設引擎**。
  - **第二階段：雲端 OpenRouter**（OpenAI 相容 `/audio/transcriptions`，base URL + API key 設定化，之後可換 Groq/OpenAI）。
    **金鑰由使用者自行在 app 內貼上、存進裝置本機（DataStore）；app 不內建、不隨版散布任何 key。**
- **語言**：多語言自動偵測；偵測到中文 → opencc `s2twp` 轉台灣正體。
- **解碼 / 切段（已修正：不靠 ffmpeg）**：
  - **本機檔 → PCM 用 Android 原生 `MediaCodec`/`MediaExtractor`**（系統內建、零相依、支援 mp4/m4a/aac/opus/mp3），輸出 16kHz mono float。
    youtubedl-android 的 `ffmpeg` 僅供 yt-dlp 內部（如 `-x`）使用，**無公開的任意 ffmpeg 指令 API**，故本機檔解碼不走它。
  - **連結 → 音訊**走 yt-dlp `-x`（那條 ffmpeg 路徑可用），落地後再用 MediaCodec 解 PCM。
  - **on-device 不硬切檔案**：whisper.cpp 內部已用 30s window，真正限制是**記憶體**（1hr float ≈ 230MB）。
    → 改成**串流式分窗餵 PCM（大窗 + 小重疊）逐窗釋放**，避免 OOM 也避免硬切在字中間造成接縫錯誤。
  - **硬切只留給雲端**（檔案大小上限）；若要在邊界切，優先**靜音點**而非固定秒數。
- **輸出**：結果頁顯示全文 + 一鍵複製 + 系統分享。**存成 .txt 列第二階段。** 不做時間軸字幕。

### musk-review 砍掉/延後的東西
- 砍「第一版兩個引擎都做」→ 第一個里程碑只跑通一個引擎，介面留好。
- 砍「一律切段」→ 改閾值觸發。
- 延後 opencc 之外的相依；延後「存成 .txt」。
- **不刪既有功能**（下載清單／畫質 picker／Threads／圖片）：刪它們是改動既有功能，違反 surgical；改以獨立 flow 隔離。

## 架構（沿用既有雙模組，新增獨立 flow）

```
:core （純 Kotlin、可測）
  transcribe/
    TranscriptionEngine.kt      介面：suspend transcribe(pcm/檔, lang?, onProgress) -> Transcript
    Transcript.kt               結果模型（text, detectedLang, segments?）
    WindowPlanner.kt            純函式：算分窗（大窗+重疊）與雲端切段點（檔案大小上限觸發）
    SubtitleVtt.kt              VTT → 純文字（去時間軸、去重複行）
    LanguageDecision.kt         偵測語言 → 是否套 opencc 的決策
    SegmentMerge.kt             多段轉錄結果合併（去重疊、接縫）

:app
  transcribe/
    WhisperCppEngine.kt         TranscriptionEngine 的 whisper.cpp JNI 實作（里程碑 1）
    OpenRouterEngine.kt         TranscriptionEngine 的雲端實作（里程碑 3）
    WhisperModelManager.kt      ggml 模型下載/快取/選擇（base / small 多語版）
    OpenCcConverter.kt          簡→繁正體（opencc4j 或 bundled dict）
    AudioToPcm.kt               MediaCodec/MediaExtractor 把任意輸入解成 16kHz mono PCM（分窗串流）
    TranscriptionService.kt     foreground service（dataSync），長任務存活
  ui/transcribe/
    TranscribeScreen.kt + ViewModel   進度 + 結果頁（複製/分享）
  （MainActivity 路由新增 transcribe 目的地）
```

JNI 整合走 whisper.cpp 官方 `examples/whisper.android` 模式：whisper.cpp 以 git submodule 引入，
CMakeLists + `WhisperContext` Kotlin wrapper。模型不進 APK，首次使用下載。

> **現況實際檔案清單（上方為初版草圖，已演進）**：
> `:core/transcribe/`：`TranscriptionEngine`、`Transcript`、`WindowPlanner`、`SegmentMerge`、`LanguageDecision`、`SubtitleVtt`、**`TranscriptFormatter`**（顯示斷句）。
> `:app/transcribe/`：`StreamingEngine`（串流介面）、`WhisperCppEngine`、`WhisperNative`、`WhisperModelManager`、`CloudTranscriptionEngine`（OpenRouter）、`AudioToPcm`、**`AudioEncoder`**（PCM→m4a，壓縮上傳）、**`AudioTrackRemuxer`**（無損抽音軌）、`OpenCcConverter`、`LinkAudioResolver`、`TranscriptJob`、`TranscriptionManager`、`TranscriptStore`、`TranscriptionService`、**`AudioExtractionService`**。
> `:app/ui/`：`transcribe/TranscribeScreen`+`TranscriptHistoryScreen`、`picker/ShareSheet`+**`LocalMediaSheet`**、`settings/SettingsScreen`（引擎/語言/模型/雲端三欄/壓縮開關+說明）。

## 既有程式碼的缺口（必補）

- **`ShareReceiverActivity` 目前只收 `text/plain` SEND**（連結/文字）。
  本機 video/voice 檔分享需新增 intent-filter：
  ```xml
  <intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="video/*" />
    <data android:mimeType="audio/*" />
  </intent-filter>
  <!-- 以及 SEND_MULTIPLE 視需求 -->
  ```
  並在接收端讀 `Intent.EXTRA_STREAM` 的 `content://`（既有路徑只讀 `EXTRA_TEXT`）。
- **share 選單整合**：URL 分享沿用既有 picker，新增「轉文字」動作；本機檔分享走新選單（只有「轉文字」）。
- 連結取音訊共用既有 yt-dlp（`-x`）；**本機檔解碼用 MediaCodec，不依賴 ffmpeg 任意指令**。

## 實作步驟（里程碑）

### 里程碑 0 — 介面與骨架（先讓端到端可編譯）
- `:core/transcribe/` 定義 `TranscriptionEngine`、`Transcript`、`WindowPlanner`、`SubtitleVtt`、`LanguageDecision`、`SegmentMerge`，全部寫 JUnit。
- App 端加 `TranscribeScreen` 空殼 + 路由 + `TranscriptionService` 骨架。
- **verify**：`:core` 測試綠燈；app 編譯過、能從分享進到一個空結果頁。

### 里程碑 1 — on-device whisper.cpp 端到端（核心驗證）
1. 引入 whisper.cpp（submodule + NDK/CMake），`WhisperCppEngine` 跑通單檔。
2. `AudioToPcm`：MediaCodec/MediaExtractor 任意輸入 → 16kHz mono PCM（分窗串流、逐窗釋放）。
3. `WhisperModelManager`：首次下載 ggml 多語模型（預設 base，可選 small）。
4. 串起：本機檔分享 →（只有「轉文字」選單）→ PCM 分窗 → 逐窗 whisper → `SegmentMerge` → `LanguageDecision`→`OpenCcConverter`（中文轉正體）→ 結果頁。
5. 進度：whisper.cpp progress callback + 分段比例，回報到 foreground service 通知。
6. 結果頁：全文 + 複製 + 分享。
- **verify（end-to-end）**：分享一段 1–2 分中文語音 → 出正體逐字稿；分享一段英文 → 出英文原文；分享一個 30 分音檔 → 切段、進度推進、不 OOM。

### 里程碑 2 — 連結輸入 + YouTube CC 捷徑
1. 連結分享：既有 picker 加「轉文字」→ yt-dlp `-x` 取 best audio（m4a/opus）→ 走里程碑 1 同一條 pipeline。
2. YouTube CC 捷徑（**best-effort，非保證路徑**）：先 `yt-dlp --skip-download --write-subs --write-auto-subs --sub-langs <auto> --sub-format vtt` 探字幕；
   有 → `SubtitleVtt` 去時間軸直接出文字（跳過引擎）；**抓不到（YouTube 常需 PO token／被擋）→ 自動 fallback 下載音訊轉錄**。
- **verify**：貼一支有 CC 的 YouTube → 秒出文字、無引擎耗時；貼一支無 CC 的 → 走轉錄。

### 里程碑 3 — 雲端 OpenRouter 引擎 + 引擎切換
1. `OpenRouterEngine`：OpenAI 相容 `/audio/transcriptions`，base URL + API key 進 DataStore 設定。
   **金鑰一律由使用者在設定頁手動貼上、只存裝置本機；原始碼/APK 不得內建任何 key。**
2. 設定頁加引擎選擇（預設 on-device）、模型選擇、金鑰輸入（密碼欄、可清除）。
3. 雲端路徑切段以**檔案大小上限**為準（OpenRouter STT 限制）。
- **verify**：切到雲端、填金鑰，同一段音訊出逐字稿；無網路時 fallback 提示或自動回 on-device。

### 里程碑 4（選配）— 存成 .txt
- 結果頁加「存檔」，沿用既有 MediaStore/SAF 儲存層寫 `.txt`。

## 進度日誌

### 目前狀態總覽（2026-06-05，已更新含 M5/M6/M7）
- **完成度 ~95%**：M0 / M1 / M2 / M2b / M2c / M3→**被 M5 取代** / M5（OpenRouter+壓縮）/ M6（可讀斷句+轉譯方式）/ M7（本機選單+抽音）/ 資源稽核 — 程式皆完成；僅選配 **M4（存成 .txt）未做**。
- **M8（裝置端 ASR 模型擴充）✅ 程式完成、建置全綠、待真機驗**：whisper 加 `large-v3-turbo-q5_0` 量化版 + 導入 sherpa-onnx（SenseVoice/Paraformer/Qwen3）為第二裝置端後端；詳見下方 M8 段。
- **建置**：`:app:compileDebugKotlin`、`:core:test`、`:app:assembleDebug`（arm64-v8a + x86_64）皆綠。
- **真機已驗（CPH1941/SD665，2026-06-05）**：on-device base/small 端到端、**OpenRouter 雲端 turbo/qwen 端到端（WAV+m4a）**、影片抽音無損 m4a、本機分享選單兩路徑、轉譯方式顯示。實測基準見文末「## 實測基準」。
- **尚未 commit**：本分支累積大量未 commit 變更（on-device 串流、M3→M5 雲端 OpenRouter 化、壓縮選項、轉譯方式、可讀斷句、本機選單+抽音）。建議分 commit：①cloud→OpenRouter+壓縮 ②顯示層(斷句+方式) ③本機選單+抽音。
- **待真機**：多窗 seek 全程長音訊、M2 連結/CC、四種 ABI release build。
- iOS 移植見文末「## iOS 移植對照」;已知問題見「## 已知問題 / 限制」。

### 環境（已驗證可用）
- branch：`feat/video2text-transcribe`。
- Sandbox Android 工具鏈完整：JDK 17（JBR）、SDK `~/adt-bundle-mac-x86_64/sdk`（platform 35/36、build-tools 35/36）、**NDK `ndk-bundle` r23（clang 12）**、CMake 3.22.1、網路可達、gradle 8.14.3。
- `local.properties`（gitignored）指 `sdk.dir`。**NDK 佈局修正**：legacy `ndk-bundle` 無 versioned 目錄，建 symlink `$SDK/ndk/23.0.7344513-beta4 → ndk-bundle`，`build.gradle` 設 `ndkVersion = "23.0.7344513-beta4"`（AGP 會警告 CXX5304 同 package id，去重後照用、非錯誤）。

### 里程碑 0 — 完成 ✅
- `:core/transcribe/`：`TranscriptionEngine`、`Transcript`/`AudioRef`、`WindowPlanner`、`SegmentMerge`、`LanguageDecision`、`SubtitleVtt` + JUnit，`:core:test` 全綠。
- `:app` 骨架：`TranscribeScreen`（結果頁，複製/分享可用）、`TranscribeActivity`、`ShareReceiverActivity` 加本機檔分支、Manifest 補 `video/*`、`audio/*` SEND filter。`:app:assembleDebug` 綠燈。
- 刻意延後：`TranscriptionService`（無引擎前是空殼，挪 M1）、URL「轉文字」picker 動作（屬 M2）、本機檔的單選選單（折進結果頁初始狀態）。

### 里程碑 1 — 完成 ✅（程式；待真機驗逐字稿品質/長音訊）
- **P1-1 完成 ✅**：whisper.cpp **v1.8.6**（`app/src/main/cpp/whisper.cpp`，submodule）+ ggml 經 NDK r23 編譯，`BUILD_SHARED_LIBS=OFF` 將 ggml 靜態折入單一 `libwhisper_jni.so`（arm64-v8a，4.3MB，已進 APK）。JNI 橋接 `whisper_jni.cpp` + Kotlin `WhisperNative`（init/free/fullTranscribe/detectedLanguage）。CMake 選項：`WHISPER_BUILD_TESTS/EXAMPLES/SERVER=OFF`、`GGML_NATIVE/OPENMP/CCACHE=OFF`。
  - **TEMP**：`build.gradle` abiFilters 暫縮 `arm64-v8a` 加速 native 迭代，release 前還原四種 ABI。
- **P1 收尾（程式完成 ✅，待實機驗）**：
  - **長音訊串流解碼**：`AudioToPcm` 新增 `durationMs`（讀容器 metadata）與 `decodeRange(start,end)`（seek + 逐窗解碼），引擎改成「依時長分窗 → 每窗只 `decodeRange` 出該窗 PCM → 用完即丟」，不再整檔載入 float（1hr 從 ~230MB 降到單窗 ~3.8MB）。時長未知時 fallback 整檔單窗。
  - **設定切 small**：`AppSettings.transcribeModel`（base/small）存 DataStore；`WhisperCppEngine` 每次執行從設定讀模型（`WhisperModel.of`），改設定下一個 job 即生效；設定頁加模型下拉 + 該模型的下載/刪除狀態。
- **P1-2/3/4/5 完成（程式碼）✅，可建置成 APK**：
  - `AudioToPcm`（MediaCodec/MediaExtractor → 16kHz mono float PCM，整檔解碼；長音訊串流解碼列 TODO）。
  - `WhisperModelManager`（首次下載 ggml-base，HF resolve URL，存 app filesDir，不進 APK）。
  - `WhisperCppEngine`（下載→解碼→`WindowPlanner` 分窗→JNI 逐窗→`SegmentMerge`→`OpenCcConverter`），首窗偵測語言後沿用。
  - `OpenCcConverter`（**opencc4j 1.14.0**，`toTraditional`；完整 s2twp 片語在地化列後續）。
  - 串接：`TranscribeActivity`→`TranscribeViewModel`→引擎，結果頁顯示進度→文字→複製/分享；引擎入 `AppContainer`（M3 換引擎用）。
- **whisper 品質驗證（host 預跑，已驗）✅**：ggml-base 跑使用者華語短片，語言偵測 **zh p=0.998**、內容與桌面 Qwen 基準幾乎一致（僅少數同音字），33s/3.95s≈8x realtime。**on-device 路線成立**。
- **模型管理（設定頁）✅**：設定頁新增「語音轉文字模型」區塊 — 顯示 `ggml-base` 狀態（未下載/下載中含進度條/已下載含大小/失敗重試）、可手動下載與刪除（`WhisperModelManager.delete`/`sizeBytes`、`SettingsViewModel.ModelState`）。引擎內 `ensure` 保留為 fallback（刪除後直接轉錄仍會自動重抓，不硬失敗）。
- **Wi-Fi gate（詢問式）✅**：模型下載前若非 Wi-Fi（`NetworkStatus.isMetered`，行動數據或計量 Wi-Fi）跳 `AlertDialog` 確認，不硬限 Wi-Fi。
- ~~待做：長音訊串流解碼；foreground service；設定切 small~~ → **皆已完成**（串流解碼見「P1 收尾」、service 見 M2b、模型切換見「P1 收尾」）。**僅剩真機跑**（裝 APK→分享影音→看逐字稿/長音訊不 OOM）。

### 里程碑 2 — 連結輸入 + YouTube CC 捷徑（程式碼完成 ✅，待實機驗）
- **`LinkAudioResolver`**（`:app/transcribe`）：給 URL →（1）先 best-effort 探字幕（`yt-dlp --skip-download --write-subs --write-auto-subs --sub-langs <優先序> --sub-format vtt`），抓到 → `SubtitleVtt` 去時間軸出純文字、依語言碼套 opencc（**完全跳過引擎**）；（2）抓不到 → `yt-dlp -f bestaudio -x --audio-format m4a` 下載音訊（含進度、`destroyProcessById` 取消保護），交回同一條 on-device pipeline。
- **分享路由**：URL 分享的既有 picker/錯誤頁各加「轉文字」按鈕（`ShareSheet`→`onTranscribe`→`TranscribeActivity.startUrl`），與下載選項並列；既有下載完全不動。
- **`TranscribeActivity`/`TranscribeViewModel` 泛化**：輸入分 `LocalFile`/`Link`；連結走「解析(0–40%)→轉錄(40–100%)」兩階段進度，captions 命中則秒出。
- **限制**：one-tap 分享模式不顯示 picker，故連結轉文字目前僅在「彈窗選擇」模式可用（surgical，不動既有 one-tap 行為）；YouTube CC 為 best-effort，抓不到自動 fallback 下載音訊。
- **待做**：實機驗（有 CC 的 YouTube 秒出文字、無 CC 的走轉錄、一般連結走轉錄）。

### 里程碑 2b — 背景轉錄子系統（即時串流 / 通知 / 續跑 / 放棄 / history）✅ 程式完成、emulator 驗
> 觀察到「< 10 分音訊單一大窗 → 進度凍結 0%」後，依使用者需求重構成背景化、串流化的完整子系統。

- **JNI 原生 callback**：`whisper_jni.cpp` 接出 whisper 的 `progress_callback`/`new_segment_callback`/`abort_callback` → Kotlin `WhisperNative.WhisperCallback`（onProgress/onSegment/isCancelled）。即時文字+進度+可中止，**不縮窗、不犧牲準確度**（取代「切 10s」方案）。
- **引擎串流 + 斷點**：`WhisperCppEngine.transcribeStreaming`，窗改 **60s+3s 重疊**當 checkpoint 單位；每窗用 native callback 串流、窗完成持久化 checkpoint，支援 `startWindow`/`priorText` 續跑。
- **狀態層**：`TranscriptJob`（id 由來源導出可續跑、status/progress/text/completedWindows/seen…）+ `TranscriptStore`（DataStore JSON）+ `TranscriptionManager`（記憶體 live `StateFlow` 為 UI 單一真相、高頻更新只在記憶體、checkpoint/terminal 才持久化；佇列、續跑、`cancel`、`clearTempFiles`）。
- **`TranscriptionService`**（foreground dataSync）：drain 佇列、link 先 resolve、跑引擎串流→更新 manager+通知（處理中 X% / 完成可點開）；**分享檔由 `ShareReceiverActivity` 複製進私有 storage**（content-URI grant 不跨行程，私有複本才能續跑）。
- **UI**：結果頁改觀察 manager job（即時文字+進度+**放棄**）；`TranscriptHistoryScreen`（Home 加入口）；`MainActivity` 開啟時 `firstUnseenCompleted`→跳結果、`hasPending`→續跑；設定加「清除暫存檔」。
- **emulator 實測（x86_64）**：✅ 進度脫離凍結（0→動起來）、✅ 端到端完成並渲染結果文字+複製/分享、✅ 前景通知、✅ 放棄（job 移除+`cache/transcribe` 清空+persist 變 `[]`）、✅ persisted job（COMPLETED/seen:true/1-1 窗/私有路徑）、✅ history 清單。
- **已知**：(1) emulator 無 AVX，whisper 慢到不具代表性（15s 音訊 encode 要數分鐘）；真機 ~8x realtime。(2) 單窗短音訊的窗內進度較粗（encode 期間不動）。

### 里程碑 2c — 轉錄語言設定（解語言誤判）✅
- 起因：whisper 語言自動偵測在第一窗，片頭是音樂/無人聲時會誤判整段（測試片頭被判 `ko`）。whisper API 只吃單一 `language`，無「主+副」概念。
- 作法：設定加 **`TranscribeLanguage`**（自動／中文／English／日本語／한국어／西/法/德），存 DataStore。非「自動」時，`TranscriptionService` 把該語言碼當 `knownLanguage` 傳給引擎 → 跳過自動偵測、直接鎖 whisper `language`。鎖主語言後，夾雜的英文等仍由多語模型照原文輸出（即使用者要的「中文為主夾雜英文」）；中文另套 OpenCC s2twp。
- **emulator 驗**：設定 UI 下拉可選並持久化（`transcribe_language`）；鎖「中文」後重跑短片頭，語言不再是 `ko`（見測試）。
- **TEMP**：`abiFilters` 仍含 `arm64-v8a`+`x86_64`（emulator 測試用，release 前還原四種）。測試用的 `READ_MEDIA_*` 權限已移除。

### 里程碑 3 — 雲端引擎 + 引擎切換（程式完成 ✅，待實機驗）
> ⚠️ **本節已被 M5 取代**：雲端從「OpenAI 相容 multipart」改為 **OpenRouter 專用 JSON+base64**，並加音訊壓縮選項。下方 M3 內容保留為歷史；**實際契約以 M5 為準**。
> 註：plan 原命名 `OpenRouterEngine`，改用更精確的 `CloudTranscriptionEngine`（任何 OpenAI 相容 `/audio/transcriptions`：OpenAI/Groq/OpenRouter…）。
- **`StreamingEngine` 介面**：抽出 `transcribeStreaming` + `StreamResult`，on-device 與雲端都實作；`TranscriptionService` 改依 `transcribeEngine` 設定選引擎，續跑/取消/通知邏輯兩者共用。
- **`CloudTranscriptionEngine`**：讀 `cloud`（baseUrl/apiKey/model）設定；以 `WindowPlanner`（10min 窗，~19MB WAV < 25MB 上限）逐窗 `decodeRange`→寫 16k mono WAV→multipart POST（`HttpURLConnection`，`response_format=verbose_json`，鎖定語言時帶 `language`）→解析 text/language→`SegmentMerge`+OpenCC；每窗一個 checkpoint（可續跑），WAV 用完即刪、結束清 `cache/transcribe/cloud`。
- **金鑰**：`CloudTranscribeConfig` 三欄存 DataStore（裝置本機），設定頁密碼欄輸入；原始碼/APK 不內建任何 key。設定頁加引擎 chips（裝置端/雲端）、雲端三欄輸入、未設定提示。
- **引擎切換安全**：`TranscriptJob.engineId` 記錄產生 checkpoint 的引擎；`TranscriptionManager.beginRun` 在引擎不同時丟棄 checkpoint（窗制不同會接縫錯亂），改乾淨重轉。
- **限制**：雲端路徑未實機驗（需使用者自備金鑰）；切 http base URL 受 cleartext 政策限制（預期 https）。

### emulator 驗（本批變更，x86_64 emulator-5554）
- ✅ **串流解碼 on-device 端到端**：clip2.mp4（33s 單窗）`COMPLETED`、`language=zh`、出正體逐字稿；新 `decodeRange` 的 MediaCodec/seek 路徑正常。
- ✅ **partial-window endUs 停止**：longvideo.mp4（208s）window 0 解出 `[0,60s)` 並進入 whisper（emulator 無 AVX，15min 才到 5%，多窗全跑不切實際，未跑完）。**真機才測得完整多窗 seek。**
- ✅ **新 `engineId` 持久化**（whisper-cpp / cloud 皆驗）。
- ✅ **cancel→清理**：放棄 longvideo 後 job 移除、`cache/transcribe/input/<id>`（8.5MB 複本）刪除。
- ✅ **pruneOrphanInputs**：植入孤兒檔 → 冷啟動 hydrate 後被清除。
- ✅ **雲端引擎 wiring**：設定切「雲端」持久化；未設定金鑰時跑檔 → 快速 `FAILED`、`engineId=cloud`、通知「雲端引擎尚未設定（缺少 API 位址、金鑰或模型）」。**含金鑰的實際雲端轉錄仍需使用者自備 key 驗。**
- **發現（既有、非本批引入）**：`TranscriptionService` finally 無論成敗都刪 `inputCopy`，故 FAILED 的本機檔 job 之私有複本被刪、無法從 checkpoint 續跑（會再失敗）。原碼即如此；若要支援 FAILED 續跑需改成只在 成功/取消 時刪複本。

### 資源管理稽核與修補 ✅
- **缺口**：`TranscriptionManager.delete(id)`（history「移除」）只刪 job/store，未刪私有輸入複本 `cache/transcribe/input/<id>` → 殘留。
- **修補**：`delete`/`cancel` 改呼叫 targeted `deleteInputCopy(id)`（不再用 `clearTempFiles` 整夾刪除，避免誤刪其他 job 的複本）；`hydrate` 加 `pruneOrphanInputs`（開機掃除非可續跑 job 的孤兒輸入複本，清掉 crash/舊版殘留）；`TranscriptionService` finally 改刪下載音訊的整個 scratch 子夾（原本只刪檔、留空目錄）。`clearTempFiles`/`clearAll`/設定「清除暫存檔」維持整夾清除語義。

### 里程碑 5 — 雲端改 OpenRouter（JSON+base64）+ 音訊壓縮選項 ✅（實機驗，2026-06-05）
> 起因：拿 OpenRouter 1-day key 實測時發現 M3 的「OpenAI 相容 multipart」打 OpenRouter 會失敗——OpenRouter 的 STT 端點是 **JSON + base64**，且不支援 `verbose_json`。

- **`CloudTranscriptionEngine` 改寫**（`app/.../transcribe/CloudTranscriptionEngine.kt`）：
  - multipart → **JSON body**：`{"model":…,"language":?,"input_audio":{"format":"wav|m4a","data":"<base64>"}}`，打 `{baseUrl}/audio/transcriptions`，`Authorization: Bearer <key>`。
  - **base64 以 3-byte 對齊分塊串流寫出**（不把整個 ~25MB base64 一次進記憶體）。
  - 回應只解析 `{text, usage}`；**OpenRouter 不回 `language`** → `detectedLanguage` 留 null。
- **音訊壓縮選項 `cloud.compressAudio`**（`core/.../model/Settings.kt`，存 DataStore）：
  - 關（預設）：上傳 WAV，**分窗縮短為 5 分鐘**（`WAV_WINDOW_MS`，避免 base64 膨脹 + 上游 ~60s timeout）。
  - 開：每窗用 **`AudioEncoder.encodeM4a`（新檔，MediaCodec AAC-LC 16kHz mono 32kbps）** 壓成 m4a 再上傳，分窗 10 分鐘。
  - 設定頁加開關 + **ⓘ 說明鍵**（AlertDialog 解釋 WAV/壓縮/分窗/費用取捨）。
- **設定文案**改「僅支援 OpenRouter（…JSON+base64…）」，placeholder 改 `https://openrouter.ai/api/v1` 與 `openai/whisper-large-v3-turbo`。
- **實機驗（CPH1941）**：DataStore 直寫設定（ColorOS 的 `adb input text` 掉字、`run-as sh -c` 不切 uid，改 `run-as cp` 寫 protobuf）→ 分享影片 → 端到端轉錄成功，turbo WAV/m4a 皆通；壓縮路徑（AudioEncoder）實機通過。
- **正體 caveat（iOS 須注意）**：OpenRouter 不回 language，**AUTO 時中文輸出停在簡體不轉正體**（OpenCC 只在 language 已知時套）。解法：把「轉錄語言」設「中文」→ knownLanguage=zh → OpenCC 轉正體。

### 里程碑 6 — 顯示層：可讀斷句 + 轉譯方式顯示 ✅
- **`TranscriptFormatter`**（`core/.../transcribe/TranscriptFormatter.kt` + JUnit）：把 raw 逐字稿轉成斷行可讀形式（**僅供顯示/複製/分享**，raw 保持無換行）。兩段式：句末標點（。！？；…/ASCII .!?）硬斷、過長無標點段在子句分隔（，、；：）軟斷，不偽造句界（whisper 中文常無標點 → 只軟斷成可讀塊）。
- **轉譯方式顯示**：`TranscriptJob.method`（新欄位）在 `TranscriptionService` 開跑時寫入，結果頁標題下顯示「轉譯方式：…」：
  - 雲端 → `雲端 · <model>（WAV|m4a）`
  - 裝置端 → `裝置端 · base|small`
  - 字幕捷徑 → `內嵌字幕（未經辨識）`
  - 記在 job 上（非當前設定），故歷史也正確。

### 里程碑 7 — 本機分享選單 + 影片抽音存檔 ✅（實機驗，2026-06-05）
> 補上原 plan 的「本機檔跳選單」並擴充：影片可選「取出聲音」存成音檔。

- **`LocalMediaSheet`**（`ui/picker/`，仿 ShareSheet 的 Dialog+Surface）：影片→「轉成文字」/「取出聲音（存成音檔）」/取消；聲音檔→「轉成文字」/取消。
- **`ShareReceiverActivity` 改**：本機檔不再直接轉錄，改顯示 `LocalMediaSheet`（`isVideo = intent.type.startsWith("video/")`）。「轉成文字」走既有 `transcribeLocalFile`；「取出聲音」→ `AudioExtractionService.start`。
- **`AudioTrackRemuxer`**（新，`transcribe/`）：`MediaExtractor`(用 `openFileDescriptor` 餵 fd)＋`MediaMuxer`(MPEG_4) **無損搬第一條音軌** → .m4a，純搬壓縮樣本不解碼、記憶體只一個 buffer。
- **`AudioExtractionService`**（新，foreground dataSync，獨立於下載佇列）：remux 到 cache → **重用 `MediaStoreStorage`/`SafStorage`** 依 `storageMode` 存到 Downloads/media-dler 或 SAF → 通知（`Notifications.extractSummary/extracted/extractFailed` 新增）。檔名 `FileNames.sanitize(來源去副檔名)+".m4a"`，mime `audio/mp4`。
- **AppContainer**：`mediaStoreStorage`/`safStorage` 由 private 改 public 供服務取用。Manifest 註冊新 service。
- **實機驗（CPH1941）**：影片分享→兩選項;「取出聲音」→ ffprobe 確認輸出 **aac/48kHz/立體聲/96kbps，與原音軌完全一致（無損）**、可播放、發完成通知;聲音檔分享→只有「轉成文字」。

### 里程碑 8 — 裝置端 ASR 模型擴充（whisper 量化版 + sherpa-onnx）🚧 規劃中（2026-06-05）
> 起因：裝置端只有 whisper `base`/`small`，中文 CER ~26–30%（見「實測基準」），是所有選項裡較弱的。2026-06 多方查找後決定**不綁死單一用法**，裝置端新增更準的選項。雲端不動（Qwen 品質需求走雲端 `qwen3-asr-flash` 更省事）。

- **whisper.cpp 加量化模型**：`WhisperModel` 加 `TURBO_Q5`（`ggml-large-v3-turbo-q5_0.bin`，**574MB**，HF `ggerganov/whisper.cpp/resolve/main/`）。準度接近 turbo、體積與 small 同級，**零架構改動**（同一 JNI/ggml 載入）。
- **導入 sherpa-onnx（ONNX Runtime）為第二個裝置端後端**，帶三個模型：
  - **SenseVoice-Small** int8 **~228MB**（`sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17`，有標點版）：中/粵/英/日/韓，非自回歸、比 whisper-large 快 ~15×，中文準度遠勝 whisper。
  - **Paraformer-zh** int8 **~227MB**（`sherpa-onnx-paraformer-zh-int8-2025-10-07`）：純中文最準（CER ~1.95%），非自回歸；引擎固定回 `language=zh`，OpenCC 必套正體。
  - **Qwen3-ASR 0.6B** int8 **~600–900MB**（`sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`）：多語、品質高；**自回歸、體積大、SD665 可能慢於即時** → UI 標「實驗／高階機」（使用者已知情仍要保留）。
- **設計決策**：維持 `TranscribeEngine{ON_DEVICE,CLOUD}` 不變；`TranscribeModel` 加 `backend`（`OnDeviceBackend{WHISPER_CPP,SHERPA}`）與 4 個新值，on-device 下拉統一列 6 個模型，**後端由所選模型推導**，使用者不需感知。新 enum 值對 DataStore 向後相容（`enumOrDefault` fallback）。
- **sherpa 模型走官方 `.tar.bz2` + 端上解壓**（下載 `github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/<name>.tar.bz2` → Apache Commons Compress 解到 `filesDir/models/sherpa/<modelId>/`）。**改動原因**：實作前驗證發現 SenseVoice 有標點版（2024-07-17）與 Qwen3 在 HF 無個別檔鏡像、且 Qwen3 含 `tokenizer/` 目錄，逐檔下載不可行 → 改 tar.bz2 統一處理（三模型皆有官方 release）。tar.bz2 大小：SenseVoice 163MB／Paraformer 228MB／**Qwen3 879MB**。音訊 `AudioToPcm`（16kHz mono float）與 sherpa **完全相容免轉換**。
- **新元件**：`SherpaModel`（enum+檔案清單）、`SherpaModelManager`（仿 WhisperModelManager，多檔 ensure/sizeBytes 加總/delete 刪子夾）、`SherpaOnnxEngine`（實作 `StreamingEngine`，`id="sherpa-${model.id}"` 使切模型即乾淨重轉；沿用 `WindowPlanner` 60s+3s、`SegmentMerge`、`OpenCcConverter`；進度為窗級）。
- **改動點**：`AppContainer.streamingEngine` 改依「引擎+所選模型 backend」dispatch；`SettingsViewModel` 的 download/delete/state 依 backend 走對應 manager；`SettingsScreen` 模型說明補各模型一句（Qwen3 標大/慢）；`transcriptionMethod()` ON_DEVICE 改用 `model.label`。
- **建置**：sherpa-onnx **無官方 Maven**（僅第三方非官方包，不用）→ 走官方做法 **vendored `kotlin-api/*.kt`（package `com.k2fsa.sherpa.onnx`）+ jniLibs 放 prebuilt `.so`**（`sherpa-onnx-v1.13.2-android.tar.bz2`，含四 ABI 的 `libsherpa-onnx-jni.so`+`libonnxruntime.so`）。Kotlin 編譯只需 `.kt`（`.so` 執行期載入）；`.so` 由 `scripts/` 下載腳本取得、放 jniLibs（不入 git，仿 whisper 由建置產出）。加 `org.apache.commons:commons-compress`（解 tar.bz2）。debug 仍 arm64-v8a+x86_64（onnxruntime 支援 x86_64 可在 emulator 測）；release 四 ABI、ABI splits 緩解體積（onnxruntime `.so` 每 ABI ~10–15MB）。
- **風險**：①sherpa AAR/API 整合是最大未知（先打通「能 new OfflineRecognizer」再往下）；②Qwen3 確切檔案清單/URL/速度待實作確認；③sherpa AUTO 取不到語言時中文不轉正體（同雲端 caveat，建議設「中文」；Paraformer 已規避）。
- **verify**：`:core:test`/`compileDebugKotlin`/`assembleDebug` 綠 → 各模型下載+轉同一中文片，比準度/速度 → resume 跨 sherpa 模型乾淨重轉 → 結果頁「轉譯方式」正確 → 補各模型體積/速度/CER 到「實測基準」表。
- **真機驗（CPH1941/SD665，3.7GB RAM，2026-06-05，同一 184s 中文片）**：
  | 模型 | 結果 | 速度 | 標點 | 備註 |
  |---|---|---|---|---|
  | **SenseVoice** | ✅ COMPLETED | **快於即時** | 有 | 正體、內容準、`language=zh` 自動回填 |
  | **Paraformer** | ✅ COMPLETED | ~3× 即時(60s 窗~快) | **無** | 正體、準；CTC 無標點，可讀性靠軟斷 |
  | **turbo-q5** | ✅ 跑通(取窗0樣本) | **~3.7× 即時**(60s 音訊≈220s) | 有 | 正體+標點、準（小誤：角磨機→腳模機）；**很慢** |
  | **Qwen3-ASR** | ❌ **OOM-killed** | — | — | decoder 756MB+autoregressive 活化超出 3.7GB 機；第一窗前行程被系統殺(`has died: fore TOP`)。中階機不可行 |
  - 全程驗證：tar.bz2 下載→Commons Compress 解壓(marker/.part 清理)→`OfflineRecognizer`(`.so` 無 UnsatisfiedLinkError)→backend dispatch→`engineId`/`method` 正確→OpenCC 正體。Qwen3 檔名確認：`conv_frontend.onnx`+`encoder.int8.onnx`+`decoder.int8.onnx`+`tokenizer/`。
- **行動數據 gate（已補 ✅）**：兩個 on-device 引擎在 `ensure` 前若「模型缺 + metered」→ FAILED 並提示連 Wi-Fi/設定頁下載（實測 turbo-q5 觸發正確）。設定頁「下載模型」按鈕原本就有 Wi-Fi 詢問。
- **設定 UI（已補 ✅）**：模型下拉標 SenseVoice「・建議」；各模型 hint 註明 whisper 系列較慢、Qwen3 中階機可能 OOM。
- **狀態**：**程式完成 ✅、建置全綠、真機驗畢（SenseVoice/Paraformer/turbo-q5 通過；Qwen3 中階機 OOM，保留為實驗/高階機選項）**。建議預設/主推 **SenseVoice**。新增檔：`SherpaModel`/`SherpaModelManager`/`SherpaOnnxEngine`、vendored `com/k2fsa/sherpa/onnx/*.kt`（v1.13.2）、`scripts/fetch-sherpa-libs.sh`；改：`Settings.kt`(+`OnDeviceBackend`/4 新值)、`WhisperModelManager`(+TURBO_Q5)、`AppContainer`(backend dispatch)、`SettingsViewModel`/`SettingsScreen`(後端感知)、`TranscriptionService`(method)、build(commons-compress + jniLibs)。APK 已含 `libsherpa-onnx-jni.so`+`libonnxruntime.so`（arm64-v8a/x86_64）。
  - **待真機/runtime 驗**：①各 sherpa 模型實際下載+解壓+轉錄；②**Qwen3 解壓後確切檔名**（目前用 sherpa 原始碼 `getOfflineModelConfig` type=61 的 `conv_frontend.onnx`/`encoder.int8.onnx`/`decoder.int8.onnx`/`tokenizer/`，未 runtime 確認）；③SenseVoice `result.lang` 格式；④whisper turbo-q5 下載。
  - **限制**：sherpa 引擎 id 靜態 `"sherpa-onnx"` → whisper↔sherpa 切換會重轉（正確），但 sherpa 模型間切換 mid-job 沿用 checkpoint（同 whisper base↔small 既有行為）。
  - 完整規劃見 `~/.claude/plans/`（M8 計畫檔）。

## OpenRouter STT API 契約（iOS 照此實作）
- **端點**：`POST https://openrouter.ai/api/v1/audio/transcriptions`，`Content-Type: application/json`，`Authorization: Bearer <key>`。
- **Body**：`{"model": "<id>", "language": "zh"(可省，省則自動偵測), "input_audio": {"format": "wav"|"m4a"|"mp3"|"flac"|"ogg", "data": "<raw base64，非 data URI>"}}`。
- **回應**：`{"text": "...", "usage": {"seconds": 183.86, "cost": 0.00204}}`。**無 language 欄、無 segments/timestamps**（不支援 `verbose_json`）。
- **計費**：按**音訊時長**（秒），與 bytes/格式無關 → 壓縮省頻寬不省錢；用 `usage.cost` 對帳。
- **限制**：上游 ~**60s timeout** → 長音訊務必分窗（本專案 WAV 5min / m4a 10min）。base URL 須 https。
- **model id 實測**（$1 key,2026-06）：
  - ✅ `openai/whisper-large-v3-turbo` — WAV+m4a 皆可，最便宜 **$0.04/hr**，推薦預設。
  - ❌ `openai/whisper-large-v3` — 反覆 `Provider returned 400`，此 provider 壞、不可用。
  - ⚠️ `qwen/qwen3-asr-flash-2026-02-10` — **只收 WAV，m4a→400**;最貴 **$0.126/hr**;同音字較準、會自動加標點。（基本 id `qwen/qwen3-asr-flash` 不存在,要帶日期）

## 實測基準：cloud vs local（184s 中文片，CPH1941 / SD665）
| 引擎·模型 | 速度 | 即時倍率 | 成本/次 | 相對 CER* |
|---|---|---|---|---|
| 裝置端 base | 141s | 0.77× | $0(離線) | ~30% |
| 裝置端 small | 358s | 1.95×(慢於即時) | $0(離線) | ~26% |
| 雲端 turbo | ~35s | 0.19× | $0.00204 | 0(基準) |
| 雲端 qwen | ~35s | 0.19× | $0.00644 | ~2% |

*相對 CER 以 turbo 當準參考(無 ground truth;絕對 WER 需參考稿)。結論:**cloud 準度/速度全面勝**;on-device 把難詞（「錘子」）聽成「垂澀/垂傘」,small 略優於 base 但慢、且會漏字。壓縮 vs WAV(turbo) CER 僅 1.5%。**on-device 只適合離線/隱私**。

## iOS 移植對照（Android → iOS；media-dler-ios）
平台無關（直接照搬邏輯/契約）：`:core` 全部（WindowPlanner 分窗、SegmentMerge 接縫去重、TranscriptFormatter 斷句、LanguageDecision、SubtitleVtt）、OpenRouter API 契約與 model 取捨、轉錄狀態機（job/checkpoint/resume/cancel）、引擎切換防呆（engineId 不同丟 checkpoint）。

| Android 元件 | iOS 對應 |
|---|---|
| whisper.cpp JNI（`WhisperNative`/`whisper_jni.cpp`） | 同一份 whisper.cpp，走 `whisper.swiftui` 範例的 Swift/ObjC 橋；ggml 模型相同 |
| `AudioToPcm`（MediaCodec/MediaExtractor→16k mono float） | AVFoundation：`AVAssetReader` 或 `AVAudioFile`+`AVAudioConverter` → 16kHz mono Float32 |
| `AudioEncoder`（PCM→AAC m4a，壓縮上傳用） | `AVAudioConverter`/`AVAssetWriter` 編 AAC（僅雲端壓縮選項需要） |
| `AudioTrackRemuxer`（無損抽音軌→m4a） | `AVAssetExportSession`(presetPassthrough,只留 audio) 或 `AVAssetReader`+`AVAssetWriter` passthrough |
| `CloudTranscriptionEngine`（JSON+base64 HTTP） | `URLSession`，契約完全相同 |
| 設定 DataStore + 金鑰 | `UserDefaults`；**金鑰建議存 Keychain**（比 Android DataStore 明文更安全） |
| `TranscriptionService`/`AudioExtractionService`（foreground service） | iOS 無前景服務：用 `BGProcessingTaskRequest`/背景 `URLSession`，或在前景跑並顯示進度。**最大結構差異**，需重新設計長任務存活 |
| `MediaStoreStorage`/`SafStorage`（Downloads/SAF 存檔） | 存 app Documents + `UIDocumentPicker` 匯出，或 share sheet；無「Downloads/media-dler」概念 |
| `ShareReceiverActivity` + intent-filter（video/*、audio/*、URL） | **Share Extension**（宣告 video/audio/URL UTType）；content uri grant → `NSItemProvider`/security-scoped URL（`startAccessingSecurityScopedResource`） |
| `LocalMediaSheet`/`ShareSheet`（Compose Dialog） | SwiftUI sheet / `confirmationDialog` |
| `Notifications`（前景/完成/失敗） | `UNUserNotificationCenter` |
| `OpenCcConverter`（opencc4j s2t） | `SwiftyOpenCC` 或 OpenCC C++（用 s2twp 片語在地化更佳） |
| yt-dlp（連結下載/字幕，youtubedl-android） | iOS 無對應；連結輸入在 iOS 較難，依 media-dler-ios 既有做法或先不做 |

## 技術決策（多方檢視後）
- **裝置端雙後端（whisper.cpp + sherpa-onnx），後端由所選模型推導**（M8 規劃）。不在 UI 暴露「後端」概念：`TranscribeEngine` 維持 `ON_DEVICE/CLOUD`，`TranscribeModel` 加 `backend` 屬性，on-device 下拉統一列全部模型。sherpa 模型走 HF 逐檔下載（免 tar/bzip2），音訊格式與 sherpa 完全相容。選 sherpa 是因 SenseVoice/Paraformer 為非自回歸、中文準度與速度均勝 whisper；Qwen3 0.6B 大且自回歸，列實驗選項。
- **雲端只支援 OpenRouter（JSON+base64），非通用 multipart**（2026-06 改，見 M5）。OpenRouter 的 `/audio/transcriptions` 不收 multipart、不支援 `verbose_json`；契約見「OpenRouter STT API 契約」。設定文案已標明「僅支援 OpenRouter」。
- **雲端音訊壓縮為使用者選項、預設關閉**（見 M5）。WAV（原樣、品質最佳、短分窗）vs m4a/AAC（小、長分窗）。**費用不受格式影響**（OpenRouter 按音訊時長計費），壓縮只省上傳頻寬;裝置端 AAC 編碼慢（SD665 +~28s）、且部分 provider 不收 m4a（qwen），故預設 WAV。
- **影片抽音用無損 remux（MediaExtractor+MediaMuxer），非重新編碼**（見 M6）。保留原音軌 codec/取樣率/聲道（如 48kHz 立體聲 AAC）；快、低記憶體。注意：讀 content uri 要用 `contentResolver.openFileDescriptor` 餵 `MediaExtractor`，`setDataSource(context,uri)` 對帶 grant 的 uri 會 `Failed to instantiate extractor`。
- **顯示層可讀斷句（TranscriptFormatter）**：raw 文字保持無換行（resume/SegmentMerge 接縫去重靠精確 suffix==prefix）;**只在顯示/複製/分享時**做斷句（句末標點硬斷 + 過長無標點軟斷），不偽造句界。
- **解碼器：MediaCodec/MediaExtractor**（已定）。原因：youtubedl-android 的 ffmpeg 不開放任意指令；MediaCodec 系統內建、零相依。
- **whisper 模型**：預設 `ggml-base`（~142MB，快）；設定可換 `ggml-small`（~466MB，中文較準）。模型不進 APK，首次使用下載（建議 Wi-Fi gate）。
- **opencc on Android**：先試 `opencc4j`（純 Java 字典、零 native）；不行再 bundled OpenCC dict。
- **whisper.cpp 取得**：官方 `examples/whisper.android` 自建（submodule + CMake，可控、無 maven 依賴風險）。
- **建置環境**：使用者已有 Android 開發環境（Android Studio）；whisper.cpp 需 NDK + CMake，缺什麼實作時再提示。實機測試由使用者執行。
- **既有專案前提**：minSdk 29 / compileSdk 35 / ABI = armeabi-v7a, arm64-v8a, x86, x86_64（whisper.cpp 四種都要編）；R8 關閉；JNI `useLegacyPackaging`。

## 已知問題 / 限制
- **FAILED 本機檔無法續跑**（既有、非本批引入）：`TranscriptionService` finally 不論成敗都刪 `inputCopy`，FAILED 的本機檔 job 私有複本被刪，重試會再失敗。修法：刪複本限縮在「成功／取消」時，FAILED 保留以利從 checkpoint 續跑。
- **多窗 seek 全程未實機驗**：emulator 無 AVX（15min 才 5%）跑不完多窗；真機（~8x realtime）才測得完整 seek 接縫品質。window 0 的 partial-window 解碼已驗。
- **雲端實際轉錄已驗（M5）**：OpenRouter turbo/qwen 真機端到端通;`verbose_json` 解析已移除（OpenRouter 不支援）。
- **OpenRouter provider 限制（實測）**：`whisper-large-v3` 反覆 400 不可用;`qwen3-asr-flash` 只收 WAV、m4a 必 400。選 model + 壓縮模式時要避開這些組合（turbo 最穩）。
- **OpenRouter AUTO 中文停在簡體**：OpenRouter 不回 language，AUTO 時不會套 OpenCC 轉正體;需把「轉錄語言」設「中文」。iOS 同此設計。
- **雲端 base URL 須 https**：http 受 Android cleartext 政策擋下。
- **雲端上傳無行動數據 gate**（模型下載有 Wi-Fi 詢問；雲端逐窗上傳目前不擋）。
- **連結轉文字僅「彈窗選擇」分享模式可用**：one-tap 模式不顯示 picker，未動既有 one-tap 行為（surgical）。
- **YouTube CC 為 best-effort**：常需 PO token／被擋，抓不到自動 fallback 下載音訊轉錄。
- **on-device 中文品質** base < 桌面 Qwen3-ASR；可切 small 或雲端補。
- **引擎切換 mid-resume**：已用 `TranscriptJob.engineId` + `TranscriptionManager.beginRun` 防呆（窗制不同丟棄 checkpoint 重轉）。
- **單窗短音訊窗內進度較粗**：encode 期間進度不動（whisper progress callback 顆粒）。

## Release 前待辦（TEMP 還原）
- `app/build.gradle.kts` abiFilters 還原四種 ABI（目前暫縮 `arm64-v8a, x86_64` 供 emulator 測試）。
- `app/src/debug/AndroidManifest.xml` 的 `READ_MEDIA_*` 為 debug-only（正式 build 不含），可保留。
- 完整 s2twp 片語在地化（目前 opencc4j 字元級 s2t）。

## 風險
- whisper.cpp NDK 建置 + 模型下載是最重的一塊（故列里程碑 1）。
- on-device 中文品質可能不如桌面 Qwen3-ASR；不足時雲端引擎（里程碑 3）補。
- 長音訊記憶體：務必逐段載入/釋放 PCM，勿一次載整檔 float。

## 不做（明確排除）
- 時間軸字幕（SRT/VTT 輸出）、講者分離、即時串流轉錄、編輯逐字稿、雲端同步。
