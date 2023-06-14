import org.apache.log4j.{Level, Logger}
import org.apache.spark.graphx._
import org.apache.spark.ml.clustering.GaussianMixture
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{DoubleType, LongType, StructType}
import org.apache.spark.sql.{DataFrame, SparkSession}

import java.util.concurrent.TimeUnit
import scala.reflect.ClassTag
import scala.util.Random

object LastTSGMM {
  def main(args: Array[String]): Unit = {
    Logger.getRootLogger.setLevel(Level.INFO)
    Logger.getLogger("org").setLevel(Level.INFO)
    Logger.getLogger("akka").setLevel(Level.INFO)

    //5k
    //val max_x = 13.07
    //val min_x = 12.96
    //val max_y = 55.589
    //val min_y = 55.5
    //40k
    val max_x = 13.46
    val min_x = 12.8
    val max_y = 55.7
    val min_y = 55.39
    //100k
    //val max_x = 14.3
    //val min_x = 12.8
    //val max_y = 56.05
    //val min_y = 55.3

    val timeSteps = 10
    var (sc, initialGraph, dataframe) = loadAssumedNetwork(max_y, min_y, max_x, min_x, 2, 0.05)
    val partitionedGraph = initialGraph.partitionBy(new PartitionStrategy {
      val edgeList: Map[(VertexId, VertexId), PartitionID] = initialGraph.edges.collect().map(e => ((e.srcId, e.dstId), e.attr._7)).toMap

      override def getPartition(src: VertexId, dst: VertexId, numParts: PartitionID): PartitionID = {
        edgeList(src, dst)
      }
    })
    val startNodeId = 312415869
    val targetNodeId = 313622791
    val r = Random
    println("StartNode ID =  " + startNodeId)
    println("TargetNode ID =  " + targetNodeId)

    var priorGraph = partitionedGraph.mapEdges(e =>
      (e.attr._1,e.attr._2, Math.max(e.attr._1-r.nextGaussian()*Math.sqrt(e.attr._2),0),
       e.attr._4,e.attr._5, Math.max(e.attr._4-r.nextGaussian()*Math.sqrt(e.attr._5),0)))                   //initial Sampling and environment observation generation
    for (i <- 0 to timeSteps) {
      priorGraph = priorGraph.mapEdges(e =>
        (e.attr._1,e.attr._2, Math.max(e.attr._1-r.nextGaussian()*Math.sqrt(e.attr._2),0),
          e.attr._4,e.attr._5, Math.max(e.attr._4-r.nextGaussian()*Math.sqrt(e.attr._5),0)))                 //Sampling and environment observation generation
      priorGraph = locallyCheckpointedGraph(priorGraph)                                                     //Checkpointing
      val (shortestPathGraph, time) = Pregel(priorGraph, startNodeId, targetNodeId)     //Pregel
      val posteriorGraph = adjustWeights(shortestPathGraph)                                                 //Weight adjustment
      priorGraph = Graph.fromEdges(posteriorGraph.edges,0)                                       //Set posterior graph as new prior graph       //Append regret in regret-array
      println(time)
    }
  }

  def clustering(max_y: Double, min_y: Double, max_x: Double, min_x: Double, numberOfPartitions: Int): DataFrame = {
    val spark = SparkSession
      .builder
      .master("local[*]")
      .appName("Clustering")
      .getOrCreate()
    //Define columns
    val schema = new StructType()
      .add("from_id", LongType, true)
      .add("to_id", LongType, true)
      .add("from_y", DoubleType, true)
      .add("from_x", DoubleType, true)
      .add("to_y", DoubleType, true)
      .add("to_x", DoubleType, true)
      .add("length", DoubleType, true)
      .add("speed", DoubleType, true)
      .add("mean_consumption", DoubleType, true)
      .add("mean_time", DoubleType, true)
    //Load network
    var df = spark.read.format("csv").schema(schema).load("/mounted-data/src/main/scala/SwedenNetwork.csv").cache()
    df = df.na.drop()
    df = df.filter(col("from_y") < max_y && col("from_y") > min_y && col("from_x") > min_x && col("from_x") < max_x)
    // Create features
    val featureCols = Array("from_y", "from_x")
    val assembler = new VectorAssembler()
      .setInputCols(featureCols)
      .setOutputCol("features")
    val vectorizedData = assembler.transform(df)
    val dataset = vectorizedData.select("mean_time", "features")

    // Trains a k-means model.
    val gmm = new GaussianMixture().setK(numberOfPartitions).setSeed(1L)
    val model = gmm.fit(dataset)

    // Make predictions and create dataframe
    val predictions = model.transform(dataset)

    val joinedData = predictions.select("features", "prediction").join(vectorizedData, Seq("features"), "inner")
    val SwedenNetworkDataset = joinedData.select("prediction", "from_id", "to_id", "length", "mean_consumption", "from_y", "from_x", "to_y", "to_x")
    SwedenNetworkDataset.distinct()
  }
  private def loadAssumedNetwork(max_y: Double, min_y: Double, max_x: Double, min_x: Double, numberOfClusters: Int, stdScalingFactor: Double): (SparkSession, Graph[Double, (Double, Double, Double, Double, Double, Double, Int)], DataFrame) = {
    val spark = SparkSession.builder
      .master("local[*]")
      .getOrCreate()
    //spark.sparkContext.setCheckpointDir("/mounted-data/src/main/scala")
    val SwedenNetwork = clustering(max_y, min_y, max_x, min_x,numberOfClusters)
    val schemaString = "prediction,from_id,to_id,length,mean_consumption,from_y,from_x,to_y,to_x"
    val SwedenNetworkDF = SwedenNetwork.filter(row => row != schemaString)
    val edges: RDD[Edge[(Double, Double, Double, Double, Double, Double, Int)]] = SwedenNetworkDF.repartition(numberOfClusters)
      .select("from_id", "to_id", "mean_consumption", "prediction").rdd.map(line =>
      Edge(line.getAs("from_id").toString.toLong, line.getAs("to_id").toString.toLong,
        (line.getAs("mean_consumption").toString.toDouble,
          Math.pow(line.getAs("mean_consumption").toString.toDouble * stdScalingFactor, 2),
          0,
          line.getAs("mean_consumption").toString.toDouble - Random.nextGaussian() * line.getAs("mean_consumption").toString.toDouble * stdScalingFactor,
          Math.pow(line.getAs("mean_consumption").toString.toDouble * stdScalingFactor, 2),
          0,
          line.getAs("prediction").toString.toInt
        )
      )
    )
    val graph = Graph.fromEdges(edges, 0D)
    println("Graph has: " + graph.numEdges + " Edges and: " + graph.numVertices + " Vertices")
    (spark, graph, SwedenNetworkDF)
  }

  def Pregel(graph: Graph[Double, (Double, Double, Double, Double, Double, Double)], sourceNode: VertexId, targetNode: VertexId): (Graph[List[Long], (Double, Double, Double, Double, Double, Double)], Long) = {
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
    val pathList = sssp.vertices.filter(e => e._1 == targetNode).map(e=>(e._1,e._2._2:+targetNode))
    val cartList = pathList.cartesian(graph.vertices)
    val mappedCartList = cartList.map(e=>(e._2._1,e._1._2))
    val newGraph = Graph(mappedCartList,graph.edges)
    val end = System.nanoTime()
    val difference = end - start
    (newGraph, TimeUnit.NANOSECONDS.toSeconds(difference))
  }
  def adjustWeights(shortestPathGraph: Graph[List[Long],(Double,Double,Double,Double,Double,Double)]): Graph[List[Long],(Double,Double,Double,Double,Double,Double)] = {
    val posteriorEdges = shortestPathGraph.triplets.map(triplet => {
      if (triplet.srcAttr.contains(triplet.srcId) && triplet.srcAttr.contains(triplet.dstId)){
        val (priorMean,priorVariance,sampledValue,environmentMean,noiseVariance,observation) = triplet.attr
        val posteriorVariance = Math.pow(1 / priorVariance + 1 / noiseVariance, -1)
        val posteriorMean = posteriorVariance * (priorMean / priorVariance + observation / noiseVariance)
        Edge(srcId = triplet.srcId, dstId = triplet.dstId, attr = (posteriorMean,posteriorVariance, sampledValue, environmentMean, noiseVariance, observation))
      }
      else
        Edge(srcId = triplet.srcId, dstId = triplet.dstId, attr = triplet.attr)
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