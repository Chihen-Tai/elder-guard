# 守門員判定演算法（EGDA）v0.4 規格

> Elder Guard Detection Algorithm（Elder Guard 是本專案早期的暫名，英文名稱現為 fkAd）。取代 `REVIEW-STANDARD.md` v0.1 成為唯一的正式規格；`REVIEW-STANDARD.md` 改為給一般人看的白話摘要。
> 狀態：**參考實作完成（`engine/egda.py`，規則 `engine/rules.json` v0.4.1）。誤判門檻通過；靜態召回率 80%，未達 90% 門檻**（第 11 節）。
> 原則：全部在手機上離線計算；判定可以解釋；**寧可漏報，也不能誤判正常 App**（誤判會讓長輩不敢用手機，也會讓人不再相信守門員）。

---

## 1. 威脅模型

| 代號 | 目標 | 典型手法 | 實機案例 |
|---|---|---|---|
| **T-FIN** | 詐騙金流 App：假投資、假交易所、殺豬盤 | 從 Play 或 APK 安裝；有儲值、提領、紅利、質押、推薦佣金等功能 | TGC |
| **T-CHAT** | 詐騙用私密聊天 App | 可以轉帳的私密 IM，用來經營投資群組 | Finstar |
| **T-ADW** | 廣告流氓工具 App | 清理、救援、PDF、QR；App 外彈窗、推播轟炸、解鎖時跳廣告、程式混淆 | 兩支手機上共 20 個 |
| **T-SIDE** | 惡意側載 APK | 假冒政府機關或銀行的 App、遠端控制木馬；要求無障礙、簡訊、安裝 App 等權限 | （沒有實機案例，依公開報告設計） |
| **T-IMP** | 冒名 App | 名稱或圖示冒充 LINE、銀行、165、健保快易通，但簽章不符 | （同上） |
| **T-SUB** | 訂閱陷阱 | AI、工具類 App 的高價週訂閱 | Nova（疑似） |

**不在目標內**：有廣告但行為正常的 App（遊戲、新聞），最多判為「提醒」；系統內建 App 交給廠商處理。

---

## 2. 觸發時機與處理流程

```
            ┌──────────────── 觸發 ────────────────┐
 安裝工作建立 (SessionCallback.onCreated) ─► 階段 0：安裝前把關（只用中繼資料）
 安裝完成 (PACKAGE_ADDED / REPLACED)      ─┐
 定時備援 (15 分鐘 getChangedPackages)    ─┼─► 階段 1 中繼資料 ─► 階段 2 內容掃描 ─► 階段 3 判定 ─► 階段 5 呈現
 使用者按「現在檢查」(全機)              ─┘                                      ▲
 每日行為複評（有權限時）                 ──────────► 階段 4 行為訊號 ─────────────┘
```

- **快取**：以 `(套件名稱, versionCode, 簽章 SHA-256, lastUpdateTime)` 為鍵保存結果；鍵沒有改變就不重掃
- **App 更新時**：一律重新判定；使用者之前「保留」的決定只對同一版本有效

---

## 3. 階段 0：安裝前把關

在 `SessionCallback.onCreated` 取得 `SessionInfo`，此時 APK 還無法讀取，只能根據**安裝來源**判斷：

| 安裝者（`installerPackageName`、`InstallSourceInfo`） | 分類 | 動作 |
|---|---|---|
| `com.android.vending` | 官方商店 | 不提醒，等安裝完成後進入階段 1 |
| 手機廠商商店（`com.heytap.market`、`com.vivo.appstore`、`com.xiaomi.market`、`com.huawei.appmarket`、`com.sec.android.app.samsungapps`…） | 廠商商店 | 同上（階段 3 的來源分數略高於 Play） |
| 系統安裝程式（`com.google.android.packageinstaller`、`com.android.packageinstaller`），發起者是瀏覽器、通訊軟體或檔案管理 | **側載** | **立即彈窗（等級 P）**：「等一下！這個 App 不是從商店來的…如果是網頁、影片或別人叫您裝的，請按『取消』」 |
| 其他 App 自行安裝（例如某個 App 在背景安裝另一個 App） | **App 代裝** | 立即彈窗（等級 P） |

- 等級 P 只是**提醒**，不是判定；安裝完成後仍走完整流程
- 開發者用 `adb` 安裝（安裝者為 null）時不會觸發 session 事件，安裝後依「側載」處理

---

## 4. 階段 1＋2：特徵擷取

### 4.1 資料來源

| 來源 | 取得方式 | 用途 |
|---|---|---|
| 套件中繼資料 | `PackageManager.getPackageInfo(GET_ACTIVITIES｜GET_SERVICES｜GET_RECEIVERS｜GET_PROVIDERS｜GET_PERMISSIONS｜GET_SIGNING_CERTIFICATES｜GET_META_DATA)`、`getInstallSourceInfo` | 元件、權限、簽章、來源、類別、名稱 |
| 元件的 intent-filter | `queryBroadcastReceivers`、`queryIntentServices`，針對特定 action 查詢 | 自動觸發（F-TRIG）、無障礙或通知監聽服務宣告 |
| dex | 讀取 `sourceDir` 與 `splitSourceDirs` 中的 `classes*.dex`，**只解析 `string_ids` 與 `type_ids` 表** | 廣告 SDK、IM SDK、加殼、關鍵字 |
| 資源字串 | 解析 `resources.arsc` 的全域 string pool | 介面文字關鍵字（例如 Finstar 的「選擇提領帳戶」） |
| 原生程式庫與腳本 | `lib/*/*.so` 可列印字串（≥ 6 字元，UTF-8）、`assets/**/*.js`、`*.bundle`、Unity `global-metadata.dat` | Flutter、React Native、Unity 寫成的 App 的關鍵字（TGC 是 Flutter） |

**預算**：單一 App 的讀取上限 96 MB、執行時間上限 4 秒；超過就標記為 `partial`，只用已經取得的特徵（第 6.4 節）。

### 4.2 特徵定義

以下「命中」一律要求**比對字串完整出現**；比對時不分大小寫，中文比對繁簡兩種寫法。

**F-SRC 安裝來源**：`store_play`｜`store_oem`｜`sideload`（系統安裝程式、瀏覽器、通訊軟體、檔案管理、null）｜`app_installed`（其他 App 代裝）

**F-PERM 權限組合**（要求的權限，加上元件宣告）：
- `ctrl`（操控類）：宣告 `BIND_ACCESSIBILITY_SERVICE` 的服務、`BIND_DEVICE_ADMIN` 的接收器、`BIND_NOTIFICATION_LISTENER_SERVICE`、`REQUEST_INSTALL_PACKAGES`、`READ_SMS`／`RECEIVE_SMS`／`SEND_SMS`、`BIND_VPN_SERVICE`、`SYSTEM_ALERT_WINDOW`
- `snoop`（窺探類）：`PACKAGE_USAGE_STATS`、`MANAGE_EXTERNAL_STORAGE`、`QUERY_ALL_PACKAGES`、`READ_CALL_LOG`、`READ_CONTACTS`

**F-SDK 廣告聯播網**：比對 dex 的 type descriptor 前綴（詳見 `rules/ad_sdks.json`）
- 國際：AdMob `Lcom/google/android/gms/ads/`、AppLovin `Lcom/applovin/`、Unity `Lcom/unity3d/ads/`、ironSource `Lcom/ironsource/`、Liftoff/Vungle `Lcom/vungle/`、Mintegral `Lcom/mbridge/msdk/`、Pangle `Lcom/bytedance/sdk/openadsdk/`、InMobi `Lcom/inmobi/`、Chartboost `Lcom/chartboost/`、DT/Fyber `Lcom/fyber/`＋`Lcom/digitalturbine/`、Meta `Lcom/facebook/ads/`、Moloco `Lcom/moloco/`、Bigo `Lsg/bigo/ads/`、Yandex `Lcom/yandex/mobile/ads/`、PubMatic `Lcom/pubmatic/`、Amazon `Lcom/amazon/device/ads/`、Smaato `Lcom/smaato/`
- 聚合平台（本身就代表「接很多家廣告」）：AppLovin MAX `Lcom/applovin/mediation/`、TradPlus `Lcom/tradplus/`、TopOn `Lcom/anythink/`
- 中國：優量匯 `Lcom/qq/e/ads/`、快手 `Lcom/kwad/sdk/`、百度 `Lcom/baidu/mobads/`、Sigmob `Lcom/sigmob/`、京東 `Lcom/jd/ad/`、華為 `Lcom/huawei/hms/ads/`、小米 `Lcom/miui/zeus/`、OPPO `Lcom/heytap/msp/mobad/`、vivo `Lcom/vivo/mobilead/`
- `n_ad` = 命中的不同聯播網數量；`mediation` = 是否有聚合平台

**F-OOA App 外廣告結構**（Out-Of-App），需要**同時**滿足下列兩項：
- (a) 有**跳出用 Activity**：`excludeFromRecents=true`，`taskAffinity` 與 App 本身不同，**而且這個 Activity 位在亂碼命名空間**（F-OBF 的判準）；排除廣告 SDK 與已知函式庫的 Activity。v0.2 沒有「亂碼命名空間」這個條件，把 Candy Crush、Disney+、WeChat 的通知或分享跳轉誤判成彈窗
- (b) 有**觸發來源**：F-TRIG 命中，或宣告通知監聽服務
- 另外，App 自己命名空間中的 Activity 名稱符合 `/PopupAd|OutApp|OutSide|LockScreen|Charg(e|ing)Screen|Unlock|ScreenOn|Float(ing)?Window/i` 時，記為 `ooa_named`

**F-TRIG 自動觸發**：接收器監聽 `USER_PRESENT`、`SCREEN_ON`、`SCREEN_OFF`、`ACTION_POWER_CONNECTED`／`DISCONNECTED`、`BATTERY_LOW`／`OKAY`、`BOOT_COMPLETED`、`PACKAGE_ADDED`；並且要求 `FOREGROUND_SERVICE`。`n_trig` = 命中的種類數

**F-OBF 元件名稱混淆**：只看 manifest 中**屬於 App 自己命名空間**的元件（Android 規定元件名稱不能被 ProGuard 改名，所以正常 App 的元件名稱都是有意義的）：
1. 把簡名依 CamelCase 拆成詞，去掉 `Activity`、`Service`、`Receiver`、`Provider` 等字尾
2. 每個詞與英文字典（約 5 萬字）、漢語拼音音節表、常見縮寫表比對
3. 長度 ≥ 5 且無法切分成字典詞（≥ 3 字母）或拼音音節的詞 → 「亂碼詞」；**套件路徑的每一段也要檢查**（垃圾 App 常把元件放在 `famifou.rigtran.belttag…` 這種假路徑下）
4. 一個元件含有 **≥ 2 個亂碼詞**才算「亂碼元件」（只有一個品牌縮寫，例如某家公司的縮寫名稱，不算）
5. 排除已知第三方函式庫後，App 的元件 ≥ 4 個，且其中 ≥ 30% 是亂碼元件 → `obf=true`
- 案例：`ArraequitActivity`、`BehteaActivity`、`ColhibiActivity`

**F-PACK 加殼**：dex 中出現殼的特徵（`Lcom/qihoo/util/`、`Lcom/stub/StubApp`、`libjiagu`、`libshell`、`libsecexe`、`Lcom/secneo/`、`Lcom/tencent/StubShell/`、`Lcom/bangcle/`），或 dex 很小但原生程式庫很大 → `packed=true`

**F-CAT 常見誘騙類型**：名稱、套件名稱、`ApplicationInfo.category` 與關鍵字表比對（中英文）：
清理（清理、垃圾、加速、clean、booster、junk）、防毒（防毒、病毒、antivirus、security scan）、省電（省電、電池、battery saver）、救援（救援、恢復、復原、recover、restore、undelete）、文件（PDF、文件、文檔、閱讀器、reader、viewer、docx、office）、QR（QR、掃描、條碼、scanner、barcode）、手電筒、VPN、Wi-Fi 工具、**GPS／指南針**、**相簿保險箱**（v0.3 新增） → `cat_bait=true`，並記錄類別

**F-FIN 詐騙金流字串群**（在 dex 字串、資源字串、原生字串、腳本中搜尋）：
| 群 | 關鍵字（節錄） |
|---|---|
| G1 儲值 | 儲值、充值、入金、deposit、recharge、top up、Buy USDT |
| G2 提領 | 提領、提現、出金、withdraw、withdrawal address |
| G3 紅利／保證獲利 | 首儲、首充紅利、保證獲利、穩賺、first deposit bonus、guaranteed profit、daily profit |
| G4 質押／礦池 | 質押、礦池、挖礦、staking、mining pool、liquidity mining |
| G5 推薦佣金 | 邀請碼、推薦獎勵、返傭、invitation code、referral、commission |
| G6 凍結／稅金 | 凍結資金、解凍、保證金、稅金、freeze funds、unfreeze、margin call |
| G7 虛擬幣 | USDT、TRC20、ERC20、wallet address、錢包地址 |
- `n_fin` = 命中的群數

**F-IM 私密通訊**：有 IM SDK（騰訊 IM `Lcom/tencent/imsdk/`、融雲 `Lio/rong/`、網易雲信 `Lcom/netease/nimlib/`、環信 `Lcom/hyphenate/`、OpenIM `Lio/openim/`）或聊天字串群（群組、好友、聊天、群成員、group、friend），**同時**有錢包字串（餘額、轉帳、提領帳戶、紅包、wallet、transfer）→ `im_wallet=true`；再加上閱後即焚、聊天密碼字串 → `im_secret=true`

**F-IMP 冒名**：名稱與受保護品牌表（LINE、各家銀行、165、健保快易通、政府機關、Google、郵局…）的相似度 ≥ 0.8（去除空白與符號後的編輯距離），**但**簽章不在該品牌的憑證清單中 → `impersonation=true`

**F-SUBS 訂閱陷阱**：有 Google Play Billing（`Lcom/android/billingclient/`），並且有「每週、週訂閱、weekly、per week、free trial、3-day trial」字串，而且屬於 AI 或工具類 → `weekly_sub=true`

**F-BEH 行為**（有權限時才計算）：
- `notif_rate`：過去 24 小時的通知數（需要通知存取）
- `notif_sync`：與其他 App 在 **5 秒內**同時推播 ≥ 3 次（同一個推播網路）
- `never_opened`：安裝超過 24 小時，但 `lastTimeUsed == 0`（需要使用情況存取）

---

## 5. 階段 3：判定

### 5.1 否決規則（任何一條成立 → **D 危險**）

| 規則 | 條件 | 理由文字（顯示給使用者） |
|---|---|---|
| **V1 詐騙金流** | `n_fin ≥ 3`，而且（G1 或 G2），而且（**G4 質押或 G7 虛擬幣**），而且不是 T1 可信開發者 | 它有儲值、提領、虛擬貨幣投資的功能，這是投資詐騙常用的手法 |
| **V2 私密金流聊天** | `im_wallet ∧ im_secret`，而且不是 T1 | 它是可以轉帳的私密聊天 App，詐騙集團常用它帶人進投資群組 |
| **V3 危險側載** | F-SRC ∈ {sideload, app_installed}，而且 `ctrl` 權限中命中無障礙、裝置管理員、簡訊、安裝 App 任一項 | 它不是從官方商店來的，還要求可以操控手機的權限 |
| **V4 冒名** | `impersonation` | 它的名字很像「{品牌}」，但不是{品牌}官方的 App |

### 5.2 廣告流氓分數

| 代號 | 條件 | 分數 | 理由文字 |
|---|---|---|---|
| A1 | `n_ad ≥ 7` 得 3 分；4–6 得 2 分；2–3 得 1 分；另外 `mediation` 加 1 分（上限 4 分） | 0–4 | 裡面有 {n} 家廣告公司的程式 |
| A2 | F-OOA 成立得 3 分；只有 `ooa_named` 得 1 分 | 0–3 | 它會在其他畫面上跳出廣告 |
| A3 | `n_trig ≥ 2` 得 2 分；`n_trig = 1` 得 1 分 | 0–2 | 它會在您打開手機時自己跑出來 |
| A4 | `obf` | 2 | 它刻意把自己的程式藏起來 |
| A5 | `cat_bait` | 1 | {類別}這類 App 常被拿來騙人 |
| A6 | `cat_bait`，而且 `snoop` 或 `ctrl` 中命中 ≥ 1 項 | 3 | 它要求的權限跟它的功能對不上 |
| A7 | `notif_rate ≥ 5` 且 `never_opened`，或 `notif_sync` | 3 | 它一直發通知給您 |
| A8 | F-SRC ∈ {sideload, app_installed} | 2 | 它不是從官方商店下載的 |
| A9 | `packed` | 2 | 它的程式被加密包起來，看不到裡面在做什麼 |
| A10 | `weekly_sub` | 2 | 它可能會用每週訂閱的方式收費 |

`score = Σ A1…A10`；`abuse = A2 ≥ 1 ∨ A3 ≥ 1 ∨ A4 ∨ A6 ∨ A7 ∨ A9`（至少有一項「不只是廣告多」的濫用行為）

| 條件 | 等級 |
|---|---|
| `score ≥ 7` **且** `abuse` | **C 要小心** |
| `score ≥ 4`，或 `n_ad ≥ 4` | **N 提醒** |
| 其他 | **S 安全** |

設計理由：「廣告多」本身不算錯（Candy Crush 有 13 家）；只有在**廣告多，而且有濫用行為**時才打擾使用者。

### 5.3 信任調整（最後套用）

| 代號 | 條件 | 效果 |
|---|---|---|
| T1 可信開發者 | 簽章 SHA-256 在 `rules/trusted_certs.json`（Google、LINE、Meta、各家銀行、政府機關、電信業者…） | V1、V2 不成立；等級最多到 **N** |
| T2 遊戲 | `category == GAME`，或安裝在遊戲目錄，而且 A2 = A6 = A9 = 0 | 最多到 **N** |
| T3 系統 App | `FLAG_SYSTEM`，而且沒有被更新成非廠商簽章 | 不判定 |
| T4 使用者保留 | 同一套件、同一版本、同一簽章，使用者選擇過「保留」 | C 不再彈窗；D **3 天後再提醒一次**，之後每 30 天提醒一次 |
| T5 自己 | 守門員本身，以及同一份規則清單中列出的安全工具 | 不判定 |

### 5.4 理由的產生
- 依分數由高到低，取最多 3 條理由；否決規則的理由永遠排第一
- 內部保留完整的稽核字串，例如 `C score=11 A1=3 A2=3 A5=1 A6=3 A3=1 src=play n_ad=10 rules=v0.2`

---

## 6. 穩健性

### 6.1 誤判防護（依重要性排序）
1. **任何 C 或 D 都需要至少兩個獨立證據來源**，只有否決規則例外（V1 本身就要求 3 群字串）
2. T1 使用**憑證指紋**，不用名稱（名稱可以冒用）
3. 關鍵字必須完整出現；英文關鍵字要比對詞的邊界（例如 `deposit` 不能命中 `depositor`，也不能從無關的長字串中擷取）
4. **正常 App 零誤判**是驗收門檻；任何反例被判成 C 或 D，都必須先修正規則才能發布

### 6.2 已知的規避手法與對策
| 手法 | 對策 |
|---|---|
| 廣告 SDK 動態下載或反射載入 | A7 行為訊號；A9 加殼 |
| 關鍵字加密 | 金流 App 必須把文字顯示給使用者，所以資源字串或原生字串中仍會出現 |
| 改用新的廣告聯播網 | 規則表隨版本更新 |
| 名稱改得很正常 | A5 只佔 1 分，其他條件不看名稱 |
| 先上架乾淨版本，更新後才變壞 | App 更新時一律重新判定 |

### 6.3 效能
- dex 只解析字串表，用 **Aho-Corasick** 一次比對所有特徵，時間複雜度 O(字串總長)
- 目標：單一 App ≤ 2 秒（M0 的簡單搜尋，TikTok 要 60 秒）；全機 100 個 App ≤ 60 秒
- 掃描在 JobService 或前景服務中執行（M0 發現 A：Activity 程序會被凍結）

### 6.4 無法完整掃描時
- `partial`（超過預算或讀取失敗）：只用已取得的特徵判定，最高只能判到 **N**，除非否決規則已經成立
- 理由中註明「部分內容無法檢查」

---

## 7. 階段 4：行為複評
- 每日一次；只在使用者開啟對應權限時才執行（通知存取、使用情況存取皆為選用）
- 只在手機上統計數量與時間，**不讀取也不保存通知內容**

## 8. 階段 5：呈現
| 等級 | 呈現方式 |
|---|---|
| P（安裝前） | 立即彈窗（可以蓋在畫面上時就蓋上去，否則用全螢幕通知） |
| D | 立即彈窗；主要按鈕「移除」；加上「撥打 165」；保留需二次確認 |
| C | 彈窗；主要按鈕「移除」 |
| N | 不彈窗，只列在首頁 |
| S | 不顯示 |

## 9. 規則資料與版本
- `rules/*.json`：`ad_sdks`、`im_sdks`、`packers`、`fin_keywords`、`bait_categories`、`trusted_certs`、`protected_brands`、`weights`、`thresholds`
- 每份規則都有版本號；規則打包在 App 中，**不從遠端下載**
- 修改規則之後必須重新跑第 10 節的驗證

## 10. 驗證方法

| 資料集 | 來源 | 門檻 |
|---|---|---|
| POS-ADW 廣告流氓 | OPPO 4 個 + vivo 18 個（今天移除的 App，已備份 APK） | ≥ 90% 判為 C 或 D |
| POS-FIN 詐騙 | TGC、Finstar | 100% 判為 D |
| NEG 正常 App | 兩支手機上仍安裝的正常 App（通訊、銀行、政府、Google、遊戲） | **0 個判為 C 或 D**；遊戲最多判為 N |
| EXT 外部樣本 | 沒有參與設計的新樣本（M1.5） | 同上，另外記錄召回率 |

- **過度擬合的風險**：規則是參考正例設計出來的，所以正例的召回率會偏樂觀；反例沒有參與設計，誤判率比較可信。**真正的召回率要看外部樣本**
- 參考實作：`engine/egda.py`（Python，電腦端），Android 版要與它**逐一比對結果**（golden test）

---

## 11. 驗證結果與修改紀錄

詳細結果：`engine/eval_v0.2.0.txt`、`engine/eval_v0.3.0.txt`；方法見第 10 節。**T1 可信開發者白名單在驗證時關閉**；否則把反例的憑證加入白名單，誤判率一定是 0，測試就失去意義。

| 版本 | 廣告流氓召回（C/D） | 詐騙判為 D | 正常 App 誤判（C/D） | 結論 |
|---|---|---|---|---|
| v0.2.0 | 12/15（80%） | 2/2 | **9/53** | 未通過 |
| **v0.3.0** | **14/15（93%）** | **2/2** | **0/53**（S 45、N 8） | 開發資料集通過 |

v0.2 → v0.3 的修改（每一項都有對應的錯誤案例）：
1. **V1 必須包含虛擬幣或質押字串群**：WeChat、Yahoo、Trip.com、高德地圖、Google Home、一個會員 App 都有一般支付用語（儲值、提領、邀請碼、凍結），被誤判為詐騙（6 個 D）
2. **A2 的彈出 Activity 必須位在亂碼命名空間**：正常 App 用同樣的旗標做通知或分享跳轉（Candy Crush、Disney+、Facebook Lite）
3. **一個元件至少要有 2 個亂碼詞才算混淆**，並擴充第三方函式庫與字典：一個品牌名稱就被判成混淆（例如電信業者、醫院、旅遊網站的 App）
4. **新增 GPS／指南針、相簿保險箱兩種誘騙類型**：漏報 2 個 GPS App 與 Galleryit
5. **移除遊戲引擎特徵 `Lcom/king/`**：誤中別的函式庫，讓 Galleryit 被當成遊戲
6. 工具錯誤（冒煙測試抓到的）：布林屬性沒有引號導致解析錯誤；讀取順序讓大型原生程式庫擠掉 dex，被誤標為 partial

**限制（必須說清楚）：**
- **規則是看著這批資料調整的**。修改 1–3 用到了反例的錯誤資訊，修改 4 用到了正例的漏報資訊，所以 93% 與 0/53 都**偏樂觀**
- **下一步必須用沒有參與調整的資料測試**：(a) OPPO 手機上的正常 App（獨立的反例測試集）；(b) 新的外部垃圾 App 樣本（獨立的正例測試集）
- 剩下的漏報「QR Code Reader」（6 分）靜態特徵溫和，要靠行為訊號（A7 推播轟炸）才能抓到
- 標記（「垃圾」「正常」）是依照 2026-09-25 的人工判斷，不是外部的標準答案
- 參考實作在電腦上每個 App 約需 5–10 秒（Python）；Android 版要依第 6.3 節最佳化

### 11.1 v0.4：用 OPPO 手機當獨立測試集之後（2026-09-25）

**v0.3 在獨立測試集上的結果**（規則先凍結，雜湊記錄在 `engine/frozen_v0.3.0.sha256`；結果見 `eval_heldout_oppo_v0.3.0.txt`）：
- 兩支手機都有的 App：0/15 誤判
- **只有 OPPO 上才有的 App：2/9 誤判** ❌ → 開發資料集上的 0/53 確實偏樂觀
  - 修改版 YouTube 用戶端 → V1 詐騙金流：命中的是除錯訊息「credential deposit failed」、指令名稱 `action.withdraw_access`，以及 Shorts 特效說明「輕觸螢幕挖礦」
  - microG → V3 危險側載：側載，而且要求讀取簡訊（見 11.2）

**v0.4 的修改（都是依原則修正，沒有針對個別 App 加例外）：**
1. **V1、V2、A10 只看使用者看得到的文字**（資源字串、Flutter `libapp.so`、React Native bundle、Unity metadata、網頁資源），不看程式內部字串。詐騙 App 一定要把「儲值、提領」顯示給使用者看，所以不會因此漏掉（TGC 仍然 7 群全中）
2. 英文關鍵字的邊界排除 `_`、`.`、數字（識別字不算詞）
3. 移除籠統的「挖礦」
4. v0.4.1 修正 v0.4 的回歸錯誤：套件名稱中的「.」是分隔符號，比對類別前要換成空白（否則 `map.ly.gps` 比對不到 `gps`）

| 版本 | 廣告流氓召回 | 詐騙 D | 開發集誤判 | OPPO 重複 App 誤判 | OPPO 新 App 誤判 |
|---|---|---|---|---|---|
| v0.3.0 | 14/15（93%）＊ | 2/2 | 0/53 | 0/15 | **2/9** |
| v0.4.0 | 11/15（73%） | 2/2 | 0/53 | — | —（有回歸錯誤） |
| **v0.4.1** | **12/15（80%）** | **2/2** | **0/53** | **0/15** | **1/9**（microG，見 11.2） |

＊**v0.3 的 93% 被一個誤中的特徵灌水**：兩個 GPS App 的 A10「週訂閱」命中的是廣告 SDK 內建 JavaScript 的驗證字串（`frequency must be one of: "daily", "weekly"…`），不是給使用者看的訂閱文字。修正後這 2 分消失，**80% 才是可信的靜態召回率**。

**沒有做的事**：沒有為了回到 90% 而降低門檻或新增加分項。那是依結果調參數，只會讓數字好看。
- 剩下 3 個漏報（兩個 GPS App、一個 QR 掃描器）的共同點：廣告多（5–9 家）、有自動觸發，但靜態分析找不到濫用證據。它們都判為 **N（首頁提醒）**，不是 S
- → **靠行為訊號 A7（推播頻率、從未開啟）補足**；第二階段在實機上驗證

**注意**：OPPO 的「新 App」在 v0.4 修正時已經被看過（YouTube 的誤判促成了修改 1–3），**所以它已經不算獨立測試集了**。下一次驗證需要新的資料：更多手機、更多外部垃圾 App 樣本。

### 11.2 待決定：側載且要讀簡訊的 App（V3）
- microG 是正常的開源軟體，使用者刻意安裝；但「**不是從商店來的 App 要讀簡訊**」正是台灣最常見的銀行木馬手法（竊取簡訊驗證碼）
- 目前設計：判為 **D 危險**；使用者可以選擇「保留」（T4），之後不再彈窗，但 3 天後會再提醒一次
- 替代方案：只讀簡訊（沒有無障礙、裝置管理員）時改判 C

---

**公開版說明（2026-09-25）**：`engine/frozen_v*.sha256` 記錄的是當時凍結的檔案雜湊。公開前，`egda.py` 只改了兩處：`aapt2`／`apksigner` 改成從環境變數或 PATH 找，另外改了兩行註解。判斷邏輯沒有變，所以 `egda.py` 的雜湊和凍結紀錄不同。`rules.json` 沒有改，雜湊仍然一致。

