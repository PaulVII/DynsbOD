package structures

import scala.collection.mutable
import scala.collection.immutable.BitSet
import utils.fastSubsetOf

final case class Hypergraph(vertices: Set[Int], edges: IndexedSeq[Set[Int]])
object Hypergraph:
  def minimal(vertices: Set[Int], edges: IterableOnce[BitSet]) =
    val distinctSortedEdges = edges.toSeq.distinct
      .sortBy(_.size)
    val minimalEdges = distinctSortedEdges.foldLeft(Vector.empty[BitSet]) {
      (minimal, candidate) =>
        if minimal.exists(_.fastSubsetOf(candidate)) then minimal
        else minimal :+ candidate
    }
    new Hypergraph(vertices, minimalEdges.map(_.toSet).toIndexedSeq)

// Implementation of the MMCS algorithm for enumerating all minimal hitting sets of a hypergraph
// Implementation of https://www.sciencedirect.com/science/article/pii/S0166218X1400016X
object MMCS:

  def enumerateMinimalHittingSets(H: Hypergraph): Set[Set[Int]] =
    // optimization for common single edge case
    if H.edges.size == 1 then return H.edges.head.map(v => Set(v)).toSet
    // vertex -> set of critical edges (edges that are only hit because this vertex is included in S)
    val crit = mutable.Map
      .empty[Int, mutable.Set[Int]]
    val uncov =
      mutable.BitSet.fromSpecific(H.edges.indices) // edges not yet covered by S

    val vertexEdgePairs = H.edges.zipWithIndex.flatMap { case (edge, idx) =>
      edge.map(v => (v, idx))
    }

    // Create a mapping from vertex to the IDs of edges it belongs to
    val vertexEdgeMap = vertexEdgePairs
      .groupBy(_._1)
      .view // group by vertex
      .mapValues(_.map(_._2).toSet) // extract edge indices as sets
      .toMap

    def dfs(current: Set[Int], candidates: Set[Int]): Set[Set[Int]] =
      // heuristic: pick smallest uncovered edge
      uncov.minByOption(e => H.edges(e).intersect(candidates).size) match
        case None => // uncov is empty
          // Check minimality: each v in S must have at least one critical edge
          if current.forall(v => crit(v).nonEmpty)
          then Set(current)
          else Set.empty
        case (Some(eIdx)) =>
          val edge = H.edges(eIdx)
          var nextCand = candidates -- edge

          edge.flatMap { v =>
            val addedCrit = mutable.Set.empty[Int]
            val removedCrit =
              mutable.Map.empty[Int, mutable.Set[Int]]
            val removedUncov = mutable.Set.empty[Int]

            // Update uncov and crit
            for edgeIdx <- vertexEdgeMap.getOrElse(v, Set.empty) do
              if uncov.contains(edgeIdx) then
                uncov.remove(edgeIdx)
                removedUncov.add(edgeIdx)
                addedCrit.add(edgeIdx)
                crit.getOrElseUpdate(v, mutable.Set.empty[Int]) += edgeIdx
              // find edges that were minimal by another vertex u and mark them non-critical
              current.filter(crit(_).contains(edgeIdx)).foreach { u =>
                crit(u).remove(edgeIdx)
                removedCrit
                  .getOrElseUpdate(u, mutable.Set.empty[Int]) += edgeIdx
              }

            val res = dfs(current + v, nextCand)

            nextCand = nextCand + v // backtrack candidate set

            // Rollback
            for idx <- removedUncov do uncov += idx
            for idx <- addedCrit do crit(v) -= idx
            for (u, idxs) <- removedCrit do crit(u) ++= idxs

            res
          }
    end dfs

    dfs(Set.empty, H.vertices)
