package io

import java.nio.file.Files
import java.nio.file.Paths

import scala.jdk.CollectionConverters.*

import config.CsvOptions
import structures.{TupleValue, RecordId, RecordOperation}

object CsvLoader:

  private def getAttributeNamesMap(
      headerLine: Option[Array[String]],
      numCols: Int
  ): Map[String, Int] = headerLine match
    case Some(lines) =>
      lines
        .map(_.trim)
        .filter(_.nonEmpty)
        .zipWithIndex
        .toMap
    case None =>
      // assume letters for column names (A=0, B=1, ...) like DISTOD
      Map.from((0 until numCols).map(i => (('A' + i).toChar.toString(), i)))

  /** Load a CSV file where all values are integers into an IndexedSeq of
    * IndexedSeq of TupleValue (which is an alias for Int in this case).
    *
    * @param path
    * @param options
    * @return
    *   a 2d seq of tuple values with operation and optional record ID, and a
    *   map from attribute name to attribute id
    */
  def loadIntegers(
      path: String,
      options: CsvOptions
  ): (
      IndexedSeq[(RecordOperation, Option[RecordId], IndexedSeq[TupleValue])],
      Map[String, Int]
  ) =
    val p = Paths.get(path)
    if !Files.exists(p) then
      throw new IllegalArgumentException(s"CSV file not found: $path")

    val cells = Files
      .readAllLines(p)
      .asScala
      .filter(_.nonEmpty)
      .map(_.split(options.delimiter, -1).map(_.trim))
      .toIndexedSeq

    val (headerOpt, data) =
      if options.hasHeader then
        (cells.headOption, cells.tail) // scalafix:ok UnsafeTraversableMethods
      else (None, cells)

    val (processedData, dataColOffset, numDataCols) =
      if options.canHaveDeletions then
        // First two columns are: operation, recordId
        val colOffset = 2
        val numCols = cells.headOption.map(_.length - colOffset).getOrElse(0)

        val processed: IndexedSeq[
          (RecordOperation, Option[RecordId], IndexedSeq[TupleValue])
        ] =
          data.zipWithIndex.map { (row, line) =>
            if row.length < 2 then
              throw new IllegalArgumentException(
                s"Expected at least 2 columns (operation, recordId) but got ${row.length} in file $path at line $line"
              )

            val operation = row(0).toLowerCase match
              case "insert" => RecordOperation.Insert
              case "update" => RecordOperation.Update
              case "delete" => RecordOperation.Delete
              case other =>
                throw new IllegalArgumentException(
                  s"Invalid operation '$other' in file $path at line $line. Expected: insert, update, or delete"
                )

            val recordId =
              try {
                row(1).toInt
              } catch
                case e: NumberFormatException =>
                  throw new IllegalArgumentException(
                    s"Invalid record ID '${row(1)}' in file $path at line $line"
                  )

            val values =
              try {
                row.drop(2).map(_.toInt).toIndexedSeq
              } catch
                case e: NumberFormatException =>
                  throw new IllegalArgumentException(
                    s"Error parsing values in line ${row
                        .mkString(",")} at file $path line $line: $e"
                  )

            (operation, Some(recordId), values)
          }

        (processed, colOffset, numCols)
      else
        // Simple format: all rows are inserts, no record ID tracking
        val numCols = cells.headOption.map(_.length).getOrElse(0)

        val processed: IndexedSeq[
          (RecordOperation, Option[RecordId], IndexedSeq[TupleValue])
        ] =
          data.zipWithIndex.map { (row, line) =>
            val values =
              try {
                row.map(_.toInt).toIndexedSeq
              } catch
                case e: NumberFormatException =>
                  throw new IllegalArgumentException(
                    s"Error parsing line ${row
                        .mkString(",")} in file $path at line $line: $e"
                  )

            (RecordOperation.Insert, None, values)
          }

        (processed, 0, numCols)

    val attributeHeaderOpt = headerOpt.map(h =>
      if options.canHaveDeletions then h.drop(dataColOffset)
      else h
    )

    val initialAttrMap = getAttributeNamesMap(attributeHeaderOpt, numDataCols)

    // Filter columns if columnsToInclude is specified
    options.columnsToInclude match
      case Some(columnsToInclude) =>
        // Find indices of columns to include
        val columnIndices = columnsToInclude.flatMap { colName =>
          initialAttrMap.get(colName).map(idx => (colName, idx))
        }

        // Warn if some columns were not found
        val foundColumns = columnIndices.map(_._1).toSet
        val missingColumns = columnsToInclude.filterNot(foundColumns.contains)
        if missingColumns.nonEmpty then
          println(
            s"Warning: The following columns were not found in the CSV and will be ignored: ${missingColumns
                .mkString(", ")}"
          )

        if columnIndices.isEmpty then
          throw new IllegalArgumentException(
            s"None of the specified columns to include were found in the CSV file: ${columnsToInclude
                .mkString(", ")}"
          )

        // Sort by original index to maintain column order
        val sortedIndices = columnIndices.sortBy(_._2)
        val indicesToKeep = sortedIndices.map(_._2)

        // Filter the data
        val filteredData = processedData.map { case (op, recordId, values) =>
          val filteredValues = indicesToKeep.map(values(_)).toIndexedSeq
          (op, recordId, filteredValues)
        }

        // Create new attribute map with remapped indices
        val newAttrMap = sortedIndices.zipWithIndex.map {
          case ((name, _), newIdx) =>
            (name, newIdx)
        }.toMap

        (filteredData, newAttrMap)

      case None =>
        (processedData, initialAttrMap)
