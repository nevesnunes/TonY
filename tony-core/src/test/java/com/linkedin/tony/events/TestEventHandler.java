/**
 * Copyright 2018 LinkedIn Corporation. All rights reserved. Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.tony.events;

import com.linkedin.tony.models.JobMetadata;
import com.linkedin.tony.util.HistoryFileUtils;
import com.linkedin.tony.util.Utils;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.avro.file.DataFileWriter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.mockito.internal.util.reflection.FieldSetter;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.linkedin.tony.util.ParserUtils.parseEvents;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;


public class TestEventHandler {
  private static final Log LOG = LogFactory.getLog(TestEventHandler.class);
  private FileSystem fs = null;
  private BlockingQueue<Event> eventQueue;
  private EventHandler eventHandlerThread;
  private Event eEventWrapper;
  private ApplicationInited eAppInitEvent = new ApplicationInited("app123", 1, "fakehost");
  private Path jobDir = new Path("./src/test/resources/jobDir");
  private JobMetadata metadata = new JobMetadata.Builder()
      .setStarted(0L)
      .setCompleted(0L)
      .build();

  @BeforeClass
  public void setup() {
    HdfsConfiguration conf = new HdfsConfiguration();
    try {
      fs = FileSystem.get(conf);
    } catch (Exception e) {
      fail("Failed setting up FileSystem object");
    }
    eEventWrapper = new Event();
    eEventWrapper.setType(EventType.APPLICATION_INITED);
    eEventWrapper.setEvent(eAppInitEvent);
    eventQueue = new LinkedBlockingQueue<>();
  }

  @Test
  public void testSetUpThread_failedToSetUpWriter() throws IOException {
    FileSystem mockFs = mock(FileSystem.class);
    when(mockFs.create(any(Path.class))).thenThrow(new IOException("IO Exception"));

    EventHandler thread = new EventHandler(mockFs, eventQueue);
    thread.setUpThread(jobDir, metadata);
    thread.stop(jobDir, metadata);

    verify(mockFs).create(any(Path.class));
  }

  @Test
  public void testEventHandlerE2E_success() throws InterruptedException, IOException {
    fs.mkdirs(jobDir);
    eventHandlerThread = new EventHandler(fs, eventQueue);
    eventHandlerThread.setUpThread(jobDir, metadata);
    eventHandlerThread.start();
    eventHandlerThread.emitEvent(eEventWrapper);

    // In real scenario, this `metadata` would be different from the
    // `metadata` passed in `setUpThread` method (i.e with status and completed time)
    eventHandlerThread.stop(jobDir, metadata);
    eventHandlerThread.join();
    List<Event> events = parseEvents(fs, jobDir);
    Event aEventWrapper = events.get(0);
    ApplicationInited aAppInitEvent = (ApplicationInited) aEventWrapper.getEvent();

    assertEquals(events.size(), 1);
    assertEquals(aAppInitEvent.getApplicationId(), eAppInitEvent.getApplicationId());
    assertEquals(aAppInitEvent.getNumTasks(), eAppInitEvent.getNumTasks());
    assertEquals(aAppInitEvent.getHost(), eAppInitEvent.getHost());
    assertEquals(aEventWrapper.getType(), eEventWrapper.getType());
    assertEquals(aEventWrapper.getTimestamp(), eEventWrapper.getTimestamp());
    assertEquals(fs.listStatus(jobDir).length, 1);

    Utils.cleanupHDFSPath(fs.getConf(), jobDir);
  }

  @Test
  public void testEventHandlerE2E_failedJobDirNotSet() throws InterruptedException, IOException {
    fs.mkdirs(jobDir);
    eventHandlerThread = new EventHandler(fs, eventQueue);
    eventHandlerThread.start();
    eventHandlerThread.stop(null, metadata); // jobDir == null
    eventHandlerThread.join();

    assertEquals(fs.listStatus(jobDir).length, 0);

    Utils.cleanupHDFSPath(fs.getConf(), jobDir);
  }

  @Test
  public void testWriteEvent() throws IOException {
    DataFileWriter<Event> writer = mock(DataFileWriter.class);
    eventQueue.add(eEventWrapper);
    eventHandlerThread = new EventHandler(fs, eventQueue);

    assertEquals(eventQueue.size(), 1);
    eventHandlerThread.writeEvent(eventQueue, writer); // should remove the event from queue
    assertEquals(eventQueue.size(), 0);
    verify(writer).append(eEventWrapper);
  }

  @Test
  public void testDrainQueue() {
    DataFileWriter<Event> writer = mock(DataFileWriter.class);
    eventQueue.add(eEventWrapper);
    eventQueue.add(eEventWrapper);
    eventQueue.add(eEventWrapper);
    eventQueue.add(eEventWrapper);
    eventHandlerThread = new EventHandler(fs, eventQueue);

    assertEquals(eventQueue.size(), 4);
    eventHandlerThread.drainQueue(eventQueue, writer); // should drain the queue
    assertEquals(eventQueue.size(), 0);
  }

  @Test
  public void testCleanUp() throws IOException, NoSuchFieldException, SecurityException,
      TimeoutException, InterruptedException {
    final boolean[] gotUnexpectedInterrupt = new boolean[] { false };
    final CountDownLatch latch = new CountDownLatch(1);

    DataFileWriter<Event> writer = mock(DataFileWriter.class);
    doAnswer(invocation -> {
      try {
        latch.await(5, TimeUnit.SECONDS); // Should unlock after interrupt is sent
      } catch (InterruptedException e) {
        gotUnexpectedInterrupt[0] = true;
      }
      return null;
    }).when(writer).close();

    Path inProgressHistFile = new Path(jobDir, HistoryFileUtils.generateFileName(metadata));
    fs.create(inProgressHistFile);
    eventQueue.add(eEventWrapper);
    eventHandlerThread = new EventHandler(fs, eventQueue);
    FieldSetter.setField(eventHandlerThread,
        eventHandlerThread.getClass().getDeclaredField("dataFileWriter"),
        writer);
    FieldSetter.setField(eventHandlerThread,
        eventHandlerThread.getClass().getDeclaredField("inProgressHistFile"),
        inProgressHistFile);

    eventHandlerThread = spy(eventHandlerThread);
    doAnswer(invocation -> {
      Object result = invocation.callRealMethod();
      latch.countDown();
      return result;
    }).when(eventHandlerThread).interrupt();

    Thread emitterThread = new Thread(() -> {
      // Ensure thread isn't blocked on writeEvent() after calling stop()
      while (!Thread.currentThread().isInterrupted()) {
        eventHandlerThread.emitEvent(eEventWrapper);
      }
    });

    eventHandlerThread.start();
    eventHandlerThread.stop(jobDir, metadata);
    eventHandlerThread.join();

    emitterThread.interrupt();
    emitterThread.join();

    if (gotUnexpectedInterrupt[0]) {
      fail("Unexpected interrupt.");
    }
    assertTrue(!eventHandlerThread.isAlive());
    assertTrue(!emitterThread.isAlive());
    verify(writer).close();
  }

  @AfterClass
  public void cleanUp() throws IOException {
    fs.delete(jobDir, true);
  }
}
