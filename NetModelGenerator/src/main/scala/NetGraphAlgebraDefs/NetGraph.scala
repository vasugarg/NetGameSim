package NetGraphAlgebraDefs

import NetGraphAlgebraDefs.NetModelAlgebra.{createAction, edgeProbability, outputDirectory}
import Randomizer.SupplierOfRandomness
import Utilz.{CreateLogger, NGSConstants}
import com.google.common.graph.{Graphs, MutableValueGraph, ValueGraphBuilder}
import org.apache.commons.io.FileUtils
import org.slf4j.Logger
import sun.nio.cs.UTF_8

import java.io.{BufferedReader, BufferedWriter, ByteArrayInputStream, File, FileInputStream, FileReader, FileWriter, ObjectInputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, StandardOpenOption}
import java.util.Base64
import scala.annotation.tailrec
import scala.collection.immutable.TreeSeqMap.OrderBy
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try, Using}

case class NetGraph(sm: NetStateMachine, initState: NodeObject) extends GraphStore:
  import NetGraph.logger

  def copy: NetGraph = NetGraph(Graphs.copyOf(sm), initState)

  def degrees: List[(Int, Int)] = sm.nodes().asScala.toList.map(node => (sm.inDegree(node), sm.outDegree(node)))

  def totalNodes: Int = sm.nodes().asScala.count(_ => true)

  def forceReachability: Set[NodeObject] =
    val orphanNodes: Set[NodeObject] = unreachableNodes()._1
    logger.info(s"Force reachability: there are ${orphanNodes.size} orphan nodes in the graph")
    if orphanNodes.size <= 0 then Set.empty
    else
      val reachableNodes: List[NodeObject] = (sm.nodes().asScala.toSet -- orphanNodes).toList.sortBy(node => (sm.outDegree(node), sm.inDegree(node)))(Ordering.Tuple2(Ordering.Int, Ordering.Int.reverse))
      val rnSize = reachableNodes.size
      if rnSize <= 0 then
        logger.error("There are no reachable nodes in the graph from the init node")
        Set.empty
      else
        val rn: NodeObject = reachableNodes.head
        val unr: NodeObject = orphanNodes.toList.minBy(node => sm.inDegree(node))
        sm.putEdgeValue(rn, unr, createAction(rn, unr))
        val pvIter: Iterator[Boolean] = SupplierOfRandomness.randProbs(orphanNodes.size*orphanNodes.size).map(_ < edgeProbability).iterator
        orphanNodes.foreach {
          node =>
            orphanNodes.foreach {
              orph =>
                if node != orph && pvIter.nonEmpty && pvIter.next() then
                  if sm.edgeValue(node, orph).isEmpty then
                    sm.putEdgeValue(node, orph, createAction(node, orph))
                  else if sm.edgeValue(orph, node).isEmpty then
                    sm.putEdgeValue(orph, node, createAction(orph, node))
                  else ()
                else ()
            }
        }
        forceReachability


  def adjacencyMatrix: Array[Array[Float]] =
    val nodes: Array[NodeObject] = sm.nodes().asScala.toArray
    val matrix: Array[Array[Float]] = Array.ofDim[Float](nodes.length, nodes.length)
    nodes.indices.foreach(i =>
      nodes.indices.foreach(j =>
        if sm.hasEdgeConnecting(nodes(i), nodes(j)) then matrix(i)(j) = sm.edgeValue(nodes(i), nodes(j)).get.cost.toFloat
        else matrix(i)(j) = Float.PositiveInfinity
      )
    )
    matrix
  end adjacencyMatrix

  extension (m: Array[Array[Float]])
    def toCsv: String =
      val sb = new StringBuilder
      m.foreach(row =>
        sb.append("\n")
        row.foreach(cell => if cell != Float.PositiveInfinity then sb.append(f"$cell%.3f").append(",") else sb.append("-").append(",")))
      sb.toString
    end toCsv
  end extension

  def maxOutDegree(): Int = sm.nodes().asScala.map(node => sm.outDegree(node)).max

  def getRandomConnectedNode(from: NodeObject): Option[(NodeObject, Action)] =
    val successors: Array[NodeObject] = sm.successors(from).asScala.toArray
    if successors.isEmpty then None
    else
      val randomSuccessor: NodeObject = successors(SupplierOfRandomness.onDemand(maxv = successors.length))
      val edge: Action = sm.edgeValue(from, randomSuccessor).get
      Some((randomSuccessor, edge))

  def unreachableNodes(): (Set[NodeObject], Int) =
    var loopsInGraph: Int = 0

    @tailrec
    def dfs(nodes: List[NodeObject], visited: Set[NodeObject]): Set[NodeObject] =
      nodes match
        case Nil => visited
        case hd :: tl =>
          if visited.contains(hd) then
            loopsInGraph += 1
            dfs(tl, visited)
          else
            dfs(sm.successors(hd).asScala.toList.filterNot(n=>visited.contains(n)) ::: tl, visited + hd) // ++ dfs(tl, visited + hd)
    end dfs

    val (reachableNodes: Set[NodeObject], loops: Int) = {
      val rns = dfs(sm.successors(initState).asScala.toList, Set())
      logger.info(s"DFS: reachable ${rns.size} nodes with $loopsInGraph loops in the graph")
      (rns, loopsInGraph)
    }
    val allNodes: Set[NodeObject] = sm.nodes().asScala.toSet
    logger.info(f"The reachability ratio is ${reachableNodes.size.toFloat * 100 / allNodes.size.toFloat}%4.2f or there are ${reachableNodes.size} reachable nodes out of total ${allNodes.size} nodes in the graph")
    (allNodes -- reachableNodes -- Set(initState), loops)
  end unreachableNodes


  def distances(): Map[NodeObject, Double] =
    val distanceMap: scala.collection.mutable.Map[NodeObject, Double] = collection.mutable.Map() ++ sm.nodes().asScala.map(node => node -> Double.PositiveInfinity).toMap
    val zeroCost: Double = 0.0d
    val noEdgeCost: Double = Double.NaN
    distanceMap += (initState -> zeroCost)

    def relax(u: NodeObject)(v: NodeObject): Boolean =
      import scala.jdk.OptionConverters.*
      val edgeCost = if sm.hasEdgeConnecting(u, v) then
        sm.edgeValue(u, v).toScala match
          case Some(action) => action.cost
          case None => noEdgeCost
      else noEdgeCost
      if edgeCost.isNaN then false
      else if distanceMap(v) > distanceMap(u) + edgeCost then
        distanceMap(v) = distanceMap(u) + edgeCost
        true
      else false
    end relax

    def explore(node: NodeObject): Unit =
      require(node != null, "The NodeObject node must not be null")
      val successors = sm.successors(node).asScala.toList
      val relaxNode: NodeObject => Boolean = relax(node)
      successors match
        case Nil => ()
        case hd :: tl => if relaxNode(hd) then explore(hd)
          tl.foreach(cn => if relaxNode(cn) then explore(cn) else ())
    end explore

    explore(initState)
    distanceMap.toMap
  end distances

object NetGraph:
  val logger: Logger = CreateLogger(classOf[NetGraph])

  def load(fileName: String, dir: String = outputDirectory): Option[NetGraph] =
    logger.info(s"Loading the NetGraph from $dir$fileName")

    Try(new FileInputStream(s"$dir$fileName")).map ( fis => (fis, new ObjectInputStream(fis)) ).map { (fis,ois) =>
      val ng = ois.readObject.asInstanceOf[List[NetGraphComponent]]
      ois.close()
      fis.close()
      ng
    }.toOption.flatMap {
      lstOfNetComponents =>
        val nodes = lstOfNetComponents.collect { case node: NodeObject => node }
        val edges = lstOfNetComponents.collect { case edge: Action => edge }
        NetModelAlgebra(nodes, edges)
    }
  end load
end NetGraph

