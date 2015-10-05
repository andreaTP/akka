/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.io.Closeable
import java.util.concurrent.{ ConcurrentHashMap, ThreadFactory, CountDownLatch, TimeoutException, RejectedExecutionException }
import java.util.concurrent.atomic.{ AtomicReference }
import java.util.concurrent.TimeUnit.MILLISECONDS
import com.typesafe.config.{ Config, ConfigFactory }
import akka.event._
import akka.dispatch._
import akka.dispatch.sysmsg.{ SystemMessageList, EarliestFirstSystemMessageList, LatestFirstSystemMessageList, SystemMessage }
import akka.japi.Util.immutableSeq
import akka.actor.dungeon.ChildrenContainer
import akka.util._
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.duration.{ FiniteDuration, Duration }
import scala.concurrent.{ Await, Future, Promise, ExecutionContext, ExecutionContextExecutor }
import scala.util.{ Failure, Success, Try }
import scala.util.control.{ NonFatal, ControlThrowable }

trait AbstractActorSystemImpl {
  self: ActorSystemImpl ⇒

  import ActorSystem._

  protected def uncaughtExceptionHandler: Thread.UncaughtExceptionHandler =
    new Thread.UncaughtExceptionHandler() {
      def uncaughtException(thread: Thread, cause: Throwable): Unit = {
        cause match {
          case NonFatal(_) | _: InterruptedException | _: NotImplementedError | _: ControlThrowable ⇒ log.error(cause, "Uncaught error from thread [{}]", thread.getName)
          case _ ⇒
            if (settings.JvmExitOnFatalError) {
              try {
                log.error(cause, "Uncaught error from thread [{}] shutting down JVM since 'akka.jvm-exit-on-fatal-error' is enabled", thread.getName)
                import System.err
                err.print("Uncaught error from thread [")
                err.print(thread.getName)
                err.print("] shutting down JVM since 'akka.jvm-exit-on-fatal-error' is enabled for ActorSystem[")
                err.print(name)
                err.println("]")
                cause.printStackTrace(System.err)
                System.err.flush()
              } finally {
                System.exit(-1)
              }
            } else {
              log.error(cause, "Uncaught fatal error from thread [{}] shutting down ActorSystem [{}]", thread.getName, name)
              terminate()
            }
        }
      }
    }

  val internalCallingThreadExecutionContext: ExecutionContext =
    dynamicAccess.getObjectFor[ExecutionContext]("scala.concurrent.Future$InternalCallbackExecutor$").getOrElse(
      new ExecutionContext with BatchingExecutor {
        override protected def unbatchedExecute(r: Runnable): Unit = r.run()
        override protected def resubmitOnBlock: Boolean = false // Since we execute inline, no gain in resubmitting
        override def reportFailure(t: Throwable): Unit = dispatcher reportFailure t
      })

  override def awaitTermination(timeout: Duration): Unit = { Await.ready(whenTerminated, timeout) }
  override def awaitTermination(): Unit = awaitTermination(Duration.Inf)

  //#create-scheduler
  /**
   * Create the scheduler service. This one needs one special behavior: if
   * Closeable, it MUST execute all outstanding tasks upon .close() in order
   * to properly shutdown all dispatchers.
   *
   * Furthermore, this timer service MUST throw IllegalStateException if it
   * cannot schedule a task. Once scheduled, the task MUST be executed. If
   * executed upon close(), the task may execute before its timeout.
   */
  protected def createScheduler(): Scheduler =
    dynamicAccess.createInstanceFor[Scheduler](settings.SchedulerClass, immutable.Seq(
      classOf[Config] -> settings.config,
      classOf[LoggingAdapter] -> log,
      classOf[ThreadFactory] -> threadFactory.withName(threadFactory.name + "-scheduler"))).get
  //#create-scheduler

  private val extensions = new ConcurrentHashMap[ExtensionId[_], AnyRef]

  def extension[T <: Extension](ext: ExtensionId[T]): T = findExtension(ext) match {
    case null ⇒ throw new IllegalArgumentException("Trying to get non-registered extension [" + ext + "]")
    case some ⇒ some.asInstanceOf[T]
  }

  def hasExtension(ext: ExtensionId[_ <: Extension]): Boolean = findExtension(ext) != null

  /**
   * Returns any extension registered to the specified Extension or returns null if not registered
   */
  @tailrec
  private def findExtension[T <: Extension](ext: ExtensionId[T]): T = extensions.get(ext) match {
    case c: CountDownLatch ⇒
      c.await(); findExtension(ext) //Registration in process, await completion and retry
    case t: Throwable ⇒ throw t //Initialization failed, throw same again
    case other ⇒
      other.asInstanceOf[T] //could be a T or null, in which case we return the null as T
  }

  @tailrec
  final def registerExtension[T <: Extension](ext: ExtensionId[T]): T = {
    findExtension(ext) match {
      case null ⇒ //Doesn't already exist, commence registration
        val inProcessOfRegistration = new CountDownLatch(1)
        extensions.putIfAbsent(ext, inProcessOfRegistration) match { // Signal that registration is in process
          case null ⇒ try { // Signal was successfully sent
            ext.createExtension(this) match { // Create and initialize the extension
              case null ⇒ throw new IllegalStateException("Extension instance created as 'null' for extension [" + ext + "]")
              case instance ⇒
                extensions.replace(ext, inProcessOfRegistration, instance) //Replace our in process signal with the initialized extension
                instance //Profit!
            }
          } catch {
            case t: Throwable ⇒
              extensions.replace(ext, inProcessOfRegistration, t) //In case shit hits the fan, remove the inProcess signal
              throw t //Escalate to caller
          } finally {
            inProcessOfRegistration.countDown //Always notify listeners of the inProcess signal
          }
          case other ⇒ registerExtension(ext) //Someone else is in process of registering an extension for this Extension, retry
        }
      case existing ⇒ existing.asInstanceOf[T]
    }
  }

  protected[this] def loadExtensions() {
    immutableSeq(settings.config.getStringList("akka.extensions")) foreach { fqcn ⇒
      dynamicAccess.getObjectFor[AnyRef](fqcn) recoverWith { case _ ⇒ dynamicAccess.createInstanceFor[AnyRef](fqcn, Nil) } match {
        case Success(p: ExtensionIdProvider) ⇒ registerExtension(p.lookup())
        case Success(p: ExtensionId[_])      ⇒ registerExtension(p)
        case Success(other)                  ⇒ log.error("[{}] is not an 'ExtensionIdProvider' or 'ExtensionId', skipping...", fqcn)
        case Failure(problem)                ⇒ log.error(problem, "While trying to load extension [{}], skipping...", fqcn)
      }
    }
  }
}
