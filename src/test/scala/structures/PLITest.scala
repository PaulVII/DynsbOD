package structures

import munit.FunSuite
import scala.collection.mutable.SortedSet

class PLITest extends FunSuite:

  test("PLI: buildPliSeq on empty data returns empty") {
    val plis = buildPliSeq(Seq.empty, 0)
    assertEquals(plis.size, 0)
  }

  test("PLI: build from small dataset builds correct mappings") {
    // rows: tupleId = index
    val data = Seq(
      (true, Seq(1, 2)), // id 0
      (true, Seq(1, 3)), // id 1
      (true, Seq(2, 3)) // id 2
    )

    val plis = buildPliSeq(data, 2)
    assertEquals(plis.size, 2)

    val a0 = plis(0)
    val a1 = plis(1)

    // attribute 0
    assertEquals(a0.tupleToValues.toMap, Map(0 -> 1, 1 -> 1, 2 -> 2))
    assertEquals(a0.valueToTuples.keySet, SortedSet(1, 2))
    assertEquals(a0.valueToTuples(1).toArray.toSeq, Seq(0, 1))
    assertEquals(a0.valueToTuples(2).toArray.toSeq, Seq(2))

    // attribute 1
    assertEquals(a1.tupleToValues.toMap, Map(0 -> 2, 1 -> 3, 2 -> 3))
    assertEquals(a1.valueToTuples.keySet, SortedSet(2, 3))
    assertEquals(a1.valueToTuples(2).toArray.toSeq, Seq(0))
    assertEquals(a1.valueToTuples(3).toArray.toSeq, Seq(1, 2))
  }

  test("PLI: insert and remove maintain both maps") {
    val pli = new SortedPli(attribute = 0)
    pli.insert(5, 10)
    pli.insert(6, 10)
    pli.insert(7, 11)

    // after inserts
    assertEquals(pli.tupleToValues.toMap, Map(5 -> 10, 6 -> 10, 7 -> 11))
    assertEquals(pli.valueToTuples(10).toArray.toSeq, Seq(5, 6))
    assertEquals(pli.valueToTuples(11).toArray.toSeq, Seq(7))

    // remove one id from a multi-bucket
    pli.remove(6)
    assertEquals(pli.tupleToValues.contains(6), false)
    assertEquals(pli.valueToTuples(10).toArray.toSeq, Seq(5))

    // remove id from single-element bucket
    pli.remove(7)
    assertEquals(pli.tupleToValues.contains(7), false)
    assertEquals(
      pli.valueToTuples.contains(11),
      false
    ) // key should be removed when empty

    // removing a non-existent id should be a no-op
    pli.remove(42)
    assertEquals(pli.tupleToValues.toMap, Map(5 -> 10))
    assertEquals(pli.valueToTuples(10).toArray.toSeq, Seq(5))
  }

  test("PLI: addRows with offset, then removeRows across all PLIs") {
    val plis = Seq(new SortedPli(0), new SortedPli(1))

    val rows = Seq(
      Seq(9, 8), // tupleId 10
      Seq(9, 7), // tupleId 11
      Seq(6, 7) // tupleId 12
    )

    plis.addTuples(rows, firstId = 10)

    val a0 = plis(0)
    val a1 = plis(1)

    // After addRows
    assertEquals(a0.tupleToValues.toMap, Map(10 -> 9, 11 -> 9, 12 -> 6))
    assertEquals(a0.valueToTuples(9).toArray.toSeq, Seq(10, 11))
    assertEquals(a0.valueToTuples(6).toArray.toSeq, Seq(12))

    assertEquals(a1.tupleToValues.toMap, Map(10 -> 8, 11 -> 7, 12 -> 7))
    assertEquals(a1.valueToTuples(8).toArray.toSeq, Seq(10))
    assertEquals(a1.valueToTuples(7).toArray.toSeq, Seq(11, 12))

    // Remove first and last ids from both PLIs
    plis.removeTuples(Seq(10, 12))

    assertEquals(a0.tupleToValues.toMap, Map(11 -> 9))
    assertEquals(a0.valueToTuples(9).toArray.toSeq, Seq(11))
    assertEquals(a0.valueToTuples.contains(6), false) // key removed when empty

    assertEquals(a1.tupleToValues.toMap, Map(11 -> 7))
    assertEquals(a1.valueToTuples(7).toArray.toSeq, Seq(11))
    assertEquals(a1.valueToTuples.contains(8), false) // key removed when empty
  }
