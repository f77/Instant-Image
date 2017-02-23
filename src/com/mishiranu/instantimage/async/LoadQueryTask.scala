package com.mishiranu.instantimage.async

import android.net.Uri

import com.mishiranu.instantimage.R
import com.mishiranu.instantimage.model.Image
import com.mishiranu.instantimage.util.ScalaHelpers._

import org.json.{JSONException, JSONObject}

import rx.android.schedulers.AndroidSchedulers
import rx.lang.scala.Observable
import rx.schedulers.Schedulers

import scalaj.http.HttpResponse

import java.io.IOException

import javax.net.ssl.SSLException

class LoadQueryTask(query: String) extends Http {
  private class InvalidResponseException extends Exception

  private def request: Observable[HttpResponse[String]] = {
    Observable[HttpResponse[String]] { e =>
      val uriString = Uri.parse("https://www.google.com/search?tbm=isch").buildUpon
        .appendQueryParameter("q", query).build.toString
      e.onNext(http(uriString).asString)
      e.onCompleted()
    }
  }

  private def getString(response: HttpResponse[String]): String = {
    if (response.code != 200) {
      throw new InvalidResponseException
    }
    response.body
  }

  private def getImages(response: String): LoadQueryTask.Result = {
    def getImageString(data: String, from: Int): List[String] = {
      val start = data.indexOf('{', from + 1)
      val end = data.indexOf('}', start)
      if (end > start && start > from) {
        getImageString(data, end) ::: List(data.substring(start, end + 1))
      } else {
        List()
      }
    }

    val index = response.indexOf("<body")
    val list = if (index >= 0) getImageString(response, index) else List()

    LoadQueryTask.Result(list.map { jsonString =>
      try {
        val jsonObject = new JSONObject(jsonString)
        val imageUriString = jsonObject.getString("ou")
        val thumbnailUriString = jsonObject.getString("tu")
        val text = jsonObject.getString("pt")
        Image(imageUriString, thumbnailUriString, text)
      } catch {
        case _: JSONException => null
      }
    }.filter(_ != null), 0)
  }

  private def handleError(t: Throwable): LoadQueryTask.Result = {
    LoadQueryTask.Result(null, t match {
      case _: InvalidResponseException => R.string.error_invalid_response
      case _: SSLException => R.string.error_ssl
      case _: IOException => R.string.error_connection
      case _ => R.string.error_unknown
    })
  }

  def load: Observable[LoadQueryTask.Result] = {
    request.subscribeOn(Schedulers.io)
      .map(getString)
      .map(getImages)
      .onErrorReturn(handleError)
      .observeOn(AndroidSchedulers.mainThread)
  }
}

object LoadQueryTask {
  case class Result(images: List[Image], errorMessageId: Int)

  def apply(query: String): Observable[Result] = {
    new LoadQueryTask(query).load
  }
}