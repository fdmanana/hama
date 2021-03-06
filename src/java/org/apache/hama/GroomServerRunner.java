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
package org.apache.hama;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hama.bsp.GroomServer;

/**
 * This class starts and runs the GroomServer 
 */
public class GroomServerRunner extends Configured implements Tool {

  public static final Log LOG = LogFactory.getLog(GroomServerRunner.class);

  @Override
  public int run(String[] args) throws Exception {
    StringUtils.startupShutdownMessage(GroomServer.class, args, LOG);

    if (args.length != 0) {
      System.out.println("usage: GroomServerRunner");
      System.exit(-1);
    }

    try {
      Configuration conf = new HamaConfiguration(getConf());
      GroomServer groom = GroomServer.constructGroomServer(GroomServer.class,
          conf);
      GroomServer.startGroomServer(groom).join();
    } catch (Throwable e) {
      LOG.fatal(StringUtils.stringifyException(e));
      return -1;
    }

    return 0;
  }

  public static void main(String[] args) throws Exception {
    int exitCode = ToolRunner.run(new GroomServerRunner(), args);
    System.exit(exitCode);
  }

}
