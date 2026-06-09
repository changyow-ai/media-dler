# S23U：不足 60 秒的影片／音檔無法轉文字

## 症狀
- 機型：Samsung Galaxy S23 Ultra（S23U）。
- 短於 60 秒的影片轉錄結果為空；純音檔同樣失敗。
- 使用者最初懷疑是「轉 WAV」的步驟出錯。

## 根因（已由實機 telemetry + 檔案結構證實）
轉 WAV 本身沒問題——PCM 來自 `AudioToPcm.decodeRange()`，PCM 空則 WAV 空、轉錄空。

### 決定性證據（S23U 實機）
`win=0 [0-56006ms] 預期≈56s 卻空 → codec=c2.android.aac.decoder mime=audio/mp4a-latm`
`in=22050Hz/2ch ... fed=0buf/0B outBuf=0 rawBytes=0 outPts=-1..-1us inEos=true`
`outEos=true eosBeforeData=true`

- **`fed=0buf/0B`** = 解碼器一個 byte 都沒被餵到。用的是 `c2.android.aac.decoder`（Google **軟解**，
  非 Samsung 硬解）。
- 餵入迴圈第一次拿到 input buffer 時 `extractor.sampleTime` 已是 **-1** → 立刻送 EOS。
  即 **MediaExtractor 對這個 m4a `selectTrack` 後從頭就讀不到任何 sample**。
- `eosBeforeData=true` 在此只是 `fed=0 → rawBytes=0` 的連帶症狀，**red herring**，非 flush 問題。

### 檔案結構（/sdcard/Download/cn_15s.m4a、cn_33s.m4a，ffprobe + box dump）
- ffmpeg 產生（`encoder=Lavf61.7.100`，yt-dlp `-x --audio-format m4a` 走 ffmpeg）。
- 帶 **edit list**：`elst ver=0 count=1 entries=[(dur, media_time=1024, 65536)]`，
  `media_time=1024` 是 AAC priming（gapless）補償；第一個 packet 是負 PTS + `skip_samples=1024`。
- `MediaExtractor` 對 edit list 的處理在部分裝置會出包，導致未 seek 的初始游標定位錯誤、回報
  無 sample。ExoPlayer 之所以自行重寫 edit-list 邏輯就是因為平台 extractor 會這樣。

### 為何「<60s」相關但非真因
所有失敗檔都是 **yt-dlp/ffmpeg 產生、帶 priming edit list 的 m4a**（含 Threads 解析出的音檔）。
使用者的短測試片剛好都是這類；長片可能來自不同來源（無此 edit list）所以看似正常。60s 是巧合。

## A/B 結論：對此 case 無效（但保留）
A（EOS 掛最後 buffer）/ B（最後 window 解到 EOF）針對「解碼器收到資料卻不吐」。此 case 是
「extractor 根本沒交資料」，故 A/B 救不到——但兩者本身是正確的健壯化改進，保留。

## 網路搜尋結論
查無 Galaxy S「短於 60 秒轉錄失敗」公開案例（本 app 特有）。但 MediaExtractor edit-list 處理
缺陷、AAC priming、Samsung codec 怪癖均有佐證
（[androidx/media #2189](https://github.com/androidx/media/issues/2189)、
[Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)）。

## 診斷指引
S23U 上重現後，`SherpaOnnxEngine` 失敗訊息會帶 `DecodeStats.summary()`：
- `fed>0 / rawBytes=0 / eosBeforeData=true` → 解碼層 flush 問題（方案 A 對症）。
- `rawBytes>0` 但仍空 → 模型層（whisper/sherpa）問題，與 WAV 無關。

## 解法清單與嘗試順序

| 方案 | 做法 | 狀態 |
|---|---|---|
| **G** | **真因對症**：`positionExtractor` 在同一次解碼裡依序試 none→closest→prev→next→skip50ms 定位游標，取第一個能吐出 sample 的策略；繞過 MediaExtractor edit-list 初始定位 bug | ✅ 已實作（主修） |
| **A** | EOS 旗標掛最後一個資料 buffer，不再另送 size=0 EOS buffer | ✅ 已實作（健壯化，對此 case 無效） |
| **B** | 最後/唯一 window 以 `endMs=Long.MAX_VALUE` 解到自然 EOF | ✅ 已實作（健壯化，對此 case 無效） |
| **C** | 顯式送 CSD priming：configure 後先以 `BUFFER_FLAG_CODEC_CONFIG` 餵 `csd-0` | ⬜ 待試 |
| **D** | MediaCodec 改 async 模式（`setCallback`） | ⬜ 待試 |
| **E** | 純音檔直接 parse header 取樣，跳過 MediaCodec | ⬜ 待試 |
| **F** | 內建 FFmpeg（ffmpeg-kit）decode→16k mono WAV，繞過平台 codec（APK 大，最後手段） | ⬜ 待試 |

### G 實作位置與判讀（厚 telemetry）
`AudioToPcm.decodeRange` → `positionExtractor()`。`DecodeStats.summary()` 新增欄位：
`tracks=N/sel=i[mime,...] durUs=… csd0=… seek=<策略> firstSample=<us>`。

下一次測試後依 `seek=` 與 `fed=` 判讀（**一次定案**）：
- `seek=closest|prev|next|skip50ms` 且 `fed>0 / rawBytes>0` → **G 修好了**（哪招有效就記下）。
- `seek=none-worked / firstSample=-9223…`（sentinel）→ 連 seek 都拿不到 sample，MediaExtractor
  對此檔徹底無法讀 → 跳 **E/F**（strip elst 重 mux，或 ffmpeg）。
- `fed>0 / rawBytes=0` → 變回「餵了卻不吐」的解碼層問題 → 走 **A 已涵蓋**，再看 `eosBeforeData`。
- `tracks=0` 或 `sel=-1` → 連音軌都認不得 → 容器／URI 權限問題，另查。

### A 實作位置
`app/src/main/java/com/changyow/mediadler/transcribe/AudioToPcm.kt` — `drainToMonoFloat`
的餵入迴圈：讀完一個資料 buffer 後 `advance()`，look-ahead 下一個 `sampleTime`；若 `<0`
或 `>= endUs`，則此即最後一個資料 buffer，直接在它上面帶 EOS 旗標。只有「整段無資料」的空
window 才回退到 size=0 EOS buffer。

### B 實作位置
`SherpaOnnxEngine.kt`、`WhisperCppEngine.kt`、`CloudTranscriptionEngine.kt` 的 window 迴圈：
`if (planned != null && index == planned.lastIndex) Long.MAX_VALUE else window.endMs`。
unknown duration（`planned == null`）維持原 bounded window，不可解到 EOF。

## ⚠️ 關鍵發現：poisoned checkpoint 一直遮蔽所有修法
第二次測試訊息變成「音訊已解碼（最長視窗 **0 samples**）但模型輸出為空」，且 `emptyDiag` 為空。
對 56s 單一 window 而言這自相矛盾——唯一可能是**迴圈跑了零次**，即從 `startWindow=1` resume，
跳過了唯一的 window 0。

機制：
- `TranscriptionService.kt:197` `startWindow = current.completedWindows`；
  `TranscriptionManager.kt:93`「FAILED 從 checkpoint resume；CANCELLED 重新開始」；job id 由來源穩定
  推導 → **重分享同一檔 = resume，不是重跑**。
- 第一次失敗時，空 window 分支執行了 `lastCompleted = index+1` (=1) + `onCheckpoint`，把「window 0
  已完成」寫進去。之後每次重裝重測都從 1 resume、跳過 window 0。
- **結論：A/B/G 從未在這個檔上實際執行過**——每次都被 poisoned checkpoint 短路。

### 修法 H（已實作）：解碼失敗的空 window 不推進 checkpoint
`SherpaOnnxEngine` / `WhisperCppEngine` / `CloudTranscriptionEngine` 的空 window 分支：只有
`expectedMs < 3000ms`（benign 尾端碎片）才推進 `lastCompleted` + `onCheckpoint`；可疑空 window
（預期有聲卻空）**不推進**，讓 retry 能重試該 window 而非永久跳過。

### 修法 I（已實作）：自癒毒化的 checkpoint + 去歧義 telemetry
使用者回報「已是最新版且重頭開始」仍出現 `0 samples`，代表 H 防得了新 job、但**舊 job 的
checkpoint=1 仍會讓 resume 跳過唯一 window**（job id 由來源穩定推導，重分享 = resume）。

- **自癒**：三個 engine 在迴圈前計算 `effectiveStart`——若 `planned != null && startWindow >=
  planned.size && priorText 空`，就從 0 重跑（毒化 job 無既有文字，重跑安全、不會重複）。
- **去歧義**：Sherpa 失敗訊息附 `[start=A→B planned=N dur=…ms]`，一眼可辨是否 resume 跳過。

判讀下一次：
- `start=1→0`（或任何 `A→0`）→ 自癒生效，已從頭重跑，看後續 `seek=`／`rawBytes` 判 G。
- `start=0→0 planned=1` 仍 `0 samples` → 真的是 window 0 沒被處理的更深問題，另查。

## 若 G 在 S23U 仍失敗
S23 不開 adb，無法在故障機自驗，故以厚 telemetry 換取「一次測試定案」。依上方 G 判讀表
決定下一步（多半是 E：把 elst 去掉重 mux，或 F：ffmpeg）。每次保留完整 `DecodeStats` 字串。
