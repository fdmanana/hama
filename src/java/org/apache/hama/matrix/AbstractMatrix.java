/**
 * Copyright 2007 The Apache Software Foundation
 *
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
package org.apache.hama.matrix;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.RegionException;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableMapper;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.MapWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hama.Constants;
import org.apache.hama.HamaAdmin;
import org.apache.hama.HamaAdminImpl;
import org.apache.hama.HamaConfiguration;
import org.apache.hama.io.VectorUpdate;
import org.apache.hama.matrix.algebra.JacobiEigenValue;
import org.apache.hama.matrix.algebra.MatrixNormMapRed;
import org.apache.hama.matrix.algebra.TransposeMap;
import org.apache.hama.matrix.algebra.TransposeReduce;
import org.apache.hama.matrix.algebra.MatrixNormMapRed.MatrixFrobeniusNormCombiner;
import org.apache.hama.matrix.algebra.MatrixNormMapRed.MatrixFrobeniusNormMapper;
import org.apache.hama.matrix.algebra.MatrixNormMapRed.MatrixFrobeniusNormReducer;
import org.apache.hama.matrix.algebra.MatrixNormMapRed.MatrixInfinityNormMapper;
import org.apache.hama.matrix.algebra.MatrixNormMapRed.MatrixInfinityNormReducer;
import org.apache.hama.matrix.algebra.MatrixNormMapRed.MatrixMaxValueNormMapper;
import org.apache.hama.matrix.algebra.MatrixNormMapRed.MatrixMaxValueNormReducer;
import org.apache.hama.matrix.algebra.MatrixNormMapRed.MatrixOneNormCombiner;
import org.apache.hama.matrix.algebra.MatrixNormMapRed.MatrixOneNormMapper;
import org.apache.hama.matrix.algebra.MatrixNormMapRed.MatrixOneNormReducer;
import org.apache.hama.util.BytesUtil;
import org.apache.hama.util.JobManager;
import org.apache.hama.util.RandomVariable;
import org.apache.log4j.Logger;

/**
 * Methods of the matrix classes
 */
public abstract class AbstractMatrix implements Matrix {
  static int tryPathLength = Constants.DEFAULT_PATH_LENGTH;
  static final Logger LOG = Logger.getLogger(AbstractMatrix.class);

  protected HamaConfiguration config;
  protected HBaseAdmin admin;
  // a matrix just need a table path to point to the table which stores matrix.
  // let HamaAdmin manage Matrix Name space.
  protected String matrixPath;
  protected HTable table;
  protected HTableDescriptor tableDesc;
  protected HamaAdmin hamaAdmin;

  protected boolean closed = true;

  /**
   * Sets the job configuration
   * 
   * @param conf configuration object
   * @throws MasterNotRunningException
   */
  public void setConfiguration(HamaConfiguration conf)
      throws MasterNotRunningException {
    this.config = conf;
    this.admin = new HBaseAdmin(config);

    hamaAdmin = new HamaAdminImpl(conf, admin);
  }

  /**
   * try to create a new matrix with a new random name. try times will be
   * (Integer.MAX_VALUE - 4) * DEFAULT_TRY_TIMES;
   * 
   * @throws IOException
   */
  protected void tryToCreateTable(String table_prefix) throws IOException {
    int tryTimes = Constants.DEFAULT_TRY_TIMES;
    do {
      matrixPath = table_prefix + "_"
          + RandomVariable.randMatrixPath(tryPathLength);

      if (!admin.tableExists(matrixPath)) { // no table 'matrixPath' in hbase.
        tableDesc = new HTableDescriptor(matrixPath);
        create();
        return;
      }

      tryTimes--;
      if (tryTimes <= 0) { // this loop has exhausted DEFAULT_TRY_TIMES.
        tryPathLength++;
        tryTimes = Constants.DEFAULT_TRY_TIMES;
      }

    } while (tryPathLength <= Constants.DEFAULT_MAXPATHLEN);
    // exhaustes the try times.
    // throw out an IOException to let the user know what happened.
    throw new IOException("Try too many times to create a table in hbase.");
  }

  /**
   * Create matrix space
   */
  protected void create() throws IOException {
    // It should run only when table doesn't exist.
    if (!admin.tableExists(matrixPath)) {
      this.tableDesc.addFamily(new HColumnDescriptor(Bytes
          .toBytes(Constants.COLUMN_FAMILY)));
      this.tableDesc.addFamily(new HColumnDescriptor(Bytes
          .toBytes(Constants.ATTRIBUTE)));
      this.tableDesc.addFamily(new HColumnDescriptor(Bytes
          .toBytes(Constants.ALIASEFAMILY)));

      // It's a temporary data.
      this.tableDesc.addFamily(new HColumnDescriptor(Bytes
          .toBytes(Constants.BLOCK)));
      // the following families are used in JacobiEigenValue computation
      this.tableDesc.addFamily(new HColumnDescriptor(Bytes
          .toBytes(JacobiEigenValue.EI_COLUMNFAMILY)));
      this.tableDesc.addFamily(new HColumnDescriptor(Bytes
          .toBytes(JacobiEigenValue.EICOL)));
      this.tableDesc.addFamily(new HColumnDescriptor(Bytes
          .toBytes(JacobiEigenValue.EIVEC)));

      LOG.info("Initializing the matrix storage.");
      this.admin.createTable(this.tableDesc);
      LOG.info("Create Matrix " + matrixPath);

      // connect to the table.
      table = new HTable(config, matrixPath);
      table.setAutoFlush(true);

      // Record the matrix type in METADATA_TYPE
      Put put = new Put(Bytes.toBytes(Constants.METADATA));
      put.add(Bytes.toBytes(Constants.ATTRIBUTE), Bytes
          .toBytes(Constants.METADATA_TYPE), Bytes.toBytes(this.getClass()
          .getSimpleName()));
      table.put(put);

      // the new matrix's reference is 1.
      setReference(1);
    }
  }

  public HTable getHTable() {
    return this.table;
  }

  protected double getNorm1() throws IOException {
    JobConf jobConf = new JobConf(config);
    jobConf.setJobName("norm1 MR job : " + this.getPath());

    jobConf.setNumMapTasks(config.getNumMapTasks());
    jobConf.setNumReduceTasks(1);

    final FileSystem fs = FileSystem.get(jobConf);
    Path outDir = new Path(new Path(getType() + "_TMP_norm1_dir_"
        + System.currentTimeMillis()), "out");
    if (fs.exists(outDir))
      fs.delete(outDir, true);

    MatrixNormMapRed.initJob(this.getPath(), outDir.toString(),
        MatrixOneNormMapper.class, MatrixOneNormCombiner.class,
        MatrixOneNormReducer.class, jobConf);

    // update the out put dir of the job
    outDir = FileOutputFormat.getOutputPath(jobConf);
    JobManager.execute(jobConf);

    // read outputs
    Path inFile = new Path(outDir, "reduce-out");
    IntWritable numInside = new IntWritable();
    DoubleWritable max = new DoubleWritable();
    SequenceFile.Reader reader = new SequenceFile.Reader(fs, inFile, jobConf);
    try {
      reader.next(numInside, max);
    } finally {
      reader.close();
    }

    fs.delete(outDir.getParent(), true);
    return max.get();
  }

  protected double getMaxvalue() throws IOException {
    JobConf jobConf = new JobConf(config);
    jobConf.setJobName("MaxValue Norm MR job : " + this.getPath());

    jobConf.setNumMapTasks(config.getNumMapTasks());
    jobConf.setNumReduceTasks(1);

    final FileSystem fs = FileSystem.get(jobConf);
    Path outDir = new Path(new Path(getType() + "_TMP_normMaxValue_dir_"
        + System.currentTimeMillis()), "out");
    if (fs.exists(outDir))
      fs.delete(outDir, true);

    MatrixNormMapRed.initJob(this.getPath(), outDir.toString(),
        MatrixMaxValueNormMapper.class, MatrixMaxValueNormReducer.class,
        MatrixMaxValueNormReducer.class, jobConf);

    // update the out put dir of the job
    outDir = FileOutputFormat.getOutputPath(jobConf);
    JobManager.execute(jobConf);

    // read outputs
    Path inFile = new Path(outDir, "part-00000");
    IntWritable numInside = new IntWritable();
    DoubleWritable max = new DoubleWritable();
    SequenceFile.Reader reader = new SequenceFile.Reader(fs, inFile, jobConf);
    try {
      reader.next(numInside, max);
    } finally {
      reader.close();
    }

    fs.delete(outDir.getParent(), true);
    return max.get();
  }

  protected double getInfinity() throws IOException {
    JobConf jobConf = new JobConf(config);
    jobConf.setJobName("Infinity Norm MR job : " + this.getPath());

    jobConf.setNumMapTasks(config.getNumMapTasks());
    jobConf.setNumReduceTasks(1);

    final FileSystem fs = FileSystem.get(jobConf);
    Path outDir = new Path(new Path(getType() + "_TMP_normInifity_dir_"
        + System.currentTimeMillis()), "out");
    if (fs.exists(outDir))
      fs.delete(outDir, true);

    MatrixNormMapRed.initJob(this.getPath(), outDir.toString(),
        MatrixInfinityNormMapper.class, MatrixInfinityNormReducer.class,
        MatrixInfinityNormReducer.class, jobConf);

    // update the out put dir of the job
    outDir = FileOutputFormat.getOutputPath(jobConf);

    JobManager.execute(jobConf);

    // read outputs
    Path inFile = new Path(outDir, "part-00000");
    IntWritable numInside = new IntWritable();
    DoubleWritable max = new DoubleWritable();
    SequenceFile.Reader reader = new SequenceFile.Reader(fs, inFile, jobConf);
    try {
      reader.next(numInside, max);
    } finally {
      reader.close();
    }

    fs.delete(outDir.getParent(), true);
    return max.get();
  }

  protected double getFrobenius() throws IOException {
    JobConf jobConf = new JobConf(config);
    jobConf.setJobName("Frobenius Norm MR job : " + this.getPath());

    jobConf.setNumMapTasks(config.getNumMapTasks());
    jobConf.setNumReduceTasks(1);

    final FileSystem fs = FileSystem.get(jobConf);
    Path outDir = new Path(new Path(getType() + "_TMP_normFrobenius_dir_"
        + System.currentTimeMillis()), "out");
    if (fs.exists(outDir))
      fs.delete(outDir, true);

    MatrixNormMapRed.initJob(this.getPath(), outDir.toString(),
        MatrixFrobeniusNormMapper.class, MatrixFrobeniusNormCombiner.class,
        MatrixFrobeniusNormReducer.class, jobConf);

    // update the out put dir of the job
    outDir = FileOutputFormat.getOutputPath(jobConf);

    JobManager.execute(jobConf);

    // read outputs
    Path inFile = new Path(outDir, "part-00000");
    IntWritable numInside = new IntWritable();
    DoubleWritable sqrt = new DoubleWritable();
    SequenceFile.Reader reader = new SequenceFile.Reader(fs, inFile, jobConf);
    try {
      reader.next(numInside, sqrt);
    } finally {
      reader.close();
    }

    fs.delete(outDir.getParent(), true);
    return sqrt.get();
  }

  /** {@inheritDoc} */
  public int getRows() throws IOException {
    Get get = new Get(Bytes.toBytes(Constants.METADATA));
    get.addFamily(Bytes.toBytes(Constants.ATTRIBUTE));
    byte[] result = table.get(get).getValue(Bytes.toBytes(Constants.ATTRIBUTE),
        Bytes.toBytes("rows"));

    return (result != null) ? BytesUtil.bytesToInt(result) : 0;
  }

  /** {@inheritDoc} */
  public int getColumns() throws IOException {
    Get get = new Get(Bytes.toBytes(Constants.METADATA));
    get.addFamily(Bytes.toBytes(Constants.ATTRIBUTE));
    byte[] result = table.get(get).getValue(Bytes.toBytes(Constants.ATTRIBUTE),
        Bytes.toBytes("columns"));

    return BytesUtil.bytesToInt(result);
  }

  /** {@inheritDoc} */
  public String getRowLabel(int row) throws IOException {
    Get get = new Get(BytesUtil.getRowIndex(row));
    get.addFamily(Bytes.toBytes(Constants.ATTRIBUTE));
    byte[] result = table.get(get).getValue(Bytes.toBytes(Constants.ATTRIBUTE),
        Bytes.toBytes("string"));

    return (result != null) ? Bytes.toString(result) : null;
  }

  /** {@inheritDoc} */
  public void setColumnLabel(int column, String name) throws IOException {
    /*
     * VectorUpdate update = new VectorUpdate(Constants.CINDEX);
     * update.put(column, name); table.commit(update.getBatchUpdate());
     */

    Put put = new Put(Bytes.toBytes(Constants.CINDEX));
    put.add(Bytes.toBytes(Constants.ATTRIBUTE), Bytes.toBytes(String
        .valueOf(column)), Bytes.toBytes(name));
    table.put(put);
  }

  /** {@inheritDoc} */
  public String getColumnLabel(int column) throws IOException {
    Get get = new Get(Bytes.toBytes(Constants.CINDEX));
    get.addFamily(Bytes.toBytes(Constants.ATTRIBUTE));
    byte[] result = table.get(get).getValue(Bytes.toBytes(Constants.ATTRIBUTE),
        Bytes.toBytes(String.valueOf(column)));

    return (result != null) ? Bytes.toString(result) : null;
  }

  /** {@inheritDoc} */
  public void setRowLabel(int row, String name) throws IOException {
    VectorUpdate update = new VectorUpdate(row);
    update.put(Constants.ATTRIBUTE, "string", name);
    table.put(update.getPut());
  }

  /** {@inheritDoc} */
  public void setDimension(int rows, int columns) throws IOException {
    Put put = new Put(Bytes.toBytes(Constants.METADATA));
    byte[] family = Bytes.toBytes(Constants.ATTRIBUTE);
    put.add(family, Bytes.toBytes("rows"), BytesUtil.intToBytes(rows));
    put.add(family, Bytes.toBytes("columns"), BytesUtil.intToBytes(columns));
    table.put(put);
  }

  /** {@inheritDoc} */
  public void add(int i, int j, double value) throws IOException {
    VectorUpdate update = new VectorUpdate(i);
    update.put(j, value + this.get(i, j));
    table.put(update.getPut());

  }

  public static class ScanMapper extends
      TableMapper<ImmutableBytesWritable, Put> {
    private static List<Double> alpha = new ArrayList<Double>();

    public void map(ImmutableBytesWritable key, Result value, Context context)
        throws IOException, InterruptedException {
      Put put = new Put(key.get());

      NavigableMap<byte[], NavigableMap<byte[], byte[]>> map = value
          .getNoVersionMap();
      for (Map.Entry<byte[], NavigableMap<byte[], byte[]>> a : map.entrySet()) {
        byte[] family = a.getKey();
        for (Map.Entry<byte[], byte[]> b : a.getValue().entrySet()) {
          byte[] qualifier = b.getKey();
          byte[] val = b.getValue();
          if (alpha.size() == 0) {
            put.add(family, qualifier, val);
          } else {
            if (Bytes.toString(family).equals(Constants.COLUMN_FAMILY)) {
              double currVal = BytesUtil.bytesToDouble(val);
              put.add(family, qualifier, BytesUtil.doubleToBytes(currVal
                  * alpha.get(0)));
            } else {
              put.add(family, qualifier, val);
            }
          }
        }
      }

      context.write(key, put);
    }

    public static void setAlpha(double a) {
      if (alpha.size() > 0)
        alpha = new ArrayList<Double>();
      alpha.add(a);
    }
  }

  /** {@inheritDoc} */
  public Matrix set(Matrix B) throws IOException {
    Job job = new Job(config, "set MR job : " + this.getPath());

    Scan scan = new Scan();
    scan.addFamily(Bytes.toBytes(Constants.COLUMN_FAMILY));
    scan.addFamily(Bytes.toBytes(Constants.ATTRIBUTE));
    scan.addFamily(Bytes.toBytes(Constants.ALIASEFAMILY));
    scan.addFamily(Bytes.toBytes(Constants.BLOCK_FAMILY));
    scan.addFamily(Bytes.toBytes(JacobiEigenValue.EI_COLUMNFAMILY));
    scan.addFamily(Bytes.toBytes(JacobiEigenValue.EICOL_FAMILY));
    scan.addFamily(Bytes.toBytes(JacobiEigenValue.EIVEC_FAMILY));

    org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil.initTableMapperJob(B
        .getPath(), scan, ScanMapper.class, ImmutableBytesWritable.class,
        Put.class, job);
    org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil.initTableReducerJob(
        this.getPath(),
        org.apache.hadoop.hbase.mapreduce.IdentityTableReducer.class, job);
    try {
      job.waitForCompletion(true);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }
    return this;
  }

  /** {@inheritDoc} */
  public Matrix set(double alpha, Matrix B) throws IOException {
    Job job = new Job(config, "set MR job : " + this.getPath());

    Scan scan = new Scan();
    scan.addFamily(Bytes.toBytes(Constants.COLUMN_FAMILY));
    scan.addFamily(Bytes.toBytes(Constants.ATTRIBUTE));
    scan.addFamily(Bytes.toBytes(Constants.ALIASEFAMILY));
    scan.addFamily(Bytes.toBytes(Constants.BLOCK_FAMILY));
    scan.addFamily(Bytes.toBytes(JacobiEigenValue.EI_COLUMNFAMILY));
    scan.addFamily(Bytes.toBytes(JacobiEigenValue.EICOL_FAMILY));
    scan.addFamily(Bytes.toBytes(JacobiEigenValue.EIVEC_FAMILY));
    ScanMapper.setAlpha(alpha);
    org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil.initTableMapperJob(B
        .getPath(), scan, ScanMapper.class, ImmutableBytesWritable.class,
        Put.class, job);
    org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil.initTableReducerJob(
        this.getPath(),
        org.apache.hadoop.hbase.mapreduce.IdentityTableReducer.class, job);
    try {
      job.waitForCompletion(true);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }
    return this;
  }

  /** {@inheritDoc} */
  public String getPath() {
    return matrixPath;
  }

  protected void setReference(int reference) throws IOException {
    Put put = new Put(Bytes.toBytes(Constants.METADATA));
    put.add(Bytes.toBytes(Constants.ATTRIBUTE), Bytes
        .toBytes(Constants.METADATA_REFERENCE), Bytes.toBytes(reference));
    table.put(put);
  }

  protected int incrementAndGetRef() throws IOException {
    int reference = 1;

    Get get = new Get(Bytes.toBytes(Constants.METADATA));
    get.addFamily(Bytes.toBytes(Constants.ATTRIBUTE));
    byte[] result = table.get(get).getValue(Bytes.toBytes(Constants.ATTRIBUTE),
        Bytes.toBytes(Constants.METADATA_REFERENCE));

    if (result != null) {
      reference = Bytes.toInt(result);
      reference++;
    }
    setReference(reference);
    return reference;
  }

  protected int decrementAndGetRef() throws IOException {
    int reference = 0;

    Get get = new Get(Bytes.toBytes(Constants.METADATA));
    get.addFamily(Bytes.toBytes(Constants.ATTRIBUTE));
    byte[] result = table.get(get).getValue(Bytes.toBytes(Constants.ATTRIBUTE),
        Bytes.toBytes(Constants.METADATA_REFERENCE));

    if (result != null) {
      reference = Bytes.toInt(result);
      if (reference > 0) // reference==0, we need not to decrement it.
        reference--;
    }
    setReference(reference);
    return reference;
  }

  protected boolean hasAliaseName() throws IOException {
    Get get = new Get(Bytes.toBytes(Constants.METADATA));
    get.addFamily(Bytes.toBytes(Constants.ALIASEFAMILY));
    byte[] result = table.get(get).getValue(
        Bytes.toBytes(Constants.ALIASEFAMILY), Bytes.toBytes("name"));

    return (result != null) ? true : false;
  }

  public void close() throws IOException {
    if (closed) // have been closed
      return;
    int reference = decrementAndGetRef();
    if (reference <= 0) { // no reference again.
      if (!hasAliaseName()) { // the table has not been aliased, we delete the
        // table.
        if (admin.isTableEnabled(matrixPath)) {
          while (admin.isTableEnabled(matrixPath)) {
            try {
              admin.disableTable(matrixPath);
            } catch (RegionException e) {
              LOG.warn(e);
            }
          }

          admin.deleteTable(matrixPath);
        }
      }
    }
    closed = true;
  }

  public Matrix transpose() throws IOException {
    Matrix result;
    if (this.getType().equals("SparseMatrix")) {
      result = new SparseMatrix(config, this.getRows(), this.getColumns());
    } else {
      result = new DenseMatrix(config, this.getRows(), this.getColumns());
    }

    JobConf jobConf = new JobConf(config);
    jobConf.setJobName("transpose MR job" + result.getPath());

    jobConf.setNumMapTasks(config.getNumMapTasks());
    jobConf.setNumReduceTasks(config.getNumReduceTasks());

    TransposeMap.initJob(this.getPath(), TransposeMap.class, IntWritable.class,
        MapWritable.class, jobConf);
    TransposeReduce.initJob(result.getPath(), TransposeReduce.class, jobConf);

    JobManager.execute(jobConf);
    return result;
  }

  public boolean save(String aliasename) throws IOException {
    // mark & update the aliase name in "alise:name" meta column.
    // ! one matrix has only one aliasename now.
    Put put = new Put(Bytes.toBytes(Constants.METADATA));
    put.add(Bytes.toBytes(Constants.ALIASEFAMILY), Bytes.toBytes("name"), Bytes
        .toBytes(aliasename));
    put.add(Bytes.toBytes(Constants.ATTRIBUTE), Bytes.toBytes("type"), Bytes
        .toBytes(this.getType()));
    table.put(put);

    return hamaAdmin.save(this, aliasename);
  }
}
