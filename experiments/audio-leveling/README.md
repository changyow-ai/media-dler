# Audio-leveling experiment

## 暫緩／封存摘要（2026-10-19）

使用者目前用不到此功能，要求記錄執行過的嘗試並 commit，之後可能封存。
本次保留實驗分支與程式，停止後續嘗試；沒有可接受的方案，也沒有接進 App。
aye 任務 `t-9de06f0ad4f749d8908c` 暫緩，日後明確需要時再恢復；不標成完成。

| 已執行的嘗試 | 結果與淘汰原因 |
| --- | --- |
| 峰值限制（−6 dBFS） | 能限制峰值，但廣告與前段響度差幾乎不變。 |
| 快速 RMS 壓縮（−28 dBFS，2:1／4:1） | 廣告有變小，一般內容也降太多。E11 第一處的前段中位數分別降約 3.6／5.2 LU。 |
| 慢速 RMS 音量調節（先 −28、再 −25 dBFS） | 較溫和版廣告約降 6.7～7.0 LU，但打鬥也降約 4.9 LU；使用者不接受。 |
| 高響度門檻、最多降 3 dB（−18／−19 LUFS） | 打鬥影響大幅縮小，但廣告整段只降約 0.6／1.3 LU，改善不足。 |
| 相對音量升高、維持、回落的區塊偵測 | 整檔判讀後，E11 兩段廣告都漏掉，卻把 90 秒打鬥全選為需降 6 dB 的區塊；E12 另一處連帶包入約 150 秒前後內容。淘汰，未轉出音檔。 |

前三類與門檻法有實際轉出短音檔並量測；慢速調節另有一支整集成品。
最後一類只驗證偵測範圍與預計增益，沒有實際套用到音訊。
廣告位置只用於事後驗證，不輸入處理程式。樣本只有同一支廣告出現四次，
不能解讀成四種廣告的通用效果；手機效能、耗電、Kodi→VLC 實播與主觀音質未驗證。

為避免封存後只剩暫存目錄連結，關鍵數據已一併納入 Git：

- [量測摘要](evidence/measurements-summary.json)：各方法設定、代表案例、響度差與一般內容影響。
- [整集媒體驗證](evidence/native-media-validation.json)：視訊內容與時間戳一致、音訊長度差及驗證限制。
- [區塊偵測對照](evidence/step-blocks-evaluation.json)：漏失、誤包範圍與來源雜湊。

原片、試聽檔、逐幀 CSV 與完整日誌仍是未納入 Git 的本機產物，封存分支不會保存它們。
程式與下方重現指令已保留，日後若再研究，應先補足能區分廣告與一般內容的證據，
不要把這批失敗規則當成已完成的功能。

This branch tests reducing sudden loudness without recognizing advertisements.
It does not change the Android app or its download/publication pipeline.

**Current verdict:** all three tested approaches remain rejected. The first
round changed ordinary content too much. The second reduced that influence but
lost most ad attenuation. The third, a frozen relative-rise/return detector,
missed both E11 ads while proposing to lower the entire fight sample by 6 dB.
No approach has been accepted for integration.

`lab.py` processes the entire selected audio stream using audio levels only. It
never reads ad markers, fingerprints, captions, or evaluation labels. `score.py`
uses the separately recorded known-ad intervals **only after processing**, to
measure whether the proposed treatment actually helps.

## Reproduce

Requires Python 3 and host FFmpeg/ffprobe (tested with FFmpeg 7.1). No Python
packages are needed. Run from the repository root. Outputs must be new paths;
source files are never overwritten. Generated media/logs stay under ignored
`build/audio-leveling/`, outside Git.

```sh
python3 experiments/audio-leveling/lab.py scan /path/to/original.mp4 build/audio-leveling/scan
python3 experiments/audio-leveling/lab.py compare /path/to/original.mp4 build/audio-leveling/sample --start 280 --duration 70 --threshold-db -25 --profiles original leveler
python3 experiments/audio-leveling/lab.py apply /path/to/original.mp4 build/audio-leveling/leveled.mp4 --profile leveler --threshold-db -25
```

Open the generated sample's `index.html` to compare audio at the same playback
volume. Play one version, pause, click **同步位置**, then play the other. This
page is a listening aid, not evidence that a person has approved the sound.

For the retained local samples, `evaluation-windows.json` records the selection
offsets and label provenance. Generate the six named sample directories using
those offsets, then run:

```sh
python3 experiments/audio-leveling/score.py build/audio-leveling --suffix=-soft --output build/audio-leveling/evaluation-soft.json
```

The generic comparison also supports `original peak rms2 rms4 leveler` profiles.
All comparison files, including `original`, use the same AAC 160 kbit/s encoding
so the untreated control includes the same encoding step. `apply` copies the
first video stream and processes the first audio stream. It is deliberately not
a production solution for multiple audio/subtitle tracks, cancellation or task
recovery.

## Third round: paired rise/return blocks, 2026-10-19

`step_blocks.py` tests relative acoustic steps on complete recordings, before
reading evaluation labels. This is a proposal detector, not an audio processor.
It has no expected-ad-duration range, known fingerprints or selected-clip input.
One rule was frozen before scoring; it was not adjusted to repair labeled misses.

- Entry: at least 6 LU abrupt rise and 6 LU rise sustained for two seconds,
  relative to the preceding 10-to-1-second active median. Prior M400ms must be
  above -45 LUFS for at least 60% of that reference window.
- Return: the following two-second median falls at least 5 LU, and 80% of the
  following three seconds are at or below the frozen baseline + 3 LU.
- Plateau: 80% of the intervening one-second medians exceed baseline + 4 LU.
- Overlapping confirmed proposals are merged. Proposed constant reduction is
  `block_median − (baseline + 3 LU)`, limited to 0–6 dB. Parameters and all
  rejected/accepted proposals are saved in the output.

```sh
python3 experiments/audio-leveling/step_blocks.py build/audio-leveling/e11-scan/source.csv build/audio-leveling/step-blocks-v1/e11.json
python3 experiments/audio-leveling/step_blocks.py build/audio-leveling/e12-scan/source.csv build/audio-leveling/step-blocks-v1/e12.json
python3 experiments/audio-leveling/step_blocks.py build/audio-leveling/ren-scan/source.csv build/audio-leveling/step-blocks-v1/ren.json
```

Actual frozen whole-recording proposal results:

| Source | Onset proposals | Merged blocks | Proposed attenuation time |
| --- | ---: | ---: | ---: |
| Moving E11 | 64 | 21 | 1,015.5 s / 32.9% |
| Moving E12 | 66 | 21 | 1,454.5 s / 44.8% |
| Ren-Lai-Bao E02 | 14 | 7 | 42.5 s / 9.8% |

Posthoc matching against the existing labels rejects the hypothesis:

- E11: neither known ad overlaps a confirmed block (0% coverage for each).
  The verified 90-second fight sample is entirely inside a proposed
  1,291.409–1,605.409 s block at 6 dB reduction.
- E12 first ad: 95.2% coverage at proposed 6 dB, with 0.50 s pre-roll spill and
  1.24 s tail missed.
- E12 second ad: complete coverage, but its 1,901.909–2,077.918 s block would
  also lower 107.85 s before and 42.39 s after the ad, at proposed 6 dB.
- The Ren control window is untouched; other unmatched candidates there are
  not automatically classified as ads or false positives.

The stopping rule was met before rendering: missed ads, excessive spill and
substantial influence on the verified fight. **No audio was rendered with this
detector**, so these dB values are proposed gains, not measured output changes.
No threshold tuning, phone work or production integration followed. This
particular paired-step rule fails to separate ads from ordinary scene changes.

`build/audio-leveling/step-blocks-v1/evaluation.json` records posthoc overlaps,
misses, spill, control-window effects, unmatched candidates and SHA256
provenance. The initial coarse onset preview reported 63 E11 candidates with
rounded timestamps; the frozen implementation retains full-precision times
for the five-second deduplication and reports 64. This numerical difference
does not repair either missed E11 ad or the excessive fight overlap.

## Second round: smaller influence, 2026-10-19

`selective.py` applies a strict loudness deadband with a maximum 3 dB reduction.
Below the threshold its requested gain is exactly unity. It uses only the
source's trailing 400 ms EBU momentary loudness, centered by 200 ms offline,
with finite 100 ms attack / 300 ms release slopes and continuous per-sample
interpolation. The analysis window and envelope still affect adjacent audio;
the script records both requested and applied gain curves.

```sh
python3 experiments/audio-leveling/selective.py /path/to/original.mp4 build/audio-leveling/e11-first-selective --start 280 --duration 70
python3 experiments/audio-leveling/score.py build/audio-leveling --suffix=-selective --output build/audio-leveling/evaluation-selective.json
```

Generate the other five labeled sample directories using the selection offsets
in `evaluation-windows.json` before running the full score command. The default
comparison renders -18 and -19 LUFS; these are experimental thresholds, not
defaults for an app. A lossless float PCM intermediate ensures both the control
and treated files are AAC-encoded only once. The raw intermediate/gain files
are deleted after successful rendering; their measurement/control CSVs remain.

| Actual rendered effect | Rejected first round | -18 LUFS / cap 3 dB | -19 LUFS / cap 3 dB |
| --- | ---: | ---: | ---: |
| Known ad, median matched S3s attenuation | 6.7–7.0 LU | 0.6 LU | 1.3 LU |
| Fight scene, median matched S3s attenuation | 4.9 LU | 0.0 LU | 0.1 LU |
| Fight scene, M400ms attenuation >0.5 LU | 100% | 2.4% | 10.9% |
| Fight scene, M400ms p95 attenuation | 6.8 LU | 0.2 LU | 1.0 LU |
| Fight scene, M400ms maximum attenuation | 7.9 LU | 1.5 LU | 2.5 LU |

The first-second momentary ad maxima fall by about 3 LU in the new variants.
Most ordinary before/after regions have zero median S3s attenuation, but that
does not mean unchanged audio everywhere: E11's second following region still
has 1.6/2.1 seconds with >0.5 LU momentary attenuation (-18/-19). The separate
show's selected clip has 1.3/2.3 such seconds. The cap bounds the generated gain
signal; AAC encoding and loudness-window alignment can produce a measured LU
change just above 3 (observed 3.1), so the control bound is not a sample-exact
claim about the re-encoded waveform.

The updated scorer retains the original regional masks and separately reports
the first/last three seconds around both ad boundaries. It adds M400ms/S3s
traces, p95/max changes, affected seconds/fractions and longest affected runs.
M metrics additionally exclude source M <= -60 LUFS; all gates depend only on
the original. Boundary windows can mix content because they are trailing
measurements. Fractions use the stated eligible denominator, not total runtime.

This answers the curve question: ordinary-content influence can be made much
smaller, but these settings barely reduce the ad's sustained loudness compared
with the first round. The selected ad and fight scene have overlapping
loudness distributions. These measurements do not support claiming a solution
that preserves ordinary content while keeping the previous ~7 LU ad reduction.
No further full-episode rendering, phone benchmark or production integration
was performed for this round.

The local `build/audio-leveling/selective-index.html` overview plots the actual
fight-scene attenuation curves together and links all six listening comparisons.
`evaluation-selective.json` contains the new output measurements; retained
`build/audio-leveling/evaluation-impact-soft.json` contains the expanded first-round
comparison. These generated artifacts are not committed.

## First-round measurements, 2026-10-19

The first-round candidate (rejected for excessive ordinary-content influence)
was **slow, downward-only frame RMS leveling**, using FFmpeg's existing filter:

```text
dynaudnorm=f=250:g=5:m=1:r=0.056234133:n=1:c=0
```

This targets -25 dBFS frame RMS with 250 ms frames and five-frame smoothing,
couples the stereo gain, and caps gain at unity. It does not amplify quiet
sections. `--threshold-db` supplies the frame RMS **target** for `leveler`, and
the compressor **threshold** for `rms2`/`rms4`; these are different operations.
The -25 value is an experimental calibration, not an established default for
other shows. The original first window measured -30.5 dBFS mean power before
the ad and -21.0 dBFS within it.

Short-term LUFS median gaps between the known ad and its preceding content:

| Evaluation window | Untreated | Peak ceiling -6 dBFS | Slow leveler -25 dBFS |
| --- | ---: | ---: | ---: |
| Moving E11, first insertion | 9.7 LU | 9.7 LU | 4.0 LU |
| Moving E11, second insertion | 5.2 LU | 5.2 LU | 2.0 LU |
| Moving E12, first insertion | 8.2 LU | 8.2 LU | 2.2 LU |
| Moving E12, second insertion | 12.35 LU | 12.4 LU | 5.5 LU |

The leveler reduced matched ad windows by 6.7–7.0 LU, and their first-second
momentary maxima by 11.0–12.0 LU. Preceding content also fell by 0.2–4.0 LU;
following content fell by 0.3–4.8 LU. A separately selected fight scene fell by
4.9 LU; another show's selected content fell by 1.5 LU. These are matched-window
medians, not guarantees about dialogue intelligibility or every sound.

Fast RMS compression at -28 dBFS also helped, but lowered ordinary content more.
Slow leveling at -28 dBFS reduced the gaps further at the cost of more
attenuation; -25 is the gentler candidate. Peak-only treatment barely changed
these loudness gaps. Whole-clip true peaks of the gentler comparisons remained
below 0 dBTP; that check does not establish absence of all audible artifacts.

Scoring skips the first three seconds of each region to avoid mixed short-term
windows. All versions use the same original-only > -50 LUFS eligibility mask.
Nominal 100 ms measurement ticks are matched, with actual timestamp offsets
reported. Onset measurements use trailing 400 ms momentary windows that fit
within the first second of the labeled ad. Video-derived label boundaries
approximate audio transitions.

The labels cover **one confirmed ad repeated four times in two episodes**.
Other selected content is not automatically declared ad-free. The listening
pages and JSON results are local artifacts, not committed source media.

An entire 3,085-second E11 was also processed without labels. On this Mac, audio
encoding plus video remux took 53.5 seconds wall time / 68.2 seconds CPU time;
the subsequent full audio scan took 15.6 seconds. Whole-file true peak changed
from -2.1 to -7.2 dBTP. Host costs do not establish Android throughput, battery
consumption or background-execution behavior.

The whole-file output reproduced the two E11 gap reductions (9.65 → 3.95 LU
and 5.05 → 1.90 LU). All 74,017 encoded video packets and their PTS/DTS/duration
records match the original. Audio duration differs by 23 microseconds, with
44.1 kHz stereo preserved. The file grew from 237,303,408 to 249,155,681 bytes
(about 5.0%) because this experiment re-encodes the original ~128 kbit/s audio
at 160 kbit/s. The full audio stream was decoded for measurement; a separate
full video decode was not repeated for the unchanged video payload.

Local evidence and the listening overview are in
`/Users/apple/Projects/fun-tools/media-dler/build/audio-leveling/`, also linked
from this worktree's ignored `build/audio-leveling/`. Important artifacts:
`index.html`, `evaluation-soft.json`, `native-media-validation.json`, and
`Moving-E0011-leveler-soft.mp4`. This source experiment lives in the isolated
`/private/tmp/media-dler-audio-leveling` worktree so another concurrent experiment
can keep using the original checkout.

Phone filter availability/performance, actual Kodi-to-VLC
playback, more distinct ads/shows, subjective sound quality, automatic target
selection and the default behavior remain undecided. The aye feature is
deferred at the user's request; this branch preserves the rejected experiments.
