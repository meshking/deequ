/**
 * Copyright 2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not
 * use this file except in compliance with the License. A copy of the License
 * is located at
 *
 *     http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.amazon.deequ.runtime.spark.operators

import com.amazon.deequ.metrics.{Distribution, DistributionValue, HistogramMetric}
import com.amazon.deequ.runtime.spark.executor.{IllegalAnalyzerParameterException, MetricCalculationException}
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{StringType, StructType}
import org.apache.spark.sql.{DataFrame, Row}

import scala.util.{Failure, Try}

/**
  * Histogram is the summary of values in a column of a DataFrame. Groups the given column's values,
  * and calculates the number of rows with that specific value and the fraction of this value.
  *
  * @param column        Column to do histogram analysis on
  * @param binningUdf    Optional binning function to run before grouping to re-categorize the
  *                      column values.
  *                      For example to turn a numerical value to a categorical value binning
  *                      functions might be used.
  * @param maxDetailBins Histogram details is only provided for N column values with top counts.
  *                      maxBins sets the N.
  *                      This limit does not affect what is being returned as number of bins. It
  *                      always returns the dictinct value count.
  */
case class HistogramOp(
    column: String,
    binningUdf: Option[UserDefinedFunction] = None,
    maxDetailBins: Integer = HistogramOp.MaximumAllowedDetailBins)
  extends Operator[FrequenciesAndNumRows, HistogramMetric] {

  private[this] val PARAM_CHECK: StructType => Unit = { _ =>
    if (maxDetailBins > HistogramOp.MaximumAllowedDetailBins) {
      throw new IllegalAnalyzerParameterException(s"Cannot return histogram values for more " +
        s"than ${HistogramOp.MaximumAllowedDetailBins} values")
    }
  }

  override def computeStateFrom(data: DataFrame): Option[FrequenciesAndNumRows] = {

    // TODO figure out a way to pass this in if its known before hand
    val totalCount = data.count()

    val frequencies = (binningUdf match {
      case Some(bin) => data.withColumn(column, bin(col(column)))
      case _ => data
    })
    .select(col(column).cast(StringType))
    .na.fill(HistogramOp.NullFieldReplacement)
    .groupBy(column)
    .count()

    Some(FrequenciesAndNumRows(frequencies, totalCount))
  }

  override def computeMetricFrom(state: Option[FrequenciesAndNumRows]): HistogramMetric = {

    state match {

      case Some(theState) =>
        val value: Try[Distribution] = Try {

          val topNRows = theState.frequencies.rdd.top(maxDetailBins)(OrderByAbsoluteCount)
          val binCount = theState.frequencies.count()

          val histogramDetails = topNRows
            .map { case Row(discreteValue: String, absolute: Long) =>
              val ratio = absolute.toDouble / theState.numRows
              discreteValue -> DistributionValue(absolute, ratio)
            }
            .toMap

          Distribution(histogramDetails, binCount)
        }

        HistogramMetric(column, value)

      case None =>
        HistogramMetric(column, Failure(Operators.emptyStateException(this)))
    }
  }

  override def toFailureMetric(exception: Exception): HistogramMetric = {
    HistogramMetric(column, Failure(MetricCalculationException.wrapIfNecessary(exception)))
  }

  override def preconditions: Seq[StructType => Unit] = {
    PARAM_CHECK :: Preconditions.hasColumn(column) :: Nil
  }
}

object HistogramOp {
  val NullFieldReplacement = "NullValue"
  val MaximumAllowedDetailBins = 1000
}

object OrderByAbsoluteCount extends Ordering[Row] {
  override def compare(x: Row, y: Row): Int = {
    x.getLong(1).compareTo(y.getLong(1))
  }
}
