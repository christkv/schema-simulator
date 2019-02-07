package com.mtools.schemasimulator.cli

import com.mtools.schemasimulator.stats.Statistics
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics
import org.knowm.xchart.BitmapEncoder
import org.knowm.xchart.XYChartBuilder
import org.knowm.xchart.XYSeries
import org.knowm.xchart.style.Styler
import org.knowm.xchart.style.markers.SeriesMarkers
import java.io.File
import java.text.DecimalFormat



class GraphGenerator(private val name:String, private val outputPath: File, private val dpi: Int, private val filters: List<String> = listOf()) {
    val df = DecimalFormat("#.00")

    fun generate(entries: Map<Long, Map<String, Statistics>>) {
        // Go over all the keys
        val keys = entries.keys.sorted()
        var max = 0.0
        var min = 0.0
        var percentil95 = 0.0
        var percentil99 = 0.0
        var mean = 0.0

        // Data per field
        val dataByStep = mutableMapOf<String, Pair<MutableList<Long>, MutableList<Double>>>()
        val statsPerLabel = mutableMapOf<String, Statistics>()

        // Generate series for each step
        keys.forEach { key ->
            entries.getValue(key)
                .entries
                .filter { filters.isEmpty() || filters.contains(it.key) }
                .forEach { entry ->

                if (!dataByStep.containsKey(entry.key)) {
                    dataByStep[entry.key] = Pair(mutableListOf(), mutableListOf())
                }

                if (!statsPerLabel.containsKey(entry.key)) {
                    statsPerLabel[entry.key] = Statistics()
                }

                dataByStep[entry.key]!!.first.add(key)
                dataByStep[entry.key]!!.second.add(if(entry.value.mean == 0.0) 1.0 else entry.value.mean)
                entry.value.descriptiveStatistics.values.forEach { statsPerLabel.getValue(entry.key).addValue(it) }
            }
        }

        // Get total variable
        if (statsPerLabel.containsKey("total")) {
            min = statsPerLabel.getValue("total").min
            max = statsPerLabel.getValue("total").max
            percentil95 = statsPerLabel.getValue("total").percentile(95.0)
            percentil99 = statsPerLabel.getValue("total").percentile(99.0)
            mean = statsPerLabel.getValue("total").mean
        }

        // Create Chart
        val chart = XYChartBuilder()
            .width(1024)
            .height(768)
            .title("Execution Graph for: $name [min: $min ms, max: $max ms, mean: ${df.format(mean)}, %95: ${df.format(percentil95)} ms, %99: ${df.format(percentil99)} ms]")
            .xAxisTitle("Time (ms)")
            .yAxisTitle("Milliseconds").build()

        // Customize Chart
        chart.styler.legendPosition = Styler.LegendPosition.InsideNW
        chart.styler.defaultSeriesRenderStyle = XYSeries.XYSeriesRenderStyle.Area
        chart.styler.yAxisLabelAlignment = Styler.TextAlignment.Right
        chart.styler.yAxisDecimalPattern = "#,###.## ms"
        chart.styler.xAxisDecimalPattern = "#,###.## ms"
//        chart.styler.isYAxisLogarithmic = true
        chart.styler.plotMargin = 0
        chart.styler.plotContentSize = .95

        // Add series to graph
        dataByStep.forEach {
            val series = chart.addSeries(it.key, it.value.first, it.value.second)
            series.xySeriesRenderStyle = XYSeries.XYSeriesRenderStyle.Area
            series.marker = SeriesMarkers.NONE
        }

        // or save it in high-res
        BitmapEncoder.saveBitmapWithDPI(chart, outputPath.absolutePath, BitmapEncoder.BitmapFormat.PNG, dpi)
    }
}
