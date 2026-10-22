# 中文短片轉錄：修正與驗證

查核日期：2026-10-22。基準：`3e4c831`（0.1.13）。這次沒有換掉預設模型，也不會把裝置端失敗的音訊自動傳到雲端。

## 找到的問題

1. 三個引擎都把不足 3 秒的空 PCM 當成可以跳過的尾段，實際上它也可能是一整段短片。
2. 較長的空 PCM 雖未立刻寫 checkpoint，但迴圈繼續後，下一個成功視窗仍會把 checkpoint 推過失敗處，造成永久漏段。
3. Whisper／雲端引擎可以回傳全空結果，服務卻標記完成、刪除私有輸入檔；重新分享同來源又只會顯示舊的空結果。
4. 無時長資訊的空白 checkpoint 不會自癒，重試可能永遠從真正 EOF 開始。
5. Whisper JNI 將非零錯誤碼吞成空字串，已經有其他文字時，辨識失敗會被誤當成無聲視窗。
6. 平台解碼沒有獨立後備方案，且把所有負 timestamp 都當成 EOF；負 timestamp 也可能是有效的 codec pre-roll。

先前 S23 Ultra 紀錄的 `fed=0` 能定位到「模型之前沒有送入壓縮音訊」。僅憑這點不能證明 AAC edit list 是唯一原因，也不能斷言所有中文短片都是同一問題。這次未取得使用者故障影片或 S23 Ultra 實機。

## 修正行為

- 平台解碼為空或拋出一般解碼例外時，最多使用**已經隨 app 打包**的 FFmpeg 重試一次，不新增 ffmpeg-kit。
- FFmpeg 僅允許本機 file protocol，輸出 16 kHz mono s16le，由 Kotlin 直接讀 PCM，不再繞回有問題的 MediaExtractor。
- 全部引擎的最後視窗仍受 60 秒（裝置端）或 5／10 分鐘（雲端）限制；不再因錯誤的時長 metadata 載入整段長片。metadata 低報時，接近全長的最後視窗會繼續做有限長度探測，直到真正結尾；僅剩已處理重疊區不再推論或寫 checkpoint。非視窗化自然 EOF 解碼另有 10 分鐘輸出上限，超限拒絕而非假裝完成。
- 不把「非空但全為零的 PCM」當成解碼失敗。實測 SenseVoice 在 2.2 秒純靜音會產生韓文 `그.`，因此三個引擎在模型前跳過**確切全零** PCM；沒有音量門檻，不會因此丟掉很小聲的人聲。整份無語音仍顯示可重試失敗，不捏造逐字稿。
- 已知應有音訊的視窗仍空時，立即失敗，不繼續到下一個 checkpoint。未知時長僅在已處理過視窗、且後備解碼成功確認空輸出後視為 EOF。
- 全空結果不再完成或刪除輸入；無文字可保留的重試從頭開始。舊版已完成但空白的記錄可以重新分享以重跑。
- 平台優先向前一個同步點 seek 再裁掉 pre-roll；移除直接跳過片頭 50 ms 的 workaround，避免漏掉短語的第一個音。平台無進展 30 秒會退到後備；FFmpeg 本地逾時是普通解碼失敗，不誤當服務取消。協程取消仍傳播並等原生程序清理。
- Whisper 原生錯誤傳回明確例外。移除把逐字稿全文寫入 logcat 的暫時除錯程式。

## 已做與未做的驗證

- 已以 Kotlin 2.0.21／JUnit 4.13.2 執行 57 個相關測試：PCM fallback、真 FFmpeg AAC/edit-list 容器與範圍、WindowPlanner、合併、字幕、格式化與語言處理，全部通過。
- 另有實際 FFmpeg 對照：90 秒音訊故意只報 10 秒 metadata，仍保留全部音訊；恰好 60 秒時不再辨識或 checkpoint 僅重疊的 3 秒尾段。公開真人錄音的 67.104 秒組合版本也用 10 秒 metadata 通過同一流程。
- FFmpeg 實際測試包含 22,050 Hz stereo AAC、1,111 ms 與 1 ms 精確範圍、真正 EOF、損壞容器、禁止巢狀網路 URL，以及自然 EOF 的容量上限。
- 已執行與 app 相同的 `NativeDownloadProcess` 程序群組監督及 Python exec trampoline，使用公開真人中文錄音與轉碼 AAC，成功取得 PCM。
- `scripts/check-short-audio.py` 使用 app 的實際 Kotlin PCM 參數及 window planner，加上相同版本 sherpa-onnx 1.13.2／SenseVoice int8，在主機驗證公開真人中文、英文及真正靜音。22 秒／67 秒案例是**同一真人錄音的組合重複**，不是自然長篇對話。原始素材來源及 SHA-256 在輸出 JSON 中。
- 非空逐字稿不等於正確。壓縮／重取樣後存在字詞替換，重複錄音也曾被自動判為粵語；報告保留原始語言和節錄，不把這些算成準確率達標。未提供 CER 或手機速度保證。
- 本機沒有 Android SDK／連線手機。主機測試不能證明 S23 Ultra 修復、APK 內 FFmpeg 能啟動或 MediaCodec 行為；仍應在目標手機用原始故障檔驗收。CI APK build 只能證明編譯，不能替代裝置測試。

執行主機音訊驗證（模型與音檔留在本機，不自動下載或上傳）：

```sh
python -m pip install sherpa-onnx==1.13.2 numpy soundfile
# 解壓官方 sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2 後：
python scripts/check-short-audio.py --model-dir /path/to/extracted-model \
  --kotlinc /path/to/kotlinc --output /tmp/short-asr-report.json
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

公開 fixture/model 來源：[sherpa SenseVoice 官方發佈說明](https://k2-fsa.github.io/sherpa/onnx/sense-voice/pretrained.html)。本測試不重新散布模型或錄音。

## 模型建議

- **先保留 SenseVoiceSmall int8 預設**：主要是華語時，可先在既有「轉錄語言」設定選中文，減少短片自動語言誤判；這不是強制翻譯或新的準確率保證。現有約 163 MB 壓縮下載。2025-09-09 包是粵語微調且無標點，並非 2024 版的全面替代。
- **立即更正 Paraformer 的 UI**：目前 2025-10-07 權重是川渝方言微調，不能寫「純中文最準」。本次改為「川渝方言」，不暗中重新下載／換權重。[官方文件](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-paraformer/paraformer-models.html)
- **真正小型的下一個實驗**：Moonshine Mandarin Tiny Streaming，官方 34M、MIT，manifest 必要資產合計 32.29 MB。需要新的 Moonshine Android runtime，不是現有 sherpa 舊 Moonshine Base。官方量化 CER 16.1% 來自 FLEURS＋WenetSpeech 抽樣，不是本 app 與 SenseVoice 的對照，不能宣稱更快更準。[官方模型與授權](https://moonshine-voice.readthedocs.io/en/latest/models/available-models/)、[評估方法](https://moonshine-voice.readthedocs.io/en/latest/models/accuracy/)
- **品質優先先實測現有 Qwen3-ASR 0.6B**：已整合，但下載約 879 MB，不是輕量模型。官方 CV-zh-tw CER 5.59% 是研究設定，不是本 app int8／手機成績。現有 maxNewTokens=128 值得用快語速及較長片段檢查截斷後再調整，不能未實測就提高預設或改換模型。[官方論文](https://arxiv.org/html/2601.21337v2)
- FireRedASR2 CTC int8 約 740 MiB、Fun-ASR-Nano int8 接近 1 GB，不優先列為「更小」方案。

授權也需依確切權重核對：SenseVoice 官方區分 MIT 程式碼與 FunASR Model License 權重；不能僅因 runtime 是 MIT 就宣稱模型也是。發版保留下載版本的來源、授權與署名要求。[SenseVoice 官方授權](https://github.com/QwenAudio/SenseVoice#license)

下一步用同一組 30–50 段實際台灣華語短影音比較 CER、空結果、漏尾字、背景音樂、中英夾雜、冷啟動／暖啟動耗時、峰值 PSS／RSS 及手機溫度，再決定是否增加「輕量模式」。模型容量不是推論 RAM，研究 GPU 效能不是手機 CPU 效能。
