package com.wavesplatform.api.http.assets

import java.util.concurrent._

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.server.Route
import cats.instances.either.catsStdInstancesForEither
import cats.instances.option.catsStdInstancesForOption
import cats.syntax.either._
import cats.syntax.traverse._
import com.wavesplatform.account.Address
import com.wavesplatform.api.common.{CommonAccountApi, CommonAssetsApi}
import com.wavesplatform.api.http.ApiError._
import com.wavesplatform.api.http._
import com.wavesplatform.api.http.assets.AssetsApiRoute.DistributionParams
import com.wavesplatform.api.http.requests._
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.{Base58, Base64}
import com.wavesplatform.http.BroadcastRoute
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.network.UtxPoolSynchronizer
import com.wavesplatform.settings.RestAPISettings
import com.wavesplatform.state.{AssetDescription, Blockchain}
import com.wavesplatform.transaction.Asset.IssuedAsset
import com.wavesplatform.transaction.TxValidationError.GenericError
import com.wavesplatform.transaction.assets.IssueTransaction
import com.wavesplatform.transaction.assets.exchange.Order
import com.wavesplatform.transaction.assets.exchange.OrderJson._
import com.wavesplatform.transaction.smart.InvokeScriptTransaction
import com.wavesplatform.transaction.smart.script.ScriptCompiler
import com.wavesplatform.transaction.{AssetIdStringLength, TransactionFactory}
import com.wavesplatform.utils.Time
import com.wavesplatform.wallet.Wallet
import io.swagger.annotations._
import javax.ws.rs.Path
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import play.api.libs.json._

import scala.concurrent.Future
import scala.util.Success

@Path("/assets")
@Api(value = "assets")
case class AssetsApiRoute(settings: RestAPISettings, wallet: Wallet, utxPoolSynchronizer: UtxPoolSynchronizer, blockchain: Blockchain, time: Time)
    extends ApiRoute
    with BroadcastRoute
    with AuthRoute {

  private[this] val commonAccountApi = new CommonAccountApi(blockchain)
  private[this] val commonAssetsApi  = new CommonAssetsApi(blockchain)

  private[this] val distributionTaskScheduler = {
    val executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue[Runnable](AssetsApiRoute.MAX_DISTRIBUTION_TASKS))
    Scheduler(executor)
  }

  override lazy val route: Route =
    pathPrefix("assets") {
      balance ~ balances ~ nft ~ balanceDistributionAtHeight ~ balanceDistribution ~ details ~ deprecatedRoute
    }

  @Path("/balance/{address}/{assetId}")
  @ApiOperation(value = "Asset's balance", notes = "Account's balance by given asset", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "address", value = "Address", required = true, dataType = "string", paramType = "path"),
      new ApiImplicitParam(name = "assetId", value = "Asset ID", required = true, dataType = "string", paramType = "path")
    )
  )
  def balance: Route =
    (get & path("balance" / Segment / Segment)) { (address, assetId) =>
      complete(balanceJson(address, assetId))
    }

  def assetDistributionTask(params: DistributionParams): Task[ToResponseMarshallable] = {
    val (asset, height, limit, maybeAfter) = params

    val distributionTask = Task.eval(
      blockchain.assetDistributionAtHeight(asset, height, limit, maybeAfter)
    )

    distributionTask.map {
      case Right(dst) => Json.toJson(dst): ToResponseMarshallable
      case Left(err)  => ApiError.fromValidationError(err)
    }
  }

  @Deprecated
  @Path("/{assetId}/distribution")
  @ApiOperation(value = "Asset balance distribution", notes = "Asset balance distribution by account", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "assetId", value = "Asset ID", required = true, dataType = "string", paramType = "path")
    )
  )
  def balanceDistribution: Route =
    (get & path(Segment / "distribution")) { assetParam =>
      val assetEi = AssetsApiRoute
        .validateAssetId(assetParam)

      val distributionTask = assetEi match {
        case Left(err) => Task.pure(ApiError.fromValidationError(err): ToResponseMarshallable)
        case Right(asset) =>
          Task
            .eval(blockchain.assetDistribution(asset))
            .map(dst => Json.toJson(dst)(com.wavesplatform.state.dstWrites): ToResponseMarshallable)
      }

      complete {
        try {
          distributionTask.runAsyncLogErr(distributionTaskScheduler)
        } catch {
          case _: RejectedExecutionException =>
            val errMsg = CustomValidationError("Asset distribution currently unavailable, try again later")
            Future.successful(errMsg.json: ToResponseMarshallable)
        }
      }
    }

  @Path("/{assetId}/distribution/{height}/limit/{limit}")
  @ApiOperation(
    value = "Asset balance distribution at height",
    notes = "Asset balance distribution by account at specified height",
    httpMethod = "GET"
  )
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "assetId", value = "Asset ID", required = true, dataType = "string", paramType = "path"),
      new ApiImplicitParam(name = "height", value = "Height", required = true, dataType = "integer", paramType = "path"),
      new ApiImplicitParam(name = "limit", value = "Number of addresses to be returned", required = true, dataType = "integer", paramType = "path"),
      new ApiImplicitParam(name = "after", value = "address to paginate after", required = false, dataType = "string", paramType = "query")
    )
  )
  def balanceDistributionAtHeight: Route =
    (get & path(Segment / "distribution" / IntNumber / "limit" / IntNumber) & parameter('after.?)) {
      (assetParam, heightParam, limitParam, afterParam) =>
        val paramsEi: Either[ValidationError, DistributionParams] =
          AssetsApiRoute
            .validateDistributionParams(blockchain, assetParam, heightParam, limitParam, settings.distributionAddressLimit, afterParam)

        val resultTask = paramsEi match {
          case Left(err)     => Task.pure(ApiError.fromValidationError(err): ToResponseMarshallable)
          case Right(params) => assetDistributionTask(params)
        }

        complete {
          try {
            resultTask.runAsyncLogErr(distributionTaskScheduler)
          } catch {
            case _: RejectedExecutionException =>
              val errMsg = CustomValidationError("Asset distribution currently unavailable, try again later")
              Future.successful(errMsg.json: ToResponseMarshallable)
          }
        }
    }

  @Path("/balance/{address}")
  @ApiOperation(value = "Account's balance", notes = "Account's balances for all assets", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "address", value = "Address", required = true, dataType = "string", paramType = "path")
    )
  )
  def balances: Route =
    (get & path("balance" / Segment)) { address =>
      complete(fullAccountAssetsInfo(address))
    }

  def details: Route = pathPrefix("details")(singleDetails ~ multipleDetails)

  private val fullDetails = parameters('full.as[Boolean].?)

  @Path("/details/{assetId}")
  @ApiOperation(value = "Information about an asset", notes = "Provides detailed information about given asset", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "assetId", value = "ID of the asset", required = true, dataType = "string", paramType = "path"),
      new ApiImplicitParam(name = "full", value = "false", required = false, dataType = "boolean", paramType = "query")
    )
  )
  def singleDetails: Route =
    (get & path(Segment) & fullDetails) { (id, full) =>
      complete(assetDetails(id, full.getOrElse(false)))
    }

  def multipleDetails: Route = pathEndOrSingleSlash(multipleDetailsGet ~ multipleDetailsPost)

  @Path("/details")
  @ApiOperation(value = "Information about assets", notes = "Provides detailed information about given assets", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "id", value = "IDs of the asset", required = true, dataType = "string", paramType = "query"),
      new ApiImplicitParam(name = "full", value = "false", required = false, dataType = "boolean", paramType = "query")
    )
  )
  def multipleDetailsGet: Route =
    (get & parameters('id.*) & fullDetails) { (ids, full) =>
      complete(ids.toList.map(id => assetDetails(id, full.getOrElse(false)).fold(_.json, identity)))
    }

  @Path("/details")
  @ApiOperation(value = "Information about assets", notes = "Provides detailed information about given assets", httpMethod = "POST")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(
        name = "json",
        value = "IDs of the asset",
        required = true,
        dataType = "string",
        paramType = "body",
        example = """{"ids": ["some1", "some2"]}"""
      ),
      new ApiImplicitParam(name = "full", value = "false", required = false, dataType = "boolean", paramType = "query")
    )
  )
  def multipleDetailsPost: Route =
    fullDetails { full =>
      jsonPost[JsObject] { jsv =>
        (jsv \ "ids").validate[List[String]] match {
          case JsSuccess(ids, _) => ids.map(id => assetDetails(id, full.getOrElse(false)).fold(_.json, identity))
          case JsError(err)      => WrongJson(errors = err)
        }
      }
    }

  @Path("/nft/{address}/limit/{limit}")
  @ApiOperation(value = "NFTs", notes = "Account's NFTs balance", httpMethod = "GET")
  @ApiImplicitParams(
    Array(
      new ApiImplicitParam(name = "address", value = "Address", required = true, dataType = "string", paramType = "path"),
      new ApiImplicitParam(name = "limit", value = "Number of tokens to be returned", required = true, dataType = "integer", paramType = "path"),
      new ApiImplicitParam(name = "after", value = "Id of token to paginate after", required = false, dataType = "string", paramType = "query")
    )
  )
  def nft: Route =
    extractScheduler(
      implicit sc =>
        (path("nft" / Segment / "limit" / IntNumber) & parameter('after.?) & get) { (addressParam, limitParam, maybeAfterParam) =>
          val response: Either[ApiError, Future[JsArray]] = for {
            addr  <- Address.fromString(addressParam).left.map(ApiError.fromValidationError)
            limit <- Either.cond(limitParam <= settings.transactionsByAddressLimit, limitParam, TooBigArrayAllocation)
            maybeAfter <- maybeAfterParam match {
              case Some(v) =>
                ByteStr
                  .decodeBase58(v)
                  .fold(
                    _ => Left(CustomValidationError(s"Unable to decode asset id $v")),
                    id => Right(Some(IssuedAsset(id)))
                  )
              case None => Right(None)
            }
          } yield {
            commonAccountApi
              .portfolioNFT(addr, maybeAfter)
              .flatMap {
                case (assetId, assetDesc) =>
                  Observable.fromEither(
                    AssetsApiRoute
                      .jsonDetails(blockchain)(assetId, assetDesc, true)
                      .leftMap(err => new IllegalArgumentException(err))
                  )
              }
              .take(limit)
              .toListL
              .map(lst => JsArray(lst))
              .runAsyncLogErr
          }

          complete(response)
        }
    )

  private def deprecatedRoute: Route =
    (path("transfer") & withAuth) {
      broadcast[TransferRequest](TransactionFactory.transferAsset(_, wallet, time))
    } ~ (path("masstransfer") & withAuth) {
      broadcast[MassTransferRequest](TransactionFactory.massTransferAsset(_, wallet, time))
    } ~ (path("issue") & withAuth) {
      broadcast[IssueRequest](TransactionFactory.issue(_, wallet, time))
    } ~ (path("reissue") & withAuth) {
      broadcast[ReissueRequest](TransactionFactory.reissue(_, wallet, time))
    } ~ (path("burn") & withAuth) {
      broadcast[BurnRequest](TransactionFactory.burn(_, wallet, time))
    } ~ (path("sponsor") & withAuth) {
      broadcast[SponsorFeeRequest](TransactionFactory.sponsor(_, wallet, time))
    } ~ (path("order") & withAuth)(jsonPost[Order] { order =>
      wallet.privateKeyAccount(order.senderPublicKey).map(pk => Order.sign(order, pk))
    }) ~ pathPrefix("broadcast")(
      path("issue")(broadcast[IssueRequest](_.toTx)) ~
        path("reissue")(broadcast[ReissueRequest](_.toTx)) ~
        path("burn")(broadcast[BurnRequest](_.toTx)) ~
        path("exchange")(broadcast[ExchangeRequest](_.toTx)) ~
        path("transfer")(broadcast[TransferRequest](_.toTx))
    )

  private def balanceJson(address: String, assetIdStr: String): Either[ApiError, JsObject] = {
    ByteStr.decodeBase58(assetIdStr) match {
      case Success(assetId) =>
        (for {
          acc <- Address.fromString(address)
        } yield Json.obj(
          "address" -> acc.stringRepr,
          "assetId" -> assetIdStr,
          "balance" -> JsNumber(BigDecimal(blockchain.balance(acc, IssuedAsset(assetId))))
        )).left
          .map(ApiError.fromValidationError)
      case _ => Left(InvalidAddress)
    }
  }

  private def fullAccountAssetsInfo(address: String): Either[ApiError, JsObject] =
    (for {
      acc <- Address.fromString(address)
    } yield {
      Json.obj(
        "address" -> acc.stringRepr,
        "balances" -> JsArray(
          (for {
            (asset @ IssuedAsset(assetId), balance)                                <- commonAccountApi.portfolio(acc) if balance > 0
            CommonAssetsApi.AssetInfo(assetInfo, issueTransaction, sponsorBalance) <- commonAssetsApi.fullInfo(asset)
          } yield Json.obj(
            "assetId"    -> assetId.toString,
            "balance"    -> balance,
            "reissuable" -> assetInfo.reissuable,
            "minSponsoredAssetFee" -> (assetInfo.sponsorship match {
              case 0           => JsNull
              case sponsorship => JsNumber(sponsorship)
            }),
            "sponsorBalance"   -> sponsorBalance,
            "quantity"         -> JsNumber(BigDecimal(assetInfo.totalVolume)),
            "issueTransaction" -> issueTransaction.fold[JsValue](JsNull)(_.json())
          )).toSeq
        )
      )
    }).left.map(ApiError.fromValidationError)

  private def assetDetails(assetId: String, full: Boolean): Either[ApiError, JsObject] = {
    (for {
      id          <- ByteStr.decodeBase58(assetId).toOption.toRight("Incorrect asset ID")
      description <- blockchain.assetDescription(IssuedAsset(id)).toRight("Failed to get description of the asset")
      result      <- AssetsApiRoute.jsonDetails(blockchain)(id, description, full)
    } yield result).left.map(m => CustomValidationError(m))
  }
}

object AssetsApiRoute {
  val MAX_DISTRIBUTION_TASKS = 5

  type DistributionParams = (IssuedAsset, Int, Int, Option[Address])

  def validateDistributionParams(
      blockchain: Blockchain,
      assetParam: String,
      heightParam: Int,
      limitParam: Int,
      maxLimit: Int,
      afterParam: Option[String]
  ): Either[ValidationError, DistributionParams] = {
    for {
      limit   <- validateLimit(limitParam, maxLimit)
      height  <- validateHeight(blockchain, heightParam)
      assetId <- validateAssetId(assetParam)
      after   <- afterParam.traverse[Either[ValidationError, ?], Address](Address.fromString)
    } yield (assetId, height, limit, after)
  }

  def validateAssetId(assetParam: String): Either[ValidationError, IssuedAsset] = {
    for {
      _ <- Either.cond(assetParam.length <= AssetIdStringLength, (), GenericError("Unexpected assetId length"))
      assetId <- Base58
        .tryDecodeWithLimit(assetParam)
        .fold(
          _ => GenericError("Must be base58-encoded assetId").asLeft[IssuedAsset],
          arr => IssuedAsset(ByteStr(arr)).asRight[ValidationError]
        )
    } yield assetId
  }

  def validateHeight(blockchain: Blockchain, height: Int): Either[ValidationError, Int] = {
    for {
      _ <- Either
        .cond(height > 0, (), GenericError(s"Height should be greater than zero"))
      _ <- Either
        .cond(height != blockchain.height, (), GenericError(s"Using 'assetDistributionAtHeight' on current height can lead to inconsistent result"))
      _ <- Either
        .cond(height < blockchain.height, (), GenericError(s"Asset distribution available only at height not greater than ${blockchain.height - 1}"))
    } yield height

  }

  def validateLimit(limit: Int, maxLimit: Int): Either[ValidationError, Int] = {
    for {
      _ <- Either
        .cond(limit > 0, (), GenericError("Limit should be greater than 0"))
      _ <- Either
        .cond(limit < maxLimit, (), GenericError(s"Limit should be less than $maxLimit"))
    } yield limit
  }

  def jsonDetails(blockchain: Blockchain)(id: ByteStr, description: AssetDescription, full: Boolean): Either[String, JsObject] = {
    // (timestamp, height)
    def additionalInfo(id: ByteStr): Either[String, (Long, Int)] =
      for {
        tt <- blockchain.transactionInfo(id).toRight("Failed to find issue/invokeScript transaction by ID")
        (h, mtx) = tt
        ts <- (mtx match {
          case tx: IssueTransaction        => Some(tx.timestamp)
          case tx: InvokeScriptTransaction => Some(tx.timestamp)
          case _                           => None
        }).toRight("No issue/invokeScript transaction found with the given asset ID")
      } yield (ts, h)

    for {
      tsh <- additionalInfo(description.source)
      (timestamp, height) = tsh
      script              = description.script.filter(_ => full)
      complexity <- script.fold[Either[String, Long]](Right(0))(script => ScriptCompiler.estimate(script, script.stdLibVersion))
      name = description.name.fold(bs => Base64.encode(bs.arr), identity)
      desc = description.description.fold(bs => Base64.encode(bs.arr), identity)
    } yield JsObject(
      Seq(
        "assetId"        -> JsString(id.toString),
        "issueHeight"    -> JsNumber(height),
        "issueTimestamp" -> JsNumber(timestamp),
        "issuer"         -> JsString(description.issuer.stringRepr),
        "name"           -> JsString(name),
        "description"    -> JsString(desc),
        "decimals"       -> JsNumber(description.decimals),
        "reissuable"     -> JsBoolean(description.reissuable),
        "quantity"       -> JsNumber(BigDecimal(description.totalVolume)),
        "scripted"       -> JsBoolean(description.script.nonEmpty),
        "minSponsoredAssetFee" -> (description.sponsorship match {
          case 0           => JsNull
          case sponsorship => JsNumber(sponsorship)
        }),
        "originTransactionId" -> JsString(description.source.toString)
      ) ++ script.toSeq.map { script =>
        "scriptDetails" -> Json.obj(
          "scriptComplexity" -> JsNumber(BigDecimal(complexity)),
          "script"           -> JsString(script.bytes().base64),
          "scriptText"       -> JsString(script.expr.toString) // [WAIT] JsString(Script.decompile(script))
        )
      }
    )
  }
}
