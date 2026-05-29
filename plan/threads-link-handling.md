# Threads 連結處理方式（詳細記錄）

> 本文件詳細記錄 media-dler 如何處理 **Meta Threads**（threads.net / threads.com）的貼文連結，
> 涵蓋從「收到分享 URL」到「解析出可下載媒體」再到「下載 + 預覽」的完整流程、各元件職責、
> 命名規則、過濾規則、兩條解析路徑的差異、已知限制與測試覆蓋。
>
> 對應原始計劃見 [`media-dler-plan.md`](./media-dler-plan.md)（特別是「實測遇到的問題與解法」第 4、8、9 點）。
> **標 ⚠️ 的是踩過的坑與正確作法。**

---

## 1. 為什麼 Threads 要特別處理

絕大多數平台（YouTube、Bilibili、IG、TikTok…）都由 yt-dlp 直接吃下去就好。**Threads 是例外**：

- ⚠️ **yt-dlp 沒有官方 Threads extractor**：`threads.com` / `threads.net` 都不匹配任何 extractor，會 fallback 到 `generic` 硬爬網頁，常常只抓到 `og:title` / `og:image` 之類垃圾，標題會顯示成像亂碼的東西（例如「Q3.e」「Threads (1)」）。
- **貼文 URL 本身不可直接下載**，但貼文的 **`/embed` 頁**會把媒體攤平到 HTML，讓 yt-dlp 的 `html5` extractor（或我們自己的 regex）抓得到直連 CDN URL。
- ⚠️ **不能只靠 yt-dlp 的 `html5`-on-embed**：它常被導到 `embed?_fb_noscript=1`（沒有 `<video>` 標籤）→ 回「Unsupported URL」。因此我們**自備一條專用 extractor 當主路徑**，yt-dlp 只當 fallback。

因此 Threads 走的是一條**雙路徑、有 fallback**的客製流程，與其他平台不同。

---

## 2. 端到端流程總覽

```
分享 / 貼上 URL
      │
      ▼
┌─────────────────────────────┐
│ RoutingMediaExtractor       │  app/.../data/RoutingMediaExtractor.kt
│ 用 ThreadsUrl.embedUrlOrNull│
│ 判斷是不是 Threads 貼文      │
└─────────────┬───────────────┘
   是 Threads  │            │ 不是 Threads
              ▼            ▼
   ┌──────────────────┐   ┌──────────────────────────┐
   │ ThreadsExtractor │   │ YtDlpMediaExtractor       │
   │ (主路徑)          │   │ (一般平台走這條)            │
   └────────┬─────────┘   └──────────────────────────┘
            │ 失敗時 recover
            ▼
   ┌──────────────────────────────────────┐
   │ YtDlpMediaExtractor (Threads fallback)│
   │ 把 URL 改寫成 /embed 餵給 html5         │
   └────────┬─────────────────────────────┘
            │ 兩條都失敗 → 拋「主路徑(ThreadsExtractor)」的錯誤訊息
            ▼
   List<MediaItem>（sourceUrl = 直連 CDN 媒體 / yt-dlp 解出的媒體）
            │
            ▼
   ┌──────────────────┐     ┌─────────────────────────────┐
   │ YtDlpDownloader  │ ──▶ │ PreviewStore                │
   │ 直接下載 sourceUrl│     │ 影片用 MediaMetadataRetriever │
   └──────────────────┘     │ 抽一格當本地預覽縮圖           │
                            └─────────────────────────────┘
```

**主路徑（ThreadsExtractor）**才是 Threads 的「正解」：自己用瀏覽器 UA 抓 `/embed`，regex 取 cdninstagram 直連 URL。
yt-dlp 路徑只是萬一主路徑掛掉時的備援。

---

## 3. 元件逐一說明

### 3.1 `ThreadsUrl` — URL 偵測、embed 改寫、短碼

檔案：`core/src/main/kotlin/com/changyow/mediadler/core/extract/ThreadsUrl.kt`（純 Kotlin，可單測）

核心正則：

```kotlin
private val POST = Regex("""https?://(?:www\.)?threads\.(?:net|com)/(@[^/?#]+/post/[^/?#]+)""")
```

- 同時匹配 `threads.net` 與 `threads.com`，`www.` 可有可無。
- 擷取群組 = `@使用者/post/短碼`，因為字元類 `[^/?#]` 在遇到 `?` `#` 就停，**自動丟掉 tracking query**（如 `?xmt=AQ&slof=1`）。

兩個函式：

| 函式 | 行為 | 例子 |
|------|------|------|
| `embedUrlOrNull(url)` | 是貼文 → 回 `https://www.threads.net/<@user/post/code>/embed`；否則 `null` | `…threads.com/@rico_y9527/post/DY3m2JQjXHZ?xmt=AQ` → `https://www.threads.net/@rico_y9527/post/DY3m2JQjXHZ/embed` |
| `postCode(url)` | 回貼文短碼（擷取群組最後一段），否則 `null` | → `DY3m2JQjXHZ` |

⚠️ **重點細節**：
- `embedUrlOrNull` **一律把 host 正規化成 `www.threads.net`**，不管輸入是 `.com` 還是 `.net`（embed 在 threads.net 才穩）。
- **個人頁不算貼文**：`threads.com/@someone`（沒有 `/post/`）→ `embedUrlOrNull` 與 `postCode` 都回 `null`，因此不會被當 Threads 處理。
- 這支同時被「主路徑判斷（Routing）」「主路徑抓 embed（ThreadsExtractor）」「fallback 改寫 + 重新命名（YtDlpMediaExtractor）」「parser 取短碼（ThreadsEmbedParser）」共用，是 Threads 流程的單一真相來源。

### 3.2 `RoutingMediaExtractor` — 路由 + fallback 串接

檔案：`app/src/main/java/com/changyow/mediadler/data/RoutingMediaExtractor.kt`

```kotlin
override suspend fun extract(url: String): Result<List<MediaItem>> =
    if (ThreadsUrl.embedUrlOrNull(url) != null) {
        threads.extract(url).recoverCatching { primary ->
            fallback.extract(url).getOrElse { throw primary }
        }
    } else {
        fallback.extract(url)
    }
```

- 用 `ThreadsUrl.embedUrlOrNull(url) != null` 判斷是不是 Threads 貼文。
- **是 Threads**：先試 `threads`（= `ThreadsExtractor`）；失敗才 `recoverCatching` 退到 `fallback`（= `YtDlpMediaExtractor`）。
- ⚠️ **錯誤訊息策略**：若 **fallback 也失敗**，`getOrElse { throw primary }` 會**拋出主路徑（ThreadsExtractor）的錯誤**，而不是 yt-dlp 的「Unsupported URL」。因為主路徑的訊息對使用者更有意義（含診斷資訊，見 3.3）。
- **不是 Threads**：直接走 `fallback`（yt-dlp 一般流程）。

DI 接線在 `app/.../di/AppContainer.kt`：

```kotlin
val mediaExtractor: MediaExtractor = RoutingMediaExtractor(
    threads = ThreadsExtractor(),
    fallback = YtDlpMediaExtractor(engine),
)
```

### 3.3 `ThreadsExtractor` — 主路徑：自抓 `/embed`

檔案：`app/src/main/java/com/changyow/mediadler/data/threads/ThreadsExtractor.kt`（在 `:app`，因為要用 `HttpURLConnection`）

流程（全程在 `Dispatchers.IO`）：

1. `ThreadsUrl.embedUrlOrNull(url)` 取 embed URL；若 `null` → 失敗「不是 Threads 連結」。
2. 用 **`HttpURLConnection` + 桌面/行動瀏覽器 UA** 抓 embed 頁：
   - UA：`Mozilla/5.0 (Linux; Android 14; Pixel 8) … Chrome/124.0 Mobile Safari/537.36`
   - 帶 `Accept` / `Accept-Language` / `Sec-Fetch-Mode: navigate` / `Sec-Fetch-Dest: document`（偽裝成正常瀏覽，避免被導去 noscript 頁）。
   - `connectTimeout = 15s`、`readTimeout = 20s`、`instanceFollowRedirects = true`。
   - ⚠️ 失敗（非 2xx）也讀 `errorStream`，方便把錯誤頁內容納入診斷。
3. 檢查 HTTP 狀態碼必須 `200..299`，否則錯誤「Threads embed 回應 HTTP $status」。
4. 把 body 丟給 `ThreadsEmbedParser.parse(body, url)` 取得 `List<MediaItem>`。
5. ⚠️ **空結果要給「會講話」的錯誤**：找不到媒體時，錯誤訊息會附上 embed URL、頁面 bytes 數、是否含 `cdninstagram`、是否含 `<video`，方便事後判斷是「純文字貼文 / 需登入 / 多圖只暴露首圖」哪一種：

   ```
   Threads embed 找不到影片/圖片（可能為純文字、需登入，或多圖只暴露首圖）
   embed：https://www.threads.net/@u/post/ABC123/embed
   頁面 12345 bytes，含 cdninstagram=true，含 <video>=false
   ```

### 3.4 `ThreadsEmbedParser` — 從 embed HTML 抽直連 CDN URL

檔案：`core/src/main/kotlin/com/changyow/mediadler/core/extract/ThreadsEmbedParser.kt`（純 Kotlin，可單測）

**前處理（反跳脫）**：先把常見跳脫還原，否則 regex 抓不到完整 URL：

```kotlin
val html = embedHtml.replace("\\u0026", "&").replace("&amp;", "&").replace("\\/", "/")
```

- `&` → `&`（JSON 內嵌）
- `&amp;` → `&`（HTML 實體）
- `\/` → `/`（JSON 路徑跳脫）

**比對與過濾**：

```kotlin
private val CDN_URL    = Regex("""https://[a-z0-9_.-]*cdninstagram\.com/[^"'\\ )<>]+""", IGNORE_CASE)
private val PROFILE_PIC = Regex("""/t51\.\d+-19/""")
private val IMAGE_EXTS  = setOf("jpg", "jpeg", "png", "webp", "heic")
```

逐一掃出 `cdninstagram.com` 的 URL，套用下列規則：

| 規則 | 作用 |
|------|------|
| 跳過 `static.cdninstagram.com` | 那是站台 UI 靜態資源（icon、rsrc），不是貼文媒體 |
| 取副檔名（先 `substringBefore('?')` 去掉 query 再取 ext） | 只收 `mp4` 與圖片副檔名（jpg/jpeg/png/webp/heic），其餘丟掉 |
| 以「去 query 的 path」去重（`seen` set） | 同一張圖不同 query 參數只留一份 |
| `mp4` → 影片清單 | 影片不會是頭像，直接收 |
| 圖片且 **不符** `/t51.<digits>-19/` → 圖片清單 | ⚠️ `-19` 是 IG 頭像（profile-pic）命名空間，**過濾掉作者頭像**，避免把頭像當貼文圖 |

**產出 `MediaItem`**（先影片、後圖片）：

- 影片：`ThreadsVideo_<code>`（單支）或 `ThreadsVideo_<code>_1`、`_2`…（多支）
- 圖片：`ThreadsImage_<code>_1`、`_2`…（**一律帶序號**）
- `sourceUrl` = 直連 CDN URL（**保留 query**，因為簽章 token 在 query 上，拿掉會 403）
- 圖片的 `thumbnailUrl` = 自己（影片為 `null`，預覽改用本地抽格，見 3.6）
- 每個 item 一個 `MediaFormat`（`label` = 「影片」/「圖片」，`hasVideo`/`hasAudio` = `!isImage`）

### 3.5 `YtDlpMediaExtractor` — fallback 路徑（也含 Threads 改寫）

檔案：`app/src/main/java/com/changyow/mediadler/data/ytdlp/YtDlpMediaExtractor.kt`

這是「一般平台」的主 extractor，同時也是 **Threads 的 fallback**。它對 Threads 做兩件事：

1. **改寫成 embed 再餵 yt-dlp**：

   ```kotlin
   val target = ThreadsUrl.embedUrlOrNull(url) ?: url   // Threads → /embed；其他 → 原 URL
   ```

   讓 yt-dlp 的 `html5` extractor 去爬 embed 頁的 `<video>`。執行參數 `-J --no-warnings --playlist-items 1:50`。

2. **覆寫垃圾標題**：yt-dlp 對 Threads 媒體命名很爛（「Threads (1)」），抽完後若 `postCode` 不為 null，就改寫成 `ThreadsVideo_<code>`（多支再加 `_<index>`），與主路徑命名一致。

另外它有**自我修復**：第一次失敗會 `engine.update()`（更新 bundled yt-dlp）再重試一次，最後才把錯誤包成含「來源：$url」的訊息。

⚠️ 為什麼這只是 fallback：html5-on-embed 常被導到 `embed?_fb_noscript=1`（無 `<video>`）→「Unsupported URL」，成功率不如自抓 embed + regex。

### 3.6 下載與預覽

- **下載**：`app/.../data/ytdlp/YtDlpDownloader.kt` 直接用 `item.sourceUrl` 當 yt-dlp 目標。對主路徑而言 `sourceUrl` 已是**直連 cdninstagram 的 `.mp4`/圖片**，yt-dlp 走 generic/直連把檔案抓下來。
- **預覽縮圖**：`app/.../download/PreviewStore.kt`。Threads（與 Bilibili）**沒有可用的遠端縮圖**，所以影片下載完成後用 Android 內建 `MediaMetadataRetriever.getFrameAtTime` 抽第一格存成本地 JPG 當清單預覽，並隨任務刪除一併清除（`previewPath` 也存進歷史）。
  - ⚠️ 別指望 youtubedl-android 的 `FFmpeg` 跑任意指令——它只有 `init` / `getInstance`，沒有 `execute`。

---

## 4. 命名規則彙整

| 來源 | 影片名 | 圖片名 |
|------|--------|--------|
| 主路徑 `ThreadsEmbedParser` | `ThreadsVideo_<code>`（單）/ `ThreadsVideo_<code>_<n>`（多） | `ThreadsImage_<code>_<n>`（恆帶序號） |
| fallback `YtDlpMediaExtractor` | `ThreadsVideo_<code>`（單）/ `ThreadsVideo_<code>_<n>`（多） | （同上邏輯，但 yt-dlp 通常只給影片） |

`<code>` = 貼文短碼（`ThreadsUrl.postCode`，例如 `DY3m2JQjXHZ`）。取不到短碼時 parser 退回字面 `threads`。

---

## 5. 兩條路徑差異對照

| 面向 | 主路徑 `ThreadsExtractor` | fallback `YtDlpMediaExtractor` |
|------|--------------------------|-------------------------------|
| 抓取方式 | 自己用瀏覽器 UA 抓 `/embed` HTML | 把 URL 改寫成 `/embed` 交給 yt-dlp html5 |
| 解析方式 | regex 取 cdninstagram 直連 URL | yt-dlp JSON（`YtDlpInfoParser`） |
| 影片 | ✅ 直連 `.mp4` | ✅（成功時） |
| 圖片 / 多圖 | ✅ 取得 embed 暴露的圖（多半僅首圖） | ✩ 通常只拿到影片 |
| 失敗模式 | 純文字 / 需登入 / 多圖僅首圖 → 空結果 | 常被導 noscript → 「Unsupported URL」 |
| 角色 | **主**（先試） | **備援**（主失敗才用） |

---

## 6. 已知限制

- ⚠️ **純圖 / 多圖（輪播）貼文多半只拿得到首圖，或完全不支援**：embed 只暴露首圖，完整輪播需登入 / 走 GraphQL，屬已知限制。
- **純文字貼文**：沒有媒體，會回空結果（帶診斷訊息）。
- **需登入內容**：embed 不含媒體 → 視為不支援。
- **依賴 embed 頁與 CDN 命名慣例**：若 Meta 改 embed 結構或 CDN 路徑（例如改掉 `t51.*-19` 頭像命名、或換掉 `cdninstagram.com` 網域），`ThreadsEmbedParser` 的 regex 需同步更新。

---

## 7. 錯誤與診斷訊息一覽

| 情境 | 來源 | 訊息（摘要） |
|------|------|--------------|
| 不是 Threads 貼文 URL | `ThreadsExtractor` | 「不是 Threads 連結：\n<url>」 |
| embed 回非 2xx | `ThreadsExtractor` | 「Threads embed 回應 HTTP <status>\nembed：<embed>」 |
| 抓到頁面但無媒體 | `ThreadsExtractor` | 「找不到影片/圖片…」＋ embed URL／bytes／含 cdninstagram／含 `<video>` 診斷 |
| 兩條路徑都失敗 | `RoutingMediaExtractor` | 拋**主路徑**的上述訊息（非 yt-dlp 的 Unsupported URL） |
| yt-dlp 解不到媒體 | `YtDlpMediaExtractor` | 「找不到可下載的媒體（可能不支援此網站或需要登入）」＋「來源：<url>」 |

---

## 8. 測試覆蓋

純邏輯都在 `:core`，以 JUnit red/green 守住：

- `core/src/test/kotlin/.../ThreadsUrlTest.kt`
  - threads.com 貼文 → 改寫成 threads.net `/embed` 並丟掉 query
  - threads.net 貼文改寫
  - 非 Threads / 個人頁 → `null`
  - 短碼擷取
- `core/src/test/kotlin/.../ThreadsEmbedParserTest.kt`
  - 影片貼文：取 `<video>` 的 mp4、**跳過頭像**、保留 query（`oe=2`）、命名 `ThreadsVideo_<code>`
  - 純圖貼文：取貼文圖、**跳過頭像與 `static.cdninstagram.com`**
  - 影片 + 圖片 → 兩個 item（影片在前）
  - 純文字 → 空清單

⚠️ `ThreadsExtractor`（網路）與 `YtDlpMediaExtractor`（引擎）屬整合層，不做單測，靠 CI 編譯與實機驗證把關。

---

## 9. 維護備忘（改 Threads 行為時看這裡）

- 改 **URL 偵測 / embed 改寫 / 短碼** → `ThreadsUrl`（同時牽動 Routing、ThreadsExtractor、YtDlpMediaExtractor、Parser）。
- 改 **要抓哪些媒體 / 過濾規則 / 命名** → `ThreadsEmbedParser`（記得補 `ThreadsEmbedParserTest`）。
- 改 **抓 embed 的 UA / header / timeout / 錯誤訊息** → `ThreadsExtractor`。
- 改 **主備路徑順序 / 錯誤訊息策略** → `RoutingMediaExtractor`。
- 改 **fallback 行為 / yt-dlp 參數 / 標題覆寫** → `YtDlpMediaExtractor`。
- 改 **下載 / 本地預覽縮圖** → `YtDlpDownloader`、`PreviewStore`。
