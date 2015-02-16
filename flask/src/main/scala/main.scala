package funnel
package flask

import java.io.File
import org.slf4j.LoggerFactory
import com.aphyr.riemann.client.RiemannClient
import scala.concurrent.duration._
import scalaz.concurrent.{Task,Strategy,Actor}
import scalaz.stream.Process
import scalaz.stream.async.mutable.Signal
import scalaz.std.option._
import scalaz.syntax.applicative._
import knobs.{Config, Required, ClassPathResource, FileResource}
import com.amazonaws.auth.{AWSCredentialsProvider, AWSCredentials}
import com.amazonaws.services.sns.{AmazonSNSClient,AmazonSNS}
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.{Region, Regions}
import funnel.{Events,DatapointParser,Datapoint,Names,Sigar,Monitoring,Instruments}
import funnel.riemann.Riemann
import funnel.http.{MonitoringServer,SSE}
import funnel.elastic._
import funnel.zeromq.Mirror
import java.net.URI

/**
  * How to use: Modify oncue/flask.cfg on the classpath
  * and run from the command line.
  *
  * Or pass the location of the config file as a command line argument.
  */
object Main {
  import Events.Event
  import scalaz.\/._

  lazy val signal: Signal[Boolean] = scalaz.stream.async.signalOf(true)

  private def shutdown(server: MonitoringServer, R: RiemannClient): Unit = {
    server.stop()
    signal.set(false).flatMap(_ => signal.close).run
    R.disconnect
  }

  private def giveUp(names: Names, cfg: Config, sns: AmazonSNS, log: String => Unit) = {
    val msg = s"${names.mine} gave up on ${names.kind} server ${names.theirs}"
    Process.eval(SNS.publish(cfg.require[String]("flask.sns-error-topic"), msg)(sns))
  }

  private def riemannErrorAndQuit(rm: RiemannCfg, f: () => Unit): Unit = {
    val msg = s"# Riemann is not running at the specified location (${rm.host}:${rm.port}) #"
    val padding = (for(_ <- 1 to msg.length) yield "#").mkString
    Console.err.println(padding)
    Console.err.println(msg)
    Console.err.println(padding)
    f()
    System.exit(1)
  }

  private def runAsync(p: Task[Unit])(implicit log: String => Unit): Unit = p.runAsync(_.fold(e => {
    e.printStackTrace()
    log(s"[ERROR] $e - ${e.getMessage}")
    log(e.getStackTrace.toList.mkString("\n","\t\n",""))
  }, identity _))

  private def httpOrZmtp(alive: Signal[Boolean])(uri: URI): Process[Task,Datapoint[Any]] =
    Option(uri.getScheme).map(_.toLowerCase) match {
      case Some("http") => SSE.readEvents(uri)
      case Some("tcp")  => Mirror.from(alive)(uri)
      case _            => Process.fail(new RuntimeException("Unknown URI scheme submitted."))
    }

  def main(args: Array[String]): Unit = {


    // merge the file config with the aws config
    val config: Task[Config] = for {
      a <- knobs.loadImmutable(List(Required(
        FileResource(new File("/usr/share/oncue/etc/flask.cfg")) or
        ClassPathResource("oncue/flask.cfg"))))
      b <- knobs.aws.config
    } yield a ++ b

    val (options, cfg) = config.flatMap { cfg =>
      val port        = cfg.lookup[Int]("flask.network.port").getOrElse(5775)
      val name        = cfg.lookup[String]("flask.name").getOrElse("flask")
      val elasticURL  = cfg.lookup[String]("flask.elastic-search.url")
      val elasticIx   = cfg.lookup[String]("flask.elastic-search.index-name")
      val elasticTy   = cfg.lookup[String]("flask.elastic-search.type-name")
      val elasticDf   =
        cfg.lookup[String]("flask.elastic-search.partition-date-format").getOrElse("yyyy.MM.dd")
      val elasticTimeout = cfg.lookup[Int]("flask.elastic-search.connection-timeout-in-ms").getOrElse(5000)
      val riemannHost = cfg.lookup[String]("flask.riemann.host")
      val riemannPort = cfg.lookup[Int]("flask.riemann.port")
      val ttl         = cfg.lookup[Int]("flask.riemann.ttl-in-minutes").map(_.minutes)
      val riemann     = (riemannHost |@| riemannPort |@| ttl)(RiemannCfg)
      val elastic     = (elasticURL |@| elasticIx |@| elasticTy)(
        ElasticCfg(_, _, _, elasticDf, elasticTimeout))
      Task((Options(elastic, riemann, port), cfg))
    }.run

    val logger = LoggerFactory.getLogger("flask")

    implicit val logPool: Strategy = Strategy.Executor(java.util.concurrent.Executors.newFixedThreadPool(1))

    val L = Actor.actor((s: String) => logger.info(s))

    implicit val log: String => Unit = s => L(s)

    val Q = SNS.client(
      new BasicAWSCredentials(
        cfg.require[String]("aws.access-key"),
        cfg.require[String]("aws.secret-key")),
      cfg.lookup[String]("aws.proxy-host"),
      cfg.lookup[Int]("aws.proxy-port"),
      cfg.lookup[String]("aws.proxy-protocol"),
      Region.getRegion(Regions.fromName(cfg.require[String]("aws.region")))
    )

    val M = Monitoring.default
    val S = MonitoringServer.start(M, options.funnelPort)

    // Determine whether to generate system statistics for the local host
    for {
      b <- cfg.lookup[Boolean]("flask.collect-local-metrics") if b == true
      t <- cfg.lookup[Int]("flask.local-metric-frequency")
    }{
      implicit val duration = t.seconds
      Sigar(new Instruments(1.minute, M)).foreach { s =>
        s.instrument
        // Flask.instrument(M, s)
      }
    }

    def retries(names: Names): Event =
      Monitoring.defaultRetries andThen (_ ++ giveUp(names, cfg, Q, log))

    val localhost = java.net.InetAddress.getLocalHost.toString

    val flaskName = cfg.lookup[String]("flask.name").getOrElse(localhost)

    runAsync(M.processMirroringEvents(httpOrZmtp(signal), flaskName, retries))

    options.elastic.foreach { elastic =>
      runAsync(Elastic(M).publish(flaskName)(elastic))
    }

    options.riemann.foreach { riemann =>
      val R = RiemannClient.tcp(riemann.host, riemann.port)
      try {
        R.connect() // urgh. Give me stregth!
      } catch {
        case e: java.io.IOException => {
          riemannErrorAndQuit(riemann, () => shutdown(S,R))
        }
      }

      runAsync(Riemann.publishToRiemann(
        M, riemann.ttl.toSeconds.toFloat)(
        R, s"${riemann.host}:${riemann.port}", retries)(flaskName))
    }
  }
}
