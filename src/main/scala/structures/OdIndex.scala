package structures
import scala.collection.immutable.BitSet
import scala.collection.mutable

import com.typesafe.scalalogging.StrictLogging
import utils.ones
import utils.SizeBucketedBitSets

// disadvantage: I might want to iterate over all ODs with a certain context
// to process multiple tuples in the same ContextNode
final case class CompatibleKey(
    attr1: AttributeId,
    attr2: AttributeId,
    sameDirection: Boolean
)

type OdKey = CompatibleKey | AttributeId
object OdKey:
  def apply(
      od: OrderDependency
  ): OdKey = od match
    case ConstantOd(_, attr) => attr
    case CompatibleOd(_, attr1, attr2, sameDirection) =>
      CompatibleKey(attr1, attr2, sameDirection)

extension (constant: AttributeId)
  def allDependentCompatibles(
      numAttributes: Int,
      exclude: BitSet = BitSet.empty
  ): Iterator[CompatibleKey] = for
    other <- BitSet.ones(numAttributes).iterator
    if !exclude.contains(other)
    dir <- Seq(true, false)
    if other != constant
  yield CompatibleKey(constant min other, constant max other, dir)

extension (key: OdKey)
  def buildOd(
      context: BitSet
  ): OrderDependency = key match
    case attr: AttributeId => ConstantOd(context, attr)
    case CompatibleKey(a1, a2, sameDirection) =>
      CompatibleOd(context, a1, a2, sameDirection)
  def includedAttributes: Array[AttributeId] = key match
    case attr: AttributeId        => Array(attr)
    case CompatibleKey(a1, a2, _) => Array(a1, a2)
  def toStringWith(using
      attributeNamesMap: AttributeIndexed[String]
  ): String = key match
    case attr: AttributeId =>
      attributeNamesMap(attr)
    case CompatibleKey(a1, a2, sameDirection) =>
      val dirSymbol = if sameDirection then "↑" else "↓"
      s"${attributeNamesMap(a1)}↑ ~ ${attributeNamesMap(a2)}${dirSymbol}"

class OdNode(
    val key: OdKey,
    val numAttributes: Int,
    val propagateOds: Seq[OdNode]
)(using AttributeIndexed[String])
    extends StrictLogging:
  private val minOds = SizeBucketedBitSets(numAttributes)
  val addedMinOds = mutable.Set[BitSet]()
  val removedMinOds = mutable.Set[BitSet]()

  def size = minOds.size

  def iterateMinOds: Iterator[BitSet] = minOds.iterator

  def removeSupersetsOf(context: BitSet): Unit =
    val toRemove = minOds.removeSupersetsOf(context)
    var i = 0
    while i < toRemove.size do
      val s = toRemove(i)
      logger.debug(
        s"Removing superset OD ${key.buildOd(s).toStringWithNames} of context ${key.buildOd(context).toStringWithNames}"
      )
      removedMinOds.add(s)
      i += 1

  def hasSubsetsOf(context: BitSet): Boolean =
    minOds.hasSubsetOf(context)

  def hasChanges: Boolean =
    addedMinOds.nonEmpty || removedMinOds.nonEmpty

  def holds(context: BitSet): Boolean =
    // minOds.contains(context) || propagateOds.exists(
    //   _.minOds.contains(context)
    // ) ||
    hasSubsetsOf(context) || propagateOds.exists(_.holds(context))

  def addMinOd(
      context: BitSet,
      removeSubsets: Boolean = false,
      removeSupersets: Boolean = false
  ): Boolean =
    val wasAdded = minOds.add(context)
    if wasAdded then addedMinOds.add(context)
    if wasAdded && removeSupersets then removeSupersetsOf(context)
    if wasAdded && removeSubsets then
      val toRemove = minOds.removeSubsetsOf(context)
      var i = 0
      while i < toRemove.size do
        val s = toRemove(i)
        logger.debug(
          s"Removing subset OD ${key.buildOd(s).toStringWithNames} of context ${key.buildOd(context).toStringWithNames}"
        )
        removedMinOds.add(s)
        i += 1
    // assert(
    //   minOds.forall(s1 => !minOds.exists(s2 => s1 != s2 && s2.subsetOf(s1)))
    // )
    wasAdded

  // Returns true if actually removed
  def removeMinOd(context: BitSet): Boolean =
    val wasRemoved = minOds.remove(context)
    if wasRemoved then removedMinOds.add(context)
    wasRemoved

class OdIndex(val numAttributes: Int)(using
    val attributeNamesMap: AttributeIndexed[String]
) extends StrictLogging:
  val odNodes = mutable.Map[OdKey, OdNode]()
  val toRecalculateNodes = mutable.Map[OdKey, OdNode]()

  def addMinOd(
      od: OrderDependency,
      odNode: OdNode,
      removeSubsets: Boolean = false,
      removeSupersets: Boolean = false
  ): Unit =
    if odNode.addMinOd(od.context, removeSubsets, removeSupersets) then
      toRecalculateNodes(odNode.key) = odNode

  def removeMinOd(
      od: OrderDependency,
      odNode: OdNode
  ): Unit =
    if odNode.removeMinOd(od.context) then
      toRecalculateNodes(odNode.key) = odNode

  def getOrCreateOdNode(
      key: OdKey
  ): OdNode =
    odNodes.get(key) match
      case Some(node) => node
      case None =>
        val propagateOds = key match
          case CompatibleKey(attr1, attr2, sameDirection) =>
            Seq(
              getOrCreateOdNode(attr1),
              getOrCreateOdNode(attr2)
            )
          case _ => Seq.empty
        val node = OdNode(key, numAttributes, propagateOds)
        odNodes(key) = node
        node

  def handleViolation(
      violatedOd: OrderDependency,
      maybeNewlyMinimalOds: Seq[OrderDependency]
  ): Unit =
    val odNode = getOrCreateOdNode(OdKey(violatedOd))
    removeMinOd(violatedOd, odNode)
    maybeNewlyMinimalOds
      .foreach { newOd =>
        // TODO: optimize by preventing duplicate work of removeSubsets?
        val newOdNode = getOrCreateOdNode(OdKey(newOd))
        if !newOdNode.holds(newOd.context) then
          addMinOd(
            newOd,
            newOdNode
          )
      }

  def holds(od: OrderDependency): Boolean =
    getOrCreateOdNode(OdKey(od)).holds(od.context)

  def handleRevalidation(
      revalidatedOd: OrderDependency
  ): Unit =
    val odNode = getOrCreateOdNode(OdKey(revalidatedOd))
    addMinOd(revalidatedOd, odNode, removeSupersets = true)
    // in case of constant, also remove supersets of compatible ODs
    revalidatedOd match
      case constant: ConstantOd =>
        // in case of constant, also remove supersets of compatible ODs
        for
          key <- constant.constant
            .allDependentCompatibles(
              numAttributes,
              exclude = revalidatedOd.context
            )
          node <- odNodes.get(key)
        do
          node.removeSupersetsOf(constant.context)
          node.removeMinOd(constant.context)
          if node.hasChanges then toRecalculateNodes(node.key) = node
      case _ => ()

  def iterator: Iterator[OrderDependency] =
    for
      (key, odNode) <- odNodes.iterator
      context <- odNode.iterateMinOds
    yield key.buildOd(context)

  def size = odNodes.values.map(_.size).sum

  def sizes: (Int, Int) =
    val (constants, compatibles) = odNodes.partitionMap {
      case (key, node) if key.isInstanceOf[Int] => Left(node.size)
      case (key, node)                          => Right(node.size)
    }
    (constants.sum, compatibles.sum)

object OdIndex:
  def apply(
      numAttributes: Int,
      initialOds: Iterable[OrderDependency]
  )(using attributeNamesMap: AttributeIndexed[String]): OdIndex =
    val index = new OdIndex(numAttributes)
    initialOds.foreach { od =>
      index.getOrCreateOdNode(OdKey(od)).addMinOd(od.context, false)
    }
    index.odNodes.foreach((_, node) => node.addedMinOds.clear)
    index
