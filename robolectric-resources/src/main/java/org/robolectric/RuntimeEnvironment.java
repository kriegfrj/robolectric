package org.robolectric;

import android.app.Application;
import android.content.pm.PackageManager;
import org.robolectric.res.builder.RobolectricPackageManager;

public class RuntimeEnvironment {
  /**
   * The main test thread. This should be Robolectric's UI
   * thread, and is simply set to the thread that loaded this class.
   * 
   * @see #isMainThread()
   */
  public static final Thread MAIN_THREAD = Thread.currentThread();
  public static Application application;

  private static String qualifiers;
  private static Object activityThread;
  private static RobolectricPackageManager packageManager;

  /**
   * Tests if the calling thread is the main thread. 
   * @return <tt>true</tt> if the calling thread is the main thread,
   * or <tt>false</tt> otherwise.
   * 
   * @see #MAIN_THREAD
   */
  public static boolean isMainThread() {
    return MAIN_THREAD == Thread.currentThread();
  }

  public static Object getActivityThread() {
    return activityThread;
  }

  public static void setActivityThread(Object newActivityThread) {
    activityThread = newActivityThread;
  }

  public static PackageManager getPackageManager() {
    return (PackageManager) packageManager;
  }

  public static RobolectricPackageManager getRobolectricPackageManager() {
    return packageManager;
  }

  public static void setRobolectricPackageManager(RobolectricPackageManager newPackageManager) {
    if (packageManager != null) {
      packageManager.reset();
    }
    packageManager = newPackageManager;
  }

  public static String getQualifiers() {
    return qualifiers;
  }

  public static void setQualifiers(String newQualifiers) {
    qualifiers = newQualifiers;
  }
}
