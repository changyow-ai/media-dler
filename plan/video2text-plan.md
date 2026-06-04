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
  - **分享本機 video/voice 檔** → 跳選單，但**只有「轉文字」一個選項**（本機檔不需下載）。
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

### 環境（已驗證可用）
- branch：`feat/video2text-transcribe`。
- Sandbox Android 工具鏈完整：JDK 17（JBR）、SDK `~/adt-bundle-mac-x86_64/sdk`（platform 35/36、build-tools 35/36）、**NDK `ndk-bundle` r23（clang 12）**、CMake 3.22.1、網路可達、gradle 8.14.3。
- `local.properties`（gitignored）指 `sdk.dir`。**NDK 佈局修正**：legacy `ndk-bundle` 無 versioned 目錄，建 symlink `$SDK/ndk/23.0.7344513-beta4 → ndk-bundle`，`build.gradle` 設 `ndkVersion = "23.0.7344513-beta4"`（AGP 會警告 CXX5304 同 package id，去重後照用、非錯誤）。

### 里程碑 0 — 完成 ✅
- `:core/transcribe/`：`TranscriptionEngine`、`Transcript`/`AudioRef`、`WindowPlanner`、`SegmentMerge`、`LanguageDecision`、`SubtitleVtt` + JUnit，`:core:test` 全綠。
- `:app` 骨架：`TranscribeScreen`（結果頁，複製/分享可用）、`TranscribeActivity`、`ShareReceiverActivity` 加本機檔分支、Manifest 補 `video/*`、`audio/*` SEND filter。`:app:assembleDebug` 綠燈。
- 刻意延後：`TranscriptionService`（無引擎前是空殼，挪 M1）、URL「轉文字」picker 動作（屬 M2）、本機檔的單選選單（折進結果頁初始狀態）。

### 里程碑 1 — 進行中
- **P1-1 完成 ✅**：whisper.cpp **v1.8.6**（`app/src/main/cpp/whisper.cpp`，submodule）+ ggml 經 NDK r23 編譯，`BUILD_SHARED_LIBS=OFF` 將 ggml 靜態折入單一 `libwhisper_jni.so`（arm64-v8a，4.3MB，已進 APK）。JNI 橋接 `whisper_jni.cpp` + Kotlin `WhisperNative`（init/free/fullTranscribe/detectedLanguage）。CMake 選項：`WHISPER_BUILD_TESTS/EXAMPLES/SERVER=OFF`、`GGML_NATIVE/OPENMP/CCACHE=OFF`。
  - **TEMP**：`build.gradle` abiFilters 暫縮 `arm64-v8a` 加速 native 迭代，release 前還原四種 ABI。
  - runtime 載入/轉錄待實機（P1-5 驗）。
- P1-2 AudioToPcm、P1-3 ModelManager、P1-4 WhisperCppEngine+OpenCc、P1-5 串接+管路驗證：待做。
- 驗證素材：使用者提供的測試短片大量為**台語**，僅適合驗「管路」（解碼→引擎→顯示通不通），品質基準待換華語片。

## 技術決策（多方檢視後）
- **解碼器：MediaCodec/MediaExtractor**（已定）。原因：youtubedl-android 的 ffmpeg 不開放任意指令；MediaCodec 系統內建、零相依。
- **whisper 模型**：預設 `ggml-base`（~142MB，快）；設定可換 `ggml-small`（~466MB，中文較準）。模型不進 APK，首次使用下載（建議 Wi-Fi gate）。
- **opencc on Android**：先試 `opencc4j`（純 Java 字典、零 native）；不行再 bundled OpenCC dict。
- **whisper.cpp 取得**：官方 `examples/whisper.android` 自建（submodule + CMake，可控、無 maven 依賴風險）。
- **建置環境**：使用者已有 Android 開發環境（Android Studio）；whisper.cpp 需 NDK + CMake，缺什麼實作時再提示。實機測試由使用者執行。
- **既有專案前提**：minSdk 29 / compileSdk 35 / ABI = armeabi-v7a, arm64-v8a, x86, x86_64（whisper.cpp 四種都要編）；R8 關閉；JNI `useLegacyPackaging`。

## 風險
- whisper.cpp NDK 建置 + 模型下載是最重的一塊（故列里程碑 1）。
- on-device 中文品質可能不如桌面 Qwen3-ASR；不足時雲端引擎（里程碑 3）補。
- 長音訊記憶體：務必逐段載入/釋放 PCM，勿一次載整檔 float。

## 不做（明確排除）
- 時間軸字幕（SRT/VTT 輸出）、講者分離、即時串流轉錄、編輯逐字稿、雲端同步。
