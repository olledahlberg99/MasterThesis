import org.apache.log4j.{Level, Logger}
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.{DataFrame, SparkSession}

import java.util.Calendar
import java.util.concurrent.TimeUnit
import scala.reflect.ClassTag
import scala.util.Random

object LastMainKMeansRegretUCBLocal {
  def main(args: Array[String]): Unit = {
    Logger.getRootLogger.setLevel(Level.INFO)
    Logger.getLogger("org").setLevel(Level.INFO)
    Logger.getLogger("akka").setLevel(Level.INFO)

    //40k
    //val max_x = 13.46
    //val min_x = 12.8
    //val max_y = 55.7
    //val min_y = 55.39

    //100k
    //val max_x = 14.3
    //val min_x = 12.8
    //val max_y = 56.05
    //val min_y = 55.3

    //200k
    //val max_x = 14.46
    //val min_x = 12.8
    //val max_y = 57.7
    //val min_y = 55.39

    //30k
    //val max_x = 13.4
    //val min_x = 12.7
    //val max_y = 55.7
    //val min_y = 55.5

    //7k
    val max_x = 13
    val min_x = 12.9278
    val max_y = 55.6
    val min_y = 55.5763
    val timeSteps = 1500
    val regret: Array[Double] = new Array[Double](timeSteps + 1)
    var (sc, priorGraph, dataframe) = loadAssumedNetwork(max_y, min_y, max_x, min_x, 1, 0.05)
    val startNodeId = 313881048
    val targetNodeId = 2283338792L
    val r = Random
    println("StartNode ID =  " + startNodeId)
    println("TargetNode ID =  " + targetNodeId)

    priorGraph = priorGraph.mapEdges(e =>
      (e.attr._1,e.attr._2, Math.max(e.attr._1-r.nextGaussian()*Math.sqrt(e.attr._2),0),
       e.attr._4,e.attr._5, Math.max(e.attr._4-r.nextGaussian()*Math.sqrt(e.attr._5),0)))                   //initial Sampling and environment observation generation
    for (i <- 0 to timeSteps) {
      priorGraph = priorGraph.mapEdges(e =>
        (e.attr._1,e.attr._2, Math.max(e.attr._1- math.sqrt(2 * math.log((math.pow(i + 1, 2) * 2704) / math.sqrt(2 * math.Pi))) * math.sqrt(e.attr._2), 0),
          e.attr._4,e.attr._5, Math.max(e.attr._4-r.nextGaussian()*Math.sqrt(e.attr._5),0)))                 //Sampling and environment observation generation
      priorGraph = locallyCheckpointedGraph(priorGraph)                                                     //Checkpointing
      val (shortestPathGraph, time, realCost, bestCost) = Pregel(priorGraph, startNodeId, targetNodeId)     //Pregel
      val posteriorGraph = adjustWeights(shortestPathGraph)                                                 //Weight adjustment
      priorGraph = Graph.fromEdges(posteriorGraph.edges,0)                                       //Set posterior graph as new prior graph
      regret(i) = realCost - bestCost                                                                       //Append regret in regret-array
      println("Regret = " + regret.mkString("Array(", ", ", ")"))
      println("Time = " + time + ", Current time = " + Calendar.getInstance().get(Calendar.HOUR) + ":" + Calendar.getInstance().get(Calendar.MINUTE) + ":" + Calendar.getInstance().get(Calendar.SECOND))
      println("Iteration: " + (i+1))
    }
  }
  private def loadAssumedNetwork(max_y: Double, min_y: Double, max_x: Double, min_x: Double, numberOfPartitions: Int, stdScalingFactor: Double): (SparkSession, Graph[Double, (Double, Double, Double, Double, Double, Double)], DataFrame) = {
    val spark = SparkSession.builder
      .master("local[*]")
      .appName("ThompsonSampling")
      .config("spark.scheduler.mode", "FAIR")
      .getOrCreate()
    var csvFile = spark.read
      .csv("src/main/scala/NewUpdatedSwedenNetwork.csv")
    for (i <- 1 until csvFile.columns.length) {
      csvFile = csvFile.withColumnRenamed(csvFile.columns(i), csvFile.head()(i).toString)
    }
    csvFile = csvFile.withColumnRenamed(csvFile.columns(0), "null")
    val first_row = csvFile.first()
    csvFile = csvFile.filter(row => row != first_row)
    csvFile = csvFile.filter(col("from_y") < max_y && col("from_y") > min_y && col("from_x") > min_x && col("from_x") < max_x)
    val initialEdges: RDD[Edge[(Double, Double, Double, Double, Double, Double)]] = csvFile.repartition(numberOfPartitions)
      .select("from_id", "to_id", "mean_consumption").rdd.map(line =>
        Edge(line.getAs("from_id").toString.toLong, line.getAs("to_id").toString.toLong,
          (line.getAs("mean_consumption").toString.toDouble,
            Math.pow(line.getAs("mean_consumption").toString.toDouble*stdScalingFactor,2),
            0,
            line.getAs("mean_consumption").toString.toDouble - Random.nextGaussian()*line.getAs("mean_consumption").toString.toDouble*stdScalingFactor,
            Math.pow(line.getAs("mean_consumption").toString.toDouble*stdScalingFactor,2),
            0
          )
        )
      )
    val initialGraph = Graph.fromEdges(initialEdges, 0D)
    println("Graph has: " + initialGraph.numEdges + " Edges and: " + initialGraph.numVertices + " Vertices")
    (spark, initialGraph, csvFile)
  }

  def Pregel(graph: Graph[Double, (Double, Double, Double, Double, Double, Double)], sourceNode: VertexId, targetNode: VertexId): (Graph[List[Long], (Double, Double, Double, Double, Double, Double)], Long, Double, Double) = {
    val start = System.nanoTime()
    val initialGraph = graph.mapVertices(
      (id, _) => Tuple4(if (id == sourceNode) 0D else Double.PositiveInfinity, List[Long](), if (id == sourceNode) 0D else Double.PositiveInfinity, 0D))
    val sssp: Graph[(Double, List[Long], Double, Double), (Double, Double, Double, Double, Double, Double)] =
      initialGraph.pregel(
        Tuple4(Double.PositiveInfinity, List[Long](), Double.PositiveInfinity, Double.PositiveInfinity), activeDirection = EdgeDirection.Out)(
      (_, dist, newDist) =>
        if (dist._1 < newDist._1 && dist._3 < newDist._3)
          dist
        else if (dist._1 > newDist._1 && dist._3 < newDist._3)
          (newDist._1, newDist._2, dist._3, newDist._4)
        else if (dist._1 < newDist._1 && dist._3 > newDist._3)
          (dist._1, dist._2, newDist._3, dist._4)
        else
          newDist
        ,
      triplet => {
        if (triplet.srcAttr._1 + triplet.attr._3 < triplet.dstAttr._1 && triplet.srcAttr._3 + triplet.attr._4 < triplet.dstAttr._3)
          Iterator((triplet.dstId, Tuple4(triplet.srcAttr._1 + triplet.attr._3, triplet.srcAttr._2 :+ triplet.srcId, triplet.srcAttr._3 + triplet.attr._4,triplet.srcAttr._4 + triplet.attr._4)))
        else if (triplet.srcAttr._1 + triplet.attr._3 > triplet.dstAttr._1 && triplet.srcAttr._3 + triplet.attr._4 < triplet.dstAttr._3)
          Iterator((triplet.dstId, Tuple4(Double.PositiveInfinity, List[Long](), triplet.srcAttr._3 + triplet.attr._4, Double.PositiveInfinity)))
        else if (triplet.srcAttr._1 + triplet.attr._3 < triplet.dstAttr._1 && triplet.srcAttr._3 + triplet.attr._4 > triplet.dstAttr._3)
          Iterator((triplet.dstId, Tuple4(triplet.srcAttr._1 + triplet.attr._3, triplet.srcAttr._2 :+ triplet.srcId, Double.PositiveInfinity,triplet.srcAttr._4 + triplet.attr._4)))
        else
        {
          Iterator.empty
        }
      },
      (a, b) =>
        if (a._1 < b._1 && a._3 < b._3)
          a
        else if (a._1 > b._1 && a._3 < b._3)
          (b._1, b._2, a._3, b._4)
        else if (a._1 < b._1 && a._3 > b._3)
          (a._1, a._2, b._3, a._4)
        else
          b
    )
    val targetVertex = sssp.vertices.filter(e => e._1 == targetNode)
    println("Expected shortest path cost = " + targetVertex.first()._2._1 + ", Real expected cost = " + targetVertex.first()._2._4 + ", shortest path expected cost = " + targetVertex.first()._2._3)
    val pathList = sssp.vertices.filter(e => e._1 == targetNode).map(e=>(e._1,e._2._2:+targetNode))
    val cartList = pathList.cartesian(graph.vertices)
    val mappedCartList = cartList.map(e=>(e._2._1,e._1._2))
    val newGraph = Graph(mappedCartList,graph.edges)
    val end = System.nanoTime()
    val difference = end - start
    (newGraph, TimeUnit.NANOSECONDS.toSeconds(difference), targetVertex.first._2._4, targetVertex.first._2._3)
  }
  def adjustWeights(shortestPathGraph: Graph[List[Long],(Double,Double,Double,Double,Double,Double)]): Graph[List[Long],(Double,Double,Double,Double,Double,Double)] = {
    val posteriorEdges = shortestPathGraph.triplets.map(triplet => {
      if (triplet.srcAttr.contains(triplet.srcId) && triplet.srcAttr.contains(triplet.dstId)){
        val (priorMean,priorVariance,sampledValue,environmentMean,noiseVariance,observation) = triplet.attr
        val posteriorVariance = Math.pow(1 / priorVariance + 1 / noiseVariance, -1)
        val posteriorMean = posteriorVariance * (priorMean / priorVariance + observation / noiseVariance)
        Edge(srcId = triplet.srcId, dstId = triplet.dstId, attr = (posteriorMean,posteriorVariance, sampledValue, environmentMean, noiseVariance, observation))
      }
      else{
        Edge(srcId = triplet.srcId, dstId = triplet.dstId, attr = triplet.attr)
      }
    })
    val posteriorGraph = Graph(shortestPathGraph.vertices,posteriorEdges)
    posteriorGraph
  }
  def locallyCheckpointedGraph[VD : ClassTag, ED : ClassTag](graph : Graph[VD, ED]) : Graph[VD, ED] = {
    val mappedGraph = graph.mapEdges(e => e.attr)
    val edgeRdd = mappedGraph.edges.map(x => x)
    val vertexRdd = mappedGraph.vertices.map(x => x)
    edgeRdd.cache()
    edgeRdd.localCheckpoint()
    edgeRdd.count() // We need this line to force the RDD to evaluate, otherwise the truncation is not performed
    vertexRdd.cache()
    vertexRdd.localCheckpoint()
    vertexRdd.count() // We need this line to force the RDD to evaluate, otherwise the truncation is not performed
    Graph(vertexRdd, edgeRdd)
  }
}