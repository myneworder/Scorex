package scorex.network

import java.net.{InetSocketAddress, NetworkInterface}

import scorex.app.Application
import scorex.network.NetworkController.{DataFromPeer, SendToNetwork}
import scorex.network.message.Message
import scorex.utils.ScorexLogging
import shapeless.Typeable._

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class PeerSynchronizer(application: Application) extends ViewSynchronizer with ScorexLogging {

  import application.basicMessagesSpecsRepo._

  override val messageSpecs = Seq(GetPeersSpec, PeersSpec)
  override val networkControllerRef = application.networkController

  private val peerManager = application.peerManager

  override def preStart: Unit = {
    super.preStart()

    val ntwMsg = Message(GetPeersSpec, Right(), None)
    val stn = NetworkController.SendToNetwork(ntwMsg, SendToRandom)
    context.system.scheduler.schedule(2.seconds, 5.seconds)(networkControllerRef ! stn)
  }

  private val own: Seq[Array[Byte]] = NetworkInterface.getNetworkInterfaces
    .flatMap(_.getInetAddresses)
    .map(_.getAddress)
    .toSeq

  override def receive: Receive = {
    case DataFromPeer(msgId, peers: Seq[InetSocketAddress]@unchecked, remote)
      if msgId == PeersSpec.messageCode && peers.cast[Seq[InetSocketAddress]].isDefined =>

      peers.foreach { isa =>
        if (!own.exists(_.sameElements(isa.getAddress.getAddress))) peerManager.addPeer(isa)
      }

    case DataFromPeer(msgId, _, remote) if msgId == GetPeersSpec.messageCode =>

      //todo: externalize the number, check on receiving, random shuffling
      val peers = peerManager.knownPeers().take(3)
      val msg = Message(PeersSpec, Right(peers), None)
      networkControllerRef ! SendToNetwork(msg, SendToChosen(Seq(remote)))

    case nonsense: Any => log.warn(s"PeerSynchronizer: got something strange $nonsense")
  }
}
