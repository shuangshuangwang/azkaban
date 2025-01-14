/*
 * Copyright 2017 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package azkaban.execapp;

import static java.lang.Thread.State.TIMED_WAITING;
import static java.lang.Thread.State.WAITING;
import static org.assertj.core.api.Assertions.assertThat;

import azkaban.Constants.JobProperties;
import azkaban.event.Event;
import azkaban.event.EventData;
import azkaban.execapp.FlowRunner.FlowRunnerProxy;
import azkaban.executor.ExecutableFlow;
import azkaban.executor.ExecutableNode;
import azkaban.executor.ExecutorLoader;
import azkaban.executor.InteractiveTestJob;
import azkaban.executor.MockExecutorLoader;
import azkaban.executor.Status;
import azkaban.flow.CommonJobProperties;
import azkaban.imagemgmt.version.VersionSet;
import azkaban.jobExecutor.JobClassLoader;
import azkaban.jobtype.JobTypeManager;
import azkaban.jobtype.JobTypePluginSet;
import azkaban.spi.EventType;
import azkaban.test.TestUtils;
import azkaban.utils.Props;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class JobRunnerTest {

  public static final String SUBMIT_USER = "testUser";
  private final Logger logger = Logger.getLogger("JobRunnerTest");
  private File workingDir;
  private JobTypeManager jobtypeManager;

  public JobRunnerTest() {

  }

  @Before
  public void setUp() throws Exception {
    System.out.println("Create temp dir");
    this.workingDir = new File("_AzkabanTestDir_" + System.currentTimeMillis());
    if (this.workingDir.exists()) {
      FileUtils.deleteDirectory(this.workingDir);
    }
    this.workingDir.mkdirs();
    this.jobtypeManager =
        new JobTypeManager(null, null, this.getClass().getClassLoader());
    final JobTypePluginSet pluginSet = this.jobtypeManager.getJobTypePluginSet();
    pluginSet.addPluginClassName("test", InteractiveTestJob.class.getName());
  }

  @After
  public void tearDown() throws IOException {
    System.out.println("Teardown temp dir");
    if (this.workingDir != null) {
      FileUtils.deleteDirectory(this.workingDir);
      this.workingDir = null;
    }
  }

  @Test
  public void testEffectiveUser() throws Exception {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    JobRunner runner =
        createJobRunner(1, "testJob", 0, false, loader, eventCollector);
    runner.run();
    String effectiveUser = runner.getEffectiveUser();
    Assert.assertEquals(SUBMIT_USER, effectiveUser);
    Assert.assertEquals(effectiveUser, runner.getProps().get("user.to.proxy"));

    this.jobtypeManager.getJobTypePluginSet()
        .addDefaultProxyUsersJobTypeClasses(InteractiveTestJob.class.getName());
    this.jobtypeManager.getJobTypePluginSet().addDefaultProxyUser("test", "defaultTestUser");

    runner =
        createJobRunner(1, "testJob", 0, false, loader, eventCollector);
    runner.run();
    effectiveUser = runner.getEffectiveUser();
    Mockito.verify(runner.getFlowRunnerProxy(), Mockito.times(1)).setEffectiveUser(runner.getJobId(),
        runner.getEffectiveUser(), Optional.of("test"));
    Assert.assertEquals("defaultTestUser", effectiveUser);
    Assert.assertEquals(effectiveUser, runner.getProps().get("user.to.proxy"));
  }

  @Test
  public void testBasicRun() throws Exception {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(1, "testJob", 0, false, loader, eventCollector);
    final ExecutableNode node = runner.getNode();
    // Job starts to queue
    runner.setTimeInQueue(System.currentTimeMillis());
    // ensure that queue duration should be > 0
    Thread.sleep(1L);

    eventCollector.handleEvent(Event.create(null, EventType.JOB_STARTED, new EventData(node)));
    Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED
        && runner.getStatus() != Status.FAILED);
    // Check flow version set and job type image version
    final ExecutableFlow flow = node.getExecutableFlow();
    Assert.assertTrue(flow.getVersionSet().getImageToVersionMap().getOrDefault(node.getType(),
        null).getVersion().equals("8.0"));

    runner.run();
    eventCollector.handleEvent(Event.create(null, EventType.JOB_FINISHED, new EventData(node)));

    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue("Node status is " + node.getStatus(),
        node.getStatus() == Status.SUCCEEDED);
    Assert.assertTrue(node.getStartTime() >= 0 && node.getEndTime() >= 0);
    Assert.assertTrue(node.getEndTime() - node.getStartTime() >= 0);
    Assert.assertTrue(runner.getQueueDuration() > 0);

    final File logFile = new File(runner.getLogFilePath());
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertTrue(outputProps != null);

    Assert.assertFalse("Thread ContextClassLoader not reset properly",
        Thread.currentThread().getContextClassLoader() instanceof JobClassLoader);

    checkRequiredJobProperties(runner, logFile);

    try (final BufferedReader br = getLogReader(logFile)) {
      final String firstLine = br.readLine();
      Assert.assertTrue("Unexpected default layout",
          firstLine.startsWith(new SimpleDateFormat("dd-MM-yyyy").format(new Date())));
    }
    // Verify that user.to.proxy is default to submit user.
    Assert.assertEquals(SUBMIT_USER, runner.getProps().get(JobProperties.USER_TO_PROXY));

    Assert.assertTrue(loader.getNodeUpdateCount(node.getId()) == 3);

    Assert.assertFalse(runner.getLogger().getAllAppenders().hasMoreElements());
    eventCollector
        .assertEvents(EventType.JOB_STARTED, EventType.JOB_STATUS_CHANGED, EventType.JOB_FINISHED);
  }

  private void checkRequiredJobProperties(JobRunner runner, File logFile) {
    Field jobField = null;
    try {
      jobField = runner.getClass().getDeclaredField("job");
    } catch (NoSuchFieldException e) {
      Assert.fail("'job' field not found");
    }
    jobField.setAccessible(true);
    InteractiveTestJob job = null;
    try {
      job = (InteractiveTestJob) jobField.get(runner);
    } catch (IllegalAccessException e) {
      Assert.fail("'job' field not accessible");
    }
    Props jobProps = job.getJobProps();
    Assert.assertEquals("Unexpected log file path in properties",
        logFile.getAbsolutePath(),
        jobProps.get(CommonJobProperties.JOB_LOG_FILE));
  }

  private BufferedReader getLogReader(File logFile) throws FileNotFoundException {
    return new BufferedReader(new InputStreamReader(new FileInputStream(logFile),
        Charset.defaultCharset()));
  }

  @Test
  public void testFailedRun() {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(1, "testJob", 0, true, loader, eventCollector);
    final ExecutableNode node = runner.getNode();

    Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED
        || runner.getStatus() != Status.FAILED);
    runner.run();

    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue(node.getStatus() == Status.FAILED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() >= 0);

    final File logFile = new File(runner.getLogFilePath());
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertEquals(0, outputProps.size());
    Assert.assertTrue(logFile.exists());
    Assert.assertTrue(eventCollector.checkOrdering());
    Assert.assertTrue(!runner.isKilled());
    Assert.assertTrue(loader.getNodeUpdateCount(node.getId()) == 3);
    // Check failureMessage and modifiedBy
    Assert.assertEquals("unknown", runner.getNode().getModifiedBy());
    Assert.assertEquals("java.lang.RuntimeException: Forced"
            + " failure of testJob", runner.getNode().getFailureMessage());

    Assert.assertFalse(runner.getLogger().getAllAppenders().hasMoreElements());
    eventCollector
        .assertEvents(EventType.JOB_STARTED, EventType.JOB_STATUS_CHANGED, EventType.JOB_FINISHED);

    Assert.assertFalse("Thread ContextClassLoader not reset properly",
        Thread.currentThread().getContextClassLoader() instanceof JobClassLoader);
  }

  @Test
  public void testDisabledRun() {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(1, "testJob", 0, false, loader, eventCollector);
    final ExecutableNode node = runner.getNode();

    node.setStatus(Status.DISABLED);

    // Should be disabled.
    Assert.assertTrue(runner.getStatus() == Status.DISABLED);
    runner.run();

    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue(node.getStatus() == Status.SKIPPED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
    // Give it 2000 ms to fail.
    Assert.assertTrue(node.getEndTime() - node.getStartTime() < 2000);

    // Log file and output files should not exist.
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertTrue(outputProps == null);
    Assert.assertTrue(runner.getLogFilePath() == null);
    Assert.assertTrue(eventCollector.checkOrdering());

    Assert.assertTrue(loader.getNodeUpdateCount(node.getId()) == null);

    Assert.assertFalse(runner.getLogger().getAllAppenders().hasMoreElements());
    eventCollector.assertEvents(EventType.JOB_STARTED, EventType.JOB_FINISHED);

    Assert.assertFalse("Thread ContextClassLoader not reset properly",
        Thread.currentThread().getContextClassLoader() instanceof JobClassLoader);
  }

  @Test
  public void testPreKilledRun() {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(1, "testJob", 0, false, loader, eventCollector);
    final ExecutableNode node = runner.getNode();

    node.setStatus(Status.KILLED);

    // Should be killed.
    Assert.assertTrue(runner.getStatus() == Status.KILLED);
    runner.run();

    // Should just skip the run and not change
    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue(node.getStatus() == Status.KILLED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
    // Give it 2000 ms to fail.
    Assert.assertTrue(node.getEndTime() - node.getStartTime() < 2000);

    Assert.assertTrue(loader.getNodeUpdateCount(node.getId()) == null);

    // Log file and output files should not exist.
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertTrue(outputProps == null);
    Assert.assertTrue(runner.getLogFilePath() == null);
    Assert.assertTrue(!runner.isKilled());
    Assert.assertFalse(runner.getLogger().getAllAppenders().hasMoreElements());
    eventCollector.assertEvents(EventType.JOB_STARTED, EventType.JOB_FINISHED);

    Assert.assertFalse("Thread ContextClassLoader not reset properly",
        Thread.currentThread().getContextClassLoader() instanceof JobClassLoader);
  }

  @Test
  public void testCancelRun() throws Exception {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(13, "testJob", 10, false, loader, eventCollector);
    final ExecutableNode node = runner.getNode();

    Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED
        || runner.getStatus() != Status.FAILED);

    final Thread thread = startThread(runner);

    StatusTestUtils.waitForStatus(node, Status.RUNNING);
    runner.getNode().setModifiedBy("dementor1");
    runner.kill();
    assertThreadIsNotAlive(thread);

    Assert.assertFalse("Thread ContextClassLoader not reset properly",
        thread.getContextClassLoader() instanceof JobClassLoader);

    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue("Status is " + node.getStatus(),
        node.getStatus() == Status.KILLED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
    // Give it some time to fail.
    Assert.assertTrue(node.getEndTime() - node.getStartTime() < 3000);
    Assert.assertTrue(loader.getNodeUpdateCount(node.getId()) == 3);
    // Check job kill time, user killed the job, and failure message
    Assert.assertEquals("dementor1", runner.getNode().getModifiedBy());
    Assert.assertTrue(runner.getJobKillTime() != -1);
    Assert.assertTrue(runner.getKillDuration() >= 0);

    // Log file and output files should not exist.
    final File logFile = new File(runner.getLogFilePath());
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertEquals(0, outputProps.size());
    Assert.assertTrue(logFile.exists());
    Assert.assertTrue(eventCollector.checkOrdering());
    Assert.assertTrue(runner.isKilled());
    Assert.assertFalse(runner.getLogger().getAllAppenders().hasMoreElements());
    eventCollector
        .assertEvents(EventType.JOB_STARTED, EventType.JOB_STATUS_CHANGED, EventType.JOB_FINISHED);
  }

  @Test
  public void testDelayedExecutionJob() throws Exception {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(1, "testJob", 0, false, loader, eventCollector);
    runner.setDelayStart(10_000);
    final long startTime = System.currentTimeMillis();
    final ExecutableNode node = runner.getNode();

    eventCollector.handleEvent(Event.create(null, EventType.JOB_STARTED, new EventData(node)));
    Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED);

    final Thread thread = startThread(runner);

    // wait for job to get into delayExecution() -> wait()
    assertThreadIsWaiting(thread);
    // Wake up delayExecution() -> wait()
    notifyWaiting(runner);
    assertThreadIsNotAlive(thread);

    eventCollector.handleEvent(Event.create(null, EventType.JOB_FINISHED, new EventData(node)));

    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue("Node status is " + node.getStatus(),
        node.getStatus() == Status.SUCCEEDED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
    Assert.assertTrue(node.getEndTime() - node.getStartTime() >= 0);
    Assert.assertTrue(node.getStartTime() - startTime >= 0);

    Assert.assertFalse("Thread ContextClassLoader not reset properly",
        thread.getContextClassLoader() instanceof JobClassLoader);

    final File logFile = new File(runner.getLogFilePath());
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertTrue(outputProps != null);
    Assert.assertTrue(logFile.exists());
    Assert.assertFalse(runner.isKilled());
    Assert.assertTrue(loader.getNodeUpdateCount(node.getId()) == 3);

    Assert.assertTrue(eventCollector.checkOrdering());
    Assert.assertFalse(runner.getLogger().getAllAppenders().hasMoreElements());
    eventCollector
        .assertEvents(EventType.JOB_STARTED, EventType.JOB_STATUS_CHANGED, EventType.JOB_FINISHED);
  }

  @Test
  public void testDelayedExecutionCancelledJob() throws Exception {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final JobRunner runner =
        createJobRunner(1, "testJob", 0, false, loader, eventCollector);
    runner.setDelayStart(10_000);
    final long startTime = System.currentTimeMillis();
    final ExecutableNode node = runner.getNode();

    eventCollector.handleEvent(Event.create(null, EventType.JOB_STARTED, new EventData(node)));
    Assert.assertTrue(runner.getStatus() != Status.SUCCEEDED);

    final Thread thread = startThread(runner);

    StatusTestUtils.waitForStatus(node, Status.READY);
    // wait for job to get into delayExecution() -> wait()
    assertThreadIsWaiting(thread);
    runner.kill();
    StatusTestUtils.waitForStatus(node, Status.KILLED);

    eventCollector.handleEvent(Event.create(null, EventType.JOB_FINISHED, new EventData(node)));

    Assert.assertTrue(runner.getStatus() == node.getStatus());
    Assert.assertTrue("Node status is " + node.getStatus(),
        node.getStatus() == Status.KILLED);
    Assert.assertTrue(node.getStartTime() > 0 && node.getEndTime() > 0);
    Assert.assertTrue(node.getEndTime() - node.getStartTime() < 1000);
    Assert.assertTrue(node.getStartTime() - startTime >= 0);
    Assert.assertTrue(node.getStartTime() - startTime <= 5000);
    Assert.assertTrue(runner.isKilled());

    Assert.assertFalse("Thread ContextClassLoader not reset properly",
        thread.getContextClassLoader() instanceof JobClassLoader);

    final File logFile = new File(runner.getLogFilePath());
    final Props outputProps = runner.getNode().getOutputProps();
    Assert.assertTrue(outputProps == null);
    Assert.assertTrue(logFile.exists());

    // wait so that there's time to make the "DB update" for KILLED status
    TestUtils.await().untilAsserted(
        () -> assertThat(loader.getNodeUpdateCount("testJob")).isEqualTo(2));
    Assert.assertFalse(runner.getLogger().getAllAppenders().hasMoreElements());
    eventCollector.assertEvents(EventType.JOB_FINISHED);
  }

  @Test
  public void testCustomLogLayout() throws IOException {
    final MockExecutorLoader loader = new MockExecutorLoader();
    final EventCollectorListener eventCollector = new EventCollectorListener();
    final Props azkabanProps = new Props();
    azkabanProps.put(JobProperties.JOB_LOG_LAYOUT, "TEST %c{1} %p - %m\n");
    final JobRunner runner =
        createJobRunner(1, "testJob", 0, false, loader, eventCollector, azkabanProps);
    runner.run();
    try (final BufferedReader br = getLogReader(runner.getLogFile())) {
      final String firstLine = br.readLine();
      Assert.assertTrue("Unexpected default layout",
          firstLine.startsWith("TEST"));
    }
    Assert.assertFalse("Thread ContextClassLoader not reset properly",
        Thread.currentThread().getContextClassLoader() instanceof JobClassLoader);
  }

  private Props createProps(final int sleepSec, final boolean fail, Props props) {
    props.put("type", "test");
    props.put("seconds", sleepSec);
    props.put("fail", String.valueOf(fail));
    return props;
  }

  private JobRunner createJobRunner(final int execId, final String name, final int time,
      final boolean fail, final ExecutorLoader loader, final EventCollectorListener listener) {
    return createJobRunner(execId, name, time, fail, loader, listener, new Props());
  }

  private JobRunner createJobRunner(final int execId, final String name, final int time,
      final boolean fail, final ExecutorLoader loader, final EventCollectorListener listener, Props jobProps) {
    final Props azkabanProps = new Props();
    final ExecutableFlow flow = new ExecutableFlow();
    flow.setExecutionId(execId);
    flow.setSubmitUser(SUBMIT_USER);
    // Test version
    flow.setVersionSet(createVersionSet());
    final ExecutableNode node = new ExecutableNode();
    node.setId(name);
    node.setParentFlow(flow);
    // Test version info
    node.setType("spark");

    final Props props = createProps(time, fail, jobProps);
    node.setInputProps(props);
    final HashSet<String> proxyUsers = new HashSet<>();
    proxyUsers.add(flow.getSubmitUser());
    FlowRunnerProxy flowRunnerProxy = Mockito.mock(FlowRunnerProxy.class);
    final JobRunner runner = new JobRunner(node, this.workingDir, loader, this.jobtypeManager,
        azkabanProps, flowRunnerProxy);
    runner.setLogSettings(this.logger, "5MB", 4);

    runner.addListener(listener);
    return runner;
  }

  private void assertThreadIsWaiting(final Thread thread) throws Exception {
    TestUtils.await().until(
        () -> thread.getState() == TIMED_WAITING || thread.getState() == WAITING);
  }

  private void assertThreadIsNotAlive(final Thread thread) throws Exception {
    thread.join(9000L);
    TestUtils.await().atMost(1000L, TimeUnit.MILLISECONDS).until(() -> !thread.isAlive());
  }

  private void notifyWaiting(final Object monitor) {
    synchronized (monitor) {
      monitor.notifyAll();
    }
  }

  private Thread startThread(final JobRunner runner) {
    final Thread thread = new Thread(runner);
    thread.start();
    return thread;
  }

  /**
   * Creates a new version set from scratch
   * @return a new version set
   */
  private VersionSet createVersionSet(){
    final String testJsonString1 = "{\"azkaban-base\":{\"version\":\"7.0.4\",\"path\":\"path1\","
        + "\"state\":\"ACTIVE\"},\"azkaban-config\":{\"version\":\"9.1.1\",\"path\":\"path2\","
        + "\"state\":\"ACTIVE\"},\"spark\":{\"version\":\"8.0\",\"path\":\"path3\","
        + "\"state\":\"ACTIVE\"}}";
    final String testMd5Hex1 = "43966138aebfdc4438520cc5cd2aefa8";
    return new VersionSet(testJsonString1, testMd5Hex1, 1);
  }
}
