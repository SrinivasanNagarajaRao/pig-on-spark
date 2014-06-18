package org.apache.spark.sql

import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan => SparkLogicalPlan}
import org.apache.pig.impl.PigContext
import org.apache.pig.{PigServer, ExecType}
import java.util.Properties
import org.apache.pig.newplan.logical.relational.{LogicalPlan => PigLogicalPlan}
import org.apache.spark.{SparkConf, SparkContext}

/**
 * Parses a Pig Latin query string into a Spark LogicalPlan. Hopefully.
 */
class PigParser(sqc: SQLContext) {
  /**
   * TODO: This might throw away some context.
   * Check eg. Pig's Main.java:486 to see if we need to take a different approach
   */
  val pc: PigContext = new PigContext(ExecType.LOCAL, new Properties)

  /**
   * Converts the given Pig Latin query into a Pig LogicalPlan
   * @param query The Pig Latin query string to be processed
   * @return
   */
  private def pigPlanOfQuery(query: String): PigLogicalPlan = {
    val pigServer: PigServer = new PigServer(pc)
    pigServer.setBatchOn
    pigServer.registerQuery(query)
    val buildLp = pigServer.getClass.getDeclaredMethod("buildLp")
    buildLp.setAccessible(true)
    buildLp.invoke(pigServer).asInstanceOf[PigLogicalPlan]
  }

  def apply(input: String): SparkLogicalPlan = {
    val pigLogicalPlan = pigPlanOfQuery(input)
    pigPlanToSparkPlan(pigLogicalPlan)
  }

  def pigPlanToSparkPlan(pigLogicalPlan: PigLogicalPlan): SparkLogicalPlan = {
    val lptv = new LogicalPlanTranslationVisitor(pigLogicalPlan, pc, sqc)
    lptv.visit()
    lptv.getRoot()
  }
}
