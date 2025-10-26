[English](./README.md) | [繁體中文](./README.zh-TW.md) | [➡️ Usability Survey](https://github.com/Ark-Repoleved/bd2-android-mod-manager/discussions/5)

# BrownDust 2 Android Mod Manager

**Manage and install your BrownDust 2 mods directly on your phone. No PC required.**

---

## ✨ Key Features

*   **📱 PC-Free Operation**: Unpacks, converts textures (ASTC), and repacks mods entirely on your Android device.
*   **🧠 Smart Mod Grouping**: Automatically scans your mod folder and groups mods by the game files they modify, preventing conflicts.
*   **⚡ Batch Processing**: Select and install multiple mods from different groups in a single operation.
*   **👀 Live Animation Preview**: Long-press any mod to preview its Spine animation before you install it.
*   **🔧 Built-in Utilities**: Includes tools to merge texture atlases and unpack game bundles.

---

## 🚀 Getting Started

### Requirements
*   Android 8 or higher (Android 16+ may have permission problem).
*   The latest official version of Brown Dust 2 installed.

### Installation & Setup

1.  **Download the App**: Grab the latest `.apk` from the [Releases page](https://github.com/Ark-Repoleved/bd2-android-mod-manager/releases) and install it.
2.  **Grant Permissions**: Open the app. You'll be prompted to grant file access permissions.
3.  **Select Mod Folder**:
    *   Tap the **"Select Mod Source Folder"** button.
    *   Navigate to the folder where you store your mods and tap **"Use this folder"**.
    > **💡 Recommendation:** For best results, create a dedicated folder (e.g., `.BD2_Mods`) in your phone's internal storage and place all your mod files there.

---

## 🛠️ How to Use

### 1. Prepare Your Mods (Important!)

For the app to recognize your mods, they must follow a specific structure. Each mod needs its own folder, and the filenames inside must **exactly match** the game's original asset names.

**Example Folder Structure:**
```
📁 .BD2_Mods/
├── 📁 Lathel_DarkKnight_IDLE/
│   ├── 📄 char000104.skel      (or .json for the skeleton)
│   ├── 📄 char000104.atlas     (the atlas mapping file)
│   └── 🖼️ char000104.png         (the texture image)
│
└── 📁 Another_Mod/
    └── ... (other mod files)
```

### 2. Install Mods

<p align="center">
  <img src="https://raw.githubusercontent.com/Ark-Repoleved/bd2-android-mod-manager/main/guide_video.gif" width="250">
</p>

1.  **Select Mods**: In the app, check the boxes for the mods you want to install.
2.  **Start Repacking**: Tap the floating **check mark (✓)** button at the bottom right.
3.  **Wait for Processing**: The app will automatically download the necessary original game files and repack your selected mods. A dialog will show the live progress.
4.  **Manually Replace File**:
    *   Once complete, the modified game file (e.g., `__0216fs6...`) will be saved to your phone's **`Download`** folder.
    *   You must **manually move** this new `__data` file into the game's data directory, overwriting the original.
    *   The path is typically: `/Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/[Hash]/*/__data`.
    > **Note:** You will need a third-party file manager that can access `Android/data` folders. For easier access, the app provides a one-click ADB command that can be used with tools like [ShizuTools](https://github.com/legendsayantan/ShizuTools).

---

## 🔧 Other Tools

### Spine Animation Preview
Not sure what a mod looks like in action?
1.  Find the mod in the list.
2.  **Long-press** on it.
3.  A preview screen will open, playing the animation.

### Spine Atlas Merger (Troubleshooting Tool)
**Use this tool ONLY if a mod causes the game to crash after installation.** Crashes are often caused by mods that use too many texture files (e.g., `_5.png`, `_6.png`). This tool merges them into a less, more stable file.
1.  On the main screen, find the mod that is causing the crash. Select **only that mod**.
2.  Tap the floating action button, then tap the **"Merge Spine"** button.
3.  The app will process the files. The originals will be backed up into a new `.old` subfolder.
4.  Re-install the newly merged mod. The crash should be resolved.

### Standalone Bundle Unpacker
For users who want to extract original game files:
1.  Make sure no mods are selected on the main screen.
2.  Tap the floating **unarchive (📤)** icon.
3.  Select the `__data` file you want to unpack.
4.  The extracted contents will be saved to the **`Download/outputs`** folder.

---

## ❓ FAQ

*   **Q: My mods aren't showing up!**
    *   **A:** Double-check that you've selected the correct "Mod Source Folder" in the setup. Also, ensure your mods are in unzipped folders and follow the correct [folder structure](#1-prepare-your-mods-important).

*   **Q: The game crashes after installing a mod.**
    *   **A:** This often happens when a mod uses too many separate texture (`.png`) files. Please use the built-in **[Spine Atlas Merger](#spine-atlas-merger)** tool to combine the textures into a single file before installing the mod.

*   **Q: The installation failed. What should I do?**
    *   **A:** Failures are usually caused by:
        1.  Poor network connection (failed to download original files).
        2.  Incorrect mod filenames.
        3.  An unexpected repacking error.
        Check the error message in the dialog for clues.

*   **Q: The in-game graphics are broken after installing a mod.**
    *   **A:** This usually means the mod is incomplete or not compatible with the Android version. A complete character mod requires three files: `.png`, `.atlas`, and either `.skel` or `.json`.

---

## 🙏 Credits

This tool was made possible by these incredible open-source projects:

*   **[browndust2-repacker-android](https://codeberg.org/kxdekxde/browndust2-repacker-android)**: For the core repacking and texture compression techniques.
*   **[ReDustX](https://github.com/Jelosus2/ReDustX)**: For the `.json`-to-`.skel` conversion logic and CDN download methods.
*   **[UnityPy](https://github.com/K0lb3/UnityPy)**: For reading and modifying Unity game assets.
*   **[astc-encoder](https://github.com/ARM-software/astc-encoder)**: For the official ASTC texture encoder.
