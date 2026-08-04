# CI 簽章設定

`build-apk.yml` 沒設定 secret 也能跑，但**強烈建議設定**，原因如下。

## 為什麼需要

Android Gradle Plugin 在找不到 debug keystore 時會**即時產生一把隨機的**。CI 每次都是全新的環境，所以：

- 每次發版的 APK 簽章都不同
- 使用者要升級，必須先把舊版 App 整個移除（Android 不允許用不同簽章覆蓋安裝）
- 比分、隊伍名稱、藍牙對應等 `localStorage` 資料會一起消失

設定共用金鑰後，簽章跨次建置固定，使用者就能正常覆蓋升級。

另外一個更隱蔽的問題：**手機與手錶的 APK 簽章必須相同**，Wearable Data Layer 才會把兩者視為同一組 App。簽章不同時手錶按了不會有任何反應，也不會有錯誤訊息。同一次 CI 建置的兩個 APK 一定同簽章，所以這點在 CI 是安全的；但如果你混用「本機建的手機版 + CI 建的手錶版」就會踩到。workflow 裡有一步會主動驗證這件事。

## 產生金鑰

在專案外的安全位置執行（**不要放進 repo**，`.gitignore` 已擋 `*.jks`）：

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v `
  -keystore scoreboard.jks -alias scoreboard `
  -keyalg RSA -keysize 2048 -validity 10000
```

會問你金鑰庫密碼、姓名／單位等資訊。密碼記下來，等下要填進 secret。

> 這把金鑰請**永久保存並備份**。弄丟了就再也無法對現有使用者發佈可覆蓋升級的版本。

## 轉成 base64

GitHub secret 只能存文字，所以要把 `.jks` 編碼：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("scoreboard.jks")) | Set-Clipboard
```

## 設定 secrets

GitHub repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**，新增四個：

| Secret 名稱 | 內容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 上一步複製到剪貼簿的那串 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | keytool 問的「金鑰庫密碼」 |
| `ANDROID_KEY_ALIAS` | `scoreboard`（上面 `-alias` 給的值） |
| `ANDROID_KEY_PASSWORD` | 金鑰密碼；keytool 若沒單獨問就填與金鑰庫密碼相同 |

設好之後，workflow log 會顯示「已載入共用簽章金鑰」而不是警告。

## 本機要不要用同一把？

不用也沒關係，本機開發用各自的預設 debug keystore 最方便。只要記住：**本機建的 APK 和 CI 建的 APK 不能互相覆蓋升級，也不能一邊手機一邊手錶混搭**。

真要讓本機也用同一把，建置前設環境變數即可（`app/build.gradle` 與 `wear/build.gradle` 都會讀）：

```powershell
$env:ANDROID_KEYSTORE_PATH     = "C:\keys\scoreboard.jks"
$env:ANDROID_KEYSTORE_PASSWORD = "你的密碼"
$env:ANDROID_KEY_ALIAS         = "scoreboard"
$env:ANDROID_KEY_PASSWORD      = "你的密碼"
```
