/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.stream

import akka.actor.{ ActorSystem, ActorRef }
import akka.stream._
import akka.stream.scaladsl._
import akka.util.Timeout

import scala.util.Try
import scala.concurrent.Future
import scala.concurrent.duration._

import java.util.UUID.randomUUID

import org.scalatest._
import org.scalatest.concurrent._

// simple XORshift* random number generator (see, e.g., http://en.wikipedia.org/wiki/Xorshift)
class RandomLongValueGenerator(seed: Long = 182642182642182642L) extends Iterator[Long] {
  private[this] var state = seed

  def hasNext = true

  def next(): Long = {
    var x = state
    x ^= x << 21
    x ^= x >>> 35
    x ^= x << 4
    state = x
    (x * 0x2545f4914f6cdd1dL) - 1
  }
}

class RandomDoubleValueGenerator(seed: Long = 182642182642182642L) extends Iterator[Double] {
  private[this] val inner = new RandomLongValueGenerator(seed)

  def hasNext = true

  def next(): Double = (inner.next() & ((1L << 53) - 1)) / (1L << 53).toDouble
}

class PiApproximationSpec extends WordSpec with BeforeAndAfterAll with ScalaFutures {
  implicit val patience = PatienceConfig(180.seconds)

  "demonstrate a complex Flow" in {

    implicit val system = ActorSystem()
    implicit val dispatcher = system.dispatcher
    implicit val materializer = ActorMaterializer()

    case class Point(x: Double, y: Double) {
      def isInner: Boolean = x * x + y * y < 1.0
    }

    sealed trait Sample
    case class InnerSample(point: Point) extends Sample
    case class OuterSample(point: Point) extends Sample

    case class State(totalSamples: Long, inCircle: Long) {
      def π: Double = (inCircle.toDouble / totalSamples) * 4.0
      def withNextSample(sample: Sample) =
        State(totalSamples + 1, if (sample.isInstanceOf[InnerSample]) inCircle + 1 else inCircle)
    }

    case object Tick

    def broadcastFilterMerge =
      GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._

        val broadcast = b.add(Broadcast[Point](2)) // split one upstream into 2 downstreams
        val filterInner = b.add(Flow[Point].filter(_.isInner).map(InnerSample))
        val filterOuter = b.add(Flow[Point].filter(!_.isInner).map(OuterSample))
        val merge = b.add(Merge[Sample](2)) // merge 2 upstreams into one downstream

        broadcast.out(0) ~> filterInner ~> merge.in(0)
        broadcast.out(1) ~> filterOuter ~> merge.in(1)

        FlowShape(broadcast.in, merge.out)
      }

    def onePerSecValve =
      GraphDSL.create() { implicit b ⇒
        import GraphDSL.Implicits._

        val zip = b.add(ZipWith[State, Tick.type, State](Keep.left)
          .withAttributes(Attributes.inputBuffer(1, 1)))
        val dropOne = b.add(Flow[State].drop(1))

        Source.tick(Duration.Zero, 1.second, Tick) ~> zip.in1
        zip.out ~> dropOne.in

        FlowShape(zip.in0, dropOne.outlet)
      }

    val generator = new RandomDoubleValueGenerator()

    val flow =
      Source.tick(1.millis, 10.nano, ()).map(_ => generator.next)
        .grouped(2)
        .take(1000)
        .map { case x +: y +: Nil ⇒ Point(x, y) }
        .via(broadcastFilterMerge)
        .scan(State(0, 0)) { _ withNextSample _ }
        .conflateWithSeed(identity)(Keep.right)
        .via(onePerSecValve)
        .map(state ⇒ f"After ${state.totalSamples}%,10d samples π is approximated as ${state.π}%.5f")

    //check if this is needed...
    //system.scheduler.scheduleOnce(100 millis) {
    //  flow.runWith(Sink.onComplete(_ ⇒ system.terminate()))
    //}

    val done = flow.runForeach(println)

    done.onComplete(_ => system.terminate())

    done.futureValue
  }

}
