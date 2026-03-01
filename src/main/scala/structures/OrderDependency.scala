package structures

import scala.collection.immutable.BitSet

import structures.AttributeIndexed
import utils.ones

extension (columnSet: BitSet)
  def toStringWithNames(using columnIds: AttributeIndexed[String]): String =
    columnSet.toSeq.map(columnIds(_)).mkString(", ")

trait OrderDependency:
  val context: BitSet
  def toStringWithNames(using columnIds: AttributeIndexed[String]): String
  def includedCols: Set[AttributeId]

extension (od: OrderDependency)
  def copyWithContext(context: BitSet) = od match
    case c: CompatibleOd => c.copy(context)
    case c: ConstantOd   => c.copy(context)

final case class ConstantOd(
    context: BitSet,
    constant: AttributeId
) extends OrderDependency:
  def toStringWithNames(using columnIds: AttributeIndexed[String]): String =
    s"{${context.toStringWithNames}}: [] ↦ ${columnIds(constant)}"
  def includedCols: Set[AttributeId] = context.toSet + constant

object ConstantOd:
  def apply(
      context: BitSet,
      constant: AttributeId
  ): ConstantOd =
    // assert(!context.contains(constant))
    new ConstantOd(context, constant)

final case class CompatibleOd(
    context: BitSet,
    attr1: AttributeId,
    attr2: AttributeId,
    sameDirection: Boolean
) extends OrderDependency:
  def toStringWithNames(using columnNames: AttributeIndexed[String]): String =
    val dir = if sameDirection then "↑" else "↓"
    s"{${context.toStringWithNames}}: ${columnNames(attr1)}↑ ~ ${columnNames(attr2)}$dir"
  def includedCols: Set[AttributeId] = context.toSet + attr1 + attr2

object CompatibleOd:
  // Enforce canonical ordering: attr1 < attr2
  def apply(
      context: BitSet,
      attr1: AttributeId,
      attr2: AttributeId,
      sameDirection: Boolean
  ): CompatibleOd =
    // assert(attr1 != attr2)
    // assert(!context.contains(attr1) && !context.contains(attr2))
    if attr1 < attr2 then new CompatibleOd(context, attr1, attr2, sameDirection)
    else new CompatibleOd(context, attr2, attr1, sameDirection)

object OrderSpec:
  def unapply(str: String): Option[(String, Boolean)] =
    val trimmed = str.trim
    if trimmed.endsWith("↑") || trimmed.endsWith("↓") then
      Some((trimmed.dropRight(1), trimmed.endsWith("↑")))
    else None

extension (b: BitSet.type)
  def fromContextString(
      context: String,
      columnIds: Map[String, AttributeId]
  ): BitSet =
    b(
      context
        .split(",")
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(columnIds)
        .toSeq*
    )

/** Parses a string representation of an order dependency. */
def parseOrderDependency(
    str: String,
    columnIds: Map[String, AttributeId]
): Option[OrderDependency] =
  try
    str match
      case s"{$context}: [] ↦ $attr" =>
        Some(
          ConstantOd(
            context = BitSet.fromContextString(context, columnIds),
            constant = columnIds(attr.trim)
          )
        )
      case s"{$context}: ${OrderSpec(attr1)} ~ ${OrderSpec(attr2)}" =>
        Some(
          CompatibleOd( // ordering enforced by companion
            context = BitSet.fromContextString(context, columnIds),
            attr1 = columnIds(attr1(0)),
            attr2 = columnIds(attr2(0)),
            sameDirection = attr1(1) == attr2(1)
          )
        )
      case _ => None
  catch case _: NoSuchElementException => None
