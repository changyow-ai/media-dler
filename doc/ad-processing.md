# 下載時廣告處理實作指南

本文件描述目前正式執行路徑，供修改、除錯與驗收使用。廣告判定與媒體完整性是兩個不同問題：判定通過不代表成品一定可播放；成品驗證成功也不代表內容完全沒有廣告。

以下驗證狀態整理至 2026-10-22；程式規則以連結的正式來源為準。

## 設定、任務快照與入口

設定在 [Settings.kt](../core/src/main/kotlin/com/changyow/mediadler/core/model/Settings.kt)，畫面在 [SettingsScreen.kt](../app/src/main/java/com/changyow/mediadler/ui/settings/SettingsScreen.kt)。預設開啟「下載時去 AD」、關閉「使用權重判斷去廣告」。總開關關閉時，權重開關停用；畫面的「清除有可靠標記的廣告（必選）」不是另一個可獨立設定的判定器。

| 總開關 | 權重開關 | 任務 `AdRemovalPolicy` | 行為 |
| --- | --- | --- | --- |
| 關 | 任意 | `OFF` | 保留原內容，不執行本文件的廣告分析或已知廣告清除 |
| 開 | 關 | `CONFIRMED_ONLY` | 快速模式，只採用完整內容命中的已知規則 |
| 開 | 開 | `WEIGHTED` | 加權候選分析；8maple 同時使用已知內容目錄 |

政策在加入佇列時寫入 [QueuedDownload.kt](../core/src/main/kotlin/com/changyow/mediadler/core/model/QueuedDownload.kt)，執行時由 claim 傳給下載請求；後續修改全域設定不會改寫既有任務。下載限速則在每次執行時讀取，適用候選抓取與正式下載，不納入媒體續傳身分。

主要調度在 [YtDlpDownloader.kt](../app/src/main/java/com/changyow/mediadler/data/ytdlp/YtDlpDownloader.kt)，原生資源部署與 Python 呼叫在 [EngineInitializer.kt](../app/src/main/java/com/changyow/mediadler/data/ytdlp/EngineInitializer.kt)。

```text
任務政策快照 + 特殊來源 HLS 快照
  |
  +-- OFF / 任務略過分析 ---------> 原始完整清單
  |
  +-- 快速：8maple ---------------> 完整已知指紋核對
  |    快速：Gimy ----------------> 原始清單（沒有快速目錄規則）
  |
  +-- 加權：Gimy / 8maple --------> 候選特徵 + OCR + 分數
                                   8maple 另合併完整已知命中
                                      |
                         固定 ad-plan / 刪除完整候選區間
                                      |
                    native HLS 下載 + 續傳 + 已保留片段快取
                                      |
                 若有略過廣告：成品影音完整性驗證
                                      |
                 8maple MP4：另掃描已知完整 payload
                                      |
                         成品發布 / 成功任務紀錄
```

一般來源 `GENERAL` 不進入特殊來源 HLS 分析。MP4 清除入口也限定 8maple、去 AD 政策非 `OFF`、未略過分析且輸出副檔名為 MP4；不是掃描使用者所有既有影片的功能。

## 支援範圍與保留策略

[SpecialSourceResume.kt](../app/src/main/java/com/changyow/mediadler/data/ytdlp/SpecialSourceResume.kt) 與 [HlsResumeGuard.kt](../core/src/main/kotlin/com/changyow/mediadler/core/download/HlsResumeGuard.kt) 只接受完整、未加密、plain TS 的 VOD media playlist：有 `#EXTM3U`、有效正數 `EXTINF`、`.ts` 參照與結尾 `#EXT-X-ENDLIST`。HTTP(S) 位址不得夾帶帳密或 fragment。

允許有限的版本、目標時長、序號、discontinuity、獨立片段及 `METHOD=NONE` 等標籤。可向下解析一層「恰好一個普通 variant」的 master；多 variant、替代影音／字幕 rendition、巢狀 master 不屬於這條可續傳路徑。

直播、加密、fMP4／`EXT-X-MAP`、byte range、未知擴充標籤等不會被廣告分析器猜測處理。無法建立受支援快照時，回到一般下載，特殊來源每次採新的工作目錄；不宣稱可延用此 HLS checkpoint，也不會因不支援而刪除片段。8maple 最後仍可能進入獨立的 MP4 已知內容清除。

「未知保留」須按層次理解：來源或候選解碼 gate 無效、快速指紋不相符、超出預算且未分析的候選，均不據此刪除。加權模式的單項線索缺失是該項零分，不重新放大其他項，亦沒有一律否決機制；其餘有效證據仍可能讓候選達到門檻。OCR 錯誤會記入 coverage，不能把整份報告說成完整分析成功。

## 已知內容目錄與候選邊界

[hls_ad_filter.py](../app/src/main/assets/hls_ad_filter.py) 用 discontinuity 將清單切成完整 region；清單頭尾也可形成邊界。`# ppvod-ad-injected v1` 只記為 `marker_hint`。標記、網址外觀、片段時長與解析度都不是刪除授權。

快速模式使用 [hls_ad_fingerprints.json](../app/src/main/assets/hls_ad_fingerprints.json)，政策為 `eightmaple-content-v1`。候選必須完整下載並 remux，且恰有一軌 H.264、一軌 AAC；工具警告亦視為無法確認。指紋同時核對：

- codec、profile、level／尺寸／像素格式或取樣率／聲道等完整 metadata。
- 影音 extradata hash（包含解碼設定）。
- 每軌完整有序 packet payload hash 序列及 packet 數量。

只有整個指紋物件相等才命中，並略過該完整 region。相同畫面但重新編碼、內容前後增減或不同編碼設定可能不命中。快速掃描上限為每 region 60 秒、128 個片段、32 MiB；最多處理 16 個合格 region，總快取預算 128 MiB。若結果會刪掉所有媒體，清空略過集合並保留原內容。

HLS 內容指紋與後述 MP4 byte payload 目錄是兩種不同表示，不能互換；新增素材需依各自格式與完整性規則建立證據。

## 加權候選、分數與選取

[weighted_hls_ads.py](../app/src/main/assets/weighted_hls_ads.py) 的正式政策為 `weighted-hls-v3`。它列舉每個非空 region 起點開始、所有相鄰且總長不超過 60 秒的完整 region 組合，沒有先依語意或標記挑選。遇空 region 或超時即停止擴張該起點；因此多個短 region 組成的一段廣告也可成為候選。

每候選最多 1,024 個片段、256 MiB；最多分析 4,096 個候選，快取總預算 2 GiB。完整候選母體數量與描述 hash 仍納入 checkpoint 身分，超額候選列為未知，不能將「列舉完成」當成「解碼／OCR 證據完整」。

特徵工具為 [probe-hls-ad-features.py](../scripts/probe-hls-ad-features.py)，計分工具為 [score-hls-ad-features.py](../scripts/score-hls-ad-features.py)；建置時以穩定模組名稱封裝進 Android。工具 CLI 本身是本機特徵／評分介面，報告 action 為 preserve；真正略過行為由正式 classifier 選取並固定計畫。

三類概念可稱為 A／B／C，但維護時以程式鍵值為準，避免將音量的 `A` 誤當成場景：

| 概念 | 程式鍵值 | 最高分與含義 |
| --- | --- | --- |
| A：場景 | `S` | 40；刪除後前後畫面接近程度 20、原切點反差 5、相鄰幀動態基準與接合相容性 15 |
| B：音量 | `A` | 25；候選 RMS 相較前／後有效 context 的增益，12 dB 達滿分；無有效音訊為零 |
| C：內容 | `C`，內含 `P` | `min(60, P + 50 × L)`；`P` 上限 25，域名占 0.65、促銷語意占 0.35；`L` 是大字與中央版面證據 |

場景比對使用候選前後接縫與移除後接縫，不把普通節目轉場直接當廣告。音量只取有效鄰側平均，數值低於有效音訊門檻不提供正證據。特徵摘要使用 `weighted_decision`，保留計分所需量測，避免計算不參與決策的完整診斷特徵。

OCR 先做 Unicode 正規化，促銷／節目詞彙採有界的模糊比對。相同域名若也出現在前後 context，會降低其新穎性。文字信心值以平方根轉成權重；框座標必須有限、尺寸為正並裁到單位影像範圍。

`L` 取每幀文字高度（相對高度 0.12 飽和）、中央區 `[0.2,0.25,0.8,0.75]` 重疊及信心的最大貢獻；不是只要有字幕便加滿分。商業文字框採 max-weight union 積分，重疊／重複框不會累加面積。貼近上下各 20% 邊緣、又缺乏大字中央版面證據的商業 overlay 會扣分；演員、配音、字幕製作等節目文字另提供最高 40 分負證據。

```text
score = clamp_0_100(S + A + C - edge_overlay - programme_negative)
候選來源 gate 有效 AND score >= 55  -> 通過候選
```

**55 分包含等於 55，沒有四捨五入後再判定。** 分數是尚未校準的證據分數，不能解讀為廣告機率或準確率。缺失項目不重新正規化，黑畫面／單色等診斷量也不是獨立刪除規則。

8maple 的完整已知命中先占用片段；加權通過者按分數由高至低、時長由短至長、region indices 排序，只選與既選集合不重疊者。較低排名的重疊候選保留並記錄原因；同一 TS index 不會被計算或刪除兩次。所有群組必須恰好分割固定略過集合；若合併結果將刪光內容，整體退回保留。

## OCR profile、重用與保守省略

正式 runtime 使用 `front-two-960-v1`：候選取有效影片視窗的 `[0.05, 0.1]`，前後 context 各取 `[0.5]`；context 取鄰側約 2 秒，圖片最大寬度 960 px。位置會對應到已解碼的最近原生幀時間，不以估算影格率捏造抽樣時刻。候選兩張可批次擷取 PNG，批次失敗回到單張擷取。

評分器仍以 **3 個預期 distinct times** 作分母；兩張 profile 沒有改成除以 2。同一實際時刻合併為同組，所以兩個 fraction 撞到同一幀不會假裝有兩次時間證據。OCR bridge 另以 PNG 內容 hash 重用結果及失敗，LRU 各最多 512 筆；前後 context 最多保留 64 筆、region remux 檔案靜止時最多 8 筆，減少重抓、重封裝與重做 OCR。

OCR 引擎為 Android ML Kit 中文 `16.0.1`，介面在 [AndroidAdOcr.kt](../app/src/main/java/com/changyow/mediadler/data/ytdlp/AndroidAdOcr.kt)。Python 發出 token，Kotlin 寫回經驗證的文字、信心與左上原點框；請求圖片／結果在完成或失敗後刪除，bridge 等待上限 30 秒。

`candidate_ocr_bound()` 可在候選 OCR 前證明「無法達門檻」：兩個 frame 最多使 `P <= 25×2/3`、layout `<=50×2/3`，故剩餘內容上界 `C <= 50`。若已得 `S+A < 5`，即使內容取上界、所有負證據為零仍不足 55，便可保守保留並省略候選 OCR。實作另加浮點安全裕量，只有上界嚴格小於 55 才省略。

此捷徑要求已知評分政策、來源 gate 有效、有效候選且既有 errors 為空；context OCR 仍照常執行。`S+A == 5` 或接近門檻時不得省略。報告以 `skipped_below_threshold`、`score_is_exact=false` 與上界說明；不能把未執行 OCR 當成完整原始 OCR 證據或精確分數。

## 快取、分析 checkpoint 與固定計畫

三層狀態都在任務私有 `.resume/` 下，但用途不同：

| 狀態 | 用途與信任邊界 |
| --- | --- |
| `ad-cache/` | 完整 TS 抓取快取；綁來源 snapshot，逐檔核對 URL 對應路徑、size、SHA-256；可丟棄，不能決定略過集合 |
| `analysis-checkpoint/` | 已完成候選的原始特徵、OCR 評估、分數、選取前 row 與累計時間；只恢復驗證通過的連續前綴 |
| `ad-plan.json` | 已固定的最終清單、群組及略過集合；一旦存在即優先使用，不重新分析、OCR 或改判 |

[ad_analysis_checkpoint.py](../app/src/main/assets/ad_analysis_checkpoint.py) 將來源 snapshot、原清單 hash、URL／headers hash、runtime policy hash、門檻及完整候選母體 hash 綁成身分。每筆記錄有候選 hash、前筆 hash、payload hash 與 record hash；完整候選分析完成且原子提交後才可重用。中斷中的半份特徵不算完成。

恢復時只接受相符候選及 gate／score／decision 自洽的連續記錄；缺漏或損壞處之後重做，舊檔以 rejected 名稱保留供排查。原始證據儲存上限每筆 16 MiB、總計 256 MiB；安全路徑或儲存錯誤會中止而非產生沒有證據的刪除計畫。`weighted-latest.json`、有界 `weighted-features.json` 是診斷輸出，不能當成續傳權威。

[WeightedHlsAds.kt](../app/src/main/java/com/changyow/mediadler/data/ytdlp/WeightedHlsAds.kt) 將 Python 模組、指紋目錄、OCR 引擎／adapter 與已知目錄開關綁入政策。程式或目錄改動會改變 policy；[AdWorkPolicy.kt](../app/src/main/java/com/changyow/mediadler/data/ytdlp/AdWorkPolicy.kt) 阻止同一舊任務混用不同廣告執行政策，保留暫存並要求重建任務。

來源快照則綁定實際 child URL、原始清單 bytes 與正規化 headers；來源、格式或政策身分改變會選到不同工作子目錄。來源變更會提示重新下載，舊暫存不套用到新內容；短暫抓清單失敗不冒充分類器版本變更。

## 「略過分析」與取消競態

[DownloadQueue.kt](../app/src/main/java/com/changyow/mediadler/download/DownloadQueue.kt) 只在該任務仍處於下載中的 `AD_ANALYSIS` 階段接受略過。先持久化 `skipAdAnalysis=true` 與 interruption，再由 [DownloadRunner.kt](../app/src/main/java/com/changyow/mediadler/download/DownloadRunner.kt) 取消目前 attempt，等待原生程序停止、清理與 acknowledgement 後才接續下一次 attempt。寫入失敗不會宣稱操作成功。

接續 attempt 採 `full-content-no-ad-analysis-v1/` 獨立 namespace，身分也加入此模式；必須使用沒有 ad plan、略過計數全為零的原始清單。它不沿用舊去廣告計畫、部分下載或已清除成品，也跳過 MP4 清除。恢復發布紀錄與發布前均檢查成品確實來自此 namespace，防止取消前後競態把舊去廣告成品當成完整內容。

原生下載開始前即送出 `DOWNLOADING` 進度以關閉略過視窗，不依賴來源是否及時送出傳輸進度。略過意圖隨任務保存，程序重啟後仍有效。

## TS 過濾、正式下載與防重複

過濾只移除已選 TS 的 `EXTINF` 與 URI 原行，保留其餘清單內容；plan validator 重新對照原始完整 regions、群組、時長、indices 與計畫身分，不允許任意半區間刪除。

[hls_resume.py](../app/src/main/assets/hls_resume.py) 將固定清單交給 bundled yt-dlp native HLS，固定單片段併發、缺片立即失敗，並核對版本及私有 API 形狀。分析時已下載且最終保留的 TS 可由 cache 複製成 downloader 的一次性 fragment；只有 URL、index、size 與 hash 均相符才使用，缺失或損壞則正常重抓，不改動固定略過集合。

cache 命中也必須恰好推進一次 yt-dlp 內部 fragment progress closure，避免 cache／網路交替時序號錯位。每次 append 核對預期 index 與 offset，寫入、flush、fsync 後才原子提交 checkpoint。恢復保守回退上一個 fragment 起點，截去未確認尾端並重抓該片段，防止重複 append 或把部分片段當完成。

## 下載中的持續解碼驗證

[HlsResumeFiles.kt](../app/src/main/java/com/changyow/mediadler/data/ytdlp/HlsResumeFiles.kt) 只在 **確實略過至少一段廣告、且下載影片** 時建立 `media_validation` token。未刪除內容不額外套用此成品驗證流程；純音訊不走下載中證明重用。

[hls_media_validation.py](../app/src/main/assets/hls_media_validation.py) 的 `DownloadValidator` 有獨立讀取器及一個持續存活的 FFmpeg child。每次已提交 append 只通知累計 byte offset、fragment 數與保留媒體秒數，通知本身不等待解碼。相對上次餵入達任一條件即處理：**30 個媒體秒 OR 12 個 TS OR 8 MiB**；不是每 30 秒牆鐘時間，也不是每批重開解碼器。

worker 以 64 KiB 區塊依序讀取已提交前綴，只保存最新 watermark；讀到未提交尾端、來源截短或 watermark 倒退都無有效證明。續傳後第一次通知也從 byte 0 重播已保留前綴，因此證明涵蓋完整輸入，並非僅覆蓋本次新下載片段。結束時補送未滿批次的尾端，關閉 stdin，等待解碼與所有輸出 pipe 排空。

收據 `download-validation.json` 包含來源完整 bytes／SHA-256、session identity、每次執行 token、片段數、EOF／完整解碼狀態，以及 validator 與 FFmpeg 執行檔 hash。每次執行先刪除舊收據；失敗／取消不得為後一次提供有效證明，postprocessing 前會 join／停止 worker。

解碼時同時建立三類內容證明：

- H.264 轉 Annex B、保留 SPS／PPS 等 decoder configuration，只移除 AUD，再對完整 encoded video bytes 雜湊。
- 保留來源 TS 的原始 AAC ADTS 標頭，對完整 encoded audio bytes 雜湊；最終 MP4 由 FFmpeg 補回 ADTS 標頭，兩側必須完全相等。來源不先移除 ADTS 再依初始設定重建，避免掩蓋途中取樣率或聲道設定變更。
- 逐 video packet 的 DTS／PTS，以第一個 DTS 為共同原點，使用精確有理數 timebase 正規化並雜湊，同時比對 packet 數；固定時間偏移可等價，PTS 改變不可被內容 hash 掩蓋。

FFmpeg 使用 `-xerror` 完整解碼影音；任何診斷、非零退出、缺少輸出、pipe 未 EOF 或 worker 錯誤都使收據 unavailable。持續解碼保留跨 TS 的 decoder 狀態，避免逐 TS／逐 batch 獨立解碼漏掉接縫或參考幀問題。

## 成品 gate、證明重用與發布

`weighted_hls_ads.validate_media()` 在發布前對整個保留成品執行：

1. ffprobe 確認影片恰有 H.264／AAC；純音訊恰有一條 audio，沒有 video。
2. 成品時長必須與保留 `EXTINF` 總和相差不超過 1 秒。
3. 獨立掃描成品每軌 packet DTS，要求有限且嚴格遞增，各預期軌皆有證據，無工具診斷。
4. 嘗試重用下載中完整解碼證明；不符就對最終成品完整解碼影音。
5. 驗證前後 size／mtime 不可改變；失敗寫出 unknown 結果並阻止發布。

重用要求 session／token／模式／片段數／預期時長與完整收據一致，validator 與 native FFmpeg 未變，且最終成品重新抽取的三類 encoded/timing 指紋完全相等。目前僅影片、AAC LC、1 或 2 聲道可重用；audio-only、轉碼、特殊 profile、內容或時間不符、收據遺失等一律回到成品全解碼，不能放寬比較提高命中率。

重用減少一次串行的完整成品解碼，但仍需讀取最終成品並核對完整內容與時間證據；不是只抽查首尾，也不是免驗證。成品 DTS 與時長始終獨立檢查，因為相同 payload 不足以證明 container 時序正確。全解碼的音訊 null 輸出以 `asetpts=N/SR/TB` 正規化，原始 DTS 合法性仍由前述獨立掃描把關。

之後若為 8maple MP4，再執行下一節清除及其保留 packet 驗證。所有必要 gate 完成後才呼叫 [DownloadPublisher.kt](../app/src/main/java/com/changyow/mediadler/data/storage/DownloadPublisher.kt)，建立可恢復發布紀錄、複製與確認成品，最後完成 MediaStore pending 狀態或 SAF 最終命名。未驗證成品不會先發布為成功下載；SAF 暫存項目的顯示仍取決於 provider。

## 已有 MP4 路徑的已知廣告清除

[ad_cleanup.py](../app/src/main/assets/ad_cleanup.py) 使用 [known_ads.json](../app/src/main/assets/known_ads.json) 的完整 byte payload 長度與 SHA-256；64-byte anchor 只加速尋找，必須核對整個 payload 才算命中。無命中回傳 unchanged，不代表無廣告。

有命中時要求 MP4、恰好 H.264／AAC、嚴格遞增 DTS；刪除邊界不得切開 packet，兩端必須是唯一 video keyframe，影音時間區間必須有序。以 packet position 過濾、同步調整 PTS／DTS，使用 `-c copy` 與 faststart 產生另一份成品，不重新編碼也不覆寫來源。

輸出核對每個保留 packet 的 hash、數量與順序、預期位移後的 PTS／DTS、嚴格 DTS、容器時長（誤差不超過 0.1 秒）及影音起訖相容性。只在全部通過後發布清除成品及結果；工具失敗、warning、來源改變等會中止且保留原下載，不能把失敗當 unchanged 並偷偷發布。

[AdCleanupFiles.kt](../app/src/main/java/com/changyow/mediadler/data/ytdlp/AdCleanupFiles.kt) 管理 attempt 私有清除副本：未納入發布紀錄的副本在取消／失敗後移除，已由發布紀錄接管者留給恢復。這條路徑只處理當次下載產物，不會回頭改寫先前下載的影片。

## 驗證、效能與現有限制

目前整合驗證紀錄為 **145 個 Python 測試、426 個 JVM 測試通過**，正式 ARM64 與隔離 QA／instrumentation APK 均已建置。新增 native 持續驗證案例因 OPPO 測試機未連線而為 **`SETUP_BLOCKED`**，沒有新案例的裝置執行證據，不應將 host FFmpeg 或 JVM 結果說成 Android native／實機通過。完整 lint 仍有既有的 3 項錯誤：分享模組兩項 `NewApi`，音訊封裝模組一項 `WrongConstant`。

在專案根目錄使用既有工具鏈；Python 媒體測試需要 PATH 有 FFmpeg／ffprobe，JVM 依專案 Gradle wrapper 執行：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s app/src/test/python -p 'test_*.py'
./gradlew :core:test :app:testDebugUnitTest
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/check_hls_ad_safety.py --report /tmp/media-dler-ad-safety.json
```

要執行 bundled yt-dlp 的全部案例，另將 `HLS_RESUME_YTDLP` 指向受支援的引擎 archive；未設定時，相關案例會明確略過。

關鍵回歸涵蓋 [Python tests](../app/src/test/python/)、[WeightedHlsAdsTest](../app/src/test/java/com/changyow/mediadler/data/ytdlp/WeightedHlsAdsTest.kt)、[AdWorkPolicyTest](../app/src/test/java/com/changyow/mediadler/data/ytdlp/AdWorkPolicyTest.kt)、[DownloadRunnerTest](../app/src/test/java/com/changyow/mediadler/download/DownloadRunnerTest.kt) 與 [DownloadQueueTest](../app/src/test/java/com/changyow/mediadler/download/DownloadQueueTest.kt)。持續解碼測試包括真實 FFmpeg 的內容、SPS 設定、途中 AAC 設定與 PTS 改變、完整覆蓋、批次、取消與 fallback。

[IncrementalHlsValidationDeviceTest.kt](../app/src/androidTest/java/com/changyow/mediadler/data/ytdlp/IncrementalHlsValidationDeviceTest.kt) 需要隔離 QA target、明確 opt-in、已固定的本機 loopback fixtures 與測試參數；一般 connected test 執行可能跳過它。它的範圍是保留清單的 native 下載及驗證 gate，不能替代真實廣告分類或實際發布的驗收。

安全基線另見 [ad-safety-v1.json](../scripts/fixtures/ad-safety-v1.json)、[check_hls_ad_safety.py](../scripts/check_hls_ad_safety.py) 與 [test_hls_ad_safety.py](../scripts/test_hls_ad_safety.py)。嚴格 runner 目前會以 **exit 1** 報告兩個已知的 synthetic false positives：正常內容的 A→B→A 場景伴隨音量提高與商業 overlay，以及硬切後的大型標題卡。這些是目前未解的演算法安全限制，不能把單元測試整體綠燈寫成 safety PASS。

安全單元測試共 10 項，其中上述兩項明確標示 expected failures，保持問題可見；它們不是已修復。合成案例直接提供特徵值，使用三個候選 OCR 時刻測 scorer 與 selection，未重新執行正式兩張 OCR profile、媒體解碼或實機。封存資料僅用於核對既有定義與觀察來源，不把舊分數變成新一次辨識成果，也不補造獨立真值；真實媒體 replay、成品與 Android 驗收仍有缺口。

效能可由 plan `cost`／`timing`、cache／context／PNG reuse、OCR 請求數與驗證 `decode_method` 觀察；累計分析時間包含 checkpoint 已保存工作，current-attempt 時間另列。快取與 checkpoint 減少重做，候選 OCR 上界減少不可能達標的請求，持續解碼將部分工作與網路重疊；候選密度、解碼速度、儲存與限速仍會影響耗時，沒有固定速度保證。

快速模式只能識別目錄中的完整編碼內容；加權模式只涵蓋可列舉的完整短區間，沒有任意逐幀廣告定位。前段兩張 OCR 可能漏掉後段才出現的訊息，60 秒限制與各項預算也可能保留廣告。現有分數與有限測試沒有提供代表性資料集上的 precision／recall 或百分比準確率；調整 profile、權重、詞彙或門檻時應同步更新政策身分與對應測試，重新驗收誤刪與漏刪。
