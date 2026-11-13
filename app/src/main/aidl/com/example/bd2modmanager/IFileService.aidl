package com.example.bd2modmanager;

interface IFileService {
    boolean copyDirectory(String sourceDirPath, String destDirPath);
    void destroy();
}
