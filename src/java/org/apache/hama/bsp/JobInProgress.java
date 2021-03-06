/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hama.bsp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;

/**
 * JobInProgress maintains all the info for keeping a Job on the straight and
 * narrow. It keeps its JobProfile and its latest JobStatus, plus a set of
 * tables for doing bookkeeping of its Tasks.ss
 */
class JobInProgress {
  /**
   * Used when the a kill is issued to a job which is initializing.
   */
  static class KillInterruptedException extends InterruptedException {
    private static final long serialVersionUID = 1L;

    public KillInterruptedException(String msg) {
      super(msg);
    }
  }

  static final Log LOG = LogFactory.getLog(JobInProgress.class);
  boolean tasksInited = false;

  Configuration conf;
  JobProfile profile;
  JobStatus status;
  Path jobFile = null;
  Path localJobFile = null;
  Path localJarFile = null;
  private LocalFileSystem localFs;

  long startTime;
  long launchTime;
  long finishTime;

  // private LocalFileSystem localFs;
  private BSPJobID jobId;
  final BSPMaster master;
  TaskInProgress tasks[] = new TaskInProgress[0];
  private long superstepCounter;

  int numBSPTasks = 0;
  int clusterSize;

  public JobInProgress(BSPJobID jobId, BSPMaster master, Configuration conf)
      throws IOException {
    this.conf = conf;
    this.jobId = jobId;
    this.localFs = FileSystem.getLocal(conf);

    this.master = master;
    this.status = new JobStatus(jobId, 0.0f, 0.0f, JobStatus.PREP);
    this.startTime = System.currentTimeMillis();
    status.setStartTime(startTime);
    this.superstepCounter = 0;

    this.localJobFile = master.getLocalPath(BSPMaster.SUBDIR + "/" + jobId
        + ".xml");
    this.localJarFile = master.getLocalPath(BSPMaster.SUBDIR + "/" + jobId
        + ".jar");

    Path jobDir = master.getSystemDirectoryForJob(jobId);
    FileSystem fs = jobDir.getFileSystem(conf);
    jobFile = new Path(jobDir, "job.xml");
    fs.copyToLocalFile(jobFile, localJobFile);
    BSPJob job = new BSPJob(jobId, localJobFile.toString());
    this.numBSPTasks = job.getNumBspTask();

    this.profile = new JobProfile(job.getUser(), jobId, jobFile.toString(), job
        .getJobName());

    String jarFile = job.getJar();
    if (jarFile != null) {
      fs.copyToLocalFile(new Path(jarFile), localJarFile);
    }

  }

  public JobProfile getProfile() {
    return profile;
  }

  public JobStatus getStatus() {
    return status;
  }

  public synchronized long getLaunchTime() {
    return launchTime;
  }

  public long getStartTime() {
    return startTime;
  }

  public long getFinishTime() {
    return finishTime;
  }

  /**
   * @return the number of desired tasks.
   */
  public int desiredBSPTasks() {
    return numBSPTasks;
  }

  /**
   * @return The JobID of this JobInProgress.
   */
  public BSPJobID getJobID() {
    return jobId;
  }

  public String toString() {
    return "jobName:" + profile.getJobName() + "\n" + "submit user:"
        + profile.getUser() + "\n" + "JobId:" + jobId + "\n" + "JobFile:"
        + jobFile + "\n";
  }

  // ///////////////////////////////////////////////////
  // Create/manage tasks
  // ///////////////////////////////////////////////////

  public synchronized void initTasks() throws IOException {
    if (tasksInited) {
      return;
    }

    // adjust number of map tasks to actual number of splits
    this.tasks = new TaskInProgress[numBSPTasks];
    for (int i = 0; i < numBSPTasks; i++) {
      tasks[i] = new TaskInProgress(getJobID(), this.jobFile.toString(),
          this.master, this.conf, this, i);
    }

    // Update job status
    this.status = new JobStatus(this.status.getJobID(), 1.0f, 1.0f,
       JobStatus.RUNNING);

    tasksInited = true;
    LOG.debug("Job is initialized.");
  }

  public synchronized Task obtainNewTask(GroomServerStatus status,
      int clusterSize, int numUniqueHosts) {
    this.clusterSize = clusterSize;
    
    if (this.status.getRunState() != JobStatus.RUNNING) {
      LOG.info("Cannot create task split for " + profile.getJobID());
      return null;
    }
    
    Task result = null;
    try {
      for (int i = 0; i < tasks.length; i++) {
        if(!tasks[i].isRunning()) {
          result = tasks[i].getTaskToRun(status);
          break;
        }
      }
      
    } catch (IOException e) {
      e.printStackTrace();
    }

    return result;
  }

  public synchronized void completedTask(TaskInProgress tip, TaskStatus status) {
    String taskid = status.getTaskId();
    updateTaskStatus(tip, status);
    LOG.info("Taskid '" + taskid + "' has finished successfully.");
    tip.completed(taskid);

    //
    // If all tasks are complete, then the job is done!
    //

    boolean allDone = true;
    for (TaskInProgress taskInProgress : tasks) {
      if (!taskInProgress.isComplete()) {
        allDone = false;
        break;
      }
    }

    if (allDone) {
      LOG.debug("Job successfully done.");

      this.status = new JobStatus(this.status.getJobID(), 1.0f, 1.0f, 1.0f,
          JobStatus.SUCCEEDED, superstepCounter);
      garbageCollect();
    }
  }

  public synchronized void updateTaskStatus(TaskInProgress tip,
      TaskStatus taskStatus) {
    tip.updateStatus(taskStatus); // update tip

    if (superstepCounter < taskStatus.getSuperstepCount()) {
      superstepCounter = taskStatus.getSuperstepCount();
      // TODO Later, we have to update JobInProgress status here

    }
  }

  public void kill() {
    // TODO Auto-generated method stub

  }

  /**
   * The job is dead. We're now GC'ing it, getting rid of the job from all
   * tables. Be sure to remove all of this job's tasks from the various tables.
   */
  synchronized void garbageCollect() {
    try {
      // Definitely remove the local-disk copy of the job file
      if (localJobFile != null) {
        localFs.delete(localJobFile, true);
        localJobFile = null;
      }
      if (localJarFile != null) {
        localFs.delete(localJarFile, true);
        localJarFile = null;
      }

      // JobClient always creates a new directory with job files
      // so we remove that directory to cleanup
      FileSystem fs = FileSystem.get(conf);
      fs.delete(new Path(profile.getJobFile()).getParent(), true);

    } catch (IOException e) {
      LOG.info("Error cleaning up " + profile.getJobID() + ": " + e);
    }
  }
}
