package structures

import munit.FunSuite
import scala.collection.immutable.BitSet
import scala.collection.SortedSet
import org.roaringbitmap.RoaringBitmap

class ContextNodeTest extends FunSuite:
  def createTestPlis(): IndexedSeq[SortedPli] =
    val data = IndexedSeq(
      (true, IndexedSeq(1, 10, 100)),
      (true, IndexedSeq(1, 20, 200)),
      (true, IndexedSeq(2, 10, 300)),
      (true, IndexedSeq(2, 20, 100))
    )
    buildPliSeq(data, 3)

  test("ContextNode: basic construction and properties") {
    val plis = createTestPlis()
    val tuples = new RoaringBitmap()
    tuples.add(0, 1, 2)
    val node = ContextNode(Seq(1, 10), tuples, plis)
    assertEquals(node.nodeValue, Seq(1, 10))
    assertEquals(node.tuples.toArray.toSet, Set(0, 1, 2))
    assertEquals(node.plis.size, 3)
  }

  test("ContextNode: hasSortedAttribute and getSortedAttribute") {
    val plis = createTestPlis()
    val tuples = new RoaringBitmap()
    tuples.add(0, 1, 2)
    val node = ContextNode(Seq(), tuples, plis)
    assertEquals(node.hasSortedAttribute(1), false)
    val sortedAttr = node.getSortedAttribute(1)
    assertEquals(node.hasSortedAttribute(1), true)
    assertEquals(sortedAttr.keySet, SortedSet(10, 20))
    assertEquals(sortedAttr(10).toArray.toSet, Set(0, 2))
    assertEquals(sortedAttr(20).toArray.toSet, Set(1))
  }

  test("ContextNode: insertTuple updates tuples and sorted attributes") {
    val plis = createTestPlis()
    val tuples = new RoaringBitmap()
    tuples.add(0, 1)
    val node = ContextNode(Seq(), tuples, plis)
    val sortedAttr = node.getSortedAttribute(1)
    val testTuple = IndexedSeq(2, 10, 300) // tuple at index 2
    node.insertTuple(2, testTuple)
    assertEquals(node.tuples.toArray.toSet, Set(0, 1, 2))
    assertEquals(sortedAttr(10).toArray.toSet, Set(0, 2))
    assertEquals(sortedAttr(20).toArray.toSet, Set(1))
  }

  test("ContextNode: buildSortedAttribute creates correct mapping") {
    val plis = createTestPlis()
    val tuples = new RoaringBitmap()
    tuples.add(0, 2, 3)
    val node = ContextNode(Seq(), tuples, plis)
    val sortedAttr = node.buildSortedAttribute(2)
    assertEquals(sortedAttr.keySet, SortedSet(100, 300))
    assertEquals(sortedAttr(100).toArray.toSet, Set(0, 3))
    assertEquals(sortedAttr(300).toArray.toSet, Set(2))
  }

class ContextIndexTest extends FunSuite:
  def createTestPlis(): IndexedSeq[SortedPli] =
    val data = IndexedSeq(
      (true, IndexedSeq(1, 10, 100)),
      (true, IndexedSeq(1, 20, 200)),
      (true, IndexedSeq(2, 10, 300)),
      (true, IndexedSeq(2, 20, 100))
    )
    buildPliSeq(data, 3)

  test("ContextIndex: basic construction") {
    val plis = createTestPlis()
    val index = ContextIndex(plis, Seq.empty)
    assertEquals(index.plis.size, 3)
  }

  test("ContextIndex: getNode creates and caches nodes") {
    val plis = createTestPlis()
    val index = ContextIndex(plis, Seq.empty)
    val cols = BitSet(0)
    val values = Seq(1)
    val node1 = index.getNode(cols, values)
    val node2 = index.getNode(cols, values)
    assert(node1 `eq` node2, "Expected cached node instance to be reused")
  }

  test("ContextIndex: getNode computes correct tuple intersection") {
    val plis = createTestPlis()
    val index = ContextIndex(plis, Seq.empty)
    val cols = BitSet(0, 1)
    val values = Seq(1, 10)
    val (node, _) = index.getNode(cols, values)
    assertEquals(node.tuples.toArray.toSet, Set(0))
    assertEquals(node.nodeValue, Seq(1, 10))
  }

// TODO: tests for checking OD, creating node from parent
