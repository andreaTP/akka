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

private[akka] class ActorSystemImpl(
  val name: String,
  applicationConfig: Config,
  val classLoader: ClassLoader,
  defaultExecutionContext: Option[ExecutionContext],
  val guardianProps: Option[Props]) extends ExtendedActorSystemImpl with AbstractActorSystemImpl {

  if (!name.matches("""^[a-zA-Z0-9][a-zA-Z0-9-_]*$"""))
    throw new IllegalArgumentException(
      "invalid ActorSystem name [" + name +
        "], must contain only word characters (i.e. [a-zA-Z0-9] plus non-leading '-' or '_')")

  import ActorSystem._

  @volatile private var logDeadLetterListener: Option[ActorRef] = None
  final val settings: Settings = new Settings(classLoader, applicationConfig, name)

  def logConfiguration(): Unit = log.info(settings.toString)

  protected def systemImpl: ActorSystemImpl = this

  def actorOf(props: Props, name: String): ActorRef =
    if (guardianProps.isEmpty) guardian.underlying.attachChild(props, name, systemService = false)
    else throw new UnsupportedOperationException("cannot create top-level actor from the outside on ActorSystem with custom user guardian")

  def actorOf(props: Props): ActorRef =
    if (guardianProps.isEmpty) guardian.underlying.attachChild(props, systemService = false)
    else throw new UnsupportedOperationException("cannot create top-level actor from the outside on ActorSystem with custom user guardian")

  def stop(actor: ActorRef): Unit = {
    val path = actor.path
    val guard = guardian.path
    val sys = systemGuardian.path
    path.parent match {
      case `guard` ⇒ guardian ! StopChild(actor)
      case `sys`   ⇒ systemGuardian ! StopChild(actor)
      case _       ⇒ actor.asInstanceOf[InternalActorRef].stop()
    }
  }

  import settings._

  // this provides basic logging (to stdout) until .start() is called below
  val eventStream = new EventStream(this, DebugEventStream)
  eventStream.startStdoutLogger(settings)

  val log: LoggingAdapter = new BusLogging(eventStream, getClass.getName + "(" + name + ")", this.getClass, logFilter)

  val scheduler: Scheduler = createScheduler()

  def deadLetters: ActorRef = provider.deadLetters

  val mailboxes: Mailboxes = new Mailboxes(settings, eventStream, dynamicAccess, deadLetters)

  val dispatchers: Dispatchers = new Dispatchers(settings, DefaultDispatcherPrerequisites(
    threadFactory, eventStream, scheduler, dynamicAccess, settings, mailboxes, defaultExecutionContext))

  val dispatcher: ExecutionContextExecutor = dispatchers.defaultGlobalDispatcher

  private[this] final val terminationCallbacks = new TerminationCallbacks(provider.terminationFuture)(dispatcher)

  override def whenTerminated: Future[Terminated] = terminationCallbacks.terminationFuture
  def lookupRoot: InternalActorRef = provider.rootGuardian

  def /(actorName: String): ActorPath = guardian.path / actorName
  def /(path: Iterable[String]): ActorPath = guardian.path / path

  private lazy val _start: this.type = try {
    registerOnTermination(stopScheduler())
    // the provider is expected to start default loggers, LocalActorRefProvider does this
    provider.init(this)
    if (settings.LogDeadLetters > 0)
      logDeadLetterListener = Some(systemActorOf(Props[DeadLetterListener], "deadLetterListener"))
    eventStream.startUnsubscriber()
    loadExtensions()
    if (LogConfigOnStart) logConfiguration()
    this
  } catch {
    case NonFatal(e) ⇒
      try terminate() catch { case NonFatal(_) ⇒ Try(stopScheduler()) }
      throw e
  }

  def start(): this.type = _start
  def registerOnTermination[T](code: ⇒ T) { registerOnTermination(new Runnable { def run = code }) }
  def registerOnTermination(code: Runnable) { terminationCallbacks.add(code) }
  override def isTerminated = whenTerminated.isCompleted

  override def shutdown(): Unit = terminate()

  override def terminate(): Future[Terminated] = {
    if (!settings.LogDeadLettersDuringShutdown) logDeadLetterListener foreach stop
    guardian.stop()
    whenTerminated
  }

  @volatile var aborting = false

  /**
   * This kind of shutdown attempts to bring the system down and release its
   * resources more forcefully than plain shutdown. For example it will not
   * wait for remote-deployed child actors to terminate before terminating their
   * parents.
   */
  def abort(): Unit = {
    aborting = true
    terminate()
  }

  /*
   * This is called after the last actor has signaled its termination, i.e.
   * after the last dispatcher has had its chance to schedule its shutdown
   * action.
   */
  protected def stopScheduler(): Unit = scheduler match {
    case x: Closeable ⇒ x.close()
    case _            ⇒
  }

  override def toString: String = lookupRoot.path.root.address.toString

  final class TerminationCallbacks[T](upStreamTerminated: Future[T])(implicit ec: ExecutionContext) {
    private[this] final val done = Promise[T]()
    private[this] final val ref = new AtomicReference(done)

    // onComplete never fires twice so safe to avoid null check
    upStreamTerminated onComplete { t ⇒ ref.getAndSet(null).complete(t) }

    /**
     * Adds a Runnable that will be executed on ActorSystem termination.
     * Note that callbacks are executed in reverse order of insertion.
     * @param r The callback to be executed on ActorSystem termination
     * Throws RejectedExecutionException if called after ActorSystem has been terminated.
     */
    final def add(r: Runnable): Unit = {
      @tailrec def addRec(r: Runnable, p: Promise[T]): Unit = ref.get match {
        case null                               ⇒ throw new RejectedExecutionException("ActorSystem already terminated.")
        case some if ref.compareAndSet(some, p) ⇒ some.completeWith(p.future.andThen { case _ ⇒ r.run() })
        case _                                  ⇒ addRec(r, p)
      }
      addRec(r, Promise[T]())
    }

    /**
     * Returns a Future which will be completed once all registered callbacks have been executed.
     */
    def terminationFuture: Future[T] = done.future
  }
}
