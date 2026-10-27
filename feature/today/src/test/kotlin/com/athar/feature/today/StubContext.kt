package com.athar.feature.today

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.res.Configuration
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase.CursorFactory
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.UserHandle
import android.view.Display
import java.io.InputStream

/**
 * Bare android.content.Context stub for JVM unit tests: every abstract member
 * throws. Only ever handed to constructors that must not be invoked at runtime
 * (receipt OCR collaborators); no member is expected to be called.
 */
// Context's deprecated abstract members still have to be overridden by this fail-loud test stub.
@Suppress("OVERRIDE_DEPRECATION")
internal object StubContext : Context() {
    override fun getAssets() = error("StubContext is not usable in unit tests")
    override fun getResources() = error("StubContext is not usable in unit tests")
    override fun getPackageManager() = error("StubContext is not usable in unit tests")
    override fun getContentResolver() = error("StubContext is not usable in unit tests")
    override fun getMainLooper() = error("StubContext is not usable in unit tests")
    override fun getApplicationContext() = error("StubContext is not usable in unit tests")
    override fun setTheme(p0: Int) = error("StubContext is not usable in unit tests")
    override fun getTheme() = error("StubContext is not usable in unit tests")
    override fun getClassLoader() = error("StubContext is not usable in unit tests")
    override fun getPackageName() = error("StubContext is not usable in unit tests")
    override fun getApplicationInfo() = error("StubContext is not usable in unit tests")
    override fun getPackageResourcePath() = error("StubContext is not usable in unit tests")
    override fun getPackageCodePath() = error("StubContext is not usable in unit tests")
    override fun getSharedPreferences(p0: String, p1: Int) = error("StubContext is not usable in unit tests")
    override fun moveSharedPreferencesFrom(p0: Context?, p1: String) = error("StubContext is not usable in unit tests")
    override fun deleteSharedPreferences(p0: String) = error("StubContext is not usable in unit tests")
    override fun openFileInput(p0: String) = error("StubContext is not usable in unit tests")
    override fun openFileOutput(p0: String, p1: Int) = error("StubContext is not usable in unit tests")
    override fun deleteFile(p0: String) = error("StubContext is not usable in unit tests")
    override fun getFileStreamPath(p0: String) = error("StubContext is not usable in unit tests")
    override fun getDataDir() = error("StubContext is not usable in unit tests")
    override fun getFilesDir() = error("StubContext is not usable in unit tests")
    override fun getNoBackupFilesDir() = error("StubContext is not usable in unit tests")
    override fun getExternalFilesDir(p0: String?) = error("StubContext is not usable in unit tests")
    override fun getExternalFilesDirs(p0: String) = error("StubContext is not usable in unit tests")
    override fun getObbDir() = error("StubContext is not usable in unit tests")
    override fun getObbDirs() = error("StubContext is not usable in unit tests")
    override fun getCacheDir() = error("StubContext is not usable in unit tests")
    override fun getCodeCacheDir() = error("StubContext is not usable in unit tests")
    override fun getExternalCacheDir() = error("StubContext is not usable in unit tests")
    override fun getExternalCacheDirs() = error("StubContext is not usable in unit tests")
    override fun getExternalMediaDirs() = error("StubContext is not usable in unit tests")
    override fun fileList() = error("StubContext is not usable in unit tests")
    override fun getDir(p0: String, p1: Int) = error("StubContext is not usable in unit tests")
    override fun openOrCreateDatabase(p0: String, p1: Int, p2: CursorFactory?) = error("StubContext is not usable in unit tests")
    override fun openOrCreateDatabase(p0: String, p1: Int, p2: CursorFactory?, p3: DatabaseErrorHandler?) = error("StubContext is not usable in unit tests")
    override fun moveDatabaseFrom(p0: Context?, p1: String) = error("StubContext is not usable in unit tests")
    override fun deleteDatabase(p0: String) = error("StubContext is not usable in unit tests")
    override fun getDatabasePath(p0: String) = error("StubContext is not usable in unit tests")
    override fun databaseList() = error("StubContext is not usable in unit tests")
    override fun getWallpaper() = error("StubContext is not usable in unit tests")
    override fun peekWallpaper() = error("StubContext is not usable in unit tests")
    override fun getWallpaperDesiredMinimumWidth() = error("StubContext is not usable in unit tests")
    override fun getWallpaperDesiredMinimumHeight() = error("StubContext is not usable in unit tests")
    override fun setWallpaper(p0: Bitmap?) = error("StubContext is not usable in unit tests")
    override fun setWallpaper(p0: InputStream?) = error("StubContext is not usable in unit tests")
    override fun clearWallpaper() = error("StubContext is not usable in unit tests")
    override fun startActivity(p0: Intent?) = error("StubContext is not usable in unit tests")
    override fun startActivity(p0: Intent?, p1: Bundle?) = error("StubContext is not usable in unit tests")
    override fun startActivities(p0: Array<Intent?>) = error("StubContext is not usable in unit tests")
    override fun startActivities(p0: Array<Intent?>, p1: Bundle?) = error("StubContext is not usable in unit tests")
    override fun startIntentSender(p0: IntentSender?, p1: Intent?, p2: Int, p3: Int, p4: Int) = error("StubContext is not usable in unit tests")
    override fun startIntentSender(p0: IntentSender?, p1: Intent?, p2: Int, p3: Int, p4: Int, p5: Bundle?) = error("StubContext is not usable in unit tests")
    override fun sendBroadcast(p0: Intent?) = error("StubContext is not usable in unit tests")
    override fun sendBroadcast(p0: Intent?, p1: String?) = error("StubContext is not usable in unit tests")
    override fun sendOrderedBroadcast(p0: Intent, p1: String?) = error("StubContext is not usable in unit tests")
    override fun sendOrderedBroadcast(p0: Intent, p1: String?, p2: BroadcastReceiver?, p3: Handler?, p4: Int, p5: String?, p6: Bundle?) = error("StubContext is not usable in unit tests")
    override fun sendBroadcastAsUser(p0: Intent?, p1: UserHandle?) = error("StubContext is not usable in unit tests")
    override fun sendBroadcastAsUser(p0: Intent?, p1: UserHandle?, p2: String?) = error("StubContext is not usable in unit tests")
    override fun sendOrderedBroadcastAsUser(p0: Intent?, p1: UserHandle?, p2: String?, p3: BroadcastReceiver?, p4: Handler?, p5: Int, p6: String?, p7: Bundle?) = error("StubContext is not usable in unit tests")
    override fun sendStickyBroadcast(p0: Intent?) = error("StubContext is not usable in unit tests")
    override fun sendStickyOrderedBroadcast(p0: Intent?, p1: BroadcastReceiver?, p2: Handler?, p3: Int, p4: String?, p5: Bundle?) = error("StubContext is not usable in unit tests")
    override fun removeStickyBroadcast(p0: Intent?) = error("StubContext is not usable in unit tests")
    override fun sendStickyBroadcastAsUser(p0: Intent?, p1: UserHandle?) = error("StubContext is not usable in unit tests")
    override fun sendStickyOrderedBroadcastAsUser(p0: Intent?, p1: UserHandle?, p2: BroadcastReceiver?, p3: Handler?, p4: Int, p5: String?, p6: Bundle?) = error("StubContext is not usable in unit tests")
    override fun removeStickyBroadcastAsUser(p0: Intent?, p1: UserHandle?) = error("StubContext is not usable in unit tests")
    override fun registerReceiver(p0: BroadcastReceiver?, p1: IntentFilter?) = error("StubContext is not usable in unit tests")
    override fun registerReceiver(p0: BroadcastReceiver?, p1: IntentFilter?, p2: Int) = error("StubContext is not usable in unit tests")
    override fun registerReceiver(p0: BroadcastReceiver?, p1: IntentFilter?, p2: String?, p3: Handler?) = error("StubContext is not usable in unit tests")
    override fun registerReceiver(p0: BroadcastReceiver?, p1: IntentFilter?, p2: String?, p3: Handler?, p4: Int) = error("StubContext is not usable in unit tests")
    override fun unregisterReceiver(p0: BroadcastReceiver?) = error("StubContext is not usable in unit tests")
    override fun startService(p0: Intent?) = error("StubContext is not usable in unit tests")
    override fun startForegroundService(p0: Intent?) = error("StubContext is not usable in unit tests")
    override fun stopService(p0: Intent?) = error("StubContext is not usable in unit tests")
    override fun bindService(p0: Intent, p1: ServiceConnection, p2: Int) = error("StubContext is not usable in unit tests")
    override fun unbindService(p0: ServiceConnection) = error("StubContext is not usable in unit tests")
    override fun startInstrumentation(p0: ComponentName, p1: String?, p2: Bundle?) = error("StubContext is not usable in unit tests")
    override fun getSystemService(p0: String) = error("StubContext is not usable in unit tests")
    override fun getSystemServiceName(p0: Class<*>) = error("StubContext is not usable in unit tests")
    override fun checkPermission(p0: String, p1: Int, p2: Int) = error("StubContext is not usable in unit tests")
    override fun checkCallingPermission(p0: String) = error("StubContext is not usable in unit tests")
    override fun checkCallingOrSelfPermission(p0: String) = error("StubContext is not usable in unit tests")
    override fun checkSelfPermission(p0: String) = error("StubContext is not usable in unit tests")
    override fun enforcePermission(p0: String, p1: Int, p2: Int, p3: String?) = error("StubContext is not usable in unit tests")
    override fun enforceCallingPermission(p0: String, p1: String?) = error("StubContext is not usable in unit tests")
    override fun enforceCallingOrSelfPermission(p0: String, p1: String?) = error("StubContext is not usable in unit tests")
    override fun grantUriPermission(p0: String, p1: Uri?, p2: Int) = error("StubContext is not usable in unit tests")
    override fun revokeUriPermission(p0: Uri?, p1: Int) = error("StubContext is not usable in unit tests")
    override fun revokeUriPermission(p0: String, p1: Uri?, p2: Int) = error("StubContext is not usable in unit tests")
    override fun checkUriPermission(p0: Uri?, p1: Int, p2: Int, p3: Int) = error("StubContext is not usable in unit tests")
    override fun checkCallingUriPermission(p0: Uri?, p1: Int) = error("StubContext is not usable in unit tests")
    override fun checkCallingOrSelfUriPermission(p0: Uri?, p1: Int) = error("StubContext is not usable in unit tests")
    override fun checkUriPermission(p0: Uri?, p1: String?, p2: String?, p3: Int, p4: Int, p5: Int) = error("StubContext is not usable in unit tests")
    override fun enforceUriPermission(p0: Uri?, p1: Int, p2: Int, p3: Int, p4: String) = error("StubContext is not usable in unit tests")
    override fun enforceCallingUriPermission(p0: Uri?, p1: Int, p2: String) = error("StubContext is not usable in unit tests")
    override fun enforceCallingOrSelfUriPermission(p0: Uri?, p1: Int, p2: String) = error("StubContext is not usable in unit tests")
    override fun enforceUriPermission(p0: Uri?, p1: String?, p2: String?, p3: Int, p4: Int, p5: Int, p6: String?) = error("StubContext is not usable in unit tests")
    override fun createPackageContext(p0: String, p1: Int) = error("StubContext is not usable in unit tests")
    override fun createContextForSplit(p0: String) = error("StubContext is not usable in unit tests")
    override fun createConfigurationContext(p0: Configuration) = error("StubContext is not usable in unit tests")
    override fun createDisplayContext(p0: Display) = error("StubContext is not usable in unit tests")
    override fun createDeviceProtectedStorageContext() = error("StubContext is not usable in unit tests")
    override fun isDeviceProtectedStorage() = error("StubContext is not usable in unit tests")
}
