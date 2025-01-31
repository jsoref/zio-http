package zio.http.netty

import io.netty.util.concurrent.{Future, GenericFutureListener}
import zio._

import java.util.concurrent.CancellationException

private[zio] final class NettyFutureExecutor[A] private (jFuture: Future[A]) {

  /**
   * Resolves when the underlying future resolves and removes the handler
   * (output: A) - if the future is resolved successfully (cause: None) - if the
   * future fails with a CancellationException (cause: Throwable) - if the
   * future fails with any other Exception
   */
  def execute: Task[Option[A]] = {
    var handler: GenericFutureListener[Future[A]] = { _ => {} }
    ZIO
      .async[Any, Throwable, Option[A]](cb => {
        handler = _ => {
          jFuture.cause() match {
            case null                     => cb(ZIO.attempt(Option(jFuture.get)))
            case _: CancellationException => cb(ZIO.succeed(Option.empty))
            case cause                    => cb(ZIO.fail(cause))
          }
        }
        jFuture.addListener(handler)
      })
      .onInterrupt(ZIO.succeed(jFuture.removeListener(handler)))
  }

  def scoped: ZIO[Scope, Throwable, Option[A]] = {
    execute.withFinalizer(_ => cancel(true))
  }

  // Cancels the future
  def cancel(interruptIfRunning: Boolean = false): UIO[Boolean] = ZIO.succeed(jFuture.cancel(interruptIfRunning))
}

object NettyFutureExecutor {
  def make[A](jFuture: => Future[A]): Task[NettyFutureExecutor[A]] = ZIO.attempt(new NettyFutureExecutor(jFuture))

  def executed[A](jFuture: => Future[A]): Task[Unit] = make(jFuture).flatMap(_.execute.unit)

  def scoped[A](jFuture: => Future[A]): ZIO[Scope, Throwable, Unit] = make(jFuture).flatMap(_.scoped.unit)
}
