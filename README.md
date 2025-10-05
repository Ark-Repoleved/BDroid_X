[English](./README.md) | [繁體中文](./README.zh-TW.md)

---

# BrownDust 2 Android Mod Manager

No need for a PC to unpack and repack.

---

## Features

*   **Automatic Scan & Grouping**: Automatically scans your specified mod folder and intelligently groups mods based on the game files they modify.
*   **Batch Installation**: You can select and process multiple mods from the same group at once.
*   **On-Device Repacking**: Unpack, convert resources (ASTC), and repack mods directly on your phone without needing a computer.

## System Requirements

*   Android 11 or higher.
*   The latest official version of BrownDust 2 installed.

## Installation

1.  Download the `.apk` file for this application.
2.  Tap the downloaded `.apk` file to install.

## First-Time Setup

Before you can start installing mods, you need to complete a one-time authorization step:

1.  Open the app, and you will see a welcome screen.
2.  Tap the **"Select Mod Source Folder"** button.
3.  In the file picker that appears, navigate to where you store your mod files (e.g., `.zip` archives or extracted folders), and then tap **"Use this folder"**.
    *   *Recommendation:* Create a folder named `BD2_Mods` in your phone's storage and place all your downloaded mods there.

## How to Install Mods

1.  **Select Mods**: On the main screen list, check the one or more mods you want to install. Note that you can only batch-install mods belonging to the same group (Target).
2.  **Start Repacking**: Tap the floating action button with the **check mark (✓)** at the bottom right.
3.  **Provide the Original File**:
    *   A dialog will appear asking for the original `__data` file.
    *   You now have two options:
        *   **Download from Server (Recommended)**: Tap this button, and the app will automatically download the correct, latest version of the `__data` file from the game's official servers.
        *   **Select File Manually**: If you prefer, you can still manually locate the original `Android/data/com.neowizgames.game.browndust2/files/UnityCache/Shared/[hashed_name]/*/__data` file, copy it to your mod folder and select it using the file picker.
4.  **Wait for Processing**: The app will automatically perform the unpacking, compression, and repacking process in the background. Please wait patiently.
5.  **Manually Replace the File (Current Version)**:
    *   After a successful installation, a new, modified `__data` file will be saved to your phone's **`Download`** folder with the filename `__[hashed_name]`.
    *   **You need to manually copy or move this file to the corresponding game folder, overwriting the original `__data` file.**
    *   You can use a third-party file manager (with special permissions) to do this. The dialog will provide a one-click ADB command that can be executed via [ShizuTools](https://github.com/legendsayantan/ShizuTools) to automate the replacement.

## FAQ

*   **Q: Why aren't my mods showing up in the app?**
    *   A: Please ensure you have correctly selected the "Mod Source Folder". Also, make sure your mods are in `.zip` format or are unzipped folders.

*   **Q: What should I do if the installation fails?**
    *   A: The most common reason is providing the wrong original `__data` file in step 3. Please make sure the file you provide matches the `hashed_name` prompted by the app.

*   **Q: What if the graphics are corrupted after installing a mod and entering the game?**
    *   A: This is likely because the mod is missing the `.skel` or `.json` file. A complete mod requires `.png`, `.atlas`, and `.skel` or `.json` files to function correctly.

---

## Credits

The development of this application would not have been possible without the contributions of the following open-source projects and tools. Special thanks to:

*   **[browndust2-repacker-android](https://codeberg.org/kxdekxde/browndust2-repacker-android)**: Provided the core techniques for repacking `__data` files, including handling ASTC texture compression and LZ4 compression.
*   **[ReDustX](https://github.com/Jelosus2/ReDustX)**: Provided the core techniques for `.json` to `.skel` mechanism and download Original `__data` file from game's CDN.
*   **[UnityPy](https://github.com/K0lb3/UnityPy)**: A Python library that is fundamental for reading, modifying, and saving Unity game assets.
*   **[ARM-software/astc-encoder](https://github.com/ARM-software/astc-encoder)**: The official ASTC texture encoder from ARM, used to convert mod textures into a format compatible with the game system.
