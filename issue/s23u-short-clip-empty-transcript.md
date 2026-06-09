# S23U：不足 60 秒的影片／音檔無法轉文字

## 症狀
- 機型：Samsung Galaxy S23 Ultra（S23U）。
- 短於 60 秒的影片轉錄結果為空；純音檔同樣失敗。
- 使用者最初懷疑是「轉 WAV」的步驟出錯。

## 根因研判
轉 WAV 本身沒問題——`CloudTranscriptionEngine.writeWav` / 各 engine 拿到的 PCM 來自
`AudioToPcm.decodeRange()`。若 PCM 為空，WAV 自然空、轉錄自然空。問題在
`AudioToPcm.drainToMonoFloat` 的 `MediaCodec` 解碼收尾方式：

- 原本以「另送一個 size=0、帶 `BUFFER_FLAG_END_OF_STREAM` 的空 buffer」來收尾。
- 權威事實：MediaCodec 解碼器吐出任何 output 前通常需先 queue 進數個 input buffer；AAC 需 CSD。
  （[Android MediaCodec](https://developer.android.com/reference/android/media/MediaCodec)、
  [bigflake](https://bigflake.com/mediacodec/)）
- 部分 Samsung 解碼器收到「零長度 EOS buffer」時不會 flush 仍 pending 的 output
  （期待 EOS flag 掛在最後一個有資料的 buffer 上）。Samsung 機型專屬 codec 怪癖有案可查：
  [androidx/media #2189](https://github.com/androidx/media/issues/2189)。
- **為何只有短片段全空**：長片段餵了數十秒、output 早已持續吐出，EOS 沒 flush 只損失尾端幾個
  frame，文字照樣有；短片段整段內容可能都還卡在「尚未 flush 的 pending buffer」，EOS 一來沒
  flush → `rawBytes=0` → 空轉錄。對應 `AudioToPcm` 註解與 telemetry 旗標
  `fed>0 / rawBytes=0 / eosBeforeData=true`（commit 77ae94b 已埋）。

## 網路搜尋結論
查無 Galaxy S 系列「短於 60 秒轉錄失敗」的公開案例（屬本 app 特有症狀）。但上述
MediaCodec 解碼器緩衝行為、AAC CSD、Samsung codec 怪癖皆有官方／社群佐證。

## 診斷指引
S23U 上重現後，`SherpaOnnxEngine` 失敗訊息會帶 `DecodeStats.summary()`：
- `fed>0 / rawBytes=0 / eosBeforeData=true` → 解碼層 flush 問題（方案 A 對症）。
- `rawBytes>0` 但仍空 → 模型層（whisper/sherpa）問題，與 WAV 無關。

## 解法清單與嘗試順序

| 方案 | 做法 | 狀態 |
|---|---|---|
| **A** | EOS 旗標掛最後一個資料 buffer，不再另送 size=0 EOS buffer（Samsung 標準解法） | ✅ 已實作 |
| **B** | 最後/唯一 window 以 `endMs=Long.MAX_VALUE` 解到自然 EOF，避免 floored-to-ms 的 endUs 截斷競爭 | ✅ 已實作 |
| **C** | 顯式送 CSD priming：configure 後、餵資料前先以 `BUFFER_FLAG_CODEC_CONFIG` 餵一次 `csd-0` | ⬜ 待試（A/B 失敗時） |
| **D** | MediaCodec 改 async 模式（`setCallback`），避開同步 interleave 時序問題 | ⬜ 待試 |
| **E** | 原始音檔（WAV/PCM）直接 parse header 取樣，跳過 MediaCodec | ⬜ 待試（針對純音檔失敗） |
| **F** | 內建 FFmpeg（ffmpeg-kit）做 decode→16k mono WAV，完全繞過平台 codec（APK 體積大，最後手段） | ⬜ 待試 |

### A 實作位置
`app/src/main/java/com/changyow/mediadler/transcribe/AudioToPcm.kt` — `drainToMonoFloat`
的餵入迴圈：讀完一個資料 buffer 後 `advance()`，look-ahead 下一個 `sampleTime`；若 `<0`
或 `>= endUs`，則此即最後一個資料 buffer，直接在它上面帶 EOS 旗標。只有「整段無資料」的空
window 才回退到 size=0 EOS buffer。

### B 實作位置
`SherpaOnnxEngine.kt`、`WhisperCppEngine.kt`、`CloudTranscriptionEngine.kt` 的 window 迴圈：
`if (planned != null && index == planned.lastIndex) Long.MAX_VALUE else window.endMs`。
unknown duration（`planned == null`）維持原 bounded window，不可解到 EOF。

## 若 A+B 在 S23U 仍失敗
依 telemetry 走向逐一嘗試 C → D → E → F；每次保留 `DecodeStats` 訊息以便比對
`rawBytes` / `eosBeforeData` 是否改變。
