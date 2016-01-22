package org.robolectric.util;

import org.robolectric.RoboSettings;

import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.*;
import static org.robolectric.util.Scheduler.IdleState.*;

/**
 * Class that manages a queue of Runnables that are scheduled to run now (or at some time in
 * the future). Runnables that are scheduled to run on the UI thread (tasks, animations, etc)
 * eventually get routed to a Scheduler instance. If
 * {@link RoboSettings#isUseGlobalScheduler()} is <tt>true</tt>, then there will
 * only be one instance of this class which is used by all components in the test.
 * 
 * The execution of a scheduler can be in one of three states (specified by the <tt>idleState</tt>
 * property):
 *
 * <ul><li>paused ({@link #pause()}): if paused, then no posted events will be run unless the Scheduler
 * is explicitly instructed to do so.</li>
 * <li>normal ({@link #unPause()}): if not paused but not set to idle constantly, then the Scheduler will
 * automatically run any {@link Runnable}s that are scheduled to run at or before the
 * Scheduler's current time, but it won't automatically run any future events. To
 * run future events the Scheduler needs to have its clock advanced.</li>
 * <li>idling constantly: if {@link #idleConstantly(boolean)} is called with
 * <tt>true</tt>, then the Scheduler will continue looping through posted events
 * (including future events), advancing its clock as it goes.</li>
 * </ul>
 *
 * The scheduler has nanosecond precision internally, but because the most common post operations
 * are done in milliseconds (and for backwards compatibility) all of the API methods assume
 * milliseconds if the required precision is not specified.
 */
public class Scheduler {

  // This field must start off initialised or else it will cause an NPE in instrumented
  // methods that try and wrap method calls in block/unblock pairs.
  public static Scheduler masterScheduler = new Scheduler();
  private volatile static Thread mainThread = Thread.currentThread();
  int isBlocked = 0;

  /**
   * Retrieves the current master scheduler. This scheduler is always used by the main
   * {@link android.os.Looper Looper}, and if the global scheduler option is set it is also used for
   * the background scheduler and for all other {@link android.os.Looper Looper}s
   * @return The current master scheduler.
   * @see #setMasterScheduler(Scheduler)
   * see org.robolectric.Robolectric#getForegroundThreadScheduler()
   * see org.robolectric.Robolectric#getBackgroundThreadScheduler()
   */
  public static Scheduler getMasterScheduler() {
    return masterScheduler;
  }

  /**
   * Sets the current master scheduler. See {@link #getMasterScheduler()} for details.
   * Note that this method is primarily intended to be called by the Robolectric core setup code.
   * Changing the master scheduler during a test will have unpredictable results.
   * @param masterScheduler the new master scheduler.
   * @see #getMasterScheduler()
   * see org.robolectric.Robolectric#getForegroundThreadScheduler()
   * see org.robolectric.Robolectric#getBackgroundThreadScheduler()
   */
  public static void setMasterScheduler(Scheduler masterScheduler) {
    Scheduler.masterScheduler = masterScheduler;
  }

  /**
   * Tests if the given thread is currently set as the main thread.
   *
   * @param thread the thread to test.
   * @return <tt>true</tt> if the specified thread is the main thread, <tt>false</tt> otherwise.
   * @see #isMainThread()
   */
  public static boolean isMainThread(Thread thread) {
    return thread == mainThread;
  }

  /**
   * Tests if the current thread is currently set as the main thread.
   *
   * @return <tt>true</tt> if the current thread is the main thread, <tt>false</tt> otherwise.
   */
  public static boolean isMainThread() {
    return isMainThread(Thread.currentThread());
  }

  /**
   * Retrieves the main thread. The main thread is the thread to which the main looper is attached.
   * Defaults to the thread that initialises the <tt>RuntimeEnvironment</tt> class.
   *
   * @return The main thread.
   * @see #setMainThread(Thread)
   * @see #isMainThread()
   */
  public static Thread getMainThread() {
    return mainThread;
  }

  /**
   * Sets the main thread. The main thread is the thread to which the main looper is attached.
   * Defaults to the thread that initialises the <tt>RuntimeEnvironment</tt> class.
   *
   * @param newMainThread the new main thread.
   * @see #setMainThread(Thread)
   * @see #isMainThread()
   */
  public static void setMainThread(Thread newMainThread) {
    mainThread = newMainThread;
  }

  public void block() {
    isBlocked++;
  }

  public void unBlock() {
    if (isBlocked == 0) {
      throw new IllegalStateException("Attempted to unblock scheduler " + this +
                                      " that was not blocked");
    }
    if (isBlocked == 1) {
      runPendingTasks();
    }
    isBlocked--;
  }

  public int isBlocked() {
    return isBlocked;
  }

  /**
   * Describes the current state of a {@link Scheduler}.
   */
  public static enum IdleState {
    /**
     * The <tt>Scheduler</tt> will not automatically advance the clock nor execute any runnables.
     */
    PAUSED,
    /**
     * The <tt>Scheduler</tt>'s clock won't automatically advance the clock but will automatically
     * execute any runnables scheduled to execute at or before the current time.
     */
    UNPAUSED,
    /**
     * The <tt>Scheduler</tt> will automatically execute any runnables (past, present or future)
     * as soon as they are posted and advance the clock if necessary.
     */
    CONSTANT_IDLE
  }

  /** Time for this scheduler, measured in nanoseconds. */
  private volatile long currentTime = 100000000;
  private boolean isExecutingRunnable = false;
  // Use Vector to provide synchronized access.
  private final List<ScheduledRunnable> runnables = new Vector<>();
  private volatile IdleState idleState = UNPAUSED;

  /**
   * Retrieves the current idling state of this <tt>Scheduler</tt>.
   * @return The current idle state of this <tt>Scheduler</tt>.
   * @see #setIdleState(IdleState)
   * @see #isPaused()
   */
  public IdleState getIdleState() {
    return idleState;
  }

  /**
   * Sets the current idling state of this <tt>Scheduler</tt>. If transitioning to the
   * {@link IdleState#UNPAUSED} state any tasks scheduled to be run at or before the current time
   * will be run, and if transitioning to the {@link IdleState#CONSTANT_IDLE} state all scheduled
   * tasks will be run and the clock advanced to the time of the last runnable.
   * @param idleState The new idle state of this <tt>Scheduler</tt>.
   * @see #setIdleState(IdleState)
   * @see #isPaused()
   */
  public void setIdleState(IdleState idleState) {
    checkMainThread();
    this.idleState = idleState;
    runPendingTasks();
  }

  /**
   * Get the current time (as seen by the scheduler), in milliseconds. Equivalent to
   * {@link #getCurrentTime(TimeUnit) getCurrentTime(MILLISECONDS}}.
   *
   * @return  Current time of this scheduler in milliseconds.
   * @see #getCurrentTime(TimeUnit)
   */
  public long getCurrentTime() {
    return getCurrentTime(MILLISECONDS);
  }

  /**
   * Get the current time (as seen by the scheduler), in the specified units.
   *
   * @param units the time units in which to return the current time.
   * @return  Current time in the given time units.
   * @see #getCurrentTime()
   */
  public long getCurrentTime(TimeUnit units) {
    return units.convert(currentTime, NANOSECONDS);
  }

  /**
   * Pause the scheduler. Equivalent to <tt>setIdleState(PAUSED)</tt>.
   *
   * @see #unPause()
   * @see #setIdleState(IdleState)
   */
  public void pause() {
    setIdleState(PAUSED);
  }

  /**
   * Un-pause the scheduler. Equivalent to <tt>setIdleState(UNPAUSED)</tt>.
   *
   * @see #pause()
   * @see #setIdleState(IdleState)
   */
  public void unPause() {
    setIdleState(UNPAUSED);
  }

  /**
   * Determine if the scheduler is paused.
   *
   * @return  <tt>true</tt> if it is paused.
   */
  public boolean isPaused() {
    return idleState == PAUSED;
  }

  /**
   * Add a runnable to the queue.
   *
   * @param runnable    Runnable to add.
   */
  public void post(Runnable runnable) {
    postDelayed(runnable, 0);
  }

  /**
   * Add a runnable to the queue to be run after a delay. Equivalent to
   * {@link #postDelayed(Runnable, long, TimeUnit) postDelayed(runnable,delayMillis,MILLISECONDS}}.
   *
   * @param runnable    the {@link Runnable} to add to the queue.
   * @param delayMillis delay in milliseconds.
   * @see #postDelayed(Runnable, long, TimeUnit)
   */
  public void postDelayed(Runnable runnable, long delayMillis) {
    postDelayed(runnable, delayMillis, MILLISECONDS);
  }

  /**
   * Add a runnable to the queue to be run after a delay.
   *
   * @param runnable Runnable to add.
   * @param delay    delay (in the specified time units).
   * @param units    the time units used to measure the delay.
   */
  public void postDelayed(Runnable runnable, long delay, TimeUnit units) {
    final long postTimeNanos = currentTime + units.toNanos(delay);
    if (isMainThread()) {
      block();
      queueRunnableAndSort(runnable, postTimeNanos);
      unBlock();
    } else {
      queueRunnableAndSort(runnable, postTimeNanos);
    }
  }

  /**
   * Add a runnable to the head of the queue.
   *
   * @param runnable  Runnable to add.
   */
  public void postAtFrontOfQueue(Runnable runnable) {
    if (isMainThread()) {
      block();
      runnables.add(0, new ScheduledRunnable(runnable, currentTime));
      unBlock();
    } else {
      runnables.add(0, new ScheduledRunnable(runnable, currentTime));
    }
  }

  /**
   * Remove a runnable from the queue.
   *
   * @param runnable  Runnable to remove.
   */
  public void remove(Runnable runnable) {
    // Prevent concurrent modification by another thread while we are iterating.
    synchronized (runnables) {
      ListIterator<ScheduledRunnable> iterator = runnables.listIterator();
      while (iterator.hasNext()) {
        ScheduledRunnable next = iterator.next();
        if (next.runnable == runnable) {
          iterator.remove();
        }
      }
    }
  }

  /**
   * Run all runnables in the queue.
   *
   * @return  True if a runnable was executed.
   */
  public boolean advanceToLastPostedRunnable() {
    checkMainThread();
    return size() >= 1 && advanceTo(runnables.get(runnables.size() - 1).scheduledTime, NANOSECONDS);
  }

  /**
   * Run the next runnable in the queue.
   *
   * @return  True if a runnable was executed.
   */
  public boolean advanceToNextPostedRunnable() {
    checkMainThread();
    return size() >= 1 && advanceTo(runnables.get(0).scheduledTime, NANOSECONDS);
  }

  /**
   * Run all runnables that are scheduled to run in the next time interval. The clock is advanced
   * by the specified amount. Equivalent to
   * {@link #advanceBy(long, TimeUnit) advanceBy(interval, MILLISECONDS)}.
   *
   * @param   interval  Time interval (in millis).
   * @return  True if a runnable was executed while the clock was being advanced.
   * @see #advanceBy(long, TimeUnit)
   * @see #advanceTo(long)
   */
  public boolean advanceBy(long interval) {
    return advanceBy(interval, MILLISECONDS);
  }

  /**
   * Run all runnables that are scheduled to run in the next time interval. The clock is advanced
   * by the specified amount.
   *
   * @param   interval  time interval (in the given units).
   * @param   units the time units in which the interval is specified.
   * @return  <tt>true</tt> if a runnable was executed.
   * @see #advanceBy(long)
   * @see #advanceTo(long, TimeUnit)
   */
  public boolean advanceBy(long interval, TimeUnit units) {
    return advanceTo(currentTime + units.toNanos(interval), NANOSECONDS);
  }

  /**
   * Run all runnables that are scheduled before the endTime. Equivalent to
   * {@link #advanceTo(long, TimeUnit) advanceTo(endTime, MILLISECONDS)}.
   *
   * @param   endTime  future time to advance to, in milliseconds.
   * @return  <tt>true</tt> if a runnable was executed.
   * @see #advanceTo(long, TimeUnit)
   * @see #advanceBy(long)
   */
  public boolean advanceTo(long endTime) {
    return advanceTo(endTime, MILLISECONDS);
  }

  private void checkMainThread() throws IllegalStateException {
    if (!isMainThread()) {
      throw new IllegalStateException("Scheduler control methods should only be called from the main thread");
    }
  }

  /**
   * Run all runnables that are scheduled before the endTime.
   *
   * @param   endTime future time to advance to.
   * @param   units   units in which <tt>endTime</tt> is measured.
   * @return  <tt>true</tt> if a runnable was executed.
   * @see #advanceTo(long)
   * @see #advanceBy(long, TimeUnit)
   */
  public boolean advanceTo(long endTime, TimeUnit units) {
    checkMainThread();
    final long endTimeNanos = units.toNanos(endTime);
    if (endTimeNanos - currentTime < 0 || size() < 1) {
      currentTime = endTimeNanos;
      return false;
    }

    int runCount = 0;
    while (nextTaskIsScheduledBefore(endTimeNanos)) {
      runOneTask();
      ++runCount;
    }
    currentTime = endTimeNanos;
    return runCount > 0;
  }

  /**
   * Run the next runnable in the queue, advancing the clock if necessary.
   *
   * @return  True if a runnable was executed.
   */
  public boolean runOneTask() {
    checkMainThread();
    if (size() < 1) {
      return false;
    }

    ScheduledRunnable postedRunnable = runnables.remove(0);
    currentTime = postedRunnable.scheduledTime;
    postedRunnable.run();
    return true;
  }

  /**
   * Determine if any enqueued runnables are enqueued before the current time.
   *
   * @return  <tt>true</tt> if any runnables can be executed.
   */
  public boolean areAnyRunnable() {
    return nextTaskIsScheduledBefore(currentTime);
  }

  /**
   * Reset the internal state of the Scheduler. Clears the runnable queue and sets the
   * <tt>idleState</tt> back to {@link IdleState#UNPAUSED UNPAUSED}.
   */
  public void reset() {
    runnables.clear();
    idleState = UNPAUSED;
  }

  /**
   * Return the number of enqueued runnables.
   *
   * @return  Number of enqueues runnables.
   */
  public int size() {
    return runnables.size();
  }

  /**
   * Set the idle state of the Scheduler. If necessary, the clock will be advanced and runnables
   * executed as required by the newly-set state.
   *
   * @param shouldIdleConstantly  If <tt>true</tt> the idle state will be set to
   *                              {@link IdleState#CONSTANT_IDLE}, otherwise it will be set to
   *                              {@link IdleState#UNPAUSED}.
   * @deprecated This method is ambiguous in how it should behave when turning off constant idle.
   * Use {@link #setIdleState(IdleState)} instead to explicitly set the state.
   */
  @Deprecated
  public void idleConstantly(boolean shouldIdleConstantly) {
    setIdleState(shouldIdleConstantly ? CONSTANT_IDLE : UNPAUSED);
  }

  private boolean nextTaskIsScheduledBefore(long endingTime) {
    return size() > 0 && runnables.get(0).scheduledTime <= endingTime;
  }

  private void queueRunnableAndSort(Runnable runnable, long scheduledTime) {
    synchronized (runnables) {
      runnables.add(new ScheduledRunnable(runnable, scheduledTime));
      Collections.sort(runnables);
    }
  }

  /**
   * Runs any pending tasks according to the current idle state.
   *
   * @see {@link #getIdleState()}
   */
  // Visible for testing
  void runPendingTasks() {
    switch (idleState) {
      case CONSTANT_IDLE:
//        advanceToLastPostedRunnable();
        // This fixes the broken test (.
        while (advanceToLastPostedRunnable()) {}
        break;
      case UNPAUSED:
        advanceBy(0);
    }
  }

  private class ScheduledRunnable implements Comparable<ScheduledRunnable>, Runnable {
    private final Runnable runnable;
    /** Scheduled time in nanoseconds. */
    private final long scheduledTime;

    private ScheduledRunnable(Runnable runnable, long scheduledTime) {
      this.runnable = runnable;
      this.scheduledTime = scheduledTime;
    }

    @Override
    public int compareTo(ScheduledRunnable runnable) {
      return Long.compare(scheduledTime, runnable.scheduledTime);
    }

    @Override
    public void run() {
      block();
      try {
        runnable.run();
      } finally {
        unBlock();
      }
    }

    @Override
    public String toString() {
      return "[" + scheduledTime + "]: " + runnable;
    }
  }
}