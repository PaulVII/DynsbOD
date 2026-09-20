package structures

import scala.collection.mutable

import com.typesafe.scalalogging.LazyLogging
import org.roaringbitmap.RoaringBitmap

type SortedAttribute = mutable.TreeMap[TupleValue, RoaringBitmap]

class ContextNode(
    val nodeValue: Seq[TupleValue],
    val tuples: RoaringBitmap,
    val plis: IndexedSeq[SortedPli]
) extends LazyLogging,
      MinMaxCache:
  // TODO: add something to check which sortedAttributes are
  // used and only update those that are
  // Map[attribute number, TreeMap[value, tuples]]
  private val sortedAttributes =
    AttributeIndexed[Option[SortedAttribute]](plis.size)(None)
  // Which attributes of `sortedAttributes` are actually built. Only those need
  // maintaining per tuple, and a node typically holds one or two of them, so
  // walking this instead of every attribute of the relation matters on wide
  // datasets.
  private var builtAttributes: Array[AttributeId] = Array.emptyIntArray

  /** Set whenever a tuple lands in this node, cleared by an eviction sweep, so
    * a sweep can tell nodes used since the last one from cold nodes.
    */
  var recentlyUsed: Boolean = true

  def hasSortedAttribute(attr: AttributeId): Boolean =
    sortedAttributes(attr).isDefined

  def getSortedAttribute(
      attr: AttributeId
  ): SortedAttribute =
    sortedAttributes(attr) match
      case Some(treeMap) => treeMap
      case None =>
        val treeMap = buildSortedAttribute(attr)
        sortedAttributes(attr) = Some(treeMap)
        builtAttributes = builtAttributes :+ attr
        treeMap

  def insertTuple(id: TupleId, tuple: IndexedSeq[TupleValue]): Unit =
    recentlyUsed = true
    tuples.add(id)
    var i = 0
    while i < builtAttributes.length do
      val attr = builtAttributes(i)
      val treeMap = sortedAttributes(attr).get
      treeMap.getOrElseUpdate(tuple(attr), new RoaringBitmap()).add(id)
      i += 1
    addToMinMaxCache(tuple, id)

  def removeTuple(id: TupleId, tuple: IndexedSeq[TupleValue]): Unit =
    tuples.remove(id)
    var i = 0
    while i < builtAttributes.length do
      val attr = builtAttributes(i)
      val treeMap = sortedAttributes(attr).get
      treeMap.get(tuple(attr)) match
        case Some(bitmap) =>
          bitmap.remove(id)
          if bitmap.isEmpty then treeMap.remove(tuple(attr))
        case None =>
      i += 1
    deleteFromMinMaxCache(tuple, id)

  def buildSortedAttribute(attr: AttributeId): SortedAttribute =
    val treeMap = mutable.TreeMap[TupleValue, RoaringBitmap]()
    // TODO?: if the parent exists and has supersetMap, use it, otherwise plis(attr)
    /*if tuples.getCardinality > 100 then
      val remaining = tuples.clone()
      while !remaining.isEmpty do
        val next = remaining.first()
        val value = plis(attr).tupleToValues(next)
        val ids = remaining.clone()
        ids.and(plis(attr).valueToTuples(value))
        treeMap.put(value, ids)
        remaining.andNot(ids)
    else*/
    tuples.forEach { tuple =>
      val bitmap = treeMap.getOrElseUpdate(
        plis(attr).tupleToValues(tuple),
        new RoaringBitmap()
      )
      bitmap.add(tuple)
    }

    treeMap
