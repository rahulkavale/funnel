package funnel
package chemist

import unfiltered.request._
import unfiltered.response._
import unfiltered.netty._
import argonaut._, Argonaut._
import scalaz.concurrent.Task
import journal.Logger

object JsonRequest {
  def apply[T](r: HttpRequest[T]) =
    new ParseWrap(r, new Parse[HttpRequest[T]] {
      def parse(req: HttpRequest[T]) = JsonParser.parse(Body.string(req))
    })
}

object JsonResponse {
  def apply[A: EncodeJson](a: A, params: PrettyParams = PrettyParams.nospace) =
    JsonContent ~> ResponseString(a.jencode.pretty(params))
}

object Server {
  private val log = Logger[Server.type]

  def start[U <: Platform](chemist: Chemist[U], platform: U): Task[Unit] =
    Task.reduceUnordered[Unit, Unit](Seq(
      chemist.init(platform),
      Task(unfiltered.netty.Server
        .http(platform.config.network.port, platform.config.network.host)
        .resources(getClass.getResource("/oncue/www/"), cacheSeconds = 3600)
        .handler(Server(chemist, platform))
        .run
      )
    ))

    // .runAsync(_.fold(
    //   e => log.error(s"Problem occoured during server initilization: $e"),
    //   s => log.warn("Background process completed sucsessfully. This may have happened in error, as typically the process matches the lifecycle of the server.")
    // ))
}

@io.netty.channel.ChannelHandler.Sharable
case class Server[U <: Platform](chemist: Chemist[U], platform: U) extends cycle.Plan with cycle.SynchronousExecution with ServerErrorResponse {
  import JSON._
  import concurrent.duration._
  import chemist.ChemistK

  private def json[A : EncodeJson](a: ChemistK[A]) =
    a(platform).attemptRun.fold(
      e => InternalServerError ~> JsonResponse(e.toString),
      o => Ok ~> JsonResponse(o))

  private def decode[A : DecodeJson](req: HttpRequest[Any])(f: A => ResponseFunction[Any]) =
    JsonRequest(req).decodeEither[A].map(f).fold(fail => BadRequest ~> ResponseString(fail), identity)

  def intent = {
    case GET(Path("/")) =>
      Redirect("/index.html")

    case GET(Path("/status")) =>
      Ok ~> JsonResponse(Chemist.version)

    case GET(Path("/distribution")) =>
      json(chemist.distribution.map(_.toList))

    case GET(Path("/lifecycle/history")) =>
      json(chemist.history.map(_.toList))

    case POST(Path("/distribute")) =>
      NotImplemented ~> JsonResponse("This feature is not avalible in this build. Sorry :-)")

    case POST(Path("/bootstrap")) =>
      json(chemist.bootstrap)

    case GET(Path(Seg("shards" :: Nil))) =>
      json(chemist.shards)

    case GET(Path(Seg("shards" :: id :: Nil))) =>
      json(chemist.shard(id))

    case POST(Path(Seg("shards" :: id :: "exclude" :: Nil))) =>
      json(chemist.exclude(id))

    case POST(Path(Seg("shards" :: id :: "include" :: Nil))) =>
      json(chemist.include(id))

  }
}
