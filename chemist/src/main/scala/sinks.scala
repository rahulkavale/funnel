package funnel
package chemist

import scalaz.Nondeterminism
import scalaz.concurrent.Task
import scalaz.stream.{Sink,sink}
import journal.Logger

object sinks {
  import Sharding.Distribution
  import Chemist.Context
  import FlaskCommand._
  import scalaz.syntax.apply._

  val log = Logger[sinks.type]

  def caching(to: StateCache): Sink[Task, Context[Plan]] =
    sink.lift { c =>
      for {
        _ <- to.plan(c.value)
        _ <- to.distribution(c.distribution)
      } yield ()
    }

  // really only used for testing purposes
  val logging: Sink[Task, Context[Plan]] =
    sink.lift(c => Task.delay(log.debug(s"recieved $c")))

  def unsafeNetworkIO(f: RemoteFlask): Sink[Task, Context[Plan]] = sink.lift {
    case Context(d, Distribute(work)) => {
      val tasks = work.fold(List.empty[Task[Unit]]
        ){ (a,b,c) => c :+ f.command(Monitor(a,b)) }

      Task.delay(log.info("distributing")) <*
      Nondeterminism[Task].gatherUnordered(tasks)
    }

    case Context(d, p@Redistribute(stop, start)) => {
      val stopping = stop.fold(List.empty[Task[Unit]]
        ){ (a,b,c) => c :+ f.command(Unmonitor(a,b)) }

      val starting = start.fold(List.empty[Task[Unit]]
        ){ (a,b,c) => c :+ f.command(Monitor(a,b)) }

      Task.delay(log.info("recieved redistribute command: $p")) <*
      Nondeterminism[Task].gatherUnordered(stopping) <*
      Nondeterminism[Task].gatherUnordered(starting)
    }

    case Context(d, Ignore) =>
      Task.delay(())
  }
}
