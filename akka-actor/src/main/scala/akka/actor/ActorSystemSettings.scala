/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.util.{ Timeout, Reflect }
import com.typesafe.config.{ Config, ConfigFactory }
import akka.japi.Util.immutableSeq
import scala.collection.immutable
import java.util.Locale

trait ActorSystemSettings {

  val Version: String = "2.4-SNAPSHOT"

  val EnvHome: Option[String] = System.getenv("AKKA_HOME") match {
    case null | "" | "." ⇒ None
    case value           ⇒ Some(value)
  }

  val SystemHome: Option[String] = System.getProperty("akka.home") match {
    case null | "" ⇒ None
    case value     ⇒ Some(value)
  }

  val GlobalHome: Option[String] = SystemHome orElse EnvHome

  /**
   * Settings are the overall ActorSystem Settings which also provides a convenient access to the Config object.
   *
   * For more detailed information about the different possible configuration options, look in the Akka Documentation under "Configuration"
   *
   * @see <a href="http://typesafehub.github.io/config/v1.3.0/" target="_blank">The Typesafe Config Library API Documentation</a>
   */
  class Settings(classLoader: ClassLoader, cfg: Config, final val name: String) {

    /**
     * The backing Config of this ActorSystem's Settings
     *
     * @see <a href="http://typesafehub.github.io/config/v1.3.0/" target="_blank">The Typesafe Config Library API Documentation</a>
     */
    final val config: Config = {
      val config = cfg.withFallback(ConfigFactory.defaultReference(classLoader))
      config.checkValid(ConfigFactory.defaultReference(classLoader), "akka")
      config
    }

    import scala.collection.JavaConverters._
    import akka.util.Helpers.ConfigOps
    import config._

    final val ConfigVersion: String = getString("akka.version")
    final val ProviderClass: String = getString("akka.actor.provider")
    final val SupervisorStrategyClass: String = getString("akka.actor.guardian-supervisor-strategy")
    final val CreationTimeout: Timeout = Timeout(config.getMillisDuration("akka.actor.creation-timeout"))
    final val UnstartedPushTimeout: Timeout = Timeout(config.getMillisDuration("akka.actor.unstarted-push-timeout"))

    final val SerializeAllMessages: Boolean = getBoolean("akka.actor.serialize-messages")
    final val SerializeAllCreators: Boolean = getBoolean("akka.actor.serialize-creators")

    final val LogLevel: String = getString("akka.loglevel")
    final val StdoutLogLevel: String = getString("akka.stdout-loglevel")
    final val Loggers: immutable.Seq[String] = immutableSeq(getStringList("akka.loggers"))
    final val LoggingFilter: String = getString("akka.logging-filter")
    final val LoggerStartTimeout: Timeout = Timeout(config.getMillisDuration("akka.logger-startup-timeout"))
    final val LogConfigOnStart: Boolean = config.getBoolean("akka.log-config-on-start")
    final val LogDeadLetters: Int = config.getString("akka.log-dead-letters").toLowerCase(Locale.ROOT) match {
      case "off" | "false" ⇒ 0
      case "on" | "true"   ⇒ Int.MaxValue
      case _               ⇒ config.getInt("akka.log-dead-letters")
    }
    final val LogDeadLettersDuringShutdown: Boolean = config.getBoolean("akka.log-dead-letters-during-shutdown")

    final val AddLoggingReceive: Boolean = getBoolean("akka.actor.debug.receive")
    final val DebugAutoReceive: Boolean = getBoolean("akka.actor.debug.autoreceive")
    final val DebugLifecycle: Boolean = getBoolean("akka.actor.debug.lifecycle")
    final val FsmDebugEvent: Boolean = getBoolean("akka.actor.debug.fsm")
    final val DebugEventStream: Boolean = getBoolean("akka.actor.debug.event-stream")
    final val DebugUnhandledMessage: Boolean = getBoolean("akka.actor.debug.unhandled")
    final val DebugRouterMisconfiguration: Boolean = getBoolean("akka.actor.debug.router-misconfiguration")

    final val Home: Option[String] = config.getString("akka.home") match {
      case "" ⇒ None
      case x  ⇒ Some(x)
    }

    final val SchedulerClass: String = getString("akka.scheduler.implementation")
    final val Daemonicity: Boolean = getBoolean("akka.daemonic")
    final val JvmExitOnFatalError: Boolean = getBoolean("akka.jvm-exit-on-fatal-error")

    final val DefaultVirtualNodesFactor: Int = getInt("akka.actor.deployment.default.virtual-nodes-factor")

    if (ConfigVersion != Version)
      throw new akka.ConfigurationException("Akka JAR version [" + Version + "] does not match the provided config version [" + ConfigVersion + "]")

    /**
     * Returns the String representation of the Config that this Settings is backed by
     */
    override def toString: String = config.root.render

  }

  /**
   * INTERNAL API
   */
  private[akka] def findClassLoader(): ClassLoader = {
    def findCaller(get: Int ⇒ Class[_]): ClassLoader =
      Iterator.from(2 /*is the magic number, promise*/ ).map(get) dropWhile { c ⇒
        c != null &&
          (c.getName.startsWith("akka.actor.ActorSystem") ||
            c.getName.startsWith("scala.Option") ||
            c.getName.startsWith("scala.collection.Iterator") ||
            c.getName.startsWith("akka.util.Reflect"))
      } next () match {
        case null ⇒ getClass.getClassLoader
        case c    ⇒ c.getClassLoader
      }

    Option(Thread.currentThread.getContextClassLoader) orElse
      (Reflect.getCallerClass map findCaller) getOrElse
      getClass.getClassLoader
  }
}
