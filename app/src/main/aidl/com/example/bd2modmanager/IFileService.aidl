package com.example.bd2modmanager;

interface IFileService {
    boolean copyFile(String sourcePath, String destPath);
    boolean copyDirectory(String sourceDirPath, String destDirPath);
    void destroy();
}
