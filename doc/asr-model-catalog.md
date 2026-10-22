# 中英離線 ASR 模型目錄與驗證

查核日期：2026-10-22。這次修改基於 **v0.1.14 / `00e1ea48d743d1b44272a381bd3f17f87f829515`**，不是仍停在舊版的 `main`。原先短片解碼、全零靜音、空白失敗與尾段／checkpoint 修正均已在基準中，不要再套用舊補丁。

## 先選什麼

- **SenseVoice Small INT8 繼續當預設與比較基準。** 不改目前選擇，不換掉正在用的 2024-07-17 archive、ITN、語言提示、80-bin 特徵、CPU 上限或下載目錄。
- **中英文與混語，先比較 FireRedASR2 AED INT8、Breeze-ASR-25 Q5、Whisper large-v3 Q5。** 這是有實際 runtime 的候選清單，不是準確率排名。FireRed 的短混語實測有錯字；Breeze / full large-v3 很慢，均不能保證勝過 SenseVoice。
- **台灣華語／句內英文詞，Breeze-ASR-25 特別值得比較。** 選 25 而非 26 是因任務相符，不是偏好舊版本。25 的官方模型針對台灣華語與中英 code-switching；26 主要是台語語音轉華語文字，不能以編號較新就當中英升級。
- **Fun-ASR-Nano-2512 INT8、Qwen3-ASR 0.6B INT8 放在「實驗」。** 有真的 ONNX adapter、權重與 tokenizer，但固定 KV cache、短段合併與手機 RAM 尚有限制。Fun 在本次 1.59 秒混語個案保留了 training，另段中文卻誤成「開飯」；不能由單例推整體勝負。
- **Whisper turbo Q5 保留作速度折衷。** base / small 保留舊設定與下載管理，但不再以體積小作為主要推薦。既有 Paraformer 包是川渝方言版，已更正標籤，不當通用中英候選。

所有新選項都是離線。首次下載會連線到列出的公開模型來源；**音訊推論留在本機，沒有新增雲端 fallback、付費 API 或自動上傳**。既有雲端功能維持獨立、自行選用。

## 實際部署，不只新增名稱

| App 選項 | 實際資產／原生路徑 | 下載量 | 權重授權與注意事項 |
|---|---|---:|---|
| SenseVoice Small INT8 | 原有 `model.int8.onnx` + `tokens.txt`；sherpa SenseVoice | 約 163 MB 壓縮 | [FunASR Model License](https://huggingface.co/FunAudioLLM/SenseVoiceSmall/blob/main/LICENSE)，不是因 runtime 是 Apache 就視為相同授權 |
| FireRedASR2 AED INT8 | `encoder.int8.onnx` + `decoder.int8.onnx` + `tokens.txt`；sherpa `fireRedAsr`，不是 CTC | 838,589,068 bytes；主要權重解壓約 1.235 GB | [官方 AED 模型](https://huggingface.co/FireRedTeam/FireRedASR2-AED)，Apache-2.0 |
| Fun-ASR-Nano-2512 INT8 | `encoder_adaptor.int8.onnx` + `llm.int8.onnx` + `embedding.int8.onnx` + `Qwen3-0.6B/{merges.txt,vocab.json,tokenizer.json}`；sherpa `funasrNano` | 841,730,611 bytes 壓縮；三個圖約 994 MB，另有 tokenizer | [官方模型](https://huggingface.co/FunAudioLLM/Fun-ASR-Nano-2512)，Apache-2.0；不是 MLT-Nano |
| Qwen3-ASR 0.6B INT8 | 原有 `conv_frontend.onnx` + encoder / decoder + tokenizer；sherpa `qwen3Asr` | 原包約 879 MB 壓縮 | [官方模型](https://huggingface.co/Qwen/Qwen3-ASR-0.6B)，Apache-2.0；保持原下載路徑 |
| Breeze-ASR-25 Q5_0 | pinned GGML；既有 whisper.cpp JNI，無新 runtime | 1,080,732,091 bytes | [MediaTek 原模型](https://huggingface.co/MediaTek-Research/Breeze-ASR-25)，Apache-2.0；[shdennlin 社群 GGML 轉換](https://huggingface.co/shdennlin/breeze-asr-25-ggml)，不是官方量化成績 |
| Whisper large-v3 Q5_0 | pinned GGML；既有 whisper.cpp JNI | 1,081,140,203 bytes | [OpenAI 模型](https://huggingface.co/openai/whisper-large-v3)，MIT；[whisper.cpp GGML](https://huggingface.co/ggerganov/whisper.cpp) |
| Whisper large-v3-turbo Q5_0 | 原有 GGML / cache 不變 | 約 574 MB | [官方模型](https://huggingface.co/openai/whisper-large-v3-turbo)，MIT；較少 decoder 層，不等於完整 large-v3 |

sherpa runtime 仍是專案釘住的 **1.13.2**，whisper.cpp 仍是 submodule **`23ee03506a91ac3d3f0071b40e66a430eebdfa1d`**。檢查了 native C++、JNI 與 Kotlin config，不是只看 config 欄位存在。沒有為模型目錄順便升級整套 native library。

新下載採精確 bytes + SHA-256，成功後才提升為可用模型；新 sherpa 包還驗證每個必要權重／tokenizer 存在，最後才寫 `.complete`。模型不放進 APK。舊 enum 名稱仍保留，設定使用名稱而非順序，已有下載／選擇不因重新排列而失效。

### 新資產固定版本

| 資產 | 固定版本／SHA-256 |
|---|---|
| `sherpa-onnx-fire-red-asr2-zh_en-int8-2026-02-26.tar.bz2` | `43015b3f1643a5688b4821e8ed323473d38b798c4ec291471fe00df1bcfc4f1c` |
| `sherpa-onnx-funasr-nano-int8-2025-12-30.tar.bz2` | `eb43d7ccc2e86b243f6a03b7df361033dda66db9523d1a92bf6aca2b50c9476b` |
| `ggml-breeze-asr-25-q5_0.bin` | HF revision `36c726093efe1760d1dd39c3cfe8b6a7282437d1`；`f51573bd6ef9b1fac0bd09a1652eda58a1a638d824a08771ccdee1102fe5990a` |
| `ggml-large-v3-q5_0.bin` | HF revision `5359861c739e955e79d9a303bcbc70fb988958b1`；`d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1` |

兩個 ONNX archive 來自 [sherpa 官方 asr-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models)，完整下載 URL 在 `SherpaModel.kt`；GGML revision URL 在 `WhisperModelManager.kt`。新包若上游同名替換會拒絕載入，應重新查核，而非關掉驗證。

## Runtime 限制與此次處理

1. **下載 MB 不是峰值 RAM。** graph、KV、音訊／特徵與 ORT / whisper 工作區會同時存在。大型模型可能 OOM、熱降頻或被 OEM 終止；不宣稱「8 GB 手機一定可以」。本次 host FireRed 進程峰值約 2.51 GiB、Fun 約 1.70 GiB、Breeze 子進程約 1.45 GiB，僅是短檔／x86 測試觀察，不能套成 Android 預算。
2. **FireRedASR2 AED：** native 自行做 80-bin 特徵；自動處理中文／英文，沒有強制語言、標點／時間戳或 LID 輸出。只從此中英模型的 Han 文字判定是否做繁體格式化，不把這個 heuristic 套到支援日文的模型。[官方使用限制](https://github.com/FireRedTeam/FireRedASR2S)建議 AED 輸入不超過 60 秒；本次每次 native decode 最多 30 秒、重疊 2 秒。另有[原生 greedy 輸出上限](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.2/sherpa-onnx/csrc/offline-fire-red-asr-greedy-search-decoder.cc)，約每秒 6 tokens、並受 metadata 限制。切短不會消除此上限，快語速／極短片仍須檢查尾字。
3. **Fun-ASR-Nano：** adapter 內含 Hamming/LFR 等專屬前處理，不能拿 SenseVoice 的外部 features 取代。[v1.13.2 native](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.2/sherpa-onnx/csrc/offline-recognizer-funasr-nano-impl.cc)直接拼接語言提示，所以 zh/en/ja 對應「中文／英文／日文」，不是把 ISO code 當 prompt。採 15 秒／重疊 2 秒、最多 256 個新 tokens。模型約每秒 16.7 audio tokens，512 KV 中還要容納 prompt 與文字；256 是上限，不保證有完整 256 的剩餘空間。
4. **Qwen3-ASR：** native 使用 128-bin Whisper features；只替這個 config 設 128，SenseVoice 維持 80。固定 512 KV 不是 512 個額外文字位置，60 秒音訊本身就可能超過。改為 15 秒／重疊 2 秒、`maxTotalLen=512`、`maxNewTokens=256`，每個 stream 用官方 `Chinese` / `English` 等名稱傳入語言提示。[原生實作](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.2/sherpa-onnx/csrc/offline-recognizer-qwen3-asr-impl.cc)
5. **Fun / Qwen 自動模式不回傳 LID。** 因包含日文，不能見 Han 就誤判中文。要穩定轉繁體，請指定「中文」；這是提示，不保證混語詞一定正確。UI 已揭露。兩者 API 沒有可靠 `finish_reason`，輸出少於 256 tokens 不能證明沒有被 KV 截斷。
6. **續傳座標不變。** 外層仍是 60 秒／重疊 3 秒，內層分段不改 `completedWindows`。只有整個外層成功才發佈 partial text 與 checkpoint；中途失敗不會把未完成文字與舊座標一起保存，重試不會因此重複整段。取消在 native 呼叫前後檢查，無法中斷正在跑的 sherpa native 呼叫。Whisper 保留成功時的即時預覽，但 native 失敗會先還原最後已提交的文字再拋錯，避免大型模型重試重複文字。兩個裝置端引擎在重疊合併前先統一字形，避免已保存的繁體與新的簡體接縫無法匹配。
7. **Whisper / Breeze：** 沿用 16 kHz mono PCM、相同 GGML loader / tokenizer 與 greedy JNI 路徑，不假稱 ONNX 或另一套加速 backend。量化、auto language、提示與 decoder 策略都會影響辨識；這次沒有引入 beam-search 調參或雲端修稿。

短段切分可降低模型輸入上限風險，但字串重疊不等於 word-level alignment。自然長篇混語、重複語句、數字／專有名詞、長靜音接縫，仍須手機驗收。

## 最近模型也查了，沒有直接塞進可選清單

版本日期是 **ASR 部署本身**，不是泛用家族日期；「最新」不等於適合此 app。

| 候選／日期 | 為什麼本次不直接接上 |
|---|---|
| [Ming-3.0-asr tiny](https://github.com/inclusionAI/Ming-3.0-asr)，2026-10-19 repo | 有中英相關能力、MIT，但官方路徑是 CUDA / 自訂 Transformers；tiny 名稱不代表手機小模型。尚無與這裡 pinned runtime 相符、已驗證的 Android graph/tokenizer 路徑。 |
| [Granite Speech 5.0 2B](https://huggingface.co/ibm-granite/granite-speech-5.0-2b)，2026-10-20 | 官方 ASR 語言清單不含中文輸入；英語轉中文翻譯不是中文 ASR，不能當中英候選。 |
| [VibeVoice-ASR Streaming 1.5B / 7B](https://huggingface.co/microsoft/VibeVoice-ASR-Streaming-1.5B)，2026-09 | 新的串流選擇，但官方部署是 Python / vLLM 等，沒有目前 Android adapter。 |
| [VibeVoice-ASR-BitNet / VibeASR.cpp](https://github.com/microsoft/VibeASR.cpp)，2026-07 | MIT、ARM NEON / x86 路徑值得下一階段研究；約 1.58 GB 的兩個 GGUF 要用獨立 VibeASR.cpp，不是改副檔名就能餵 whisper.cpp。 |
| [Confucius4-R2T2](https://github.com/netease-youdao/Confucius4-R2T2)，2026-09 | Qwen-ASR 衍生路線；官方套件的 Linux x86 / CUDA、Tokenizer 與自訂 NetEase 授權都需另行評估，不等於既有 Qwen 0.6B ONNX 可直接換。 |
| [FireRedAudio](https://huggingface.co/FireRedTeam/FireRedAudio)，2026-08 | 約 9B 的音訊 LLM，官方 CUDA / flash-attention 路徑；不是 FireRedASR2 AED 的相同架構新版。 |
| [Cohere Transcribe 03-2026](https://huggingface.co/CohereLabs/cohere-transcribe-03-2026) | **sherpa 1.13.2 已有 adapter**，不能說完全不支援。但 INT8 約 1.7 GB，官方限制包含必須指定語言、混語可能不一致；可列下一階段純英文比較，不作目前混語預設。 |
| [Qwen3-ASR 1.7B](https://github.com/QwenLM/Qwen3-ASR) | ASR 發布為 2026-01-29；6 月 Transformers 整合不是新一代 ASR。1.7B 權重／ONNX 轉換值得高 RAM 桌面或自架比較，但未核得可直接替換目前 archive 的同一完整部署，不捏造下載連結。 |
| [Breeze-ASR-26](https://huggingface.co/MediaTek-Research/Breeze-ASR-26) | 主要為台語語音轉華語文字；card 的 30 段自建台語測試／合成訓練，不等於通用華語或英文成績。 |
| [Dolphin](https://github.com/DataoceanAI/Dolphin/blob/main/languages.md) | 40 種東亞／東方語言覆蓋不含英文，不能因多語數量多就當中英升級。 |

這些是**未整合的本機／自架研究候選**，不是已提供的雲端選項。沒有建立付費服務，也沒有送使用者音訊去測。

## taigi 參考範圍

唯讀查看 [oskuhsiu/taigi](https://github.com/oskuhsiu/taigi) 的 `06943d08e3084d723ee73307db285e71644152e5`，參考 `ModelCatalog.swift` / `ModelProfile.swift` / `TaiwaneseModelProfiles.swift` 的 X-ASR、SenseVoice、Breeze 26、Qwen 等部署。該專案的 Swift / sherpa 1.13.8 / whisper 1.8.7，並非本 app 的相同 binary。

X-ASR 的研究授權／串流與特定領域版本、台語輸入法需求，不能直接推成通用華語／英文推薦。已知 taigi 使用經驗也不能反過來當這次語料的公平排名。本次沒有修改 taigi。

## 證據強度

- **公開報告：** Breeze 25 官方在同一組測試中列出 ASCEND、台灣華語與 code-switch 結果，可以支持其任務定位；FireRed / Fun / Qwen 各自也有官方 benchmark。不同語料、CER/WER 正規化、解碼、量化、VAD 與硬體的數字**不能橫排成一張總榜**。這裡沒有宣稱重現其分數。
- **本次測量：** 同一公開中文 5.592 秒、英文 7.152 秒素材，以及一段 [ML2021_ASR_ST](https://huggingface.co/datasets/ky552/ML2021_ASR_ST) test row 1279 的真人混語 1.59 秒。後者標註為「放進你的 training 裡面」。原 WAV 是雙聲道，相同取平均成 16 kHz mono 後才給每個模型，不是自己拼接的假混語。
- **仍不是準確率評測：** 三段 loading / inference smoke 只能證明部署能跑與暴露個案錯誤，沒有代表性 CER/WER、置信區間或自然長音訊涵蓋。首次 Breeze 中文使用 zh 提示，其餘部分為 auto；CPU 有並行工作，耗時也不能當公平速度榜。詳細輸出在 `asr-model-smoke-2026-10-22.json`。
- **下一步公平比較：** 在使用者裝置用一份固定、獲授權、人工對稿的語料測全部候選；分純中文、純英文、句內混語、1–3 秒、噪音、長尾與專有名詞。固定語言提示、數字／繁簡／標點正規化，分別報中文 CER、英文 WER、混語 MER，以及缺尾／幻覺、峰值 RAM、耗時與失敗率。先保留 SenseVoice，通過才考慮換預設。

## 驗證與重跑

- 主機 Kotlin 2.0.21 / JUnit：74 個相關測試通過，含原有 PCM fallback、真 FFmpeg 範圍／EOF、短尾、合併、字幕／格式化，以及新增 catalog / config / SHA / 內層切片。
- 獨立 fake-native harness 編譯**實際 SherpaOnnxEngine**，9 個案例通過：第二內段失敗／取消、35 秒切 15/15/9、SenseVoice 單次、Qwen 每段語言提示、1 sample 尾、精確邊界、不一致字形續傳接縫、FireRed EOF 語言保留。另以實際 WhisperCppEngine 通過 6 個案例：部分預覽後失敗／首段失敗回復文字、成功預覽與 checkpoint、繁簡續傳接縫、後段失敗回復最新 checkpoint、自動語言完成轉繁體。PCM/native/Android/OpenCC 依賴是替身，不是手機推論測試。
- 重新執行既有 `scripts/check-short-audio.py`：公開錄音 2 秒／5.592 秒／22.368 秒／67.104 秒、低報 metadata、英文與真靜音均通過解碼／邊界檢查；保留了可見辨識錯誤，不把「有字」當全對。
- 實際下載並核對四個新模型 checksum，使用 pinned sherpa Python native binding 或 exact submodule 的 C++ CPU runtime 執行，沒有用模型名稱假裝已測。
- **沒有 Android SDK／連線手機，沒有產出或驗證 APK。** Gradle wrapper 在此環境無法透過 Java 下載 distribution（`Network is unreachable`）；host Kotlin 編譯使用最小 Android stubs，不能取代 Compose / Android / JNI 打包與裝置測試。

正常 Android 開發環境應執行：

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

本機短音訊 smoke，**不自動下載或上傳**：

```sh
python -m pip install sherpa-onnx==1.13.2 numpy soundfile
python scripts/check-model-catalog.py --kind fun-asr-nano \
  --model /path/to/sherpa-onnx-funasr-nano-int8-2025-12-30 \
  --fixture /path/to/zh.wav --fixture /path/to/en.wav \
  --fixture /path/to/codeswitch.wav --output /tmp/funasr-smoke.json
# --kind 亦支援 sensevoice / fire-red-asr2 / qwen3-asr；--language zh 可測強制中文。
```

測 Whisper / Breeze 時，以 exact submodule 建置 host helper：

```sh
git submodule update --init app/src/main/cpp/whisper.cpp
cmake -S app/src/main/cpp/whisper.cpp -B /tmp/asr-whisper \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF
cmake --build /tmp/asr-whisper -j2
c++ -std=c++17 scripts/whisper-model-smoke.cpp \
  -Iapp/src/main/cpp/whisper.cpp/include -Iapp/src/main/cpp/whisper.cpp/ggml/include \
  -L/tmp/asr-whisper/src -lwhisper -Wl,-rpath,/tmp/asr-whisper/src \
  -o /tmp/whisper-model-smoke
python scripts/check-model-catalog.py --kind whisper --model /path/to/model.bin \
  --whisper-runner /tmp/whisper-model-smoke --fixture /path/to/codeswitch.wav \
  --output /tmp/whisper-smoke.json
```

報告含音訊轉錄文字；私人素材的報告也應留在本機。手機驗收尤其要測模型下載中斷、低儲存空間、重新開 app 的續傳、切換模型、背景／熄屏、1–3 秒片段與自然長篇混語。模型來源／授權連結可從設定頁直接開啟。
