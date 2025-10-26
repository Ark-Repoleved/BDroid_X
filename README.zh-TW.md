[English](./README.md) | [繁體中文](./README.zh-TW.md) | [➡️ 實用度調查](https://github.com/Ark-Repoleved/bd2-android-mod-manager/discussions/5)

# BrownDust 2 Android 模組管理器

**直接在手機上管理並安裝《棕色塵埃2》模組，無需電腦。**

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
*   Android 11 或更高版本。
*   已安裝最新版的《棕色塵埃2》官方遊戲。

### 安裝與設定

1.  **下載應用程式**：從 [Releases 頁面](https://github.com/Ark-Repoleved/bd2-android-mod-manager/releases)下載最新的 `.apk` 檔案並安裝。
2.  **授予權限**：開啟 App，並依照提示授予必要的檔案存取權限。
3.  **選擇模組資料夾**：
    *   點擊 **"Select Mod Source Folder"** 按鈕。
    *   找到您存放所有模組的資料夾，然後點擊 **"使用這個資料夾"**。
    > **💡 建議：** 在手機根目錄建立一個專用資料夾 (例如：`.BD2_Mods`) 來存放所有模組，方便管理。

---

## 🛠️ 使用教學

### 1. 準備您的模組 (重要！)

為確保 App 能正確識別，您的模組必須遵循特定的資料夾結構。每個模組都需要有自己獨立的資料夾，且內部的檔案名稱**必須**與遊戲原始資產的名稱完全相同。

**範例結構：**
```
📁 .BD2_Mods/
├── 📁 拉泰爾_闇黑騎士/
│   ├── 📄 char000104.skel      (或 .json 骨架檔)
│   ├── 📄 char000104.atlas     (圖集定義檔)
│   └── 🖼️ char000104.png         (紋理貼圖)
│
└── 📁 另一個模組/
    └── ... (其他模組檔案)
```

### 2. 安裝模組

<p align="center">
  <img src="https://raw.githubusercontent.com/Ark-Repoleved/bd2-android-mod-manager/main/guide_video.gif" width="250">
</p>

1.  **選擇模組**：在 App 主畫面中，勾選您想安裝的模組。
2.  **開始打包**：點擊右下角的 **打勾 (✓)** 浮動按鈕。
3.  **等待處理**：App 將自動下載最新的遊戲原始檔案並重新打包您選擇的模組。進度會即時顯示在彈出視窗中。
4.  **手動替換檔案**：
    *   打包完成後，修改過的遊戲檔案 (例如 `__0216fs6...`) 會儲存到您手機的 **`Download`** 資料夾。
    *   您必須**手動**將這個新的 `__data` 檔案移動到遊戲的資料目錄中，並覆蓋原始檔案。
    *   遊戲路徑通常位於：`/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/[Hash]/*/__data`。
    > **注意：** 您需要使用第三方檔案管理器才能存取 `Android/data` 資料夾。為了方便操作，App 會提供一鍵複製的 ADB 指令，可搭配 [ShizuTools](https://github.com/legendsayantan/ShizuTools) 等工具自動執行替換。

---

## 🔧 其他工具

### Spine 動畫預覽
不確定模組動起來的效果如何？
1.  在列表中找到您想查看的模組。
2.  **長按**該模組。
3.  預覽視窗將會彈出並播放動畫。

### Spine 圖集合併工具 (Spine Atlas Merger)
如果一個模組包含多個紋理貼圖 (`.png` 檔案)，例如 `char_2.png`、`char_3.png`，容易導致遊戲閃退。此工具可以將它們合併成單一檔案以提升穩定性。
1.  在主畫面，**只勾選一個**您想合併的多圖檔模組。
2.  點擊浮動按鈕，然後選擇 **"Merge Spine"**。
3.  App 會自動處理。原始的 `.png` 和 `.atlas` 檔案會被備份到一個新的 `.old` 子資料夾中。
4.  完成後，您就可以正常安裝這個合併過後的模組了。

### 獨立資源解包工具
供進階使用者提取遊戲原始檔案。
1.  確保主畫面上沒有勾選任何模組。
2.  點擊右下角的**解包 (📤)** 浮動按鈕。
3.  選擇您想解包的 `__data` 檔案。
4.  解包後的內容將儲存於 **`Download/outputs`** 資料夾。

---

## ❓ 常見問題

*   **Q: 為什麼我的模組沒有顯示在 App 裡？**
    *   **A:** 請再次確認您在初次設定時選擇了正確的「模組來源資料夾」。同時，確保您的模組都已解壓縮，並遵循正確的[資料夾結構](#1-準備您的模組-重要)。

*   **Q: 裝完模組後遊戲閃退或崩潰了怎麼辦？**
    *   **A:** 這通常是因為模組使用了過多的 `.png` 紋理檔案。請使用 App 內建的 **[Spine 圖集合併工具](#spine-圖集合併工具-spine-atlas-merger)** 將紋理合併成單一檔案後，再重新安裝模組。

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
