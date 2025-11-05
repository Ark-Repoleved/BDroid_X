[![GitHub All Releases](https://img.shields.io/github/downloads/Ark-Repoleved/BDroid_X/total)](https://github.com/Ark-Repoleved/BDroid_X/releases) [![GitHub release (latest by date)](https://img.shields.io/github/downloads/Ark-Repoleved/BDroid_X/latest/total)](https://github.com/Ark-Repoleved/BDroid_X/releases/latest)

[English](./README.md) | [繁體中文](./README.zh-TW.md) | [實用度調查](https://github.com/Ark-Repoleved/bd2-android-mod-manager/discussions/5) | <a href="https://ko-fi.com/issekisaji">
    <img alt="Static Badge" align="top" src="https://raw.githubusercontent.com/Ark-Repoleved/bd2-android-mod-manager/refs/heads/main/.github/sparkle-mug.gif" height="24">
</a>

# BrownDust 2 Android 模組管理器

**直接在手機上管理並安裝《棕色塵埃2》模組。**

---

## ✨ 主要功能

*   **📱 無需電腦**：完全在您的 Android 裝置上完成模組解包、紋理轉換 (ASTC) 和重新打包。
*   **🧠 智慧模組分組**：自動掃描您的模組資料夾，並根據修改的目標遊戲檔案進行分組，避免衝突。
*   **⚡ 批次處理**：一次選取並安裝來自不同分組的多個模組，省時又省力。
*   **👀 動畫即時預覽**：安裝前長按任何模組，即可預覽其 Spine 動畫的實際效果。
*   **🔧 內建實用工具**：包含 Spine 圖集合併、遊戲資源檔解包等進階工具。

---

## 🚀 開始使用

### 系統需求
*   Android 8 或更高版本 (Android 16 以上在使用 shizutools 時可能有權限問題)。
*   已安裝最新版的《棕色塵埃2》官方遊戲。

### 安裝與設定

1.  **下載應用程式**：從 [Releases 頁面](https://github.com/Ark-Repoleved/bd2-android-mod-manager/releases)下載最新的 `.apk` 檔案並安裝。
2.  **選擇模組資料夾**：
    *   打開 app 並點擊 **"Select Mod Source Folder"** 按鈕。
    *   找到您存放所有模組的資料夾，然後點擊 **"使用這個資料夾"**。
    > **💡 建議：** 在手機根目錄建立一個專用資料夾 (例如：`.BD2_Mods`) 來存放所有模組，方便管理。

---

## 🛠️ 使用教學

### 1. 準備您的模組 (重要！)

為確保 App 能正確識別，您的模組必須遵循特定的資料夾結構。每個模組都需要有自己獨立的資料夾，且內部的檔案名稱**必須**與遊戲原始資產的名稱完全相同。

**範例結構：**
```
📁 .BD2_Mods/
├── 📁 Lathel_DarkKnight_IDLE/
│   ├── 📄 char000104.skel      (或 .json 骨架檔)
│   ├── 📄 char000104.atlas     (圖集定義檔)
│   └── 🖼️ char000104.png         (紋理貼圖)
│
└── 📁 Another_Mod/
    └── ... (其他模組檔案)
```

### 2. 安裝模組

<p align="center">
  <img src="https://raw.githubusercontent.com/Ark-Repoleved/bd2-android-mod-manager/main/guide_video.gif" width="250">
</p>

1.  **選擇模組**：在 App 主畫面中，勾選您想安裝的模組。
2.  **開始打包**：點擊右下角的 **Repack (✓)** 浮動按鈕。
3.  **等待處理**：App 將自動下載最新的遊戲原始檔案並重新打包您選擇的模組。進度會即時顯示在彈出視窗中。
4.  **手動替換檔案**：
    *   打包完成後，一個包含所有模組檔案的新資料夾 **`Shared`** 將會儲存到您手機的 **`Download`** 資料夾中。
    *   您必須**手動**將這個新的 `Shared` 資料夾移動到遊戲的快取目錄中，並覆蓋任何現有檔案。
    *   目標路徑為：`/Android/data/com.neowizgames.game.browndust2/files/UnityCache/`。
    > **注意：** 您需要使用第三方檔案管理器才能存取 `Android/data` 資料夾。為了方便操作，App 會提供一鍵複製的 ADB 指令，可搭配 [ShizuTools](https://github.com/legendsayantan/ShizuTools) 等工具自動執行替換。

---

## 🔧 其他工具

### Spine 動畫預覽
不確定模組動起來的效果如何？
1.  在列表中找到您想查看的模組。
2.  **長按**該模組。
3.  預覽視窗將會彈出並播放動畫。

### Spine 圖集合併工具 (問題排解專用)
**只有在安裝某個模組導致遊戲閃退時，才需要使用此工具。** 

閃退通常是因為該模組使用了太多紋理貼圖檔案 (例如 `_5.png`, `_6.png`)。此工具能將它們合併為較少、更穩定的檔案。
1.  在主畫面，找到導致閃退的模組，並**只勾選該模組**。
2.  點擊 **"Merge Spine"** 浮動按鈕。
3.  App 會自動處理檔案，原始檔案將被備份至新的 `.old` 子資料夾。
4.  重新安裝合併後的新模組，閃退問題應該就會解決。

### 獨立資源解包工具
供進階使用者提取遊戲原始檔案。
1.  確保主畫面上沒有勾選任何模組。
2.  點擊右下角的**解包 (📤)** 浮動按鈕。
3.  選擇您想解包的 `__data` 檔案。
4.  解包後的內容將儲存於 **`Download/outputs`** 資料夾。

---

## ❤️ 支持這個專案

如果您覺得這個工具對你有幫助，歡迎 <a href="https://ko-fi.com/issekisaji" style="text-decoration: none;">請我喝杯咖啡 <img alt="在 Ko-fi 上支持我" align="top" src="https://raw.githubusercontent.com/Ark-Repoleved/bd2-android-mod-manager/refs/heads/main/.github/sparkle-mug.gif" height="24"></a>，任何支持都會是我持續開發的動力！

---

## ❓ 常見問題

*   **Q: 為什麼我的模組沒有顯示在 App 裡？**
    *   **A:** 請再次確認您在初次設定時選擇了正確的「模組來源資料夾」。同時，確保您的模組都已解壓縮，並遵循正確的[資料夾結構](#1-準備您的模組-重要)。

*   **Q: 裝完模組後遊戲閃退或崩潰了怎麼辦？**
    *   **A:** 這通常是因為模組使用了過多的 `.png` 紋理檔案。請使用 App 內建的 **[Spine 圖集合併工具](#spine-圖集合併工具-問題排解專用)** 將紋理合併後，再重新安裝模組。

*   **Q: 安裝失敗了怎麼辦？**
    *   **A:** 安裝失敗的常見原因有：
        1.  網路連線不穩，導致無法下載遊戲原始檔。
        2.  模組內的檔案名稱不正確。
        3.  打包過程中發生非預期的錯誤。
        請檢查彈出視窗中的錯誤訊息以獲得更多資訊。

*   **Q: 安裝後遊戲內的畫面顯示不正常。**
    *   **A:** 這很可能是模組本身不完整，或與 Android 版不相容。一個完整的角色模組需要包含 `.png`、`.atlas` 以及 `.skel` (或 `.json`) 三種檔案。

---

## 🙏 致謝

本專案的完成離不開以下優秀的開源專案與工具：

*   **[browndust2-repacker-android](https://codeberg.org/kxdekxde/browndust2-repacker-android)**: 提供 `__data` 檔案重新打包、ASTC 紋理壓縮與 LZ4 壓縮的核心技術。
*   **[ReDustX](https://github.com/Jelosus2/ReDustX)**: 提供 `.json` 轉 `.skel` 以及從遊戲 CDN 下載原始檔案的機制。
*   **[UnityPy](https://github.com/K0lb3/UnityPy)**: 用於讀取、修改及儲存 Unity 遊戲資產的基礎 Python 函式庫。
*   **[astc-encoder](https://github.com/ARM-software/astc-encoder)**: 由 ARM 官方提供的 ASTC 紋理編碼器。

---

## ⭐ Stargazers over time
[![Stargazers over time](https://starchart.cc/Ark-Repoleved/BDroid_X.svg?variant=adaptive)](https://starchart.cc/Ark-Repoleved/BDroid_X)
