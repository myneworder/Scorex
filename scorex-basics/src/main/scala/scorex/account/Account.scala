package scorex.account

import scorex.crypto.hash.SecureCryptographicHash
import SecureCryptographicHash._
import scorex.crypto.encode.Base58


class Account(val address: String) extends Serializable {

  lazy val bytes = Base58.decode(address).get

  override def toString: String = address

  override def equals(b: Any): Boolean = b match {
    case a: Account => a.address == address
    case _ => false
  }

  override def hashCode(): Int = address.hashCode()
}


object Account {

  val AddressVersion: Byte = 1
  val ChecksumLength = 4
  val HashLength = 20
  val AddressLength = 1 + ChecksumLength + HashLength

  def fromPubkey(publicKey: Array[Byte]): String = {
    val publicKeyHash = hash(publicKey).take(HashLength)
    val withoutChecksum = AddressVersion +: publicKeyHash //prepend ADDRESS_VERSION
    Base58.encode(withoutChecksum ++ calcCheckSum(withoutChecksum))
  }

  def isValidAddress(address: String): Boolean =
    Base58.decode(address).map { addressBytes =>
      if (addressBytes.length != Account.AddressLength)
        false
      else {
        val checkSum = addressBytes.takeRight(ChecksumLength)

        val checkSumGenerated = calcCheckSum(addressBytes.dropRight(ChecksumLength))

        checkSum.sameElements(checkSumGenerated)
      }
    }.getOrElse(false)

  private def calcCheckSum(withoutChecksum: Array[Byte]): Array[Byte] = hash(withoutChecksum).take(ChecksumLength)

}
