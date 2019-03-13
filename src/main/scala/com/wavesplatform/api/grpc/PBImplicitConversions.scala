package com.wavesplatform.api.grpc
import com.google.protobuf.ByteString
import com.wavesplatform.account.{Address, PublicKeyAccount}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils._
import com.wavesplatform.consensus.nxt.NxtLikeConsensusBlockData
import com.wavesplatform.protobuf.block.{PBBlock, PBBlocks, VanillaBlock}
import com.wavesplatform.protobuf.transaction._
import com.wavesplatform.transaction.Proofs
import com.wavesplatform.{crypto, block => vb}

//noinspection ScalaStyle
trait PBImplicitConversions {
  implicit class VanillaTransactionConversions(tx: VanillaTransaction) {
    def toPB = PBTransactions.protobuf(tx)
  }

  implicit class PBSignedTransactionConversions(tx: PBSignedTransaction) {
    def toVanilla = PBTransactions.vanilla(tx).explicitGet()
  }

  implicit class PBTransactionConversions(tx: PBTransaction) {
    def toVanilla = PBSignedTransaction(Some(tx)).toVanilla
    def sender    = PublicKeyAccount(tx.senderPublicKey.toByteArray)

    def signed(signer: Array[Byte]): PBSignedTransaction = {
      import com.wavesplatform.common.utils._
      PBSignedTransaction(
        Some(tx),
        Proofs.create(Seq(ByteStr(crypto.sign(signer, toVanilla.bodyBytes())))).explicitGet().map(bs => ByteString.copyFrom(bs.arr)))
    }
  }

  implicit class VanillaBlockConversions(block: VanillaBlock) {
    def toPB = PBBlocks.protobuf(block)
  }

  implicit class PBBlockConversions(block: PBBlock) {
    def toVanilla = PBBlocks.vanilla(block).explicitGet()
  }

  implicit class PBBlockSignedHeaderConversionOps(header: PBBlock.SignedHeader) {
    def toVanilla: vb.BlockHeader = {
      new vb.BlockHeader(
        header.getHeader.timestamp,
        header.getHeader.version.toByte,
        header.getHeader.reference.toByteStr,
        vb.SignerData(header.getHeader.generator.toPublicKeyAccount, header.signature.toByteStr),
        NxtLikeConsensusBlockData(header.getHeader.baseTarget, header.getHeader.generationSignature.toByteStr),
        0,
        header.getHeader.featureVotes.map(intToShort).toSet
      )
    }
  }

  implicit class VanillaHeaderConversionOps(header: vb.BlockHeader) {
    def toPBHeader: PBBlock.SignedHeader = {
      val unsignedHeader = PBBlock.Header(
        header.reference.toPBByteString,
        header.consensusData.baseTarget,
        header.consensusData.generationSignature.toPBByteString,
        header.featureVotes.map(shortToInt).toSeq,
        header.timestamp,
        header.version,
        ByteString.copyFrom(header.signerData.generator.publicKey)
      )

      PBBlock.SignedHeader(Some(unsignedHeader), header.signerData.signature.toPBByteString)
    }
  }

  implicit class PBRecipientConversions(r: Recipient) {
    def toAddress        = PBRecipients.toAddress(r).explicitGet()
    def toAlias          = PBRecipients.toAlias(r).explicitGet()
    def toAddressOrAlias = PBRecipients.toAddressOrAlias(r).explicitGet()
  }

  implicit class VanillaByteStrConversions(bs: ByteStr) {
    def toPBByteString     = ByteString.copyFrom(bs.arr)
    def toPublicKeyAccount = PublicKeyAccount(bs.arr)
    def toAddress          = Address.fromBytes(bs.arr).explicitGet()
  }

  implicit class PBByteStringConversions(bs: ByteString) {
    def toByteStr          = ByteStr(bs.toByteArray)
    def toPublicKeyAccount = PublicKeyAccount(bs.toByteArray)
    def toAddress          = Address.fromBytes(bs.toByteArray).explicitGet()
  }

  implicit def vanillaByteStrToPBByteString(bs: ByteStr)    = bs.toPBByteString
  implicit def pbByteStringToVanillaByteStr(bs: ByteString) = bs.toByteStr

  private[this] implicit def shortToInt(s: Short): Int = {
    java.lang.Short.toUnsignedInt(s)
  }

  private[this] def intToShort(int: Int): Short = {
    require(int >= 0 && int <= 65535, s"Short overflow: $int")
    int.toShort
  }
}