package com.twitter.finagle.naming

import com.twitter.finagle.Status.StatusOrdering
import com.twitter.finagle._
import com.twitter.finagle.factory.ServiceFactoryCache
import com.twitter.finagle.util.{Drv, Rng}
import com.twitter.logging.Logger
import com.twitter.util.{Future, Time}

/**
 * Builds a factory from a [[com.twitter.finagle.NameTree]]. Leaves
 * are taken from the given
 * [[com.twitter.finagle.factory.ServiceFactoryCache]]; Unions become
 * random weighted distributors.
 */
private object NameTreeFactory {
  private val log = Logger.get(getClass.getName)

  def apply[Key, Req, Rep](
    path: Path,
    tree: NameTree[Key],
    factoryCache: ServiceFactoryCache[Key, Req, Rep],
    rng: Rng = Rng.threadLocal
  ): ServiceFactory[Req, Rep] = {

    lazy val noBrokersAvailableFactory = Failed(new NoBrokersAvailableException(path.show))

    case class Failed(exn: Throwable) extends ServiceFactory[Req, Rep] {
      val service: Future[Service[Req, Rep]] = Future.exception(exn)

      def apply(conn: ClientConnection) = service
      override def status = Status.Closed
      def close(deadline: Time) = Future.Done
    }

    case class Leaf(key: Key) extends ServiceFactory[Req, Rep] {
      def apply(conn: ClientConnection) = factoryCache.apply(key, conn)
      override def status = factoryCache.status(key)
      def close(deadline: Time) = Future.Done
    }

    case class Weighted(
      drv: Drv,
      factories: Seq[ServiceFactory[Req, Rep]]
    ) extends ServiceFactory[Req, Rep] {
      def apply(conn: ClientConnection) = factories(drv(rng)).apply(conn)

      override def status = Status.worstOf[ServiceFactory[Req, Rep]](factories, _.status)
      def close(deadline: Time) = Future.Done
    }

    case class Alted(
      factories: Seq[ServiceFactory[Req, Rep]]
    ) extends ServiceFactory[Req, Rep] {

      def apply(conn: ClientConnection) = {
        log.trace("Alted: " + factories.map(_.status).mkString(", "))
        factories.sortBy(_.status)(StatusOrdering.reverse).headOption.getOrElse(noBrokersAvailableFactory).apply(conn)
      }

      override def status = Status.bestOf[ServiceFactory[Req, Rep]](factories, _.status)

      def close(deadline: Time) = Future.Done
    }

    def factoryOfTree(tree: NameTree[Key]): ServiceFactory[Req, Rep] =
      tree match {
        case NameTree.Neg | NameTree.Fail | NameTree.Empty => noBrokersAvailableFactory
        case NameTree.Leaf(key) => Leaf(key)
        case NameTree.Alt(trees@_*) => Alted(trees.map(factoryOfTree))

        case NameTree.Union(weightedTrees @ _*) =>
          val (weights, trees) = weightedTrees.unzip { case NameTree.Weighted(w, t) => (w, t) }
          Weighted(Drv.fromWeights(weights), trees.map(factoryOfTree))
      }

    factoryOfTree(tree)
  }
}
