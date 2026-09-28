# 長輩保護（0.6.0）：廣告防護、防止亂跳 App、嚴格模式

> 0.6.0（2026-09-29）新增的三個保護功能的設計、限制與測試結果。App 靜態掃描演算法（EGDA）仍以 [ALGORITHM.md](ALGORITHM.md) 為準；本文件不改動它。

## 0. 設計原則

**不是「不准 App 開別的 App」，而是「不准 App 在使用者沒有要求時，擅自把使用者帶去別的 App」**。同時，能在網路層擋掉的廣告，就先用本機 VPN 擋掉。

優先順序：可靠性 > 不影響正常使用 > 安全 > 隱私 > 低誤判 > 低耗電 > 功能數量。

## 1. Android 真正做得到什麼：三個等級

| 等級 | 意思 | 守門員用的機制 | 需要 |
|---|---|---|---|
| **A. 在啟動「之前」就拒絕** | Android 系統直接不讓不在名單上的 App 出現 | 嚴格模式：`DevicePolicyManager.setLockTaskPackages` + lock task、`setPackagesSuspended` | **Device Owner**（家人用電腦設定一次，手機上不能有帳號） |
| **B. 發生「之後」立刻反應** | 在跳過去的約 0.3–1 秒內，帶使用者回去或先問 | 防止亂跳 App：無障礙服務的視窗切換事件 + `GLOBAL_ACTION_BACK` / 重新打開原本的 App / `HOME` | 無障礙權限 |
| **C. 降低廣告出現的機會** | 廣告網址在載入前就解析不到 | 廣告防護：本機 DNS-only VPN | VPN 同意（同時只能有一個 VPN） |

**一般 App（非 Device Owner）沒有任何正式 API 能在第三方 App 呼叫 `startActivity()` 之前攔截**。所以 B 是「事後立刻撤銷」，文件與介面都不會把 B 說成 A。

## 2. 廣告防護（本機 VPN）

### 2.1 架構

```
其他 App 查詢網址（DNS）
     │   系統的 DNS 伺服器被設成 192.0.2.53 / fd00:e1de:9a4d::53（VPN 內的假位址）
     ▼
TUN（只有這兩個 /32、/128 位址走進 VPN；其他所有流量直接走 Wi-Fi／行動網路）
     ▼
TunnelEngine ── 一個執行緒在 poll() 裡睡著，有封包才醒
     │ IpUdp.parse → Dns.parseQuery（只接受一般的單一問題查詢；其他一律丟棄）
     ▼
FilterEngine.match(name)
 ├─ 擋 → 立刻回 0.0.0.0 / ::（其他型別回 NODATA），TTL 60 秒
 └─ 放行 → 工作執行緒（最多 8 個）→ DnsUpstream → 回覆寫回 TUN
                                     ├ UdpUpstream：目前網路的 DNS（預設），或 Cloudflare／Google／Quad9
                                     ├ DohUpstream：DNS-over-HTTPS（RFC 8484）
                                     └ FailoverUpstream：加密 DNS 連續失敗 3 次 → 暫時改用網路 DNS（5 分鐘後再試），並顯示在診斷頁
```

| 元件 | 檔案 | 職責 |
|---|---|---|
| `AdBlockVpnService` | `vpn/AdBlockVpnService.kt` | 生命週期、建立 TUN、追蹤實體網路、健康檢查、通知（暫停 15 分鐘／打開守門員） |
| `TunnelEngine` | `vpn/TunnelEngine.kt` | 讀寫 TUN、分派查詢 |
| `IpUdp`、`Dns` | `vpn/DnsPacket.kt` | IPv4/IPv6/UDP/DNS 解析與組包（含 checksum） |
| `DnsUpstream` 系列 | `vpn/DnsUpstream.kt` | 上游：UDP、DoH、明示的備援 |
| `VpnHealthMonitor` | `vpn/VpnHealthMonitor.kt` | 純邏輯：何時重建、何時放棄（fail-open） |
| `VpnController`、`VpnRuntime`、`VpnStatus` | `vpn/VpnController.kt` | 開關、真實狀態（服務狀態 **且** Android 真的有這個 VPN） |
| `VpnSettings`、`PerAppVpnPolicy` | `vpn/VpnSettings.kt` | 設定、每個 App 是否經過 VPN |
| `DnsStats` | `vpn/DnsStats.kt` | 計數、選用的 DNS 紀錄（批次寫入） |
| `FilterEngine`、`DomainSet`、`RuleSet` | `filter/` | 網域比對 |
| `FilterParser`、`FilterStore`、`FilterRepository`、`FilterUpdater`、`FilterUpdateJob` | `filter/` | 清單解析、原子儲存、組裝規則、安全下載、定時更新 |

### 2.2 為什麼是 DNS-only，而不是 BlockAds 那種全流量 tunnel

BlockAds 把 `0.0.0.0/0` 全部導進 gVisor + tun2socks 的使用者空間網路堆疊（一個 19 MB 的 Go AAR）。守門員只路由假的 DNS 位址：

- 網頁、影片、下載**完全不經過守門員**：沒有吞吐量損失，也沒有任何內容可以被看到（實測路由表只有 `192.0.2.2`、`192.0.2.53` 兩條）
- 引擎出事時影響只限於 DNS；關掉 tunnel，手機立刻回到原本的 DNS
- 程式小、可以逐行審查，不需要原生程式庫

代價：擋不到直接寫死 IP 或自帶 DoH 的 App（見 2.9）。

### 2.3 規則比對（Filter Engine）

- 每條規則存成 **64-bit hash**（FNV-1a 反向 + murmur3 fmix64），放在排序過的 `LongArray`；查詢時一次反向掃描就得到所有上層網域的 hash，每一層做一次二分搜尋
- 實測（JVM）：**100 萬條規則，每次查詢 0.37 µs**；內建清單 74,760 條，編譯 170 ms；25 萬條規則約佔 2 MB
- 碰撞機率約 n / 2^64，實務上不會發生
- 兩種規則：
  - **suffix**：`||ads.example.com^`、純網域行、使用者自訂規則 → 也擋 `x.ads.example.com`
  - **exact**：hosts 檔的 `0.0.0.0 ads.example.com` → 只擋這個名字（hosts 本來就是精確比對；轉成 suffix 會誤擋，例如列出的 `s3.amazonaws.com` 會擋掉所有 bucket）
- **優先順序**（可預期）：
  1. 使用者規則優先於所有清單；使用者規則之間**最具體的網域勝出**，同一層允許優先於封鎖。例：擋 `example.com`、允許 `api.example.com` → `api.example.com` 放行，`x.example.com` 被擋
  2. 清單規則同樣最具體者勝出，同一層允許優先；內建的「安全白名單」（連線檢查、推播 FCM、時間同步、Play 下載）永遠不會被清單擋掉
  3. 都沒有命中 → 放行
- **不會把規則放大**：有路徑、萬用字元、regex 或 `$modifier`（`$important` 除外）的規則直接略過，不會被剪成全域封鎖（BlockAds 把 `^` 之後全部切掉，`||x.com^$third-party` 就變成整個 x.com 被擋）
- 拒收過於廣泛的名稱：單一標籤（`com`）、IP、常見公共後綴（`com.tw`、`googleapis.com`、`github.io`…）

### 2.4 清單與更新

| 清單 | 授權 | 預設 |
|---|---|---|
| StevenBlack 統一 hosts（廣告＋惡意網站，含 URLhaus） | MIT | **開啟，內建一份在 APK 裡，離線可用** |
| AdGuard DNS filter | GPL-3.0 | 關閉（要下載） |
| HaGeZi Light | GPL-3.0 | 關閉（要下載） |
| 使用者自訂 https 清單 | — | 使用者加入 |

- **從網路更新預設關閉**。開啟後用系統 `JobScheduler`，每 6／12／24／48 小時（預設 24），預設只在 Wi-Fi
- 下載流程：**暫存檔（限 40 MB）→ 解析（限 200 萬條）→ 驗證 → 原子替換（寫暫存 → fsync → rename）**
- 驗證：只接受 https（轉址後也檢查）、HTTP 200、不是網頁（Wi-Fi 登入頁）、至少 10 條可用規則、不能突然少於上一版的 30%
- 任何一步失敗：**舊規則繼續使用**，錯誤顯示在清單頁；內容沒變（SHA-256 相同）就不重寫
- 每個 `.bin` 有 CRC32；損壞時自動改用 `.bak`（上一版），再不行就重新編譯內建清單；使用者可以「還原上一版」
- 使用者自己加的清單，其中的 `@@` 例外規則**不會**解除其他清單的封鎖（避免被入侵或惡意的清單關掉惡意網站清單）
- 全部清單合計最多 300 萬條規則（約 24 MB）
- 規則或清單變更後會**重建 tunnel**：Android 的 DNS 快取以網路為單位，重建後快取清空，新規則立刻生效（實測：已快取的 example.com 在「擋掉」後要重建才生效）

### 2.5 每個 App 是否經過 VPN

Android 只允許二選一：

- **全部 App**（預設）：`addDisallowedApplication` 排除使用者選的 App（銀行、公司、其他 VPN 類 App）
- **只有選定的 App**：`addAllowedApplication`；沒有選任何 App 時拒絕啟動（空的允許清單在 Android 代表「全部」）

守門員自己永遠在 VPN 外面（它的 DoH 和清單下載不能繞回自己）。變更會重建 tunnel。

### 2.6 狀態一定是真的

介面顯示「運作中」必須**同時**滿足：使用者開啟、服務回報 RUNNING、**Android 的網路清單裡真的有一個屬於守門員的 VPN**（`NetworkCapabilities.getOwnerUid()`）。其他狀態分別顯示：暫停中、被其他 VPN 取代、需要再同意一次、暫時停止（網路照常）。

### 2.7 穩定性

- **網路切換**：用 `registerBestMatchingNetworkCallback`（NOT_VPN + INTERNET）追蹤實體網路。**不能用 `registerDefaultNetworkCallback`**：在 Android 16 模擬器上，它對 VPN 自己的 UID 回報的是 VPN 本身，DNS 被轉回自己的 tunnel，所有放行的網址都解析失敗（開發過程中實際發生並修正）
- **健康檢查**：螢幕亮時每 20 秒、暗時每 5 分鐘；只看引擎的真實計數（BlockAds 從自己被排除的 UID 去探測 tunnel，永遠到不了）
- **fail-open**：引擎死掉，或網路本身正常（已驗證）但上游連續失敗 ≥ 8 次且 30 秒沒有成功 → 重建一次 → 還是不行就**關掉 VPN**，通知「廣告防護暫時停止，網路仍可正常使用」，附「再試一次」
- **系統的「封鎖未使用 VPN 的連線」（lockdown）開著時**：關掉 VPN 等於斷網，所以暫停改成「tunnel 保留、只停止過濾」，fail-open 改成每分鐘重試
- **開機**：`BOOT_COMPLETED` 時，若使用者開著且同意仍有效就恢復；App 更新（`MY_PACKAGE_REPLACED`）同樣恢復
- **其他 VPN 取走名額**（`onRevoke`）：尊重使用者，不搶回來

### 2.8 永遠開啟 VPN

宣告 `SUPPORTS_ALWAYS_ON=true`，但守門員**不會自己修改系統 VPN 設定**。VPN 頁說明這是選用功能，並提供按鈕打開系統的 VPN 設定頁。

### 2.9 DNS 封鎖不是萬能（介面與 README 都有寫）

- **同一網域同時提供內容和廣告**（例如 YouTube 影片廣告、許多第一方廣告）：DNS 無法分開。這正是「防止亂跳 App」存在的原因
- App 寫死 IP、自帶 DoH／DoT：繞過
- **系統「私人 DNS」指定了主機名稱**：Android 會直接用 DoT 查詢，繞過守門員。VPN 頁與診斷頁會偵測（`LinkProperties.getPrivateDnsServerName()`）並提示改回「自動」
- **只能有一個 VPN**：開啟廣告防護會斷開 WireGuard、Tailscale、公司 VPN 等，反之亦然
- 走 TCP 的 DNS（回應被截斷時）不處理；回應大小已放寬到 64 KB，實務上很少發生

### 2.10 不做的事

HTTPS 攔截（MITM）、安裝 CA 憑證、解密、HTML／CSS／JS 注入、root／iptables 模式、WireGuard。這些 BlockAds 都有，守門員一個都沒有。

## 3. 防止亂跳 App（Anti Redirect）

### 3.1 元件

| 元件 | 檔案 | 職責 |
|---|---|---|
| `RedirectGuard` | `redirect/RedirectGuard.kt` | 在無障礙服務裡接收事件、執行決定、通知、只在需要時開加速度計 |
| `ForegroundTracker` | `redirect/RedirectEngine.kt` | 從視窗事件算出「從哪個 App 切到哪個 App」，並記住每個 App 是怎麼進來的 |
| `UserIntentTracker` | `redirect/IntentSignals.kt` | 點按、捲動、點通知、搖晃（只存套件名、view 類別、時間，60 秒） |
| `SystemFlowClassifier` | `redirect/SystemFlowClassifier.kt` | 系統畫面、桌面、最近使用、分享選單、目標類型（商店／購物／瀏覽器／金融） |
| `RedirectEngine` | `redirect/RedirectEngine.kt` | 決策順序與風險分數（純 Kotlin，單元測試涵蓋 A–K） |
| `RedirectLoopDetector`、`TemporaryUnlockManager`、`ShakeDetector` | `redirect/IntentSignals.kt` | 迴圈、暫停、搖晃 |
| `RedirectRecoveryManager` | `redirect/RedirectRecoveryManager.kt` | BACK → 再 BACK → 重新打開原本的 App → HOME |
| `RedirectPromptActivity` | `redirect/RedirectPromptActivity.kt` | 「這是您要打開的嗎？」與「仍然開啟」 |
| `RedirectRuleRepository`、`RedirectHistoryRepository`、`ProtectedAppManager` | `redirect/RedirectStore.kt` | 來源→目標規則、一次性允許、紀錄、受保護的 App |

### 3.2 決策順序

只有**受保護的 App** 當來源才會分析（預設「自動」：掃描時發現含廣告 SDK 的使用者 App；可改成手動或全部 App，全部不是預設）。

1. **一定放行**：從桌面（`LAUNCHER_FLOW`）、最近使用（`RECENTS_FLOW`）、分享／開啟方式選單（`SHARE_FLOW`）過去；目標是系統畫面（撥號、設定、權限、相機、檔案挑選、照片挑選、安裝程式、鍵盤、Google 帳號…，從手機的角色與 intent handler 算出，不是寫死單一品牌）；點了該 App 的通知（`REASON_CLICK`，或剛拉下通知欄且目標 App 有通知在）；暫停中；「這次允許」；使用者設定的「以後都允許」；**回到原本的 App**（只限「反轉一趟使用者要求的前往」，見 3.4）；登入流程（受保護 App 經使用者點按開了瀏覽器後 10 分鐘內的往返）
2. **一定阻止**：使用者設定的「以後都不要」；迴圈冷卻中
3. **多重訊號風險分數**（內部 0–100+，長輩介面不顯示）：

| 訊號 | 分數 |
|---|---|
| 基礎 | +20 |
| 4 秒內在來源 App 有點按 | −40（1.5 秒內）／−30；**在廣告頁上點的只算 −10**（廣告常把「✕」做成點擊） |
| 沒有點按，且這個 App 平常會回報點按 | +45 |
| 沒有點按，但這個 App 從不回報點按（Unity 等遊戲） | +15（不當作證據） |
| 來源是廣告 SDK 的全螢幕頁（10 秒內） | +30 |
| 3 秒內手機晃動（沒點按 +35；有點按 +10） | +35／+10 |
| 目標是應用程式商店／購物 App／瀏覽器 | +15／+15／+5 |
| 來源進入前景不到 1.5 秒就跳走 | +10 |
| 同一對跳轉最近被擋過 | +15 |
| 來源 24 小時內亂跳過 | +10 |

**≥ 60 → 阻止；30–59 → 先問；< 30 → 放行。** 目標或來源像是銀行／付款／OTP App 時，阻止降為「先問」（`FINANCE_CAUTION`），絕不硬擋金流與驗證。

### 3.3 反應

- **阻止**：等 0.35 秒（讓稍晚到的通知點擊、點按事件趕上）再重新判斷一次 → BACK → 0.7 秒後還在就再 BACK → 再不行就重新打開來源 App（無障礙服務不受背景啟動限制）→ 最後 HOME。通知「已阻止疑似廣告跳轉／『某遊戲』企圖開啟『Shopee』／[仍然開啟]」，同一對 10 秒內只通知一次
- **先問**：半透明的提問畫面蓋在目標上，**安全選項是主要按鈕**：「不要，回到『某遊戲』」／「要，繼續使用」／「以後都允許」。沒有倒數。提問時隱藏其他 App 的浮動視窗並過濾被遮蔽的觸控，防止誘騙點擊
- **用字**：「疑似廣告跳轉」「未經確認的 App 跳轉」，不說「惡意廣告已被阻止」

### 3.4 迴圈與「回到原本的 App」

- 同一對 10 秒內被擋 3 次 → `LOOP_DETECTED`：改回桌面（回到遊戲只會再跳一次），30 秒冷卻，冷卻中不再通知
- 「回到原本的 App」只在**反轉一趟有正面證據的前往**時成立。每次進入 App 都會記錄進入方式：`FORWARD_OK`、`RETURN`、`UNJUDGED`、`TAINTED`。單元測試抓到過一個漏洞：「允許一次 → 返回 → 廣告再跳」原本會被當成返回而放行，形成來回跳。守門員拉回來的進入也標為 `TAINTED`

### 3.5 暫停與規則

- 「暫停跳轉保護」30 秒／1 分鐘／5 分鐘／10 分鐘，**只存在記憶體**：時間到或重新開機都會恢復
- 規則一律是**來源→目標**的配對：允許「WeChat → Bilibili」不代表任何 App 都能開 Bilibili
- 永久規則只有使用者按「以後都允許」才會建立；系統**不會**自動學習
- 「這次允許」只對這一對、只用一次、30 秒內有效

### 3.6 已知限制

- **不是事前攔截**（見第 1 節），使用者會看到目標 App 閃一下
- **遊戲（Unity、SurfaceView）不發出點按事件**：Android 沒有在不改變觸控行為的情況下觀察觸控的方法（`setMotionEventSources` 會攔截觸控；touch exploration 會變成 TalkBack 模式）。這類 App 沒點按時只會「先問」，不會直接擋，除非有廣告頁、搖晃、重複等其他訊號
- **App 可以自己呼叫 `performClick()` 偽造點按事件**，惡意程度高的 SDK 可以因此被判為使用者點按
- 通知點擊打開的是**另一個** App 時（例如新聞通知開 Chrome），會被判為先問
- 模擬器上「搖一搖」訊號有時趕不上判斷時間點（廣告在第一下晃動就跳）；已改成「一次強烈晃動即算」並容許 0.5 秒延遲，兩次實測一次有記到搖晃訊號，兩次都有擋下
- 使用者從桌面、最近使用切換 App 一律放行；在桌面上沒有點按卻突然出現的 App，屬於原本「彈出廣告偵測」的範圍

### 3.7 無障礙服務的資料最小化

- 接收：視窗切換、點按、長按、捲動事件；`canRetrieveWindowContent=false`（拿不到畫面內容樹）
- Android 會把被點元件的文字附在點按事件上；**程式完全不讀**（整個專案沒有 `getText`、`getSource`、`rootInActiveWindow`），只用套件名、view 類別名與時間，保留 60 秒
- 不記錄鍵盤、聊天、密碼、銀行資料，不存畫面文字，不上傳任何東西

## 4. 嚴格模式（Device Owner，選用）

只給「專門給長輩用的手機」。一般使用者不需要。

- **設定**：手機上沒有帳號時（通常是剛恢復原廠），家人在電腦執行 `adb shell dpm set-device-owner org.elderguard/.owner.GuardAdminReceiver`。守門員不會自己變成裝置擁有者
- **Lock task**：以守門員的畫面為 locked task 的根（HOME 類型的 task 不能被鎖，實測過），開放 `HOME`、`OVERVIEW`、`NOTIFICATIONS`、`GLOBAL_ACTIONS`（關機選單）、`KEYGUARD`、`SYSTEM_INFO`。不在允許清單的 App 會被 **Android 直接拒絕啟動**（實測 `am start` 回傳錯誤碼 101）
- **永遠允許**：由 `CriticalPackageGuard` 從角色與 intent handler 算出，包括撥號／緊急電話、桌面、設定、權限、安裝程式、鍵盤、System UI、WebView、Google Play 服務與守門員自己。預設允許清單另外加入：簡訊、相機、相簿、一個瀏覽器，以及已安裝的 LINE、WeChat、YouTube、Bilibili、WhatsApp
- **暫停 App**（`setPackagesSuspended`）：只允許使用者安裝、且不擔任任何系統角色的 App；系統 App、持久化 App、上述關鍵套件一律拒絕
- **管理密碼**：6–12 位數字，PBKDF2-SHA256（12 萬輪、隨機 salt），只存 hash。錯 5 次後每次等待 30 秒、60 秒、120 秒…（最多 1 小時），用開機後經過的時間計算，改系統時鐘無效。已有密碼時要先輸入舊密碼才能更換。嚴格模式開著時，允許清單與暫停清單都不能修改
- **安全出口**：輸入密碼 → 清空允許清單 → Android 立即結束 lock task；「解除裝置擁有者」會先恢復所有暫停的 App
- **開機**：`BOOT_COMPLETED` 之後才重新進入，重開機不會把人鎖在開機前
- **忘記密碼**：只能恢復原廠（非 testOnly 的 Device Owner 無法用 adb 移除）。請家人記好密碼

## 5. 長輩介面

- 首頁最上方是「長輩保護」卡片：全部開啟／有功能沒有開啟、今天已阻止 N 次（DNS 封鎖 + 跳轉阻止 + 彈窗關閉）、廣告防護、防止亂跳 App、可疑 App 數量；沒全開時只有一個按鈕「一鍵開啟長輩保護」
- 一鍵設定頁逐項列出：廣告防護（VPN 同意）、防止亂跳 App（無障礙）、彈出廣告偵測（使用情況存取）、假通知偵測（通知存取）、通知權限、可疑 App 檢查。每一項的狀態都是即時讀取的真實狀態
- Device Owner 可用時顯示「進階保護可用」，不強制
- 技術細節（TUN、UID、resolver、分數）只出現在「設定 → 進階設定」

## 6. 隱私

| 資料 | 存哪裡 | 保留 | 預設 |
|---|---|---|---|
| 跳轉紀錄（套件名、決定、原因代碼、時間；**只有受保護 App 發起的**） | `redirect_history.jsonl` | 7 天 | 開 |
| DNS 紀錄（網域、是否擋、哪個 App） | `dns_history.jsonl` + 記憶體 | 24 小時、最多 500 筆 | **關**；關閉時刪除 |
| DNS 計數 | SharedPreferences | 累計 | 開 |
| 規則、清單 | `filters/`、`redirect_rules.json` | — | — |
| 管理密碼 hash | SharedPreferences | — | — |

- 全部只在手機上；`allowBackup=false`，並用 `dataExtractionRules` 排除雲端備份**與裝置轉移**
- 「匯出記錄檔」不含 DNS 紀錄與跳轉紀錄
- **會連網的程式碼只有三處**：`UdpUpstream`（DNS 問題送到 DNS 伺服器）、`DohUpstream`（DNS 問題 POST 到選定的 DoH 提供者）、`FilterUpdater`（GET 清單，只在使用者開啟更新時）。都不傳送 DNS 問題本身以外的使用者資料；清單的 CDN 會看到手機的 IP 與下載時間

## 7. 耗電與效能（Android 16 x86_64 模擬器實測）

| 項目 | 結果 |
|---|---|
| 閒置 CPU（螢幕亮、VPN + 無障礙 + 監測都開） | 120 秒內 290 ms（約單核 0.24%） |
| 40 次 DNS 查詢經過 tunnel | 610 ms CPU（含同期基準） |
| 整套跳轉情境腳本（約 4 分鐘，大量視窗／點按事件） | 1.52 秒 CPU |
| 記憶體 | PSS 81.7 MB（Java heap 13 MB、native 10.5 MB）；25 萬條規則的 hash 約 2 MB |
| DNS 延遲 | VPN 開與關的分布相同（模擬器的 DNS 本身約 500 ms，量不出差異）；**實機數字待測** |
| 吞吐量 | 不受影響：資料流量不經過 tunnel（路由表只有兩個 DNS 位址） |
| 比對速度（JVM） | 100 萬條規則 0.37 µs／次 |

省電設計：TUN 讀取在 `poll()` 裡睡著；計數與 DNS 紀錄每 60 秒批次寫入；健康檢查螢幕暗時每 5 分鐘；加速度計只在受保護 App 在前景、螢幕亮時開啟（`SENSOR_DELAY_UI`），離開就關閉（`dumpsys sensorservice` 已確認）。

## 8. 測試

- **單元測試**：共 94 個，全數通過（其中 4 個類別是 0.5.0 以前就有的）。涵蓋 Filter（比對、優先順序、格式、原子寫入、還原、損壞、更新驗證、效能）、DNS 封包（IPv4/IPv6 checksum、封鎖回應、畸形封包）、健康檢查、per-app、跳轉案例 A–K 與其他情境
- **模擬器端到端**（Android 16，測試 App `testapps/redirsim`、`testapps/shop`，另有 debug 限定的測試台 `DebugHarnessActivity`）：見第 9 節表格

## 9. 實測結果（2026-09-29，Android 16 模擬器）

| 項目 | 結果 |
|---|---|
| 擋廣告網域／放行一般網域 | 通過 |
| 自訂封鎖／自訂允許（含已快取的網址，重建 tunnel 後） | 通過 |
| Wi-Fi → 行動網路 → Wi-Fi | 通過（underlying network 跟著換） |
| 飛航模式開／關 | 通過（開時解析失敗是預期；關閉後自動恢復，VPN 不中斷） |
| VPN 停止／啟動、App 更新後自動恢復 | 通過 |
| DoH（Cloudflare）、Quad9、回到自動 | 通過 |
| Per-app 排除 | 通過（被排除的 App 拿到真實 IP） |
| 下載 AdGuard 清單（175,487 條）；下載到網頁的壞清單 | 通過（壞清單被拒，舊規則照常） |
| 私人 DNS 指定主機 | 偵測並提示；**此時守門員被繞過**（預期中的限制） |
| 上游失效 → fail-open | 通過：56 秒後關掉 VPN，網路照常 |
| 重新開機 | 通過：VPN、無障礙、規則、自訂規則都恢復；暫停不延續 |
| 畸形 UDP 封包（0、1、36、600 位元組） | 通過：不再當掉（修正前 1 位元組就能讓整個 process 結束） |
| 跳轉 A（點按開 Chrome） | 放行 `USER_CLICK` |
| 跳轉 D（10 秒沒互動後自動跳購物 App） | 阻止並回到遊戲 |
| 跳轉 D2（自動跳 Play 商店） | 模擬器映像沒有 `market://` 的處理程式，**無法實測**（單元測試通過） |
| 跳轉 E（廣告的 ✕ 被接到廣告連結） | 阻止（65 分）；沒有歷史紀錄時為先問（55 分） |
| 跳轉 E2（廣告頁自動跳） | 阻止 |
| 跳轉 F（搖一搖） | 兩次都阻止；一次記到 `SHAKE_REDIRECT`，一次沒趕上（見 3.6） |
| 跳轉 G（分享選單 → Messages） | 放行 `SHARE_FLOW` |
| 跳轉 H（登入：點按開瀏覽器 → 返回） | 放行 |
| 跳轉 I（遊戲 → 桌面 → 開購物 App） | 放行 `LAUNCHER_FLOW` |
| 跳轉 J（點通知開 App） | 單元測試通過；模擬器沒有可點的測試通知，**未實測** |
| 跳轉 K（一直重複跳） | 擋 2 次後第 3 次 `LOOP_DETECTED`，回到桌面 |
| 「仍然開啟」 | 通過（`USER_ALLOW_ONCE`） |
| 嚴格模式 | 通過：lock task 為 LOCKED；不在名單的 App 被系統拒絕；桌面、允許的 Chrome、撥號可用；錯誤密碼被拒；正確密碼離開；解除 Device Owner |
| 真實 WeChat／LINE／Bilibili／抖音／Shopee | **未測**（模擬器沒有這些 App 與帳號）；需要實機測試 |

## 10. 授權分析

- 守門員：**GPL-3.0**
- **BlockAds**（`pass-with-high-score/blockads-android`）：GPL-3.0，與守門員相容；它的 Go tunnel 另外用了 tun2socks（GPL-3.0）、gVisor（Apache-2.0）、miekg/dns（BSD-3）等
- 雖然授權相容，**守門員沒有複製 BlockAds 的任何程式碼**，也沒有用它的 AAR。只研讀了架構與 Android API 的用法，然後依 RFC 與 Android 文件重新實作，而且架構本身就不同（DNS-only vs 全流量）。參考與重寫的對照：

| 部分 | 來源 |
|---|---|
| VpnService 生命週期、Builder、always-on meta-data、onRevoke、per-app 排除 | 參考 BlockAds 的做法與 Android 文件，重新實作 |
| TUN 讀寫、IP/UDP/DNS 解析與組包、checksum | 依 RFC 791／8200／768／1035 自行實作 |
| 網域比對（排序 hash 陣列） | 自行設計（BlockAds 用 mmap trie + Bloom filter） |
| 清單解析、原子更新、驗證、還原 | 自行實作；刻意避開 BlockAds 的已知問題（`$modifier` 放大、覆寫使用中的 mmap 檔、沒有大小限制、靜默 DoH 降級、從被排除的 UID 探測 tunnel） |
| DoH | 依 RFC 8484 自行實作（`HttpURLConnection`） |
| 防止亂跳 App、嚴格模式 | 自行設計與實作（BlockAds 沒有這些功能） |

- 第三方資料：
  - StevenBlack hosts：MIT，隨 APK 附上一份，授權全文在 `android/app/src/main/assets/filters/LICENSE-stevenblack-hosts.txt`
  - AdGuard DNS filter、HaGeZi：GPL-3.0，不隨 APK 附上，只在使用者開啟後下載
- 第三方程式庫：沒有新增（只用 Android 框架與原本的 AndroidX／Compose，都是 Apache-2.0）
- **發佈 APK 時**：GPL-3.0 要求提供對應的原始碼（本 repo 公開即可），並保留授權聲明與 StevenBlack 的 MIT 授權檔

## 11. Google Play 政策（若將來要上架）

依目前的理解，上架前仍要以最新政策為準：

- **VPN 廣告封鎖**：Play 的「裝置和網路濫用」政策不允許 App 干擾其他 App 的廣告顯示，**VPN 廣告防護很可能不能上架**。上架版應移除廣告防護，只保留在側載／F-Droid 版
- **無障礙**：守門員不是無障礙工具（`isAccessibilityTool=false`）；Play 對非無障礙用途的使用有嚴格審查，需要聲明與影片說明，而且可能被拒
- **`QUERY_ALL_PACKAGES`**：防毒與安全類 App 屬於允許的核心用途，需要填寫聲明
- **前景服務 `specialUse`**：需要在 Play Console 說明用途
- **Device Owner**：只能用 adb 設定，不影響上架

建議：**Play 版**只保留 App 檢查、假通知偵測、彈出廣告偵測；**側載版**保留全部功能。

## 12. OEM 相容性

- 無障礙服務、前景服務、開機自動啟動在 OPPO／realme（ColorOS）、vivo（OriginOS）、小米、華為上常被系統停掉。守門員的做法：診斷頁以真實狀態顯示（例如「設定是開啟，但服務沒有在執行」），並引導到電池「不限制」；各品牌的「自動啟動」名稱不同，只做文字說明，不寫死單一品牌的流程
- Android 13 以上，自行安裝的 App 要先「允許受限制的設定」才能開無障礙
- 實機（vivo V2417 / Android 16、OPPO Reno10 Pro / Android 15）**尚未**測試 0.6.0
