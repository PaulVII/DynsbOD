package io

import java.nio.file.Files
import java.nio.file.Paths

import scala.jdk.CollectionConverters.*

import structures.OrderDependency
import structures.parseOrderDependency
import com.typesafe.scalalogging.StrictLogging

object OdLoader extends StrictLogging:
  // Placeholder for future OD loading functionality
  // Currently, there are no specific requirements or implementations for loading ODs
  // This object can be expanded in the future as needed
  def loadOds(
      path: String,
      attributeToId: Map[String, Int]
  ): Seq[OrderDependency] =
    logger.debug(s"Loading ODs from '$path' ...")
    // Implementation goes here
    val p = Paths.get(path)
    if !Files.exists(p) then
      throw new IllegalArgumentException(s"CSV file not found: $path")
    val lines = Files.readAllLines(p).asScala.toIndexedSeq

    val result = lines
      .map(parseOrderDependency(_, attributeToId))
      .collect({ case Some(od) => od })

    if result.size != lines.size then
      println(
        s"Warning: OD file has ${lines.size} lines, but only ${result.size} ODs could be parsed successfully."
      )
    result
