package structures

import munit.FunSuite

class MMCSTest extends FunSuite:

  test("MMCS: empty hypergraph yields only empty set") {
    val H = Hypergraph(Set.empty, IndexedSeq.empty)
    val result = MMCS.enumerateMinimalHittingSets(H).toSet
    assertEquals(result, Set(Set.empty[Int]))
  }

  test("MMCS: single edge, single vertex") {
    val H = Hypergraph(Set(1), IndexedSeq(Set(1)))
    val result = MMCS.enumerateMinimalHittingSets(H).toSet
    assertEquals(result, Set(Set(1)))
  }

  test("MMCS: single edge, multiple vertices") {
    val H = Hypergraph(Set(1, 2, 3), IndexedSeq(Set(1, 2, 3)))
    val result = MMCS.enumerateMinimalHittingSets(H).toSet
    assertEquals(result, Set(Set(1), Set(2), Set(3)))
  }

  test("MMCS: two disjoint edges") {
    val H = Hypergraph(Set(1, 2), IndexedSeq(Set(1), Set(2)))
    val result = MMCS.enumerateMinimalHittingSets(H).toSet
    assertEquals(result, Set(Set(1, 2)))
  }

  test("MMCS: two overlapping edges") {
    val H = Hypergraph(Set(1, 2), IndexedSeq(Set(1, 2), Set(2)))
    val result = MMCS.enumerateMinimalHittingSets(H).toSet
    assertEquals(result, Set(Set(2)))
  }

  test("MMCS: triangle (3 edges, 3 vertices)") {
    val H =
      Hypergraph(Set(1, 2, 3), IndexedSeq(Set(1, 2), Set(2, 3), Set(1, 3)))
    val result = MMCS.enumerateMinimalHittingSets(H).toSet
    val expected = Set(Set(1, 2), Set(1, 3), Set(2, 3))
    assertEquals(result, expected)
  }

  test("MMCS: edge with all vertices") {
    val H = Hypergraph(Set(1, 2, 3), IndexedSeq(Set(1, 2, 3)))
    val result = MMCS.enumerateMinimalHittingSets(H).toSet
    assertEquals(result, Set(Set(1), Set(2), Set(3)))
  }

  test("MMCS: different sized solutions") {
    val H = Hypergraph(
      Set(1, 2, 3, 4, 5),
      IndexedSeq(Set(1, 2), Set(1, 3, 4), Set(1, 4, 5))
    )
    val result = MMCS.enumerateMinimalHittingSets(H).toSet
    val expected = Set(
      Set(1),
      Set(2, 4),
      Set(2, 3, 5)
    )
    assertEquals(result, expected)
  }
