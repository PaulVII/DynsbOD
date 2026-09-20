package structures
import scala.collection.immutable.BitSet

import com.typesafe.scalalogging.StrictLogging

import collection.mutable

// Map from a sequence of values (in column order defined by the BitSet) to a ContextNode
type ValueNodes = mutable.Map[Seq[TupleValue], ContextNode]

final class IterationData(
    val od: OrderDependency,
    val attr: AttributeId,
    val sortedAttr: SortedAttribute,
    val mmCache: MinMaxCache
)
object IterationData:
  def unapply(
      data: IterationData
  ): (OrderDependency, AttributeId, SortedAttribute, MinMaxCache) =
    (data.od, data.attr, data.sortedAttr, data.mmCache)

case class AttributeNode(
    nodes: ValueNodes = mutable.Map.empty,
    ods: mutable.Set[OrderDependency] = mutable.Set.empty,
    attributeCounts: Array[Int]
)

class ContextIndex(val plis: IndexedSeq[SortedPli])
    extends StrictLogging,
      MinMaxCache:
  val attributeNodes = mutable.Map.empty[BitSet, AttributeNode]

  def numContexts = attributeNodes.size

  private val numAttributes = plis.length

  val emptyContextOds = mutable.Set[OrderDependency]()

  def getCheapestPossibleAttribute(
      od: OrderDependency,
      cNode: ContextNode,
      aNode: AttributeNode
  ): AttributeId = od match
    case ConstantOd(_, constant) => constant
    case CompatibleOd(context, attr1, attr2, _) =>
      if cNode.hasSortedAttribute(attr1) then attr1
      else if cNode.hasSortedAttribute(attr2) then attr2
      else Seq(attr1, attr2).maxBy(aNode.attributeCounts)

  def insertAndIterate(
      id: TupleId,
      tuple: IndexedSeq[TupleValue]
  ): Iterator[IterationData] =
    addToMinMaxCache(tuple, id)
    emptyContextOdIterator ++ attributeNodes.iterator.flatMap {
      (context, aNode) =>
        val values = context.toIndexedSeq.map(tuple)
        val cNode = aNode.nodes.getOrElseUpdate(
          values,
          buildContextNode(context, values)
        )
        cNode.insertTuple(id, tuple)
        // Two tuples can only violate an OD if they agree on its context, so an
        // equivalence class holding nothing but the inserted tuple cannot
        // violate any OD with this context. Skipping it also avoids
        // materializing the node's sorted clusters, which would then have to be
        // maintained for every later tuple.
        if cNode.tuples.getCardinality < 2 then Iterator.empty
        else
          aNode.ods.iterator.map { od =>
            val attr: AttributeId =
              getCheapestPossibleAttribute(od, cNode, aNode)
            IterationData(od, attr, cNode.getSortedAttribute(attr), cNode)
          }
    }

  def emptyContextOdIterator: Iterator[
    (IterationData)
  ] = for od <- emptyContextOds.iterator yield {
    val attr = od match
      case ConstantOd(_, constant)      => constant
      case CompatibleOd(_, attr1, _, _) => attr1
    IterationData(od, attr, plis(attr).valueToTuples, this)
  }

  def getOrCreateAttributeNode(context: BitSet) =
    attributeNodes.getOrElseUpdate(
      context,
      // this should never be triggered due to registration, but needed for type safety
      AttributeNode(attributeCounts = Array.fill(numAttributes)(0))
    )

  /** Retrieve (or build) the context node for the given columns/values.
    *
    * @param cols
    *   BitSet describing which attributes these values correspond to.
    * @param values
    *   Values for the attributes in the same iteration order as
    *   cols.toIndexedSeq
    */
  def getNode(
      cols: BitSet,
      values: Seq[TupleValue]
  ): (ContextNode, Array[Int]) =
    // Validate size when assertions enabled
    val attributeNode = getOrCreateAttributeNode(cols)
    (
      attributeNode._1.getOrElseUpdate(
        values,
        buildContextNode(cols, values.toIndexedSeq)
      ),
      attributeNode.attributeCounts
    )

  def deregisterOd(od: OrderDependency): Unit =
    if od.context.isEmpty then emptyContextOds -= od
    else
      attributeNodes.get(od.context) match
        case Some(AttributeNode(_, ods, counts)) =>
          ods.remove(od)
          od match
            case CompatibleOd(_, a1, a2, _) =>
              counts(a1) = math.max(0, counts(a1) - 1)
              counts(a2) = math.max(0, counts(a2) - 1)
            case _ =>
          if ods.isEmpty then attributeNodes.remove(od.context)
        case None => ()

  def registerOd(
      od: OrderDependency,
      currentTuple: Option[IndexedSeq[TupleValue]] = None,
      currentId: Option[TupleId] = None
  ): Unit =
    if od.context.isEmpty then emptyContextOds += od
    else
      val attributeNode = getOrCreateAttributeNode(od.context)
      attributeNode.ods += od
      od match
        case CompatibleOd(_, attr1, attr2, _) =>
          attributeNode.attributeCounts(attr1) =
            attributeNode.attributeCounts(attr1) + 1
          attributeNode.attributeCounts(attr2) =
            attributeNode.attributeCounts(attr2) + 1
        case _: OrderDependency =>

      (currentTuple, currentId) match
        case (Some(tuple), Some(id)) if od.context.nonEmpty =>
          getNode(od.context, od.context.toSeq.map(tuple))._1
            .insertTuple(id, tuple)
        case _ => ()

  private def buildContextNode(
      cols: BitSet,
      values: IndexedSeq[TupleValue]
  ): ContextNode =
    buildNewContextNode(cols, values)
    // the following code is not worth it with the current slow lookup
    // try to find a parent node with one less attribute
    /*var result: Option[ContextNode] = None
    val colsIterator = cols.iterator
    var colIndex = 0
    var found = false
    while (cols.size > 1 && colsIterator.hasNext && !found) do
      val addedCol = colsIterator.next()
      val reducedCols = cols - addedCol
      attributeNodes.get(reducedCols) match
        case Some(parentNode) if parentNode._2.nonEmpty =>
          // usually finds at index 0 or 1
          // logger.debug(s"Found parent at index $colIndex")
          val addedValue = values(colIndex)
          val reducedValues =
            values.zipWithIndex.filter(_._2 != colIndex).map(_._1)
          result = Some(
            refineExistingContextNode(
              addedCol,
              addedValue,
              reducedValues,
              getNode(reducedCols, reducedValues)
            )
          )
          found = true
        case _ => ()
      colIndex += 1
    result.getOrElse(
      buildNewContextNode(
        cols,
        values
      )
    )*/

  // unused alternative to inserting directly
  def insertTuple(id: TupleId, tuple: IndexedSeq[TupleValue]): Unit =
    attributeNodes.foreach { case (columns, attributeNode) =>
      if attributeNode.ods.isEmpty then attributeNodes.remove(columns)
      else
        attributeNode.nodes.get(columns.toArray.map(tuple)) match
          case Some(node) => node.insertTuple(id, tuple)
          case None       => ()
    }

  def removeTupleId(id: TupleId, tuple: IndexedSeq[TupleValue]): Unit =
    deleteFromMinMaxCache(tuple, id)
    attributeNodes.foreach { case (columns, attributeNode) =>
      if attributeNode.ods.isEmpty then attributeNodes.remove(columns)
      else
        val values = columns.toArray.map(tuple)
        attributeNode.nodes.get(values) match
          case Some(node) =>
            node.removeTuple(id, tuple)
            if node.tuples.isEmpty then attributeNode.nodes.remove(values)
          case None => ()
    }

  def getRevalidationData(id: TupleId, nonOd: OrderDependency): IterationData =
    if nonOd.context.isEmpty then
      val attr = nonOd match
        case ConstantOd(_, constant)      => constant
        case CompatibleOd(_, attr1, _, _) => attr1
      return IterationData(
        nonOd,
        attr,
        plis(attr).valueToTuples,
        this
      )
    else
      val aNode = getOrCreateAttributeNode(nonOd.context)
      val values = nonOd.context.toIndexedSeq.map(plis(_).tupleToValues(id))
      val cNode = aNode._1.getOrElseUpdate(
        values,
        buildContextNode(nonOd.context, values)
      )
      val attr = getCheapestPossibleAttribute(nonOd, cNode, aNode)
      IterationData(
        nonOd,
        attr,
        cNode.getSortedAttribute(attr),
        cNode
      )

  private def buildNewContextNode(
      cols: BitSet,
      values: Seq[TupleValue]
  ): ContextNode =
    val colsIt = cols.iterator
    val valuesIt = values.iterator
    val bitmaps = values.indices
      .map { _ =>
        plis(colsIt.next()).valueToTuples(valuesIt.next())
      }
      .sortBy(_.getLongCardinality)

    val bitmapsIter = bitmaps.iterator
    val tupleIds = bitmapsIter.next().clone()
    while bitmapsIter.hasNext && !tupleIds.isEmpty do
      tupleIds.and(bitmapsIter.next())
    ContextNode(values, tupleIds, plis)

  /** Drops the value nodes that are not earning their memory, keeping the hot
    * ones. Every context is visited on every insert, but only the *one* value
    * node holding the inserted tuple's equivalence class is, so value nodes —
    * which dominate memory — are the right granularity to evict.
    *
    * Two rules, in order:
    *   - a singleton class is dropped outright: it cannot hold a violation
    *     until it grows, and it only costs a rebuild if a matching tuple ever
    *     arrives. On selective contexts these are nearly one per tuple and are
    *     otherwise retained until the next full wipe.
    *   - otherwise second-chance (CLOCK): nodes used since the last sweep
    *     survive with their flag cleared, cold nodes are dropped. A repeated
    *     sweep therefore escalates by itself.
    *
    * @return
    *   the number of value nodes dropped
    */
  def evictColdContextNodes(): Int =
    var dropped = 0
    attributeNodes.foreach { case (_, attributeNode) =>
      attributeNode.nodes.filterInPlace { (_, contextNode) =>
        if contextNode.tuples.getCardinality < 2 || !contextNode.recentlyUsed
        then
          dropped += 1
          false
        else
          contextNode.recentlyUsed = false
          true
      }
    }
    dropped

  def removeAllContextNodes(): Unit =
    clearMinMaxCache()
    attributeNodes.values.foreach { case attributeNode =>
      attributeNode.nodes.clear()
    // try to just clear sortedAttributes - slower
    // attributeNode.nodes.foreach{case (values, contextNode) => contextNode.clearSortedAttributes}
    }

object ContextIndex:
  def apply(
      plis: IndexedSeq[SortedPli],
      ods: Iterable[OrderDependency]
  ): ContextIndex =
    val index = new ContextIndex(plis)
    for od <- ods do index.registerOd(od)
    index
