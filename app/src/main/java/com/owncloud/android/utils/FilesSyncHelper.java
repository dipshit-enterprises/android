/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2020 Chris Narkiewicz <hello@ezaquarii.com>
 * SPDX-FileCopyrightText: 2017 Mario Danic <mario@lovelyhq.com>
 * SPDX-FileCopyrightText: 2017 Nextcloud GmbH
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 */
package com.owncloud.android.utils;

import android.accounts.Account;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import com.nextcloud.client.account.UserAccountManager;
import com.nextcloud.client.device.BatteryStatus;
import com.nextcloud.client.device.PowerManagementService;
import com.nextcloud.client.jobs.BackgroundJobManager;
import com.nextcloud.client.jobs.FilesSyncWork;
import com.nextcloud.client.jobs.upload.FileUploadHelper;
import com.nextcloud.client.network.ConnectivityService;
import com.owncloud.android.MainApp;
import com.owncloud.android.datamodel.FileSystemDataSet;
import com.owncloud.android.datamodel.FilesystemDataProvider;
import com.owncloud.android.datamodel.MediaFolderType;
import com.owncloud.android.datamodel.SyncedFolder;
import com.owncloud.android.datamodel.SyncedFolderProvider;
import com.owncloud.android.datamodel.UploadsStorageManager;
import com.owncloud.android.db.OCUpload;
import com.owncloud.android.db.ProviderMeta;
import com.owncloud.android.db.UploadResult;
import com.owncloud.android.lib.common.utils.Log_OC;

import org.lukhnos.nnio.file.FileVisitResult;
import org.lukhnos.nnio.file.Path;
import org.lukhnos.nnio.file.Paths;
import org.lukhnos.nnio.file.SimpleFileVisitor;
import org.lukhnos.nnio.file.attribute.BasicFileAttributes;
import org.lukhnos.nnio.file.Files;

import java.io.File;
import java.io.IOException;

import static com.owncloud.android.datamodel.OCFile.PATH_SEPARATOR;

/**
 * Various utilities that make auto upload tick
 */
public final class FilesSyncHelper {
    public static final String TAG = "FileSyncHelper";

    public static final String GLOBAL = "global";

    private FilesSyncHelper() {
        // utility class -> private constructor
    }

    // Must run completely to make FilesSyncWork persistent
    public static void insertNewFilesIntoFSDatabase(
        String folderPath,
        SyncedFolder syncedFolder,
        FilesystemDataProvider filesystemDataProvider){

        //TODO: Maybe File not needed -> Path is enough
        File[] files = new File(folderPath).listFiles();
        if (files == null) {
            return;
        }

        // TODO: Add multiple files at once -> Maybe more efficient
        // see storeUploads()
        // ContentResolver.applyBatch() -> https://developer.android.com/guide/topics/providers/content-provider-basics
        for (File file : files) {
            filesystemDataProvider.insertFileIntoDB(file, syncedFolder);
            if (file.isDirectory()){
                insertNewFilesIntoFSDatabase(file.getAbsolutePath(), syncedFolder, filesystemDataProvider);
            }
        }
    }

    public static boolean checkFileForChanges(
        FileSystemDataSet fileData,
        FilesystemDataProvider filesystemDataProvider
                                           ){

        File file = new File(fileData.getLocalPath());
        boolean changed = false;
        if (file.lastModified() > fileData.getFoundAt()){
            long newCrc32 = FilesystemDataProvider.getFileChecksum(fileData.getLocalPath());
            if (fileData.getCrc32() == null || (newCrc32 != -1 && !fileData.getCrc32().equals(Long.toString(newCrc32)))) {
                changed = true;
                fileData.setCrc32(Long.toString(newCrc32));
            }
        }

        fileData.setFoundAt(System.currentTimeMillis());
        filesystemDataProvider.updateFilesystemDataSet(fileData);
        return changed;
    }




    public static void addNewFilesToDB(SyncedFolder syncedFolder, FilesystemDataProvider filesystemDataProvider) {

        long current = System.nanoTime();
        String[] paths = new File(syncedFolder.getLocalPath()).list();
        if (paths == null) {
            return;
        }
        Log_OC.d(TAG, "File-sync reading files took " + (System.nanoTime() - current) + "ns");
        current = System.nanoTime();

        for (String localPath : paths){
            filesystemDataProvider.addNewFilesystemFileToDB(localPath, syncedFolder);
        }
        Log_OC.d(TAG, "File-sync adding files to DB took " + (System.nanoTime() - current) + "ns");

    }

    private static void insertCustomFolderIntoDB(Path path,
                                                 SyncedFolder syncedFolder,
                                                 FilesystemDataProvider filesystemDataProvider,
                                                 long lastCheck,
                                                 long thisCheck) {

        final long enabledTimestampMs = syncedFolder.getEnabledTimestampMs();

        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    File file = path.toFile();
                    if (syncedFolder.isExcludeHidden() && file.isHidden()) {
                        // exclude hidden file or folder
                        return FileVisitResult.CONTINUE;
                    }

                    if (attrs.lastModifiedTime().toMillis() < lastCheck) {
                        // skip files that were already checked
                        return FileVisitResult.CONTINUE;
                    }

                    if (syncedFolder.isExisting() || attrs.lastModifiedTime().toMillis() >= enabledTimestampMs) {
                        // storeOrUpdateFileValue takes a few ms
                        // -> Rest of this file check takes not even 1 ms.
                        filesystemDataProvider.storeOrUpdateFileValue(path.toAbsolutePath().toString(),
                                                                      attrs.lastModifiedTime().toMillis(),
                                                                      file.isDirectory(), syncedFolder);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (syncedFolder.isExcludeHidden() && dir.compareTo(Paths.get(syncedFolder.getLocalPath())) != 0 && dir.toFile().isHidden()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            syncedFolder.setLastScanTimestampMs(thisCheck);
        } catch (IOException e) {
            Log_OC.e(TAG, "Something went wrong while indexing files for auto upload", e);
        }
    }

    public static void insertAllDBEntriesForSyncedFolder(SyncedFolder syncedFolder) {
        final Context context = MainApp.getAppContext();
        final ContentResolver contentResolver = context.getContentResolver();

        final long enabledTimestampMs = syncedFolder.getEnabledTimestampMs();

        if (syncedFolder.isEnabled() && (syncedFolder.isExisting() || enabledTimestampMs >= 0)) {
            MediaFolderType mediaType = syncedFolder.getType();
            final long lastCheckTimestampMs = syncedFolder.getLastScanTimestampMs();
            final long thisCheckTimestampMs = System.currentTimeMillis();

            Log_OC.d(TAG,"File-sync start check folder "+syncedFolder.getLocalPath());
            long startTime = System.nanoTime();

            if (mediaType == MediaFolderType.IMAGE) {
                FilesSyncHelper.insertContentIntoDB(MediaStore.Images.Media.INTERNAL_CONTENT_URI,
                                                    syncedFolder,
                                                    lastCheckTimestampMs, thisCheckTimestampMs);
                FilesSyncHelper.insertContentIntoDB(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                                    syncedFolder,
                                                    lastCheckTimestampMs, thisCheckTimestampMs);
            } else if (mediaType == MediaFolderType.VIDEO) {
                FilesSyncHelper.insertContentIntoDB(MediaStore.Video.Media.INTERNAL_CONTENT_URI,
                                                    syncedFolder,
                                                    lastCheckTimestampMs, thisCheckTimestampMs);
                FilesSyncHelper.insertContentIntoDB(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                                                    syncedFolder,
                                                    lastCheckTimestampMs, thisCheckTimestampMs);
            } else {
                    FilesystemDataProvider filesystemDataProvider = new FilesystemDataProvider(contentResolver);
                    Path path = Paths.get(syncedFolder.getLocalPath());
                    FilesSyncHelper.insertCustomFolderIntoDB(path, syncedFolder, filesystemDataProvider, lastCheckTimestampMs, thisCheckTimestampMs);
            }

            Log_OC.d(TAG,"File-sync finished full check for custom folder "+syncedFolder.getLocalPath()+" within "+(System.nanoTime() - startTime)+ "ns");
        }
    }

    public static void insertChangedEntries(SyncedFolder syncedFolder,
                                            String[] changedFiles) {
        final ContentResolver contentResolver = MainApp.getAppContext().getContentResolver();
        final FilesystemDataProvider filesystemDataProvider = new FilesystemDataProvider(contentResolver);
        for (String changedFileURI : changedFiles){
            String changedFile = getFileFromURI(changedFileURI);
            if (syncedFolder.isEnabled() && syncedFolder.containsFile(changedFile)){
                File file = new File(changedFile);
                filesystemDataProvider.storeOrUpdateFileValue(changedFile,
                                                              file.lastModified(),file.isDirectory(),
                                                              syncedFolder);
            }
        }
    }

    private static String getFileFromURI(String uri){
        final Context context = MainApp.getAppContext();

        Cursor cursor;
        int column_index_data;
        String filePath = null;

        String[] projection = {MediaStore.MediaColumns.DATA};

        cursor = context.getContentResolver().query(Uri.parse(uri), projection, null, null, null, null);

        if (cursor != null && cursor.moveToFirst()) {
            column_index_data = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
            filePath = cursor.getString(column_index_data);
            cursor.close();
        }
        return filePath;
    }

    private static void insertContentIntoDB(Uri uri, SyncedFolder syncedFolder,
                                            long lastCheckTimestampMs, long thisCheckTimestampMs) {
        final Context context = MainApp.getAppContext();
        final ContentResolver contentResolver = context.getContentResolver();

        Cursor cursor;
        int column_index_data;
        int column_index_date_modified;

        final FilesystemDataProvider filesystemDataProvider = new FilesystemDataProvider(contentResolver);

        String contentPath;
        boolean isFolder;

        String[] projection = {MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DATE_MODIFIED};

        String path = syncedFolder.getLocalPath();
        if (!path.endsWith(PATH_SEPARATOR)) {
            path = path + PATH_SEPARATOR;
        }
        path = path + "%";

        long enabledTimestampMs = syncedFolder.getEnabledTimestampMs();

        cursor = context.getContentResolver().query(uri, projection, MediaStore.MediaColumns.DATA + " LIKE ?",
                                                    new String[]{path}, null);

        if (cursor != null) {
            column_index_data = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
            column_index_date_modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            while (cursor.moveToNext()) {
                contentPath = cursor.getString(column_index_data);
                isFolder = new File(contentPath).isDirectory();

                if (syncedFolder.getLastScanTimestampMs() != SyncedFolder.NOT_SCANNED_YET &&
                    cursor.getLong(column_index_date_modified) < (lastCheckTimestampMs / 1000)) {
                    continue;
                }

                if (syncedFolder.isExisting() || cursor.getLong(column_index_date_modified) >= enabledTimestampMs / 1000) {
                    // storeOrUpdateFileValue takes a few ms
                    // -> Rest of this file check takes not even 1 ms.
                    filesystemDataProvider.storeOrUpdateFileValue(contentPath,
                                                                  cursor.getLong(column_index_date_modified), isFolder,
                                                                  syncedFolder);
                }
            }
            cursor.close();
            syncedFolder.setLastScanTimestampMs(thisCheckTimestampMs);
        }
    }

    public static void restartUploadsIfNeeded(final UploadsStorageManager uploadsStorageManager,
                                              final UserAccountManager accountManager,
                                              final ConnectivityService connectivityService,
                                              final PowerManagementService powerManagementService) {
        
        new Thread(() -> {
            FileUploadHelper.Companion.instance().retryFailedUploads(
                uploadsStorageManager,
                connectivityService,
                accountManager,
                powerManagementService);
        }).start();
    }

    public static void scheduleFilesSyncForAllFoldersIfNeeded(Context context, SyncedFolderProvider syncedFolderProvider, BackgroundJobManager jobManager) {
        for (SyncedFolder syncedFolder : syncedFolderProvider.getSyncedFolders()) {
            if (syncedFolder.isEnabled()) {
                jobManager.schedulePeriodicFilesSyncJob(syncedFolder.getId());
            }
        }
        if (context != null) {
            jobManager.scheduleContentObserverJob();
        }
    }

    public static void startFilesSyncForAllFolders(SyncedFolderProvider syncedFolderProvider, BackgroundJobManager jobManager, boolean overridePowerSaving, String[] changedFiles) {
        for (SyncedFolder syncedFolder : syncedFolderProvider.getSyncedFolders()) {
            if (syncedFolder.isEnabled()) {
                jobManager.startImmediateFilesSyncJob(syncedFolder.getId(),overridePowerSaving,changedFiles);
            }
        }
    }
}

