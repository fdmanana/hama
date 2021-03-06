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

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableFactories;
import org.apache.hadoop.io.WritableFactory;

/**
 *
 */
public class GroomServerStatus implements Writable {
  public static final Log LOG = LogFactory.getLog(GroomServerStatus.class);
  
  static {
    WritableFactories.setFactory
      (GroomServerStatus.class,
       new WritableFactory() {
         public Writable newInstance() { return new GroomServerStatus(); }
       });
  }
  
  String groomName;
  String peerName;
  int failures;
  List<TaskStatus> taskReports;
  
  volatile long lastSeen;
  private int maxTasks;

  public GroomServerStatus() {
    taskReports = new ArrayList<TaskStatus>();
  }
  
  public GroomServerStatus(String groomName, String peerName,
      List<TaskStatus> taskReports, int failures, int maxTasks) {
    this.groomName = groomName;
    this.peerName = peerName;
    this.taskReports = new ArrayList<TaskStatus>(taskReports);
    this.failures = failures;
    this.maxTasks = maxTasks;
  }
  
  public String getGroomName() {
    return groomName;
  }
  
  /**
   * The host (and port) from where the groom server can be reached.
   *
   * @return The groom server address in the format hostname:port
   */
  public String getPeerName() {
    return peerName;
  }
  
  /**
   * Get the current tasks at the GroomServer.
   * Tasks are tracked by a {@link TaskStatus} object.
   * 
   * @return a list of {@link TaskStatus} representing 
   *         the current tasks at the GroomServer.
   */
  public List<TaskStatus> getTaskReports() {
    return taskReports;
  }
  
  public int getFailures() {
    return failures;
  }  
  
  public long getLastSeen() {
    return lastSeen;
  }
  
  public void setLastSeen(long lastSeen) {
    this.lastSeen = lastSeen;
  }

  public int getMaxTasks() {
    return maxTasks;
  }
  
  /**
   * Return the current MapTask count
   */
  public int countTasks() {
    int taskCount = 0;
    for (Iterator<TaskStatus> it = taskReports.iterator(); it.hasNext();) {
      TaskStatus ts = it.next();
      TaskStatus.State state = ts.getRunState();
      if(state == TaskStatus.State.RUNNING || 
           state == TaskStatus.State.UNASSIGNED) {
             taskCount++;
      }    
    }    
    
    return taskCount;    
  }
  
  /* (non-Javadoc)
   * @see org.apache.hadoop.io.Writable#readFields(java.io.DataInput)
   */
  @Override
  public void readFields(DataInput in) throws IOException {
    this.groomName = Text.readString(in);
    this.peerName = Text.readString(in);
    this.failures = in.readInt();
    this.maxTasks = in.readInt();
    taskReports.clear();
    int numTasks = in.readInt();
    
    TaskStatus status;
    for (int i = 0; i < numTasks; i++) {
      status = new TaskStatus();
      status.readFields(in);
      taskReports.add(status);
    }   
  }

  /* (non-Javadoc)
   * @see org.apache.hadoop.io.Writable#write(java.io.DataOutput)
   */
  @Override
  public void write(DataOutput out) throws IOException {
    Text.writeString(out, groomName);
    Text.writeString(out, peerName);
    out.writeInt(failures);
    out.writeInt(maxTasks);
    out.writeInt(taskReports.size());
    for(TaskStatus taskStatus : taskReports) {
      taskStatus.write(out);
    }
  }

  public Iterator<TaskStatus> taskReports() {
    return taskReports.iterator();
  }

}
