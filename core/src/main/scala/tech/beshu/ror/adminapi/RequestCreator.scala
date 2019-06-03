package tech.beshu.ror.adminapi

import com.twitter.finagle.http.{Method, Request, Version}
import com.twitter.io.{Buf, Reader}

object RequestCreator {

  def createPostRequest(uri: String, body: String): Request = {
    Request(Version.Http11, Method.Post,  uri, Reader.value(Buf.Utf8(body)))
  }

  def createGetRequest(uri: String): Request = {
    Request(Version.Http11, Method.Get, uri)
  }
}
