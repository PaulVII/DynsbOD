package engine

import munit.FunSuite
import structures.{OdIndex, ConstantOd, AttributeIndexed, RecordOperation}
import scala.collection.immutable.BitSet

class ConstantOdTest extends FunSuite:
  def setupAlgorithm(): IncrementalOdDiscovery =
    val data = IndexedSeq(
      // valid from start A -> BC, BC -> A
      (RecordOperation.Insert, None, IndexedSeq(1, 10, 100)),
      (RecordOperation.Insert, None, IndexedSeq(2, 10, 200)),
      (RecordOperation.Insert, None, IndexedSeq(3, 20, 300)),
      (RecordOperation.Insert, None, IndexedSeq(4, 30, 300))
    )
    given AttributeIndexed[String] = Array("A", "B", "C")
    val algo = IncrementalOdDiscovery(data, OdIndex(3, Seq.empty))
    algo

  val initiallyValidOds = Seq(
    ConstantOd(BitSet(0), 1), // A -> B
    ConstantOd(BitSet(0), 2), // A -> C
    ConstantOd(BitSet(1, 2), 0) // B,C -> A
  )

  test("ConstantOD: stays valid after inserting unrelated tuples") {
    val algo = setupAlgorithm()
    // Add initial ODs to the index
    initiallyValidOds.foreach { od =>
      val node = algo.odIndex.getOrCreateOdNode(structures.OdKey(od))
      node.addMinOd(od.context)
    }

    // Insert tuples that do not violate the ODs
    algo.applyIncrement(
      IndexedSeq(
        (RecordOperation.Insert, None, IndexedSeq(5, 10, 400)),
        (RecordOperation.Insert, None, IndexedSeq(6, 35, 300))
      )
    )
    assertEquals(initiallyValidOds.toSet, algo.odIndex.iterator.toSet)
  }

  test("ConstantOd: insert tuple causing single violation") {
    val algo = setupAlgorithm()
    // Add initial ODs to the index
    initiallyValidOds.foreach { od =>
      val node = algo.odIndex.getOrCreateOdNode(structures.OdKey(od))
      node.addMinOd(od.context)
    }

    // Insert a tuple that violates A -> B
    val newTuples =
      IndexedSeq((RecordOperation.Insert, None, IndexedSeq(4, 40, 300)))
    algo.applyIncrement(newTuples)

    val expectedOds: Set[structures.OrderDependency] = Set(
      ConstantOd(BitSet(0), 2), // A -> C (still valid)
      ConstantOd(BitSet(1, 2), 0) // B,C -> A (still valid)
      // A -> B is violated, no suitable replacement exists
    )
    assertEquals(expectedOds, algo.odIndex.iterator.toSet)
  }

  test("ConstantOd: violation generates new valid ODs") {
    val algo = setupAlgorithm()
    // Add initial ODs to the index
    initiallyValidOds.foreach { od =>
      val node = algo.odIndex.getOrCreateOdNode(structures.OdKey(od))
      node.addMinOd(od.context)
    }

    // Insert a tuple that violates A -> B
    val newTuples =
      IndexedSeq((RecordOperation.Insert, None, IndexedSeq(4, 40, 400)))
    algo.applyIncrement(newTuples)

    val expectedOds: Set[structures.OrderDependency] = Set(
      ConstantOd(BitSet(0, 1), 2), // A -> C (newly valid)
      ConstantOd(BitSet(0, 2), 1), // A -> C (newly valid)
      ConstantOd(BitSet(1, 2), 0) // B,C -> A (still valid)
      // A -> B and A->C are violated, no suitable replacement exists
    )
    assertEquals(expectedOds, algo.odIndex.iterator.toSet)
  }

  test("ConstantOd: empty context") {
    val data = IndexedSeq(
      (RecordOperation.Insert, None, IndexedSeq(1, 10, 100)),
      (RecordOperation.Insert, None, IndexedSeq(2, 10, 200)),
      (RecordOperation.Insert, None, IndexedSeq(3, 10, 300)),
      (RecordOperation.Insert, None, IndexedSeq(4, 10, 300))
    )
    given AttributeIndexed[String] = Array("A", "B", "C")
    val algo = IncrementalOdDiscovery(data, OdIndex(3, Seq.empty))
    // Add initial OD with empty context
    val initialOd = ConstantOd(BitSet.empty, 1) // -> B
    val node = algo.odIndex.getOrCreateOdNode(structures.OdKey(initialOd))
    node.addMinOd(initialOd.context)

    algo.applyIncrement(
      IndexedSeq((RecordOperation.Insert, None, IndexedSeq(5, 10, 500)))
    )
    assertEquals(
      Set[structures.OrderDependency](ConstantOd(BitSet.empty, 1)),
      algo.odIndex.iterator.toSet
    )
    algo.applyIncrement(
      IndexedSeq((RecordOperation.Insert, None, IndexedSeq(6, 20, 600)))
    )
    assertEquals(
      Set[structures.OrderDependency](
        ConstantOd(BitSet(0), 1),
        ConstantOd(BitSet(2), 1)
      ),
      algo.odIndex.iterator.toSet
    )
  }

  test("ConstantOd: insert tuple duplicate") {
    val algo = setupAlgorithm()
    // Add initial ODs to the index
    initiallyValidOds.foreach { od =>
      val node = algo.odIndex.getOrCreateOdNode(structures.OdKey(od))
      node.addMinOd(od.context)
    }

    // Insert a tuple that is a duplicate of an existing one
    algo.applyIncrement(
      IndexedSeq((RecordOperation.Insert, None, IndexedSeq(1, 10, 100)))
    )
    assertEquals(
      initiallyValidOds.toSet: Set[structures.OrderDependency],
      algo.odIndex.iterator.toSet
    )
  }
