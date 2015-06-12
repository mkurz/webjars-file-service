package utils

import play.api.libs.ws.{DefaultWSClientConfig, WSAPI, WSClient, WSRequestHolder}

import scala.concurrent.{ExecutionContext, Future}

class StandaloneWS extends WSAPI with java.io.Closeable {
  import play.api.libs.ws.ning.{NingAsyncHttpClientConfigBuilder, NingWSClient}

  lazy val config = DefaultWSClientConfig(compressionEnabled = Some(true))
  lazy val builder = new NingAsyncHttpClientConfigBuilder(config)
  lazy val ningWsClient: NingWSClient = new NingWSClient(builder.build())
  override def client: WSClient = ningWsClient
  override def url(url: String): WSRequestHolder = client.url(url)
  def close(): Unit = ningWsClient.close()
}

object StandaloneWS {

  def apply() = new StandaloneWS()

  def withWs[T](f: WSAPI => Future[T])(implicit ec: ExecutionContext) = {
    val ws = new StandaloneWS()
    val future = f(ws)
    future.onComplete(_ => ws.close())
    future
  }

}