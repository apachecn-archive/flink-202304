/*
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
package org.apache.flink.table.planner.plan.rules.physical.stream

import org.apache.flink.table.planner.plan.nodes.FlinkConventions
import org.apache.flink.table.planner.plan.nodes.logical.FlinkLogicalExpand
import org.apache.flink.table.planner.plan.nodes.physical.stream.StreamPhysicalExpand

import org.apache.calcite.plan.RelOptRule
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.convert.ConverterRule
import org.apache.calcite.rel.convert.ConverterRule.Config

/** Rule that converts [[FlinkLogicalExpand]] to [[StreamPhysicalExpand]]. */
class StreamPhysicalExpandRule(config: Config) extends ConverterRule(config) {

  def convert(rel: RelNode): RelNode = {
    val expand = rel.asInstanceOf[FlinkLogicalExpand]
    val newTrait = rel.getTraitSet.replace(FlinkConventions.STREAM_PHYSICAL)
    val newInput = RelOptRule.convert(expand.getInput, FlinkConventions.STREAM_PHYSICAL)
    new StreamPhysicalExpand(
      rel.getCluster,
      newTrait,
      newInput,
      expand.projects,
      expand.expandIdIndex)
  }
}

object StreamPhysicalExpandRule {
  val INSTANCE: RelOptRule = new StreamPhysicalExpandRule(
    Config.INSTANCE.withConversion(
      classOf[FlinkLogicalExpand],
      FlinkConventions.LOGICAL,
      FlinkConventions.STREAM_PHYSICAL,
      "StreamPhysicalExpandRule"))
}
