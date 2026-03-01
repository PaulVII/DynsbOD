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
        treeMap

  def insertTuple(id: TupleId, tuple: IndexedSeq[TupleValue]): Unit =
    tuples.add(id)
    for attr <- plis.indices do
      sortedAttributes(attr) match
        case Some(treeMap) =>
          val bitmap = treeMap.getOrElseUpdate(tuple(attr), new RoaringBitmap())
          bitmap.add(id)
        case None =>
    addToMinMaxCache(tuple, id)

  def removeTuple(id: TupleId, tuple: IndexedSeq[TupleValue]): Unit =
    tuples.remove(id)
    for attr <- plis.indices do
      sortedAttributes(attr) match
        case Some(treeMap) =>
          treeMap.get(tuple(attr)) match
            case Some(bitmap) =>
              bitmap.remove(id)
              if bitmap.isEmpty then treeMap.remove(tuple(attr))
            case None =>
        case None =>
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
