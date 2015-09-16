package utils

import java.io.{File, InputStream}
import java.net.{URLEncoder, URL}
import java.nio.file.Files
import java.util.jar.JarInputStream
import shade.memcached.MemcachedCodecs._

import org.webjars.WebJarAssetLocator
import play.api.Play
import play.api.libs.ws.WSResponse

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{Success, Try}
import scala.concurrent.ExecutionContext.Implicits.global

object MavenCentral {

  lazy val tempDir: File = Files.createTempDirectory("webjars").toFile

  val primaryBaseJarUrl = Play.current.configuration.getString("webjars.jarUrl.primary").get
  val fallbackBaseJarUrl = Play.current.configuration.getString("webjars.jarUrl.fallback").get

  def getFile(groupId: String, artifactId: String, version: String): Try[(JarInputStream, InputStream)] = {
    val tmpFile = new File(tempDir, s"$groupId-$artifactId-$version.jar")

    if (tmpFile.exists()) {
      val fileInputStream = Files.newInputStream(tmpFile.toPath)
      Success((new JarInputStream(fileInputStream), fileInputStream))
    }
    else {
      val fileInputStreamFuture = getFileInputStream(primaryBaseJarUrl, groupId, artifactId, version).recoverWith {
        case _ =>
          getFileInputStream(fallbackBaseJarUrl, groupId, artifactId, version)
      }

      fileInputStreamFuture.map { fileInputStream =>
        // todo: not thread safe!
        // write to the fs
        Files.copy(fileInputStream, tmpFile.toPath)
        fileInputStream.close()

        val tmpFileInputStream = Files.newInputStream(tmpFile.toPath)
        // read it from the fs since we've drained the http response
        (new JarInputStream(tmpFileInputStream), tmpFileInputStream)
      }
    }
  }

  def getFileInputStream(baseJarUrl: String, groupId: String, artifactId: String, version: String): Try[InputStream] = {
    Try {
      val url = new URL(baseJarUrl.format(groupId.replace(".", "/"), artifactId, URLEncoder.encode(version, "UTF-8"), artifactId, URLEncoder.encode(version, "UTF-8")))
      url.openConnection().getInputStream
    }
  }

  def fetchFileList(groupId: String, artifactId: String, version: String): Future[List[String]] = {
    val cacheKey = s"listfiles-$groupId-$artifactId-$version"

    Global.memcached.get[List[String]](cacheKey).flatMap { maybeFileList =>
      maybeFileList.fold(Future.failed[List[String]](new Exception("cache miss")))(Future.successful)
    } recoverWith { case e: Exception =>
      Future.fromTry {
        getFile(groupId, artifactId, version).map { case (jarInputStream, inputStream) =>
          val webJarFiles = Iterator.continually(jarInputStream.getNextJarEntry).
            takeWhile(_ != null).
            filterNot(_.isDirectory).
            map(_.getName).
            filter(_.startsWith(WebJarAssetLocator.WEBJARS_PATH_PREFIX)).
            toList
          jarInputStream.close()
          inputStream.close()
          Global.memcached.set(cacheKey, webJarFiles, Duration.Inf)
          webJarFiles
        }
      }
    }
  }

  case class UnexpectedResponseException(response: WSResponse) extends RuntimeException {
    override def getMessage: String = response.statusText
  }

}
