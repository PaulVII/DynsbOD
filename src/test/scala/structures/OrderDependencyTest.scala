package structures

import scala.collection.immutable.BitSet

class OrderDependencyTest extends munit.FunSuite {

  val testColumnIds = Map(
    "active" -> 0,
    "franchID" -> 1,
    "franchName" -> 2,
    "NAassoc" -> 3,
    "score" -> 4
  )

  test("parseOrderDependency - ConstantOd with valid single context column") {
    val input = "{franchID}: [] ↦ NAassoc"
    val result = parseOrderDependency(input, testColumnIds)
    val expected = Some(
      ConstantOd(
        context = BitSet(1),
        constant = 3
      )
    )
    assertEquals(result, expected)
  }

  test(
    "parseOrderDependency - ConstantOd with valid multiple context columns"
  ) {
    val input = "{active, franchID}: [] ↦ NAassoc"
    val result = parseOrderDependency(input, testColumnIds)
    val expected = Some(
      ConstantOd(
        context = BitSet(0, 1),
        constant = 3
      )
    )
    assertEquals(result, expected)
  }

  test("parseOrderDependency - ConstantOd with empty context") {
    val input = "{}: [] ↦ score"
    val result = parseOrderDependency(input, testColumnIds)
    val expected = Some(
      ConstantOd(
        context = BitSet.empty,
        constant = 4
      )
    )
    assertEquals(result, expected)
  }

  test("parseOrderDependency - ConstantOd with whitespace handling") {
    val input = "{franchID }: [] ↦ NAassoc  "
    val result = parseOrderDependency(input, testColumnIds)
    val expected = Some(
      ConstantOd(
        context = BitSet(1),
        constant = 3
      )
    )
    assertEquals(result, expected)
  }

  test("parseOrderDependency - ConstantOd with invalid context column") {
    val input = "{invalidColumn}: [] ↦ NAassoc"
    val result = parseOrderDependency(input, testColumnIds)
    assertEquals(result, None)
  }

  test("parseOrderDependency - ConstantOd with invalid target column") {
    val input = "{active}: [] ↦ invalidTarget"
    val result = parseOrderDependency(input, testColumnIds)
    assertEquals(result, None)
  }

  test(
    "parseOrderDependency - ConstantOd with mixed valid/invalid context columns"
  ) {
    val input = "{active, invalidColumn}: [] ↦ NAassoc"
    val result = parseOrderDependency(input, testColumnIds)
    assertEquals(result, None)
  }

  test(
    "parseOrderDependency - CompatibleOd with same direction (both ascending)"
  ) {
    val input = "{active, franchName}: franchID↑ ~ NAassoc↑"
    val result = parseOrderDependency(input, testColumnIds)
    val expected = Some(
      CompatibleOd(
        context = BitSet(0, 2),
        attr1 = 1,
        attr2 = 3,
        sameDirection = true
      )
    )
    assertEquals(result, expected)
  }

  test(
    "parseOrderDependency - CompatibleOd with same direction (both descending)"
  ) {
    val input = "{active}: franchID↓ ~ NAassoc↓"
    val result = parseOrderDependency(input, testColumnIds)
    val expected = Some(
      CompatibleOd(
        context = BitSet(0),
        attr1 = 1,
        attr2 = 3,
        sameDirection = true
      )
    )
    assertEquals(result, expected)
  }

  test("parseOrderDependency - CompatibleOd with different directions") {
    val input = "{active, franchName}: franchID↑ ~ NAassoc↓"
    val result = parseOrderDependency(input, testColumnIds)
    val expected = Some(
      CompatibleOd(
        context = BitSet(0, 2),
        attr1 = 1,
        attr2 = 3,
        sameDirection = false
      )
    )
    assertEquals(result, expected)
  }

  test("parseOrderDependency - CompatibleOd with empty context") {
    val input = "{}: franchID↑ ~ score↓"
    val result = parseOrderDependency(input, testColumnIds)
    val expected = Some(
      CompatibleOd(
        context = BitSet.empty,
        attr1 = 1,
        attr2 = 4,
        sameDirection = false
      )
    )
    assertEquals(result, expected)
  }

  test("parseOrderDependency - CompatibleOd with invalid context column") {
    val input = "{invalidColumn}: franchID↑ ~ NAassoc↓"
    val result = parseOrderDependency(input, testColumnIds)
    assertEquals(result, None)
  }

  test("parseOrderDependency - CompatibleOd with invalid first attribute") {
    val input = "{active}: invalidAttr↑ ~ NAassoc↓"
    val result = parseOrderDependency(input, testColumnIds)
    assertEquals(result, None)
  }

  test("parseOrderDependency - CompatibleOd with invalid second attribute") {
    val input = "{active}: franchID↑ ~ invalidAttr↓"
    val result = parseOrderDependency(input, testColumnIds)
    assertEquals(result, None)
  }

  test("parseOrderDependency - CompatibleOd missing direction arrows") {
    val input = "{active}: franchID ~ NAassoc"
    val result = parseOrderDependency(input, testColumnIds)
    assertEquals(result, None)
  }

  test("parseOrderDependency - invalid format") {
    val input = "not a valid format"
    val result = parseOrderDependency(input, testColumnIds)
    assertEquals(result, None)
  }

  test("parseOrderDependency - malformed ConstantOd") {
    val input = "{active}: franchID ↦ NAassoc" // missing []
    val result = parseOrderDependency(input, testColumnIds)
    assertEquals(result, None)
  }

  test("parseOrderDependency - malformed CompatibleOd") {
    val input = "{active}: franchID↑ NAassoc↓" // missing ~
    val result = parseOrderDependency(input, testColumnIds)
    assertEquals(result, None)
  }

  test("parseOrderDependency - whitespace handling in CompatibleOd") {
    val input = "{ active , franchName }: franchID↑ ~ NAassoc↓"
    val result = parseOrderDependency(input, testColumnIds)
    val expected = Some(
      CompatibleOd(
        context = BitSet(0, 2),
        attr1 = 1,
        attr2 = 3,
        sameDirection = false
      )
    )
    assertEquals(result, expected)
  }
}
