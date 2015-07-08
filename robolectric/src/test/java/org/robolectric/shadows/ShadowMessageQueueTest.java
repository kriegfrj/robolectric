package org.robolectric.shadows;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;

import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.robolectric.TestRunners;
import org.robolectric.util.Scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

@RunWith(TestRunners.MultiApiWithDefaults.class)
public class ShadowMessageQueueTest {
  private Looper looper;
  private MessageQueue queue;
  private ShadowMessageQueue shadowQueue;
  private Message testMessage;
  private volatile TestHandler handler;
  private Scheduler scheduler;
  private String quitField;
  private boolean isNativeStatic;
  private boolean isLongPtr;
  
  private static class TestHandler extends Handler {
    public List<Message> handled = new ArrayList<>();
    
    public TestHandler(Looper looper) {
      super(looper);
    }
    
    @Override
    public void handleMessage(Message msg) {
      handled.add(msg);
    }
  }
  
  private static Looper newLooper() {
    return newLooper(true);
  }
  
  private static Looper newLooper(boolean canQuit) {
    return callConstructor(Looper.class, from(boolean.class, canQuit));
  }
  
  private static MessageQueue newQueue() {
    return newQueue(true);
  }
  
  private static MessageQueue newQueue(boolean canQuit) {
    return callConstructor(MessageQueue.class, from(boolean.class, canQuit));
  }
  
  @Before
  public void setUp() throws Exception {
    // Queues and loopers are closely linked; can't easily test one without the other.
    looper = newLooper();
    handler = new TestHandler(looper);
    queue = looper.getQueue(); 
    shadowQueue = shadowOf(queue);
    scheduler = shadowQueue.getScheduler();
    scheduler.pause();
    testMessage = handler.obtainMessage();
    quitField = Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ? "mQuitting" : "mQuiting";
    isNativeStatic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2;
    isLongPtr = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
  }

  private ClassParameter<?> getPtrClass() {
    return getPtrClass((Number)getField(queue, "mPtr"));
  }
  
  private ClassParameter<?> getPtrClass(Number num) {
    return isLongPtr ? from(long.class, num.longValue()) : from(int.class, num.intValue());
  }
  
  private void shouldAssert(String method, ClassParameter<?>... params) {
    boolean ran = false;
    try {
      invokeNative(method, params);
      ran = true;
    } catch (Throwable t) {
      if (!(t instanceof IllegalStateException)) {
        Assertions.fail("Expected IllegalStateException when invoking " + (isNativeStatic ? "static " : "") + "method " + method + ", got: " + t, t);
      }
    }
    assertThat(ran).as(method).overridingErrorMessage("Expected IllegalStateException but no exception was thrown").isFalse();
  }
  
  private <T> T invokeNative(String method, ClassParameter<?>... params) {
    if (isNativeStatic) {
      return callStaticMethod(MessageQueue.class, method, params);
    } else {
      return callInstanceMethod(queue, method, params);
    }
  }
  
  private void nativePollOnce(int timeoutMillis) {
     invokeNative("nativePollOnce", getPtrClass(), from(int.class, timeoutMillis));
  }

  private void nativeWake() {
    invokeNative("nativeWake", getPtrClass());
  }

  private class PollThread extends TestThread {
    volatile boolean exited = false;

    @Override
    public void run() {
      nativePollOnce(1000);
      exited = true;
    }
  }
  
  // Starts the poll thread and waits until it has started waiting (maximum 1000ms wait)
  private static void waitForPollThread(PollThread pt) {
    pt.start();
    long endTime = System.currentTimeMillis() + 1000;
    while (pt.getState() != Thread.State.WAITING && pt.getState() != Thread.State.TIMED_WAITING && System.currentTimeMillis() < endTime) {
      Thread.yield();
    }
  }
  
  @Test
  public void nativePollOnce_shouldScheduleCallbackAtTimeout() throws InterruptedException {
    PollThread pt = new PollThread();
    waitForPollThread(pt);
    assertThat(scheduler.advanceBy(999)).as("before poll timeout").isFalse();
    assertThat(pt.exited).as("thread shouldn't exit yet").isFalse();
    assertThat(scheduler.size()).as("size before").isEqualTo(1);
    scheduler.advanceBy(1);
    assertThat(scheduler.size()).as("size after").isEqualTo(0);
    pt.join(1000);
    assertThat(pt.exited).as("thread exited").isTrue();
  }
 
  @Test
  public void nativeWake_shouldInterruptPoll() throws InterruptedException {
    PollThread pt = new PollThread();
    waitForPollThread(pt);
    assertThat(pt.exited).as("thread shouldn't exit yet").isFalse();
    nativeWake();
    pt.join(1000);
    assertThat(pt.exited).as("thread exited").isTrue();
  }

  @Test
  public void nativeInit_shouldAssert() {
    shouldAssert("nativeInit");
  }

  @Test
  public void mPtr_shouldBeUnique_forEachQueue() {
    Number ptrBoxed = getField(queue, "mPtr");
    MessageQueue queue2 = newQueue();
    Number ptr2Boxed = getField(queue2, "mPtr");
    assertThat(ptrBoxed).isNotEqualTo(ptr2Boxed);
  }

  @Test
  public void newQueue_shouldBePlacedInNativeMap() {
    Map<? extends Number, WeakReference<ShadowMessageQueue>> nativeMap = getStaticField(ShadowMessageQueue.class, "nativeMap");
    Number ptr = getField(queue, "mPtr");
    assertThat(nativeMap.get(ptr).get()).isSameAs(shadowQueue);
  }
  
  @Test
  public void nativeDestroy_shouldRemoveFromNativeMap() {
    // We're violating type safety here by casting it to Map<Number,...> rather than Map<? extends Number,...>,
    // but we're not planning to put anything into the map so that's ok. Do this because AssertJ's
    // doesNotContainKey() assertion tries to enforce the type and won't accept Number as a key.
    Map<Number, ?> nativeMap = getStaticField(ShadowMessageQueue.class, "nativeMap");
    Number ptr = getField(queue, "mPtr");
    if (isNativeStatic) {
      callStaticMethod(MessageQueue.class, "nativeDestroy", getPtrClass(ptr));
    } else {
      callInstanceMethod(queue, "nativeDestroy");
    }
    assertThat(nativeMap).doesNotContainKey(ptr);
  }
  
  @Test
  public void constructor_shouldSetQuitAllowed() {
    assertThat((Boolean)getField(queue, "mQuitAllowed")).as("quitAllowed:true").isTrue();
    MessageQueue queue2 = newQueue(false);
    assertThat((Boolean)getField(queue2, "mQuitAllowed")).as("quitAllowed:false").isFalse();
  }

  @Test
  public void constructor_shouldInitializeScheduler() {
    assertThat(getField(shadowQueue, "scheduler")).as("mainThread").isSameAs(scheduler);
  }

  @Test
  public void test_setGetHead() {
    shadowQueue.setHead(testMessage);
    assertThat(shadowQueue.getHead()).as("getHead()").isSameAs(testMessage);
  }

  private boolean enqueueMessage(Message msg, long when) {
    return callInstanceMethod(queue, "enqueueMessage",
        from(Message.class, msg),
        from(long.class, when)
        );    
  }

  private void removeMessages(Handler handler, int what, Object token) {
    callInstanceMethod(queue, "removeMessages",
        from(Handler.class, handler),
        from(int.class, what),
        from(Object.class, token)
        );    
  }
  
  private void doDispatch(Message msg) {
    callInstanceMethod(shadowQueue, "doDispatch", from(Message.class, msg));
  }
  
  @Test
  public void enqueueMessage_setsHead() {
    enqueueMessage(testMessage, 100);
    assertThat(shadowQueue.getHead()).as("head").isSameAs(testMessage);
  }

  @Test
  public void enqueueMessage_returnsTrue() {
    assertThat(enqueueMessage(testMessage, 100)).as("retval").isTrue();
  }

  @Test
  public void enqueueMessage_setsWhen() {
    enqueueMessage(testMessage, 123);
    assertThat(testMessage.getWhen()).as("when").isEqualTo(123);
  }
  
  @Test
  public void enqueueMessage_returnsFalse_whenQuitting() {
    setField(queue, quitField, true);
    assertThat(enqueueMessage(testMessage, 1)).as("enqueueMessage()").isFalse();
  }

  @Test
  public void enqueueMessage_doesntSchedule_whenQuitting() {
    setField(queue, quitField, true);
    enqueueMessage(testMessage, 1);
    assertThat(scheduler.size()).as("scheduler_size").isEqualTo(0);
  }
  
  @Test
  public void enqueuedMessage_isSentToHandler() {
    enqueueMessage(testMessage, 200);
    scheduler.advanceTo(199);
    assertThat(handler.handled).as("handled:before").isEmpty();
    scheduler.advanceTo(200);
    assertThat(handler.handled).as("handled:after").containsExactly(testMessage);
  }
  
  @Test
  public void removedMessage_isNotSentToHandler() {
    enqueueMessage(testMessage, 200);
    assertThat(scheduler.size()).as("scheduler size:before").isEqualTo(1);
    removeMessages(handler, testMessage.what, null);
    scheduler.advanceToLastPostedRunnable();
    assertThat(scheduler.size()).as("scheduler size:after").isEqualTo(0);
    assertThat(handler.handled).as("handled").isEmpty();
  }

  @Test
  public void enqueueMessage_withZeroWhen_postsAtFront() {
    enqueueMessage(testMessage, 0);
    Message m2 = handler.obtainMessage(2);
    enqueueMessage(m2, 0);
    scheduler.advanceToLastPostedRunnable();
    assertThat(handler.handled).as("handled").containsExactly(m2, testMessage);
  }
  
  @Test
  public void dispatchedMessage_isMarkedInUse_andRecycled() {
    Handler handler = new Handler(looper) {
      @Override
      public void handleMessage(Message msg) {
        boolean inUse = callInstanceMethod(msg, "isInUse");
        assertThat(inUse).as(msg.what + ":inUse").isTrue();
        Message next = getField(msg, "next");
        assertThat(next).as(msg.what + ":next").isNull();
      }
    };
    Message msg = handler.obtainMessage(1);
    enqueueMessage(msg, 200);
    Message msg2 = handler.obtainMessage(2);
    enqueueMessage(msg2, 205);
    scheduler.advanceToNextPostedRunnable();
    
    // Check that it's been properly recycled.
    assertThat(msg.what).as("msg.what").isZero();
    
    scheduler.advanceToNextPostedRunnable();

    assertThat(msg2.what).as("msg2.what").isZero();
  }
  
  @Test 
  public void reset_shouldClearMessageQueue() {
    Message msg  = handler.obtainMessage(1234);
    Message msg2 = handler.obtainMessage(5678);
    handler.sendMessage(msg);
    handler.sendMessage(msg2);
    assertThat(handler.hasMessages(1234)).as("before-1234").isTrue();
    assertThat(handler.hasMessages(5678)).as("before-5678").isTrue();
    shadowQueue.reset();
    assertThat(handler.hasMessages(1234)).as("after-1234").isFalse();
    assertThat(handler.hasMessages(5678)).as("after-5678").isFalse();
  }

  @Test
  public void reset_shouldSetNewScheduler() {
    Scheduler old = shadowQueue.getScheduler();
    shadowQueue.reset();
    assertThat(shadowQueue.getScheduler()).isNotSameAs(old);
  }
  
  @Rule
  public TestName testName = new TestName();
  
  // Thread for conveniently setting the thread name to something sensible
  private class TestThread extends Thread {
    public TestThread() {
      super(testName.getMethodName());
    }
    
    public TestThread(Runnable r) {
      super(r, testName.getMethodName());
    }
  }
  
  @Test
  public void dispatchMessage_shouldDispatchToTargetThread_whenInvokedFromAnotherThread() throws InterruptedException {
    // Used Vector rather than ArrayList because Vector is synchronized.
    final List<String> events = new Vector<>();
    final AtomicReference<Message> msgToDispatch = new AtomicReference<>();
    final AtomicReference<Message> msgReceived = new AtomicReference<>();
    final CountDownLatch started = new CountDownLatch(1);
    new TestThread(new Runnable() {
      @Override
      public void run() {
        // Need the queue to be created on a thread other than the test/UI thread.
        Looper.prepare();
        queue = Looper.myQueue();
        shadowQueue = shadowOf(queue);
        handler = new TestHandler(Looper.myLooper());
        msgToDispatch.set(handler.obtainMessage());
        started.countDown();
        msgReceived.set(shadowQueue.next());
        // Main thread *should* block waiting for this
        // thread to call "done". If it does not,
        // then this little sleep will cause it to run and
        // add the post-dispatch event before we add
        // "next returned", which will cause the test to 
        // fail. This has the potential to be flaky and I'd
        // like to figure out a way to test this without
        // relying on an arbitrary-length sleep. 
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {}
        events.add("next returned");
        shadowQueue.doneDispatch();
      }
    }).start();
    started.await();
    events.add("pre-dispatch");
    doDispatch(msgToDispatch.get());
    events.add("post-dispatch");
    
    assertThat(msgReceived.get()).as("dispatched msg").isSameAs(msgToDispatch.get());
    assertThat(events).as("event order").containsExactly("pre-dispatch", "next returned", "post-dispatch");
  }

  @Test
  public void doQuit_causesNextToReturnNull() throws InterruptedException {
    // final AtomicReference<Message> msg = new AtomicReference<>();
    // final CountDownLatch flag = new CountDownLatch(1);
    // new TestThread(new Runnable() {
    //   @Override
    //   public void run() {
    //     msg.set(shadowQueue.next());
    //     flag.countDown();
    //   }
    // }).start();
    // shadowQueue.doQuit();
    // flag.await(1000, TimeUnit.MILLISECONDS);
    // assertThat(msg.get()).isNull();
  }
  
  @Test
  public void whenQueueIsEmpty_andQuitting_shouldCallDoQuit() {
//    final AtomicReference<Message> received = new AtomicReference<>();
//    ReflectionHelpers.setField(queue, quitField, true);
//    new TestThread(new Runnable() {
//      @Override
//      public void run() {
//        shadowQueue.dispatchMessage(handler.obtainMessage());
//      }
//    }).start();
//    Message msg = shadowQueue.next();
//    assertThat(msg).as("msg").isNotNull();
//    shadowQueue.doneDispatch();
//    assertThat(shadowQueue.next()).as("terminate signal").isNull();
  }
}

