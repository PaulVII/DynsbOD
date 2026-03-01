package structures

import scala.collection.mutable
import config.AlgoConfig

case class ClusterKey(
    column: AttributeId,
    value: TupleValue
)

case class MinMaxValue(
    min: TupleValue,
    max: TupleValue,
    minId: TupleId,
    maxId: TupleId
)

/** Helps speedup getViolatingCompatible by checking if there could be
  * violations (see couldHaveViolations) before actually looking at all values.
  * Is supposed to be used inside a class that can represent the context, either
  * a ContextNode or a SortedPli (representing empty context). The ClusterKey
  * defines which attribute to further filter on within the context and the
  * value to filter for (primaryAttr) and the other attributeID is otherAttr. It
  * stores the minimum and maximum of otherAttr in the subcluster by
  * primaryAttr.
  *
  * Tracks tuple Ids to that on tuple deletion, invalid min/max caches can be
  * detected and removed.
  */
type MinMaxCacheValue =
  mutable.Map[ClusterKey, mutable.Map[AttributeId, MinMaxValue]]

// helps to answer these queries: for an OD {X,Y}: A ~ B
// what are the min/max values for B when filtering by a specific value of A
trait MinMaxCache:
  val minMaxCache: MinMaxCacheValue =
    mutable.Map.empty

  def tryGetMinMaxValue(
      filteringCluster: ClusterKey,
      accessedAttribute: AttributeId
  ): Option[MinMaxValue] =
    minMaxCache.get(filteringCluster).flatMap(_.get(accessedAttribute))

  def createNewMinMaxValue(
      indexingCluster: ClusterKey,
      accessedAttribute: AttributeId,
      minValue: TupleValue,
      maxValue: TupleValue,
      minTupleId: TupleId,
      maxTupleId: TupleId
  ): Unit =
    assert(minValue <= maxValue)
    val cache = minMaxCache.getOrElseUpdate(indexingCluster, mutable.Map.empty)
    cache(accessedAttribute) = MinMaxValue(
      minValue,
      maxValue,
      minTupleId,
      maxTupleId
    )

  /** Update an existing min/max cache with the values from the given tuple.
    * Should be called whenever a new tuple is added to the node.
    *
    * @param tuple
    *   The tuple to use for updating the min/max cache.
    */
  def addToMinMaxCache(tuple: IndexedSeq[TupleValue], tupleId: TupleId): Unit =
    for
      (cluster, cache) <- minMaxCache
      if tuple(cluster.column) == cluster.value
      (attr, minMax) <- cache
      value = tuple(attr)
    do
      if value < minMax.min
      then cache(attr) = minMax.copy(min = value, minId = tupleId)
      else if value > minMax.max
      then cache(attr) = minMax.copy(max = value, maxId = tupleId)

  /** Ensures valid min/max cache after tuple deletion. If the deleted tuple was
    * a min or max for any cached cluster/attribute, the corresponding cache
    * entry is removed.
    *
    * @param tuple
    * @param tupleId
    */
  def deleteFromMinMaxCache(
      tuple: IndexedSeq[TupleValue],
      tupleId: TupleId
  ): Unit =
    for
      (cluster, cache) <- minMaxCache
      if tuple(cluster.column) == cluster.value
      (attr, mv) <- cache
      if mv.minId == tupleId || mv.maxId == tupleId
    do
      cache.remove(attr) match
        case Some(_) =>
          if cache.isEmpty then minMaxCache.remove(cluster)
        case None => ()

  def couldHaveViolations(
      filteringCluster: ClusterKey,
      accessedAttribute: AttributeId,
      clusterSize: Int,
      newTupleShouldBeSmaller: Boolean,
      t: IndexedSeq[TupleValue]
  )(using algoConfig: AlgoConfig): Boolean =
    if clusterSize < algoConfig.minMaxCacheThreshold
    then true
    else
      tryGetMinMaxValue(
        filteringCluster,
        accessedAttribute
      ) match
        case Some(MinMaxValue(min, max, _, _)) =>
          if newTupleShouldBeSmaller then min <= t(accessedAttribute)
          else max >= t(accessedAttribute)
        case None => true // no cache, so we have to look
