/*
 * Copyright 2015 LinkedIn Corp.
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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;

import azkaban.executor.ExecutorInfo;
import azkaban.utils.JSONUtils;

public class ServerStatisticsServlet extends HttpServlet  {
  private static final long serialVersionUID = 1L;
  private static final int  cacheTimeInMilliseconds = 1000;
  private static final int  samplesToTakeForMemory = 1;
  private static final Logger logger = Logger.getLogger(ServerStatisticsServlet.class);
  private static final String noCacheParamName = "nocache";

  protected static long lastRefreshedTime = 0;
  protected static ExecutorInfo cachedstats = null;

  /**
   * Handle all get request to Statistics Servlet {@inheritDoc}
   *
   * @see javax.servlet.http.HttpServlet#doGet(javax.servlet.http.HttpServletRequest,
   *      javax.servlet.http.HttpServletResponse)
   */
  protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {

    boolean noCache = null!= req && Boolean.valueOf(req.getParameter(noCacheParamName));

    if (noCache || System.currentTimeMillis() - lastRefreshedTime > cacheTimeInMilliseconds){
      this.populateStatistics(noCache);
    }

    JSONUtils.toJSON(cachedstats, resp.getOutputStream(), true);
  }

  /**
   * fill the result set with the percent of the remaining system memory on the server.
   * @param stats reference to the result container which contains all the results, this specific method
   *              will only work work on the property "remainingMemory" and "remainingMemoryPercent".
   *
   * NOTE:
   * a double value will be used to present the remaining memory,
   *         a returning value of '55.6' means 55.6%
   */
  protected void fillRemainingMemoryPercent(ExecutorInfo stats){
    if (new File("/bin/bash").exists() &&  new File("/usr/bin/free").exists()) {
      java.lang.ProcessBuilder processBuilder =
          new java.lang.ProcessBuilder("/bin/bash", "-c", String.format("/usr/bin/free -m -s 0.1 -c %s | grep Mem:",
              samplesToTakeForMemory));
      try {
        List<String> output = new ArrayList<String>();
        Process process = processBuilder.start();
        process.waitFor();
        InputStream inputStream = process.getInputStream();
        try {
          java.io.BufferedReader reader =
              new java.io.BufferedReader(new InputStreamReader(inputStream));
          String line = null;
          while ((line = reader.readLine()) != null) {
            output.add(line);
          }
        }finally {
          inputStream.close();
        }
        // process the output from bash call.
        if (output.size() > 0) {
          long totalMemory = 0 ;
          long freeMemory  = 0 ;
          int  sampleCount = 0 ;
          for(String line : output){
            String[] splitedresult = line.split("\\s+");
            // expected return format -
            // "Mem:" | total | used | free | shared | buffers | cached
            if (splitedresult.length == 7){
              // create a temp copy of all the readings, if anything goes wrong, we drop the
              // temp reading and move on.
              long tmp_totalMemory = 0 ;
              long tmp_freeMemory  = 0 ;
              try {
                tmp_totalMemory = Long.parseLong(splitedresult[1]);
                tmp_freeMemory = Long.parseLong(splitedresult[3]);

              } catch(NumberFormatException e){
                logger.error("skipping the unprocessable line from the output -" + line);
                continue;
              }

              // add up the result.
              ++sampleCount;
              totalMemory += tmp_totalMemory ;
              freeMemory  += tmp_freeMemory;
            }
          }

          // set the value.
          if (sampleCount > 0){
            freeMemory  = freeMemory  / sampleCount;
            totalMemory = totalMemory / sampleCount;
            logger.info(String.format("total memory - %s , free memory - %s", totalMemory, freeMemory));
            stats.setRemainingMemory(freeMemory);
            stats.setRemainingMemoryPercent(totalMemory == 0? 0 :
              ((double)freeMemory/(double)totalMemory) * 100);
          }
        }
      }
      catch (Exception ex){
        logger.error("failed fetch system memory info " +
                     "as exception is captured when fetching result from bash call. ex -" + ex.getMessage());
      }
    } else {
        logger.error("failed fetch system memory info " +
                     "as 'bash' or 'free' command can't be found on the current system.");
    }
  }

  /**
   * call the data providers to fill the returning data container for statistics data.
   * This function refreshes the static cached copy of data in case if necessary.
   * */
  protected synchronized void populateStatistics(boolean noCache){
    //check again before starting the work.
    if (noCache || System.currentTimeMillis() - lastRefreshedTime  > cacheTimeInMilliseconds){
      final ExecutorInfo stats = new ExecutorInfo();

      List<Thread> workerPool = new ArrayList<Thread>();
      workerPool.add(new Thread(new Runnable(){ public void run() {
        fillRemainingMemoryPercent(stats); }},"RemainingMemoryPercent"));

      workerPool.add(new Thread(new Runnable(){ public void run() {
        fillRemainingFlowCapacityAndLastDispatchedTime(stats); }},"RemainingFlowCapacityAndLastDispatchedTime"));

      workerPool.add(new Thread(new Runnable(){ public void run() {
        fillCpuUsage(stats); }},"CpuUsage"));

      // start all the working threads.
      for (Thread thread : workerPool){thread.start();}

      // wait for all the threads to finish their work.
      // NOTE: the result container itself is not thread safe, we are good as for now no
      //       working thread will modify the same property, nor have any of them
      //       need to compute values based on value(s) of other properties.
      for (Thread thread : workerPool){
        try {
          // we gave maxim 5 seconds to let the thread finish work.
          thread.join(5000);;
        } catch (InterruptedException e) {
          logger.error(String.format("failed to collect information for %s as the working thread is interrupted. Ex - ",
              thread.getName(), e.getMessage()));
        }}

      cachedstats = stats;
      lastRefreshedTime =  System.currentTimeMillis();
    }
  }

  /**
   * fill the result set with the remaining flow capacity .
   * @param stats reference to the result container which contains all the results, this specific method
   *              will only work on the property "remainingFlowCapacity".
   */
  protected void fillRemainingFlowCapacityAndLastDispatchedTime(ExecutorInfo stats){

    AzkabanExecutorServer server = AzkabanExecutorServer.getApp();
    if (server != null){
      FlowRunnerManager runnerMgr =  AzkabanExecutorServer.getApp().getFlowRunnerManager();
      int assignedFlows = runnerMgr.getNumRunningFlows() + runnerMgr.getNumQueuedFlows();
      stats.setRemainingFlowCapacity(runnerMgr.getMaxNumRunningFlows() - assignedFlows);
      stats.setNumberOfAssignedFlows(assignedFlows);
      stats.setLastDispatchedTime(runnerMgr.getLastFlowSubmittedTime());
    }else {
      logger.error("failed to get data for remaining flow capacity or LastDispatchedTime" +
                   " as the AzkabanExecutorServer has yet been initialized.");
    }
  }


  /**<pre>
   * fill the result set with the Remaining temp Storage .
   * Note : As the Top bash call doesn't yield accurate result for the system load,
   *        the implementation has been changed to load from the "proc/loadavg" which keeps
   *        the moving average of the system load, we are pulling the average for the recent 1 min.
   *</pre>
   * @param stats reference to the result container which contains all the results, this specific method
   *              will only work on the property "cpuUdage".
   */
  protected void fillCpuUsage(ExecutorInfo stats){
    if (new File("/bin/bash").exists() && new File("/bin/cat").exists() &&  new File("/proc/loadavg").exists()) {
      java.lang.ProcessBuilder processBuilder =
          new java.lang.ProcessBuilder("/bin/bash", "-c", "/bin/cat /proc/loadavg");
      try {
        ArrayList<String> output = new ArrayList<String>();
        Process process = processBuilder.start();
        process.waitFor();
        InputStream inputStream = process.getInputStream();
        try {
          java.io.BufferedReader reader =
              new java.io.BufferedReader(new InputStreamReader(inputStream));
          String line = null;
          while ((line = reader.readLine()) != null) {
            output.add(line);
          }
        }finally {
          inputStream.close();
        }

        // process the output from bash call.
        if (output.size() > 0) {
          String[] splitedresult = output.get(0).split("\\s+");
          double cpuUsage = 0.0;

          try {
            cpuUsage = Double.parseDouble(splitedresult[1].split("%")[0]);
          }catch(NumberFormatException e){
            logger.error("yielding 0.0 for CPU usage as output is invalid -" + output.get(0));
          }
          stats.setCpuUpsage(cpuUsage);
        }
      }
      catch (Exception ex){
        logger.error("failed fetch system load info " +
                     "as exception is captured when fetching result from bash call. Ex -" + ex.getMessage());
      }
    } else {
        logger.error("failed fetch system load info, one or more files from the following list are missing -  " +
                     "'/bin/bash'," + "'/bin/cat'," +"'/proc/loadavg'");
    }
  }
}
