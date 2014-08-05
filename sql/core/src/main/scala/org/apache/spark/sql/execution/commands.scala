/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{SchemaRDD, SQLContext, Row}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.SparkContext
import org.apache.spark.sql.catalyst.types.ByteArrayType

trait Command {
  /**
   * A concrete command should override this lazy field to wrap up any side effects caused by the
   * command or any other computation that should be evaluated exactly once. The value of this field
   * can be used as the contents of the corresponding RDD generated from the physical plan of this
   * command.
   *
   * The `execute()` method of all the physical command classes should reference `sideEffectResult`
   * so that the command can be executed eagerly right after the command query is created.
   */
  protected[sql] lazy val sideEffectResult: Seq[Any] = Seq.empty[Any]
}

/**
 * :: DeveloperApi ::
 */
@DeveloperApi
case class SetCommand(
    key: Option[String], value: Option[String], output: Seq[Attribute])(
    @transient context: SQLContext)
  extends LeafNode with Command {

  override protected[sql] lazy val sideEffectResult: Seq[(String, String)] = (key, value) match {
    // Set value for key k.
    case (Some(k), Some(v)) =>
      context.set(k, v)
      Array(k -> v)

    // Query the value bound to key k.
    case (Some(k), _) =>
      Array(k -> context.getOption(k).getOrElse("<undefined>"))

    // Query all key-value pairs that are set in the SQLConf of the context.
    case (None, None) =>
      context.getAll

    case _ =>
      throw new IllegalArgumentException()
  }

  def execute(): RDD[Row] = {
    val rows = sideEffectResult.map { case (k, v) => new GenericRow(Array[Any](k, v)) }
    context.sparkContext.parallelize(rows, 1)
  }

  override def otherCopyArgs = context :: Nil
}

/**
 * :: DeveloperApi ::
 */
@DeveloperApi
case class ExplainCommand(
    child: SparkPlan, output: Seq[Attribute])(
    @transient context: SQLContext)
  extends UnaryNode with Command {

  // Actually "EXPLAIN" command doesn't cause any side effect.
  override protected[sql] lazy val sideEffectResult: Seq[String] = this.toString.split("\n")

  def execute(): RDD[Row] = {
    val explanation = sideEffectResult.map(row => new GenericRow(Array[Any](row)))
    context.sparkContext.parallelize(explanation, 1)
  }

  override def otherCopyArgs = context :: Nil
}

/**
 * :: DeveloperApi ::
 */
@DeveloperApi
case class CacheCommand(tableName: String, doCache: Boolean)(@transient context: SQLContext)
  extends LeafNode with Command {

  override protected[sql] lazy val sideEffectResult = {
    if (doCache) {
      context.cacheTable(tableName)
    } else {
      context.uncacheTable(tableName)
    }
    Seq.empty[Any]
  }

  override def execute(): RDD[Row] = {
    sideEffectResult
    context.emptyResult
  }

  override def output: Seq[Attribute] = Seq.empty
}

/**
 * PIG
 * Writes the child RDD to the given file using the given delimiter
 */
case class PigStoreCommand(
                     path: String,
                     delimiter: String,
                     child: SparkPlan)(
                     @transient sc: SparkContext)
  extends UnaryNode with Command {

  override protected[sql] lazy val sideEffectResult: Seq[Row] = {
    val childRdd = child.execute().map(_.copy())
    assert(childRdd != null)
    childRdd.saveAsCSVFile(path, delimiter)
    childRdd.collect().toSeq
  }

  override def execute() = {
    val childRows = sideEffectResult
    sc.parallelize(childRows)
  }

  override def output = child.output
  override def otherCopyArgs = sc :: Nil
}

/**
 * PIG
 * Loads the file at the given path, splitting on the given delimiter
 * Stores it into a schemaRDD with the schema given by output and registers it as a table with the given alias
 */
case class PigLoadCommand(
                    path: String,
                    delimiter: String,
                    alias: String,
                    output: Seq[Attribute])(
                    @transient val sc: SparkContext,
                    @transient val sqc: SQLContext)
  extends LeafNode with Command {

  lazy val castProjection = schemaCaster(output)

  override protected[sql] lazy val sideEffectResult: Seq[GenericRow] = {
    // The -1 option lets us keep empty strings at the end of a line
    val splitLines = sc.textFile(path).map(_.split(delimiter, -1))

    val rowRdd = splitLines.map { r =>
      val withNulls = r.map(x => if (x == "") null else x)
      new GenericRow(withNulls.asInstanceOf[Array[Any]])
    }

    // Make sure that castProjection gets initialized during our side effect stage
    castProjection.currentValue

    rowRdd.collect.toSeq
  }

  def execute() = {
    // The -1 option lets us keep empty strings at the end of a line
    val splitLines = sc.textFile(path).map(_.split(delimiter, -1))

    val rowRdd = splitLines.map { r =>
      val withNulls = r.map(x => if (x == "") null else x)
      new GenericRow(withNulls.asInstanceOf[Array[Any]])
    }

    rowRdd.map(castProjection)

    /*
    val rowRdd = sc.parallelize(sideEffectResult)

    // TODO: This is a janky hack. A cleaner public API for parsing files into schemaRDD is on our to-do list
    val leafRdd = ExistingRdd(output, rowRdd.map(castProjection))
    val schemaRDD = new SchemaRDD(sqc, SparkLogicalPlan(leafRdd))

    sqc.registerRDDAsTable(schemaRDD, alias)
    schemaRDD
    */
  }

  /**
   * Generates a projections that will cast an all-ByteArray row into a row
   *  with the types in schema
   */
  protected def schemaCaster(schema: Seq[Attribute]): MutableProjection = {
    val startSchema = (1 to schema.length).toSeq.map(
      i => new AttributeReference(s"c_$i", ByteArrayType, nullable = true)())
    val casts = schema.zipWithIndex.map{case (ar, i) => Cast(startSchema(i), ar.dataType)}
    new MutableProjection(casts, startSchema)
  }
}