package org.cameratestharness.experiment

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExperimentDataExporter {

    fun exportToCsvString(records: List<ExperimentRecord>): String {
        val sb = StringBuilder()
        sb.append(ExperimentRecord.CSV_HEADER).append("\n")
        for (record in records) {
            sb.append(record.toCsvRow()).append("\n")
        }
        return sb.toString()
    }

    fun parseCsv(csvContent: String): List<ExperimentRecord> {
        val lines = parseCsvLines(csvContent)
        if (lines.isEmpty()) return emptyList()

        val header = lines.first()
        val expectedHeaderCols = ExperimentRecord.CSV_HEADER.split(",")
        if (header.size != expectedHeaderCols.size) {
            throw IllegalArgumentException("Malformed CSV header: expected ${expectedHeaderCols.size} columns but found ${header.size}")
        }

        val records = mutableListOf<ExperimentRecord>()
        for (i in 1 until lines.size) {
            val cols = lines[i]
            if (cols.isEmpty() || (cols.size == 1 && cols[0].isBlank())) continue
            if (cols.size != expectedHeaderCols.size) {
                throw IllegalArgumentException("Malformed CSV row at line ${i + 1}: expected ${expectedHeaderCols.size} columns, got ${cols.size}")
            }

            records.add(
                ExperimentRecord(
                    sampleId = cols[0],
                    timestamp = cols[1],
                    scenarioId = cols[2],
                    groundTruthContext = cols[3],
                    userAction = cols[4],
                    cameraEvent = cols[5],
                    cameraId = cols[6],
                    packageName = cols[7],
                    cameraPermission = cols[8],
                    activityState = cols[9],
                    appVisibility = cols[10],
                    foregroundServiceActive = cols[11].toBoolean(),
                    foregroundServiceType = cols[12],
                    screenState = cols[13],
                    sessionState = cols[14],
                    sessionDurationMs = cols[15],
                    recentUserInteraction = cols[16].toBoolean(),
                    lifecycleEvent = cols[17],
                    cameraAvailability = cols[18],
                    notes = cols[19]
                )
            )
        }
        return records
    }

    fun exportToFile(context: Context, records: List<ExperimentRecord>): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "cameraguard_experiment_${timestamp}.csv"
        val exportDir = File(context.cacheDir, "experiments").apply { mkdirs() }
        val file = File(exportDir, fileName)
        file.writeText(exportToCsvString(records))
        return file
    }

    private fun parseCsvLines(csv: String): List<List<String>> {
        val result = mutableListOf<List<String>>()
        val currentLine = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < csv.length) {
            val c = csv[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < csv.length && csv[i + 1] == '"') {
                            currentField.append('"')
                            i++ // skip doubled quote
                        } else {
                            inQuotes = false
                        }
                    } else {
                        currentField.append(c)
                    }
                }
                c == '"' -> {
                    inQuotes = true
                }
                c == ',' -> {
                    currentLine.add(currentField.toString())
                    currentField.clear()
                }
                c == '\r' -> {
                    // Check for \r\n
                    if (i + 1 < csv.length && csv[i + 1] == '\n') {
                        i++
                    }
                    currentLine.add(currentField.toString())
                    currentField.clear()
                    result.add(ArrayList(currentLine))
                    currentLine.clear()
                }
                c == '\n' -> {
                    currentLine.add(currentField.toString())
                    currentField.clear()
                    result.add(ArrayList(currentLine))
                    currentLine.clear()
                }
                else -> {
                    currentField.append(c)
                }
            }
            i++
        }

        if (currentField.isNotEmpty() || currentLine.isNotEmpty()) {
            currentLine.add(currentField.toString())
            result.add(currentLine)
        }

        return result
    }
}
