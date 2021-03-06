package com.intel.analytics.bigdl.apps.job2Career

import com.intel.analytics.bigdl.apps.job2Career.TrainWithD2VGlove.loadWordVecMap
import com.intel.analytics.bigdl.apps.recommendation.Utils._
import com.intel.analytics.bigdl.apps.recommendation.{Evaluation, ModelParam, ModelUtils}
import com.intel.analytics.bigdl.nn._
import com.intel.analytics.bigdl.optim.Adam
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric.NumericFloat
import com.intel.analytics.zoo.common.NNContext
import com.intel.analytics.zoo.pipeline.nnframes.{NNClassifier, NNClassifierModel, NNEstimator, NNModel}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.sql.functions._
import org.apache.spark.sql._
import scopt.OptionParser

object TrainWithNCF_Glove {

  def main(args: Array[String]): Unit = {

    val defaultParams = TrainParam()

    Logger.getLogger("org").setLevel(Level.ERROR)
    val conf = new SparkConf()
    conf.setAppName("jobs2career").set("spark.sql.crossJoin.enabled", "true")
    val sc = NNContext.initNNContext(conf)
    val sqlContext = SQLContext.getOrCreate(sc)

    val parser = new OptionParser[TrainParam]("jobs2career") {
      opt[String]("inputDir")
        .text(s"inputDir")
        .action((x, c) => c.copy(inputDir = x))
      opt[String]("outputDir")
        .text(s"outputDir")
        .action((x, c) => c.copy(outputDir = x))
      opt[String]("dictDir")
        .text(s"wordVec data")
        .action((x, c) => c.copy(dictDir = x))
      opt[String]("valDir")
        .text(s"valDir data")
        .action((x, c) => c.copy(valDir = x))
      opt[Int]('b', "batchSize")
        .text(s"batchSize")
        .action((x, c) => c.copy(batchSize = x.toInt))
      opt[Int]('e', "nEpochs")
        .text("epoch numbers")
        .action((x, c) => c.copy(nEpochs = x))
      opt[Int]('f', "vectDim")
        .text("dimension of glove vectors")
        .action((x, c) => c.copy(vectDim = x))
      opt[Double]('l', "learningRate")
        .text("learning rate")
        .action((x, c) => c.copy(learningRate = x.toDouble))
      opt[Double]('d', "learningRateDecay")
        .text("learning rate decay")
        .action((x, c) => c.copy(learningRateDecay = x.toDouble))

    }

    parser.parse(args, defaultParams).map {
      params =>
        run(sqlContext, params)
    } getOrElse {
      System.exit(1)
    }
  }

  def run(sqlContext: SQLContext, param: TrainParam): Unit = {

    val input = param.inputDir
    val modelPath = param.inputDir + "/model/all"

    val indexed = sqlContext.read.parquet(input + "/indexed")
      .drop("itemIdOrg").drop("userIdOrg")
    // .withColumnRenamed("itemId", "itemIdOrg").withColumnRenamed("itemIdIndex", "itemId").withColumnRenamed("userId", "userIdOrg").withColumnRenamed("userIdIndex", "userId")
    val userDict = sqlContext.read.parquet(input + "/userDict")
    //  .withColumnRenamed("userId", "userIdOrg").withColumnRenamed("userIdIndex", "userId")
    val itemDict = sqlContext.read.parquet(input + "/itemDict")
    //.withColumnRenamed("itemId", "itemIdOrg").withColumnRenamed("itemIdIndex", "itemId")

    indexed.printSchema()
    indexed.show(3)
    userDict.printSchema()
    userDict.show(3)
    itemDict.printSchema()
    itemDict.show(3)
    val splitNum = (userDict.count() * 0.8).toInt

    //    val indexedTrain = indexed.filter(col("userIdIndex") <= splitNum)
    //    val indexedValidation = indexed.filter(col("userIdIndex") > splitNum)

    val indexedWithNegative = DataProcess.negativeJoin(indexed, itemDict, userDict, negativeK = 1)
      .withColumn("label", add1(col("label")))

    val Array(trainWithNegative, validationWithNegative) = indexedWithNegative.randomSplit(Array(0.8, 0.2), 1L)

    println("---------distribution of label trainWithNegative ----------------")
    // trainWithNegative.select("label").groupBy("label").count().show()
    val trainingDF = getFeaturesLP(trainWithNegative)
    val validationDF = getFeaturesLP(validationWithNegative)

    trainingDF.groupBy("label").count().show()
    validationDF.groupBy("label").count().show()

    trainingDF.cache()
    validationDF.cache()

    val time1 = System.nanoTime()
    val modelParam = ModelParam(userEmbed = 20,
      itemEmbed = 20,
      hiddenLayers = Array(40, 20),
      labels = 2)

    val recModel = new ModelUtils(modelParam)

    // val model = recModel.ncf(userCount, itemCount)
    val model = recModel.mlp3

    val criterion = ClassNLLCriterion()

    val dlc: NNEstimator[Float] = NNClassifier[Float](model, criterion, Array(2 * param.vectDim))
      .setBatchSize(param.batchSize)
      .setOptimMethod(new Adam())
      .setLearningRate(param.learningRate)
      .setLearningRateDecay(param.learningRateDecay)
      .setMaxEpoch(param.nEpochs)

    val dlModel: NNModel[Float] = dlc.fit(trainingDF)

    println(dlModel.model.getParameters())
    dlModel.model.saveModule(modelPath, null, true)

    val time2 = System.nanoTime()

    val predictions: DataFrame = dlModel.transform(validationDF)

    val time3 = System.nanoTime()

    predictions.cache().count()
    predictions.show(20)
    println("validation results")
    Evaluation.evaluate2(predictions.withColumn("label", toZero(col("label")))
      .withColumn("prediction", toZero(col("prediction"))))

    val time4 = System.nanoTime()

    val trainingTime = (time2 - time1) * (1e-9)
    val predictionTime = (time3 - time2) * (1e-9)
    val evaluationTime = (time4 - time3) * (1e-9)

    println("training time(s):  " + toDecimal(3)(trainingTime))
    println("prediction time(s):  " + toDecimal(3)(predictionTime))
    println("evaluation time(s):  " + toDecimal(3)(evaluationTime))

    trainingDF.unpersist()
    validationDF.unpersist()

    processGoldendata(sqlContext, param, modelPath)
    println("stop")

  }

  def processGoldendata(sqlContext: SQLContext, para: TrainParam, modelPath: String) = {

    val loadedModel = Module.loadModule(modelPath, null)
    val dlModel = NNClassifierModel[Float](loadedModel, Array(2 * para.vectDim))
      .setBatchSize(para.batchSize)

    val validationIn = sqlContext.read.parquet(para.valDir)
    // validationIn.printSchema()
    val validationDF = validationIn
      .select("resume_id", "job_id", "resume.resume.normalizedBody", "description", "apply_flag")
      .withColumn("label", add1(col("apply_flag")))
      .withColumnRenamed("resume.resume.normalizedBody", "normalizedBody")
      .withColumnRenamed("resume_id", "userId")
      .withColumnRenamed("normalizedBody", "userDoc")
      .withColumnRenamed("description", "itemDoc")
      .withColumnRenamed("job_id", "itemId")
      .select("userId", "itemId", "userDoc", "itemDoc", "label")
      .filter(col("itemDoc").isNotNull && col("userDoc").isNotNull && col("userId").isNotNull
        && col("itemId").isNotNull && col("label").isNotNull)

    val dict: Map[String, Array[Float]] = loadWordVecMap(para.dictDir)
    val br: Broadcast[Map[String, Array[Float]]] = sqlContext.sparkContext.broadcast(dict)

    val validationCleaned = DataProcess.cleanData(validationDF, br.value)
    val validationVectors = DataProcess.getGloveVectors(validationCleaned, br)
    val validationLP = getFeaturesLP(validationVectors).coalesce(32)

    val predictions2: DataFrame = dlModel.transform(validationLP)
    println("predictions:-----------------------------")
    predictions2.printSchema()
    predictions2.show(5)
    predictions2.count()
    predictions2.groupBy("label").count().show()
    predictions2.groupBy("prediction").count().show()
    predictions2.persist().count()
    predictions2.select("userId", "itemId", "label", "prediction").show(20, false)

    println("validation results on golden dataset")
    val dataToValidation = predictions2.withColumn("label", toZero(col("label")))
      .withColumn("prediction", toZero(col("prediction")))
    Evaluation.evaluate2(dataToValidation)

    // predictions2.write.mode(SaveMode.Overwrite).parquet(para.outputDir)

  }

}