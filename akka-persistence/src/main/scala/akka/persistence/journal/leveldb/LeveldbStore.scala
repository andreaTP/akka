/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 * Copyright (C) 2012-2013 Eligotech BV.
 */

package akka.persistence.journal.leveldb

import java.io.File
import akka.actor._
import akka.persistence._
import akka.persistence.journal.{ WriteJournalBase, AsyncWriteTarget }
import akka.serialization.SerializationExtension
import org.iq80.leveldb._
import scala.collection.immutable
import scala.util._
import scala.concurrent.Future
import scala.util.control.NonFatal
import akka.persistence.journal.AsyncWriteJournal

/**
 * INTERNAL API.
 */
private[persistence] trait LeveldbStore extends Actor with WriteJournalBase with LeveldbIdMapping with LeveldbRecovery {
  val configPath: String

  val config = context.system.settings.config.getConfig(configPath)
  val nativeLeveldb = config.getBoolean("native")

  val leveldbOptions = new Options().createIfMissing(true)
  def leveldbReadOptions = new ReadOptions().verifyChecksums(config.getBoolean("checksum"))
  val leveldbWriteOptions = new WriteOptions().sync(config.getBoolean("fsync")).snapshot(false)
  val leveldbDir = new File(config.getString("dir"))
  var leveldb: DB = _

  def leveldbFactory =
    if (nativeLeveldb) org.fusesource.leveldbjni.JniDBFactory.factory
    else org.iq80.leveldb.impl.Iq80DBFactory.factory

  val serialization = SerializationExtension(context.system)

  import Key._

  def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] =
    Future.fromTry(Try {
      withBatch(batch ⇒ messages.map { a ⇒
        Try(a.payload.foreach(message ⇒ addToMessageBatch(message, batch)))
      })
    })

  def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    try Future.successful {
      withBatch { batch ⇒
        val nid = numericId(persistenceId)

        // seek to first existing message
        val fromSequenceNr = withIterator { iter ⇒
          val startKey = Key(nid, 1L, 0)
          iter.seek(keyToBytes(startKey))
          if (iter.hasNext) keyFromBytes(iter.peekNext().getKey).sequenceNr else Long.MaxValue
        }

        fromSequenceNr to toSequenceNr foreach { sequenceNr ⇒
          batch.delete(keyToBytes(Key(nid, sequenceNr, 0)))
        }
      }
    } catch {
      case NonFatal(e) ⇒ Future.failed(e)
    }

  def leveldbSnapshot(): ReadOptions = leveldbReadOptions.snapshot(leveldb.getSnapshot)

  def withIterator[R](body: DBIterator ⇒ R): R = {
    val ro = leveldbSnapshot()
    val iterator = leveldb.iterator(ro)
    try {
      body(iterator)
    } finally {
      iterator.close()
      ro.snapshot().close()
    }
  }

  def withBatch[R](body: WriteBatch ⇒ R): R = {
    val batch = leveldb.createWriteBatch()
    try {
      val r = body(batch)
      leveldb.write(batch, leveldbWriteOptions)
      r
    } finally {
      batch.close()
    }
  }

  def persistentToBytes(p: PersistentRepr): Array[Byte] = serialization.serialize(p).get
  def persistentFromBytes(a: Array[Byte]): PersistentRepr = serialization.deserialize(a, classOf[PersistentRepr]).get

  private def addToMessageBatch(persistent: PersistentRepr, batch: WriteBatch): Unit = {
    val nid = numericId(persistent.persistenceId)
    batch.put(keyToBytes(counterKey(nid)), counterToBytes(persistent.sequenceNr))
    batch.put(keyToBytes(Key(nid, persistent.sequenceNr, 0)), persistentToBytes(persistent))
  }

  override def preStart() {
    leveldb = leveldbFactory.open(leveldbDir, if (nativeLeveldb) leveldbOptions else leveldbOptions.compressionType(CompressionType.NONE))
    super.preStart()
  }

  override def postStop() {
    leveldb.close()
    super.postStop()
  }
}

