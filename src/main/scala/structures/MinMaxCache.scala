package structures

import scala.collection.mutable
import config.AlgoConfig

/** Extremes of one attribute within one cluster. Mutable so that maintaining
  * them on tuple insertion does not allocate.
  */
final class MinMaxValue(
    var min: TupleValue,
    var max: TupleValue,
    var minId: TupleId,
    var maxId: TupleId
)

/** Helps speedup getViolatingCompatible by checking if there could be
  * violations (see couldHaveViolations) before actually looking at all values.
  * Is supposed to be used inside a class that can represent the context, either
  * a ContextNode or a SortedPli (representing empty context). The cluster is
  * identified by which attribute to further filter on within the context
  * (primaryAttr) and the value to filter for; the other attributeID is
  * otherAttr. It stores the minimum and maximum of otherAttr in the subcluster
  * by primaryAttr.
  *
  * Tracks tuple Ids so that on tuple deletion, invalid min/max caches can be
  * detected and removed.
  *
  * Entries are indexed by their filtering column first. Keeping the cache
  * current on tuple insertion and deletion therefore costs one lookup per
  * column that has ever held a cached cluster, instead of a scan over every
  * cached cluster.
  */
trait MinMaxCache:
  // column -> value of that column -> (accessed attribute -> extremes)
  private var byColumn
      : Array[mutable.LongMap[mutable.Map[AttributeId, MinMaxValue]]] = null
  // Columns that hold, or have held, a cached cluster. Never shrinks: a column
  // falling out of use only costs one failed lookup per tuple.
  private val activeColumns = mutable.ArrayBuffer[AttributeId]()

  /** The value map of `column`, creating it if needed. */
  private def columnMap(
      column: AttributeId
  ): mutable.LongMap[mutable.Map[AttributeId, MinMaxValue]] =
    if byColumn == null then byColumn = new Array(column + 8)
    else if column >= byColumn.length then
      byColumn = java.util.Arrays.copyOf(byColumn, column + 8)
    var perValue = byColumn(column)
    if perValue == null then
      perValue = mutable.LongMap.empty
      byColumn(column) = perValue
      activeColumns += column
    perValue

  /** The cluster cache for `column == value`, or null. */
  private def lookup(
      column: AttributeId,
      value: TupleValue
  ): mutable.Map[AttributeId, MinMaxValue] =
    val columns = byColumn
    if columns == null || column >= columns.length then null
    else
      val perValue = columns(column)
      if perValue == null then null else perValue.getOrElse(value.toLong, null)

  def tryGetMinMaxValue(
      column: AttributeId,
      value: TupleValue,
      accessedAttribute: AttributeId
  ): MinMaxValue =
    val cache = lookup(column, value)
    if cache == null then null else cache.getOrElse(accessedAttribute, null)

  def createNewMinMaxValue(
      column: AttributeId,
      value: TupleValue,
      accessedAttribute: AttributeId,
      minValue: TupleValue,
      maxValue: TupleValue,
      minTupleId: TupleId,
      maxTupleId: TupleId
  ): Unit =
    assert(minValue <= maxValue)
    val cache =
      columnMap(column).getOrElseUpdate(value.toLong, mutable.Map.empty)
    cache(accessedAttribute) =
      MinMaxValue(minValue, maxValue, minTupleId, maxTupleId)

  /** Update the existing min/max caches with the values from the given tuple.
    * Should be called whenever a new tuple is added to the node.
    *
    * @param tuple
    *   The tuple to use for updating the min/max cache.
    */
  def addToMinMaxCache(tuple: IndexedSeq[TupleValue], tupleId: TupleId): Unit =
    var i = 0
    while i < activeColumns.size do
      val column = activeColumns(i)
      val cache = lookup(column, tuple(column))
      if cache != null then
        cache.foreachEntry { (attr, minMax) =>
          val value = tuple(attr)
          if value < minMax.min then
            minMax.min = value
            minMax.minId = tupleId
          else if value > minMax.max then
            minMax.max = value
            minMax.maxId = tupleId
        }
      i += 1

  /** Ensures valid min/max caches after tuple deletion. If the deleted tuple
    * was a min or max for any cached cluster/attribute, the corresponding cache
    * entry is removed.
    *
    * @param tuple
    * @param tupleId
    */
  def deleteFromMinMaxCache(
      tuple: IndexedSeq[TupleValue],
      tupleId: TupleId
  ): Unit =
    var i = 0
    while i < activeColumns.size do
      val column = activeColumns(i)
      val value = tuple(column)
      val cache = lookup(column, value)
      if cache != null then
        cache.filterInPlace((_, minMax) =>
          minMax.minId != tupleId && minMax.maxId != tupleId
        )
        if cache.isEmpty then byColumn(column).remove(value.toLong)
      i += 1

  def clearMinMaxCache(): Unit =
    byColumn = null
    activeColumns.clear()

  def couldHaveViolations(
      column: AttributeId,
      value: TupleValue,
      accessedAttribute: AttributeId,
      clusterSize: Int,
      newTupleShouldBeSmaller: Boolean,
      t: IndexedSeq[TupleValue]
  )(using algoConfig: AlgoConfig): Boolean =
    if clusterSize < algoConfig.minMaxCacheThreshold
    then true
    else
      val minMax = tryGetMinMaxValue(column, value, accessedAttribute)
      // no cache, so we have to look
      if minMax == null then true
      else if newTupleShouldBeSmaller then minMax.min < t(accessedAttribute)
      else minMax.max > t(accessedAttribute)
