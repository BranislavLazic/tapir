package sttp.tapir.server.vertx

import cats.effect.{IO, Resource}
import io.vertx.core.Vertx
import sttp.monad.FutureMonad
import sttp.tapir.server.tests.{CreateServerTest, ServerAuthenticationTests, ServerBasicTests, ServerMetricsTest, backendResource}
import sttp.tapir.tests.{Test, TestSuite}

import scala.concurrent.{ExecutionContext, Future}

class VertxServerTest extends TestSuite {
  def vertxResource: Resource[IO, Vertx] =
    Resource.make(IO.delay(Vertx.vertx()))(vertx => IO.delay(vertx.close()).void)

  override def tests: Resource[IO, List[Test]] = backendResource.flatMap { backend =>
    vertxResource.map { implicit vertx =>
      implicit val m: FutureMonad = new FutureMonad()(ExecutionContext.global)
      implicit val l: VertxBodyListener[Future] = new VertxBodyListener[Future]

      val interpreter = new VertxTestServerInterpreter(vertx)
      val createServerTest = new CreateServerTest(interpreter)

      new ServerBasicTests(
        backend,
        createServerTest,
        interpreter,
        multipartInlineHeaderSupport = false // README: doesn't seem supported but I may be wrong
      ).tests() ++ new ServerAuthenticationTests(backend, createServerTest).tests() ++
        new ServerMetricsTest(backend, createServerTest).tests()
    }
  }
}
