/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.util.concurrent.{ ThreadFactory, RejectedExecutionException }
import akka.event._
import akka.dispatch._
import akka.actor.dungeon.ChildrenContainer
import scala.util.Try
import scala.util.control.{ NonFatal, ControlThrowable }

trait ExtendedActorSystemImpl extends ExtendedActorSystem {
  self: ActorSystemImpl ⇒

  import ActorSystem._

  import settings._

  val provider: ActorRefProvider = try {
    val arguments = Vector(
      classOf[String] -> name,
      classOf[Settings] -> settings,
      classOf[EventStream] -> eventStream,
      classOf[DynamicAccess] -> dynamicAccess)

    dynamicAccess.createInstanceFor[ActorRefProvider](ProviderClass, arguments).get
  } catch {
    case NonFatal(e) ⇒
      Try(stopScheduler())
      throw e
  }

  def guardian: LocalActorRef = provider.guardian

  def systemGuardian: LocalActorRef = provider.systemGuardian

  def systemActorOf(props: Props, name: String): ActorRef = systemGuardian.underlying.attachChild(props, name, systemService = true)

  final val threadFactory: MonitorableThreadFactory =
    MonitorableThreadFactory(name, settings.Daemonicity, Option(classLoader), uncaughtExceptionHandler)

  /**
   * This is an extension point: by overriding this method, subclasses can
   * control all reflection activities of an actor system.
   */
  protected def createDynamicAccess(): DynamicAccess = new ReflectiveDynamicAccess(classLoader)

  private val _pm: DynamicAccess = createDynamicAccess()
  def dynamicAccess: DynamicAccess = _pm

  val logFilter: LoggingFilter = {
    val arguments = Vector(classOf[Settings] -> settings, classOf[EventStream] -> eventStream)
    dynamicAccess.createInstanceFor[LoggingFilter](LoggingFilter, arguments).get
  }

  override def printTree: String = {
    def printNode(node: ActorRef, indent: String): String = {
      node match {
        case wc: ActorRefWithCell ⇒
          val cell = wc.underlying
          (if (indent.isEmpty) "-> " else indent.dropRight(1) + "⌊-> ") +
            node.path.name + " " + Logging.simpleName(node) + " " +
            (cell match {
              case real: ActorCell ⇒ if (real.actor ne null) real.actor.getClass else "null"
              case _               ⇒ Logging.simpleName(cell)
            }) +
            (cell match {
              case real: ActorCell ⇒ " status=" + real.mailbox.currentStatus
              case _               ⇒ ""
            }) +
            " " + (cell.childrenRefs match {
              case ChildrenContainer.TerminatingChildrenContainer(_, toDie, reason) ⇒
                "Terminating(" + reason + ")" +
                  (toDie.toSeq.sorted mkString ("\n" + indent + "   |    toDie: ", "\n" + indent + "   |           ", ""))
              case x @ (ChildrenContainer.TerminatedChildrenContainer | ChildrenContainer.EmptyChildrenContainer) ⇒ x.toString
              case n: ChildrenContainer.NormalChildrenContainer ⇒ n.c.size + " children"
              case x ⇒ Logging.simpleName(x)
            }) +
            (if (cell.childrenRefs.children.isEmpty) "" else "\n") +
            ({
              val children = cell.childrenRefs.children.toSeq.sorted
              val bulk = children.dropRight(1) map (printNode(_, indent + "   |"))
              bulk ++ (children.lastOption map (printNode(_, indent + "    ")))
            } mkString ("\n"))
        case _ ⇒
          indent + node.path.name + " " + Logging.simpleName(node)
      }
    }
    printNode(lookupRoot, "")
  }

}