# media-dler

從任何 app「**分享**」一個連結或影音檔過來，media-dler 就能：

- **下載**影片 / 圖片 / 音訊 — 以 [yt-dlp](https://github.com/yt-dlp/yt-dlp) 為引擎，涵蓋 YouTube、Instagram、TikTok、Threads、X / Twitter、Facebook、Reddit、Bilibili… 等 1000+ 平台。
- **轉成逐字稿**（語音轉文字）— 裝置端離線或雲端，多語自動偵測、中文自動轉台灣正體。
- **取出純音檔** — 從影片無損抽出原音軌存成音檔。

![build](https://github.com/changyow-ai/media-dler/actions/workflows/build.yml/badge.svg)

- **平台**：Android 10（API 29）以上
- **散布**：sideload / F-Droid 取向，**不上 Google Play**

---

## 功能

### 下載

- **分享即下載**：在任意 app 點「分享 → media-dler」，或對選取文字用「處理文字」、或開啟連結；也可複製連結後在首頁按「貼上連結」。
- **兩種模式**（設定可切）：**一鍵**直接用預設值下載；**彈窗**逐項選畫質 / 音訊 / 圖片再下載。
- **多媒體勾選**：IG 輪播、多圖貼文等含多個項目時可勾選要下載哪些。
- **格式選擇**：最佳畫質、限制解析度（1080p / 720p / 480p / 360p）、音訊轉 **MP3 / M4A**、圖片原檔。
- **儲存位置**：預設公開的 `Download/media-dler/`（相簿 / 檔案 app 可見），或用 SAF 指定任意資料夾。
- **下載歷史**與**前景進度通知**，完成可點開檔案；來源沒縮圖時自動抽一張影格當預覽。
- **引擎線上更新**：yt-dlp 可在 app 內更新，平台改版時不必重裝。

### 語音轉文字（逐字稿）

- **三種來源**：分享本機**影片 / 聲音檔**、分享**影片連結**（自動取音訊轉錄）、或有字幕的 **YouTube**（直接抓字幕、秒出，跳過辨識）。
- **兩種引擎**（設定可切）：
  - **裝置端（預設）**：whisper.cpp，**完全離線、免金鑰、隱私**；可選 `base`（快）/ `small`（較準）模型，首次使用時下載。
  - **雲端**：OpenRouter（**自備 API 金鑰**），速度與中文準度更佳。
- **語言**：多語自動偵測，中文自動轉**台灣正體**；可在設定**鎖定主要語言**，避免無人聲片頭被誤判。
- **背景進行**：通知顯示進度、可中止，app 被關掉也能續跑；完成後可**複製 / 分享**，結果頁標示這次用的引擎與模型，並有「**轉錄記錄**」清單。

### 取出聲音

- 分享**影片**時選「**取出聲音**」，會**無損**抽出原音軌存成 `.m4a`（保留原音質、不重新編碼），存到你的下載位置。

---

## 安裝

到 [Releases](https://github.com/changyow-ai/media-dler/releases) 下載 APK：

- 多數現代手機選 **`arm64-v8a`**；不確定就用 **`universal`**（較大但通用）。
- 安裝時需允許「安裝未知來源應用程式」。

---

## 使用

1. **下載**：在任一 app 點分享 → media-dler；一鍵模式直接下載，彈窗模式選好畫質 / 音訊 / 勾選項目再下載。
2. **轉逐字稿**：分享影片 / 聲音檔 → 選「**轉成文字**」；或分享連結 → 彈窗選「**轉文字**」。
3. **取出聲音**：分享影片 → 選「**取出聲音**」。
4. 設定裡可切換轉錄引擎 / 語言 / 模型，雲端金鑰也在設定貼上。

---

## 注意與限制

- **平台支援度取決於 yt-dlp**：下載失敗先更新引擎（設定 → 下載引擎 → 更新 yt-dlp），YouTube 尤其常需更新。Threads 影片可下載，**純圖 / 多圖貼文不支援**；IG / FB 私人貼文等需登入，目前僅支援公開內容。
- **雲端轉錄需自備金鑰**：在設定貼上你的 OpenRouter 金鑰（**只存在裝置本機，不內建於 app、不隨版散布**）。按音訊**時長**計費，建議用 `openai/whisper-large-v3-turbo`（最便宜也最穩定）。
- **裝置端模型首次需下載**（`base` ~142MB / `small` ~466MB），非 Wi-Fi 會先詢問；中階手機上 `small` 可能慢於即時，中文準度低於雲端。
- 進度百分比為盡力呈現；以「解析中 / 處理中 / 完成」狀態為主。
- 請尊重各平台服務條款與著作權，只下載 / 轉錄你有權保存的內容。

---

## 致謝

本專案站在這些之上：[yt-dlp](https://github.com/yt-dlp/yt-dlp)、[FFmpeg](https://ffmpeg.org/)、[youtubedl-android](https://github.com/JunkFood02/youtubedl-android)、[whisper.cpp](https://github.com/ggerganov/whisper.cpp)、[OpenCC](https://github.com/BYVoid/OpenCC)，雲端轉錄透過 [OpenRouter](https://openrouter.ai/)。

> 設計、架構與實作筆記見 [`plan/media-dler-plan.md`](plan/media-dler-plan.md)（下載）與 [`plan/video2text-plan.md`](plan/video2text-plan.md)（語音轉文字 / 抽音）。
