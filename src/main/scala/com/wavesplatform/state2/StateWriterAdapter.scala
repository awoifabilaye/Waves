package com.wavesplatform.state2

import cats._
import cats.implicits._
import cats.syntax.all._
import cats.kernel.Monoid
import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state2._
import com.wavesplatform.state2.diffs.BlockDiffer
import com.wavesplatform.state2.reader.{CompositeStateReader, StateReader}
import play.api.libs.json.JsObject
import scorex.account.{Account, Alias}
import scorex.block.Block
import scorex.transaction._
import scorex.transaction.assets.IssueTransaction
import scorex.transaction.assets.exchange.{ExchangeTransaction, Order}
import scorex.transaction.state.database.state.{AccState, AddressString, Reasons}

import scala.reflect.ClassTag
import scala.util.{Failure, Try}

class StateWriterAdapter(r: StateWriter with StateReader, settings: FunctionalitySettings, bc: BlockChain) extends State {

  private val MinInMemDiff = 100
  private val MaxInMemDiff = 200

  @volatile var inMemoryDiff: BlockDiff = {
    val storedBlocks = bc.height()
    val statedBlocks = r.height
    if (statedBlocks > storedBlocks) {
      throw new IllegalArgumentException(s"storedBlocks = $storedBlocks, statedBlocks=$statedBlocks")
    } else if (statedBlocks == storedBlocks) {
      Monoid[BlockDiff].empty
    } else {
      rebuildDiff(statedBlocks + 1, storedBlocks + 1)
    }
  }

  private def rebuildDiff(from: Int, to: Int): BlockDiff =
    Range(from, to).foldLeft(Monoid[BlockDiff].empty) { (diff, h) =>
      val block = bc.blockAt(h).get
      val blockDiff = BlockDiffer(settings)(new CompositeStateReader(r, diff), block).right.get
      Monoid[BlockDiff].combine(diff, blockDiff)
    }

  override def processBlock(block: Block): Try[State] = Try {
    BlockDiffer(settings)(r, block) match {
      case Right(blockDiff) =>
        r.applyBlockDiff(blockDiff)
        this
      case Left(m) =>
        println(m)
        ???
    }
  }

  // legacy

  override def included(signature: Array[Byte]): Option[Int] = r.transactionInfo(EqByteArray(signature)).map(_._1)

  override def findTransaction[T <: Transaction](signature: Array[Byte])(implicit ct: ClassTag[T]): Option[T]
  = r.findTransaction(signature)

  override def accountTransactions(account: Account, limit: Int): Seq[_ <: Transaction] =
    r.accountTransactionIds(account).flatMap(r.transactionInfo).map(_._2)

  override def lastAccountPaymentTransaction(account: Account): Option[PaymentTransaction] = ??? // not needed

  override def balance(account: Account): Long = r.accountPortfolio(account).balance

  override def assetBalance(account: AssetAcc): Long =
    r.accountPortfolio(account.account)
      .assets
      .getOrElse(EqByteArray(account.assetId.get), 0)

  override def getAccountBalance(account: Account): Map[AssetId, (Long, Boolean, Long, IssueTransaction)] =
    r.accountPortfolio(account).assets.map { case (id, amt) =>
      val assetInfo = r.assetInfo(id).get
      id.arr -> (amt, assetInfo.isReissuable, assetInfo.volume, findTransaction[IssueTransaction](id.arr).get)
    }

  override def assetDistribution(assetId: Array[Byte]): Map[String, Long] =
    r.assetDistribution(EqByteArray(assetId))
      .map { case (acc, amt) => (acc.address, amt) }

  override def effectiveBalance(account: Account): Long = r.accountPortfolio(account).effectiveBalance

  override def getLeasedSum(address: AddressString): Long = {
    val portfolio = r.accountPortfolio(Account.fromString(address).right.get)
    portfolio.effectiveBalance - portfolio.balance
  }

  override def isReissuable(id: Array[Byte]): Boolean =
    r.assetInfo(EqByteArray(id)).get.isReissuable

  override def totalAssetQuantity(assetId: AssetId): Long =
    r.assetInfo(EqByteArray(assetId)).get.volume

  override def balanceWithConfirmations(account: Account, confirmations: Int): Long = ???

  override def wavesDistributionAtHeight(height: Int): Seq[(AddressString, Long)] = ???

  override def effectiveBalanceWithConfirmations(account: Account, confirmations: Int, height: Int): Long =
    r.effectiveBalanceAtHeightWithConfirmations(account, height, confirmations)

  override def findPrevOrderMatchTxs(order: Order): Set[ExchangeTransaction] = ???

  override def resolveAlias(a: Alias): Option[Account] = ???

  override def getAlias(a: Account): Option[Alias] = ???

  override def stateHeight: Int = r.height

  override def toJson(heightOpt: Option[Int]): JsObject = ???

  override def rollbackTo(height: Int): State = ???

  override def applyChanges(changes: Map[AssetAcc, (AccState, Reasons)], blockTs: Long): Unit = ???

  override def calcNewBalances(trans: Seq[Transaction], fees: Map[AssetAcc, (AccState, Reasons)], allowTemporaryNegative: Boolean): Map[AssetAcc, (AccState, Reasons)] = ???

  override def addAsset(assetId: AssetId, height: Int, transactionId: Array[Byte], quantity: Long, reissuable: Boolean): Unit = ???

  override def burnAsset(assetId: AssetId, height: Int, transactionId: Array[Byte], quantity: Long): Unit = ???

  override def assetRollbackTo(assetId: Array[Byte], height: Int, newReissuable: Option[Boolean] = None): Unit = ???

}
