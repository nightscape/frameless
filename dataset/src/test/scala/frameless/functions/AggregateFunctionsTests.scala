package frameless
package functions

import frameless.functions.aggregate._
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop._

class AggregateFunctionsTests extends TypedDatasetSuite {

  def approximatelyEqual[A](a: A, b: A)(implicit numeric: Numeric[A]): Prop = {
    val da = numeric.toDouble(a)
    val db = numeric.toDouble(b)
    val epsilon = 1E-6
    // Spark has a weird behaviour concerning expressions that should return Inf
    // Most of the time they return NaN instead, for instance stddev of Seq(-7.827553978923477E227, -5.009124275715786E153)
    if((da.isNaN || da.isInfinity) && (db.isNaN || db.isInfinity)) proved
    else if (
      (da - db).abs < epsilon ||
      (da - db).abs < da.abs / 100)
        proved
    else falsified :| s"Expected $a but got $b, which is more than 1% off and greater than epsilon = $epsilon."
  }

  def sparkSchema[A: TypedEncoder, U](f: TypedColumn[X1[A], A] => TypedColumn[X1[A], U]): Prop = {
    val df = TypedDataset.create[X1[A]](Nil)
    val col = f(df.col('a))

    import col.uencoder

    val sumDf = df.select(col)

    TypedExpressionEncoder.targetStructType(sumDf.encoder) ?= sumDf.dataset.schema
  }

  test("sum") {
    case class Sum4Tests[A, B](sum: Seq[A] => B)

    def prop[A: TypedEncoder, Out: TypedEncoder : Numeric](xs: List[A])(
      implicit
      summable: CatalystSummable[A, Out],
      summer: Sum4Tests[A, Out]
    ): Prop = {
      val dataset = TypedDataset.create(xs.map(X1(_)))
      val A = dataset.col[A]('a)

      val datasetSum: List[Out] = dataset.select(sum(A)).collect().run().toList

      datasetSum match {
        case x :: Nil => approximatelyEqual(summer.sum(xs), x)
        case other => falsified
      }
    }

    // Replicate Spark's behaviour : Ints and Shorts are cast to Long
    // https://github.com/apache/spark/blob/7eb2ca8/sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/aggregate/Sum.scala#L37
    implicit def summerDecimal = Sum4Tests[BigDecimal, BigDecimal](_.sum)
    implicit def summerDouble = Sum4Tests[Double, Double](_.sum)
    implicit def summerLong = Sum4Tests[Long, Long](_.sum)
    implicit def summerInt = Sum4Tests[Int, Long](_.map(_.toLong).sum)
    implicit def summerShort = Sum4Tests[Short, Long](_.map(_.toLong).sum)

    check(forAll(prop[BigDecimal, BigDecimal] _))
    check(forAll(prop[Long, Long] _))
    check(forAll(prop[Double, Double] _))
    check(forAll(prop[Int, Long] _))
    check(forAll(prop[Short, Long] _))

    check(sparkSchema[BigDecimal, BigDecimal](sum))
    check(sparkSchema[Long, Long](sum))
    check(sparkSchema[Int, Long](sum))
    check(sparkSchema[Double, Double](sum))
    check(sparkSchema[Short, Long](sum))
  }

  test("sumDistinct") {
    case class Sum4Tests[A, B](sum: Seq[A] => B)

    def prop[A: TypedEncoder, Out: TypedEncoder : Numeric](xs: List[A])(
      implicit
      summable: CatalystSummable[A, Out],
      summer: Sum4Tests[A, Out]
    ): Prop = {
      val dataset = TypedDataset.create(xs.map(X1(_)))
      val A = dataset.col[A]('a)

      val datasetSum: List[Out] = dataset.select(sumDistinct(A)).collect().run().toList

      datasetSum match {
        case x :: Nil => approximatelyEqual(summer.sum(xs), x)
        case other => falsified
      }
    }

    // Replicate Spark's behaviour : Ints and Shorts are cast to Long
    // https://github.com/apache/spark/blob/7eb2ca8/sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/aggregate/Sum.scala#L37
    implicit def summerLong = Sum4Tests[Long, Long](_.toSet.sum)
    implicit def summerInt = Sum4Tests[Int, Long]( x => x.toSet.map((_:Int).toLong).sum)
    implicit def summerShort = Sum4Tests[Short, Long](x => x.toSet.map((_:Short).toLong).sum)

    check(forAll(prop[Long, Long] _))
    check(forAll(prop[Int, Long] _))
    check(forAll(prop[Short, Long] _))

    check(sparkSchema[Long, Long](sum))
    check(sparkSchema[Int, Long](sum))
    check(sparkSchema[Short, Long](sum))
  }

  test("avg") {
    case class Averager4Tests[A, B](avg: Seq[A] => B)

    def prop[A: TypedEncoder, Out: TypedEncoder : Numeric](xs: List[A])(
      implicit
      averageable: CatalystAverageable[A, Out],
      averager: Averager4Tests[A, Out]
    ): Prop = {
      val dataset = TypedDataset.create(xs.map(X1(_)))
      val A = dataset.col[A]('a)

      val Vector(datasetAvg): Vector[Option[Out]] = dataset.select(avg(A)).collect().run().toVector

      xs match {
        case Nil => datasetAvg ?= None
        case _ :: _ => datasetAvg match {
          case Some(x) => approximatelyEqual(averager.avg(xs), x)
          case other => falsified
        }
      }
    }

    // Replicate Spark's behaviour : If the datatype isn't BigDecimal cast type to Double
    // https://github.com/apache/spark/blob/7eb2ca8/sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/expressions/aggregate/Average.scala#L50
    implicit def averageDecimal = Averager4Tests[BigDecimal, BigDecimal](as => as.sum/as.size)
    implicit def averageDouble = Averager4Tests[Double, Double](as => as.sum/as.size)
    implicit def averageLong = Averager4Tests[Long, Double](as => as.map(_.toDouble).sum/as.size)
    implicit def averageInt = Averager4Tests[Int, Double](as => as.map(_.toDouble).sum/as.size)
    implicit def averageShort = Averager4Tests[Short, Double](as => as.map(_.toDouble).sum/as.size)

    check(forAll(prop[BigDecimal, BigDecimal] _))
    check(forAll(prop[Double, Double] _))
    check(forAll(prop[Long, Double] _))
    check(forAll(prop[Int, Double] _))
    check(forAll(prop[Short, Double] _))
  }

  test("stddev and variance") {
    def verifyStat[A: Numeric](xs: List[A],
                               datasetEstimate: Option[Double],
                               rddBasedEstimate: Double) = {
      xs match {
        case Nil => datasetEstimate ?= None
        case _ :: Nil => datasetEstimate match {
          case Some(x) => if (x.isNaN) proved else falsified
          case _ => falsified
        }
        case _ => datasetEstimate match {
          case Some(x) => approximatelyEqual(rddBasedEstimate, x)
          case _ => falsified
        }
      }
    }

    def prop[A: TypedEncoder : CatalystVariance : Numeric](xs: List[A]): Prop = {
      val dataset = TypedDataset.create(xs.map(X1(_)))
      val A = dataset.col[A]('a)

      val Vector(datasetStd) = dataset.select(stddev(A)).collect().run().toVector
      val Vector(datasetVar) = dataset.select(variance(A)).collect().run().toVector
      val std = sc.parallelize(xs.map(implicitly[Numeric[A]].toDouble)).sampleStdev()
      val `var` = sc.parallelize(xs.map(implicitly[Numeric[A]].toDouble)).sampleVariance()

      verifyStat(xs, datasetStd, std) && verifyStat(xs, datasetVar, `var`)
    }

    check(forAll(prop[Short] _))
    check(forAll(prop[Int] _))
    check(forAll(prop[Long] _))
    check(forAll(prop[BigDecimal] _))
    check(forAll(prop[Double] _))
  }

  test("count") {
    def prop[A: TypedEncoder](xs: List[A]): Prop = {
      val dataset = TypedDataset.create(xs)
      val Vector(datasetCount) = dataset.select(count()).collect().run().toVector

      datasetCount ?= xs.size.toLong
    }

    check(forAll(prop[Int] _))
    check(forAll(prop[Byte] _))
  }

  test("count('a)") {
    def prop[A: TypedEncoder](xs: List[A]): Prop = {
      val dataset = TypedDataset.create(xs.map(X1(_)))
      val A = dataset.col[A]('a)
      val datasetCount = dataset.select(count(A)).collect().run()

      datasetCount ?= List(xs.size.toLong)
    }

    check(forAll(prop[Int] _))
    check(forAll(prop[Byte] _))
  }

  test("max") {
    def prop[A: TypedEncoder: CatalystOrdered](xs: List[A])(implicit o: Ordering[A]): Prop = {
      val dataset = TypedDataset.create(xs.map(X1(_)))
      val A = dataset.col[A]('a)
      val datasetMax = dataset.select(max(A)).collect().run().toList

      datasetMax ?= List(xs.reduceOption(o.max))
    }

    check(forAll(prop[Long] _))
    check(forAll(prop[Double] _))
    check(forAll(prop[Int] _))
    check(forAll(prop[Short] _))
    check(forAll(prop[Byte] _))
    check(forAll(prop[String] _))
  }

  test("min") {
    def prop[A: TypedEncoder: CatalystOrdered](xs: List[A])(implicit o: Ordering[A]): Prop = {
      val dataset = TypedDataset.create(xs.map(X1(_)))
      val A = dataset.col[A]('a)

      val datasetMin = dataset.select(min(A)).collect().run().toList

      datasetMin ?= List(xs.reduceOption(o.min))
    }

    check(forAll(prop[Long] _))
    check(forAll(prop[Double] _))
    check(forAll(prop[Int] _))
    check(forAll(prop[Short] _))
    check(forAll(prop[Byte] _))
    check(forAll(prop[String] _))
  }

  test("first") {
    def prop[A: TypedEncoder](xs: List[A]): Prop = {
      val dataset = TypedDataset.create(xs.map(X1(_)))
      val A = dataset.col[A]('a)

      val datasetFirst = dataset.select(first(A)).collect().run().toList

      datasetFirst ?= List(xs.headOption)
    }

    check(forAll(prop[BigDecimal] _))
    check(forAll(prop[Long] _))
    check(forAll(prop[Double] _))
    check(forAll(prop[Int] _))
    check(forAll(prop[Short] _))
    check(forAll(prop[Byte] _))
    check(forAll(prop[String] _))
  }

  test("last") {
    def prop[A: TypedEncoder](xs: List[A]): Prop = {
      val dataset = TypedDataset.create(xs.map(X1(_)))
      val A = dataset.col[A]('a)

      val datasetLast = dataset.select(last(A)).collect().run().toList

      datasetLast ?= List(xs.lastOption)
    }

    check(forAll(prop[BigDecimal] _))
    check(forAll(prop[Long] _))
    check(forAll(prop[Double] _))
    check(forAll(prop[Int] _))
    check(forAll(prop[Short] _))
    check(forAll(prop[Byte] _))
    check(forAll(prop[String] _))
  }

  // Generator for simplified and focused aggregation examples
  def getLowCardinalityKVPairs: Gen[Vector[(Int, Int)]] = {
    val kvPairGen: Gen[(Int, Int)] = for {
      k <- Gen.const(1) // key
      v <- Gen.choose(10, 100) // values
    } yield (k, v)

    Gen.listOfN(200, kvPairGen).map(_.toVector)
  }

  test("countDistinct") {
    check {
      forAll(getLowCardinalityKVPairs) { xs: Vector[(Int, Int)] =>
        val tds = TypedDataset.create(xs)
        val tdsRes: Seq[(Int, Long)] = tds.groupBy(tds('_1)).agg(countDistinct(tds('_2))).collect().run()
        tdsRes.toMap ?= xs.groupBy(_._1).mapValues(_.map(_._2).distinct.size.toLong).toSeq.toMap
      }
    }
  }

  test("approxCountDistinct") {
    // Simple version of #approximatelyEqual()
    // Default maximum estimation error of HyperLogLog in Spark is 5%
    def approxEqual(actual: Long, estimated: Long, allowedDeviationPercentile: Double = 0.05): Boolean = {
      val delta: Long = Math.abs(actual - estimated)
      delta / actual.toDouble < allowedDeviationPercentile * 2
    }

    check {
      forAll(getLowCardinalityKVPairs) { xs: Vector[(Int, Int)] =>
        val tds = TypedDataset.create(xs)
        val tdsRes: Seq[(Int, Long, Long)] =
          tds.groupBy(tds('_1)).agg(countDistinct(tds('_2)), approxCountDistinct(tds('_2))).collect().run()
        tdsRes.forall { case (_, v1, v2) => approxEqual(v1, v2) }
      }
    }

    check {
      forAll(getLowCardinalityKVPairs) { xs: Vector[(Int, Int)] =>
        val tds = TypedDataset.create(xs)
        val allowedError = 0.1 // 10%
        val tdsRes: Seq[(Int, Long, Long)] =
          tds.groupBy(tds('_1)).agg(countDistinct(tds('_2)), approxCountDistinct(tds('_2), allowedError)).collect().run()
        tdsRes.forall { case (_, v1, v2) => approxEqual(v1, v2, allowedError) }
      }
    }
  }

  test("collectList") {
    def prop[A: TypedEncoder : Ordering](xs: List[X2[A, A]]): Prop = {
      val tds = TypedDataset.create(xs)
      val tdsRes: Seq[(A, Vector[A])] = tds.groupBy(tds('a)).agg(collectList(tds('b))).collect().run()

      tdsRes.toMap.mapValues(_.sorted) ?= xs.groupBy(_.a).mapValues(_.map(_.b).toVector.sorted)
    }

    check(forAll(prop[Long] _))
    check(forAll(prop[Int] _))
    check(forAll(prop[Byte] _))
    check(forAll(prop[String] _))
  }

  test("collectSet") {
    def prop[A: TypedEncoder : Ordering](xs: List[X2[A, A]]): Prop = {
      val tds = TypedDataset.create(xs)
      val tdsRes: Seq[(A, Vector[A])] = tds.groupBy(tds('a)).agg(collectSet(tds('b))).collect().run()

      tdsRes.toMap.mapValues(_.toSet) ?= xs.groupBy(_.a).mapValues(_.map(_.b).toSet)
    }

    check(forAll(prop[Long] _))
    check(forAll(prop[Int] _))
    check(forAll(prop[Byte] _))
    check(forAll(prop[String] _))
  }

  test("lit") {
    def prop[A: TypedEncoder](xs: List[X1[A]], l: A): Prop = {
      val tds = TypedDataset.create(xs)
      tds.select(tds('a), lit(l)).collect().run() ?= xs.map(x => (x.a, l))
    }

    check(forAll(prop[Long] _))
    check(forAll(prop[Int] _))
    check(forAll(prop[Vector[Vector[Int]]] _))
    check(forAll(prop[Byte] _))
    check(forAll(prop[Vector[Byte]] _))
    check(forAll(prop[String] _))
    check(forAll(prop[Vector[Long]] _))
    check(forAll(prop[BigDecimal] _))
  }
}
