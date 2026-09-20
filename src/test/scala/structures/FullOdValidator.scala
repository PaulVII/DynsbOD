package structures

import munit.FunSuite
import org.roaringbitmap.RoaringBitmap
import scala.collection.immutable.BitSet

class FullOdValidatorTest extends FunSuite:
  val data = IndexedSeq(
    (false, Seq(0, 1, 1)),
    (false, Seq(0, 1, 5)),
    (false, Seq(0, 2, 2)),
    (false, Seq(1, 2, 3)),
    (false, Seq(1, 3, 4))
  )
  val containedTuples = RoaringBitmap.bitmapOf(data.indices.toArray*)
  given attributeNamesMap: AttributeIndexed[String] =
    AttributeIndexed.fromSeq(Seq("A", "B", "C"))

  val plis = buildPliSeq(data, data.head._2.size)

  val validator =
    FullOdValidator(ContextIndex(plis, Seq.empty), plis, containedTuples)

  test("iterates correctly with zero attributes") {
    assertEquals(
      validator.contextIterator(BitSet.empty, Seq.empty).toSeq,
      Seq((Seq.empty, containedTuples))
    )
  }

  test("iterates correctly with single attribute from start") {
    val result = validator
      .contextIterator(BitSet(0), Seq(0))
      .toSeq
    val expected = Seq(
      (Seq(0), RoaringBitmap.bitmapOf(0, 1, 2)),
      (Seq(1), RoaringBitmap.bitmapOf(3, 4))
    )
    assertEquals(result, expected)
  }

  test("iterates correctly with single attribute from other point") {
    val result = validator
      .contextIterator(BitSet(0), Seq(1))
      .toSeq
    val expected = Seq(
      (Seq(1), RoaringBitmap.bitmapOf(3, 4)),
      (Seq(0), RoaringBitmap.bitmapOf(0, 1, 2))
    )
    assertEquals(result, expected)
  }

  test("iterates correctly with two attributes from other point") {
    val result = validator
      .contextIterator(BitSet(0, 1), Seq(1, 2))
      .toSeq
    val expected = Seq(
      (Seq(1, 2), RoaringBitmap.bitmapOf(3)),
      (Seq(1, 3), RoaringBitmap.bitmapOf(4)),
      (Seq(0, 1), RoaringBitmap.bitmapOf(0, 1)),
      (Seq(0, 2), RoaringBitmap.bitmapOf(2))
    )
    assertEquals(result, expected)
  }

  test("revalidates simple OD") {
    assertEquals(
      validator.odHolds(CompatibleOd(BitSet(0), 1, 2, true), 1, data(1)._2),
      None
    )
  }

  test("detects violation of simple OD") {
    val result =
      validator.odHolds(CompatibleOd(BitSet(0), 1, 2, true), 0, data(0)._2)
    assert(result.isDefined)
    val violation = result.get
    assert(violation.side1.contains(1) || violation.side2.contains(1))
    assert(violation.side1.contains(2) || violation.side2.contains(2))
  }

  test("behaves different with sameDirections=false") {
    val result =
      validator.odHolds(CompatibleOd(BitSet(0), 1, 2, false), 0, data(0)._2)
    assert(result.isDefined)
    val violation = result.get
    assert(violation.side1.contains(3) || violation.side2.contains(3))
    assert(violation.side1.contains(4) || violation.side2.contains(4))
  }

// TODO: check validation with larger/empty contexts
