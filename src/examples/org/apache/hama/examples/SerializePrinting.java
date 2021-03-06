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
package org.apache.hama.examples;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.bsp.BSP;
import org.apache.hama.bsp.BSPJob;
import org.apache.hama.bsp.BSPJobClient;
import org.apache.hama.bsp.BSPPeer;
import org.apache.hama.bsp.ClusterStatus;
import org.apache.zookeeper.KeeperException;

public class SerializePrinting {
  
  public static class HelloBSP extends BSP {
    public static final Log LOG = LogFactory.getLog(HelloBSP.class);
    private Configuration conf;
    private final static int PRINT_INTERVAL = 5000;

    @Override
    public void bsp(BSPPeer bspPeer) throws IOException, KeeperException,
        InterruptedException {
      int num = Integer.parseInt(conf.get("bsp.peers.num"));

      int i = 0;
      for (String otherPeer : bspPeer.getAllPeerNames()) {
        if (bspPeer.getPeerName().equals(otherPeer)) {
          LOG.info("Hello BSP from " + (i + 1) + " of " + num + ": "
              + bspPeer.getPeerName());
        }
        
        Thread.sleep(PRINT_INTERVAL);
        bspPeer.sync();
        i++;
      }
    }

    @Override
    public Configuration getConf() {
      return conf;
    }

    @Override
    public void setConf(Configuration conf) {
      this.conf = conf;
    }

  }

  public static void main(String[] args) throws InterruptedException,
      IOException {
    // BSP job configuration
    HamaConfiguration conf = new HamaConfiguration();

    BSPJob bsp = new BSPJob(conf, SerializePrinting.class);
    // Set the job name
    bsp.setJobName("serialize printing");
    bsp.setBspClass(HelloBSP.class);
    
    // Set the task size as a number of GroomServer
    BSPJobClient jobClient = new BSPJobClient(conf);
    ClusterStatus cluster = jobClient.getClusterStatus(false);
    bsp.setNumBspTask(cluster.getGroomServers());
    
    BSPJobClient.runJob(bsp);
  }
}
