package akka.persistence.journal

import scala.collection.immutable.Seq

import akka.actor._
import akka.persistence._
import akka.persistence.JournalProtocol._
import akka.testkit._

import com.typesafe.config._

object JournalSpec {
  val config = ConfigFactory.parseString(
    """
    akka.persistence.publish-plugin-commands = on
    """)
}

/**
 * This spec aims to verify custom akka-persistence Journal implementations.
 * Plugin authors are highly encouraged to include it in their plugin's test suites.
 *
 * In case your journal plugin needs some kind of setup or teardown, override the `beforeAll` or `afterAll`
 * methods (don't forget to call `super` in your overridden methods).
 *
 * For a Java and JUnit consumable version of the TCK please refer to [[akka.persistence.japi.journal.JavaJournalSpec]].
 *
 * @see [[akka.persistence.journal.JournalPerfSpec]]
 * @see [[akka.persistence.japi.journal.JavaJournalPerfSpec]]
 */
abstract class JournalSpec(config: Config) extends PluginSpec(config) {

  implicit lazy val system: ActorSystem = ActorSystem("JournalSpec", config.withFallback(JournalSpec.config))

  private var senderProbe: TestProbe = _
  private var receiverProbe: TestProbe = _

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    senderProbe = TestProbe()
    receiverProbe = TestProbe()
    writeMessages(1, 5, pid, senderProbe.ref, writerUuid)
  }

  /**
   * Implementation may override and return false if it does not
   * support atomic writes of several events, as emitted by `persistAll`.
   */
  def supportsAtomicPersistAllOfSeveralEvents: Boolean = true

  def journal: ActorRef =
    extension.journalFor(null)

  def replayedMessage(snr: Long, deleted: Boolean = false, confirms: Seq[String] = Nil): ReplayedMessage =
    ReplayedMessage(PersistentImpl(s"a-${snr}", snr, pid, "", deleted, Actor.noSender, writerUuid))

  def writeMessages(fromSnr: Int, toSnr: Int, pid: String, sender: ActorRef, writerUuid: String): Unit = {
    val msgs =
      if (supportsAtomicPersistAllOfSeveralEvents)
        (fromSnr to toSnr).map { i ⇒
          AtomicWrite(PersistentRepr(payload = s"a-$i", sequenceNr = i, persistenceId = pid, sender = sender,
            writerUuid = writerUuid))
        }
      else
        (fromSnr to toSnr - 1).map { i ⇒
          if (i == toSnr - 1)
            AtomicWrite(List(
              PersistentRepr(payload = s"a-$i", sequenceNr = i, persistenceId = pid, sender = sender,
                writerUuid = writerUuid),
              PersistentRepr(payload = s"a-${i + 1}", sequenceNr = i + 1, persistenceId = pid, sender = sender,
                writerUuid = writerUuid)))
          else
            AtomicWrite(PersistentRepr(payload = s"a-${i}", sequenceNr = i, persistenceId = pid, sender = sender,
              writerUuid = writerUuid))
        }

    val probe = TestProbe()

    journal ! WriteMessages(msgs, probe.ref, actorInstanceId)

    probe.expectMsg(WriteMessagesSuccessful)
    fromSnr to toSnr foreach { i ⇒
      probe.expectMsgPF() {
        case WriteMessageSuccess(PersistentImpl(payload, `i`, `pid`, _, _, `sender`, `writerUuid`), _) ⇒
          payload should be(s"a-${i}")
      }
    }
  }

  "A journal" must {
    "replay all messages" in {
      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      1 to 5 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay messages using a lower sequence number bound" in {
      journal ! ReplayMessages(3, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      3 to 5 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay messages using an upper sequence number bound" in {
      journal ! ReplayMessages(1, 3, Long.MaxValue, pid, receiverProbe.ref)
      1 to 3 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay messages using a count limit" in {
      journal ! ReplayMessages(1, Long.MaxValue, 3, pid, receiverProbe.ref)
      1 to 3 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay messages using a lower and upper sequence number bound" in {
      journal ! ReplayMessages(2, 4, Long.MaxValue, pid, receiverProbe.ref)
      2 to 4 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay messages using a lower and upper sequence number bound and a count limit" in {
      journal ! ReplayMessages(2, 4, 2, pid, receiverProbe.ref)
      2 to 3 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay a single if lower sequence number bound equals upper sequence number bound" in {
      journal ! ReplayMessages(2, 2, Long.MaxValue, pid, receiverProbe.ref)
      2 to 2 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "replay a single message if count limit equals 1" in {
      journal ! ReplayMessages(2, 4, 1, pid, receiverProbe.ref)
      2 to 2 foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "not replay messages if count limit equals 0" in {
      journal ! ReplayMessages(2, 4, 0, pid, receiverProbe.ref)
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "not replay messages if lower  sequence number bound is greater than upper sequence number bound" in {
      journal ! ReplayMessages(3, 2, Long.MaxValue, pid, receiverProbe.ref)
      receiverProbe.expectMsg(ReplayMessagesSuccess)
    }
    "not replay permanently deleted messages (range deletion)" in {
      val cmd = DeleteMessagesTo(pid, 3, true)
      val sub = TestProbe()

      subscribe[DeleteMessagesTo](sub.ref)
      journal ! cmd
      sub.expectMsg(cmd)

      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref)
      List(4, 5) foreach { i ⇒ receiverProbe.expectMsg(replayedMessage(i)) }
    }
    "replay logically deleted messages with deleted field set to true (range deletion)" in {
      val cmd = DeleteMessagesTo(pid, 3, false)
      val sub = TestProbe()

      subscribe[DeleteMessagesTo](sub.ref)
      journal ! cmd
      sub.expectMsg(cmd)

      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pid, receiverProbe.ref, replayDeleted = true)
      (1 to 5).foreach {
        case i @ (1 | 2 | 3) ⇒ receiverProbe.expectMsg(replayedMessage(i, deleted = true))
        case i @ (4 | 5)     ⇒ receiverProbe.expectMsg(replayedMessage(i))
      }
    }

    "return a highest stored sequence number > 0 if the persistent actor has already written messages and the message log is non-empty" in {
      journal ! ReadHighestSequenceNr(3L, pid, receiverProbe.ref)
      receiverProbe.expectMsg(ReadHighestSequenceNrSuccess(5))

      journal ! ReadHighestSequenceNr(5L, pid, receiverProbe.ref)
      receiverProbe.expectMsg(ReadHighestSequenceNrSuccess(5))
    }
    "return a highest stored sequence number == 0 if the persistent actor has not yet written messages" in {
      journal ! ReadHighestSequenceNr(0L, "non-existing-pid", receiverProbe.ref)
      receiverProbe.expectMsg(ReadHighestSequenceNrSuccess(0))
    }

    "reject non-serializable events" in {
      // there is no chance that a journal could create a data representation for type of event
      val notSerializableEvent = new Object { override def toString = "not serializable" }
      val msgs = (6 to 8).map { i ⇒
        val event = if (i == 7) notSerializableEvent else s"b-$i"
        AtomicWrite(PersistentRepr(payload = event, sequenceNr = i, persistenceId = pid, sender = Actor.noSender,
          writerUuid = writerUuid))
      }

      val probe = TestProbe()
      journal ! WriteMessages(msgs, probe.ref, actorInstanceId)

      probe.expectMsg(WriteMessagesSuccessful)
      val Pid = pid
      val WriterUuid = writerUuid
      probe.expectMsgPF() {
        case WriteMessageSuccess(PersistentImpl(payload, 6L, Pid, _, _, Actor.noSender, WriterUuid), _) ⇒ payload should be(s"b-6")
      }
      probe.expectMsgPF() {
        case WriteMessageRejected(PersistentImpl(payload, 7L, Pid, _, _, Actor.noSender, WriterUuid), _, _) ⇒
          payload should be(notSerializableEvent)
      }
      probe.expectMsgPF() {
        case WriteMessageSuccess(PersistentImpl(payload, 8L, Pid, _, _, Actor.noSender, WriterUuid), _) ⇒ payload should be(s"b-8")
      }

    }
  }
}
