package scorex.network

import akka.actor.FSM
import scorex.app.Application
import scorex.block.Block
import scorex.block.Block.BlockId
import scorex.network.NetworkController.{DataFromPeer, SendToNetwork}
import scorex.network.NetworkObject.ConsideredValue
import scorex.network.message.Message
import scorex.transaction.History
import shapeless.Typeable._

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

//todo: write tests
class HistorySynchronizer(application: Application)
  extends ViewSynchronizer with FSM[HistorySynchronizer.Status, Seq[ConnectedPeer]] {

  import HistorySynchronizer._
  import application.basicMessagesSpecsRepo._

  private implicit val consensusModule = application.consensusModule
  private implicit val transactionalModule = application.transactionModule

  override val messageSpecs = Seq(ScoreMessageSpec, GetSignaturesSpec, SignaturesSpec, BlockMessageSpec, GetBlockSpec)

  private lazy val scoreSyncer = new ScoreNetworkObject(self)

  private lazy val history = application.history

  protected override lazy val networkControllerRef = application.networkController

  private lazy val blockGenerator = application.blockGenerator

  private val GettingExtensionTimeout = 15.seconds

  override def preStart: Unit = {
    super.preStart()
    context.system.scheduler.schedule(1.second, 1.seconds) {
      val msg = Message(ScoreMessageSpec, Right(history.score()), None)
      networkControllerRef ! NetworkController.SendToNetwork(msg, SendToRandom)
    }
  }

  val initialState = if (application.settings.offlineGeneration) Synced else Syncing
  startWith(initialState, Seq())

  when(Syncing) {
    case Event(ConsideredValue(Some(networkScore: History.BlockchainScore), witnesses), _) =>
      val localScore = history.score()
      if (networkScore > localScore) {
        log.info(s"networkScore=$networkScore > localScore=$localScore")
        val lastIds = history.lastBlocks(100).map(_.uniqueId)
        val msg = Message(GetSignaturesSpec, Right(lastIds), None)
        networkControllerRef ! NetworkController.SendToNetwork(msg, SendToChosen(witnesses))
        context.system.scheduler.scheduleOnce(GettingExtensionTimeout)(self ! StateTimeout)
        goto(GettingExtension) using witnesses
      } else goto(Synced) using Seq()
  }

  private val blocksToReceive = mutable.Queue[BlockId]()

  when(GettingExtension, GettingExtensionTimeout) {
    case Event(StateTimeout, _) =>
      goto(Syncing)

    //todo: aggregating function for block ids (like score has)
    case Event(DataFromPeer(msgId, blockIds: Seq[Block.BlockId]@unchecked, remote), witnesses)
      if msgId == SignaturesSpec.messageCode &&
        blockIds.cast[Seq[Block.BlockId]].isDefined &&
        witnesses.contains(remote) => //todo: ban if non-expected sender
      val newBLockIds = blockIds.filter(!history.contains(_))
      log.info(s"Got SignaturesMessage with ${blockIds.length} sigs, ${newBLockIds.size} are new")

      val common = blockIds.head
      assert(application.history.contains(common)) //todo: what if not?
      Try(application.blockStorage.removeAfter(common)) //todo we don't need this call for blockTree

      blocksToReceive.clear()

      if (newBLockIds.nonEmpty) {
        newBLockIds.foreach { blockId =>
          blocksToReceive += blockId
        }

        networkControllerRef ! NetworkController.SendToNetwork(Message(GetBlockSpec, Right(blocksToReceive.front), None),
          SendToChosen(Seq(remote)))
        goto(GettingBlock)
      } else goto(Syncing)
  }

  when(GettingBlock, 15.seconds) {
    case Event(StateTimeout, _) =>
      blocksToReceive.clear()
      goto(Syncing)

    case Event(CheckBlock(blockId), witnesses) =>
      if (blocksToReceive.nonEmpty && blocksToReceive.front.sameElements(blockId)) {
        val sendTo = SendToRandomFromChosen(witnesses)
        val stn = NetworkController.SendToNetwork(Message(GetBlockSpec, Right(blockId), None), sendTo)
        networkControllerRef ! stn
      }
      stay()

    case Event(DataFromPeer(msgId, block: Block@unchecked, remote), _)
      if msgId == BlockMessageSpec.messageCode && block.cast[Block].isDefined =>

      val blockId = block.uniqueId
      log.info("Got block: " + blockId)

      if (processNewBlock(block, local = false)) {
        if (blocksToReceive.nonEmpty && blocksToReceive.front.sameElements(blockId)) blocksToReceive.dequeue()

        if (blocksToReceive.nonEmpty) {
          val blockId = blocksToReceive.front
          val ss = SendToRandomFromChosen(Seq(remote))
          val stn = NetworkController.SendToNetwork(Message(GetBlockSpec, Right(blockId), None), ss)
          networkControllerRef ! stn
          context.system.scheduler.scheduleOnce(5.seconds)(self ! CheckBlock(blockId))
        }
      } else if (!history.contains(block.referenceField.value)) {
        log.warning("No parent block in history")
        blocksToReceive.clear()
        blocksToReceive.enqueue(block.referenceField.value)
      }
      if (blocksToReceive.nonEmpty) {
        self ! CheckBlock(blocksToReceive.front)
        stay()
      } else {
        goto(Syncing) using Seq()
      }
  }

  //accept only new block from local or remote
  when(Synced) {
    case Event(block: Block, _) =>
      processNewBlock(block, local = true)
      stay()

    case Event(ConsideredValue(Some(networkScore: History.BlockchainScore), witnesses), _) =>
      val localScore = history.score()
      if (networkScore > localScore) goto(Syncing) using witnesses
      else stay() using Seq()

    case Event(DataFromPeer(msgId, block: Block@unchecked, remote), _)
      if msgId == BlockMessageSpec.messageCode && block.cast[Block].isDefined =>
      processNewBlock(block, local = false)
      stay()
  }

  //common logic for all the states
  whenUnhandled {
    //init signal(boxed Unit) matching
    case Event(Unit, _) if stateName == initialState =>
      stay()

    //todo: check sender
    case Event(DataFromPeer(msgId, content: History.BlockchainScore, remote), _)
      if msgId == ScoreMessageSpec.messageCode =>
      scoreSyncer.networkUpdate(remote, content)
      stay()

    //todo: check sender
    case Event(DataFromPeer(msgId, otherSigs: Seq[Block.BlockId]@unchecked, remote), _)
      if msgId == GetSignaturesSpec.messageCode && otherSigs.cast[Seq[Block.BlockId]].isDefined =>

      log.info(s"Got GetSignaturesMessage with ${otherSigs.length} sigs within")

      otherSigs.exists { parent =>
        val headers = application.history.lookForward(parent, application.settings.MaxBlocksChunks)

        if (headers.nonEmpty) {
          val msg = Message(SignaturesSpec, Right(Seq(parent) ++ headers), None)
          val ss = SendToChosen(Seq(remote))
          networkControllerRef ! SendToNetwork(msg, ss)
          true
        } else false
      }
      stay()


    //todo: check sender?
    case Event(DataFromPeer(msgId, sig: Block.BlockId@unchecked, remote), _)
      if msgId == GetBlockSpec.messageCode =>

      application.history.blockById(sig).foreach { b =>
        val msg = Message(BlockMessageSpec, Right(b), None)
        val ss = SendToChosen(Seq(remote))
        networkControllerRef ! SendToNetwork(msg, ss)
      }
      stay()

    case Event(ConsideredValue(Some(networkScore: History.BlockchainScore), witnesses), _) =>
      stay()

    case nonsense: Any =>
      log.warning(s"Got something strange in the state ($stateName) :: $nonsense")
      stay()
  }

  onTransition {
    case from -> to =>
      log.info(s"Transition from $from to $to")
      if (from == Synced) blockGenerator ! BlockGenerator.StopGeneration
      if (to == Synced) blockGenerator ! BlockGenerator.StartGeneration
      if (to == Syncing) scoreSyncer.consideredValue.foreach(cv => self ! cv)
  }

  initialize()

  private def processNewBlock(block: Block, local: Boolean): Boolean = {
    if (block.isValid) {
      log.info(s"New block(local: $local): ${block.json}")

      //broadcast block only if it is generated locally
      if (local) {
        val blockMsg = Message(BlockMessageSpec, Right(block), None)
        networkControllerRef ! SendToNetwork(blockMsg, Broadcast)
        true
      } else {
        val oldHeight = history.height()
        val oldScore = history.score()
        val appending = transactionalModule.blockStorage.appendBlock(block)
        appending match {
          case Success(_) =>
            block.transactionModule.clearFromUnconfirmed(block.transactionDataField.value)
            log.info(s"(height, score) = ($oldHeight, $oldScore) vs (${history.height()}, ${history.score()})")
          case Failure(e) =>
            e.printStackTrace
            log.warning(s"failed to append block: $e")
        }
        appending.isSuccess
      }
    } else {
      log.warning(s"Invalid new block(local: $local): ${block.json}")
      false
    }
  }
}

object HistorySynchronizer {

  sealed trait Status

  case object Syncing extends Status

  case object GettingExtension extends Status

  case object GettingBlock extends Status

  case object Synced extends Status

  case class CheckBlock(id: BlockId)

}
